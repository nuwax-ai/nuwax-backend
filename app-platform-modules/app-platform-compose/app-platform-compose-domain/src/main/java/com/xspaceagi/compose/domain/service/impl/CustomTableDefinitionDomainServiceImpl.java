package com.xspaceagi.compose.domain.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.idev.excel.FastExcel;
import com.alibaba.fastjson2.JSON;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.dynamic.datasource.tx.DsPropagation;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.compose.domain.dto.CustomEmptyTableVo;
import com.xspaceagi.compose.domain.dto.FieldCreationResult;
import com.xspaceagi.compose.domain.model.CustomFieldDefinitionModel;
import com.xspaceagi.compose.domain.model.CustomTableDefinitionModel;
import com.xspaceagi.compose.domain.repository.ICustomDorisTableRepository;
import com.xspaceagi.compose.domain.repository.ICustomFieldDefinitionRepository;
import com.xspaceagi.compose.domain.repository.ICustomTableDefinitionRepository;
import com.xspaceagi.compose.domain.service.CustomDorisTableDomainService;
import com.xspaceagi.compose.domain.service.CustomTableDefinitionDomainService;
import com.xspaceagi.compose.domain.util.TableDbWrapperUtil;
import com.xspaceagi.compose.domain.util.excel.ExcelUtils;
import com.xspaceagi.compose.sdk.enums.DefaultTableFieldEnum;
import com.xspaceagi.compose.sdk.enums.TableFieldTypeEnum;
import com.xspaceagi.compose.sdk.request.DorisToolTableDefineRequest;
import com.xspaceagi.compose.sdk.request.QueryDorisTableDefinePageRequest;
import com.xspaceagi.compose.sdk.vo.define.TableFieldDefineVo;
import com.xspaceagi.compose.spec.constants.DorisConfigContants;
import com.xspaceagi.system.infra.redis.annotation.RedisLock;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.utils.DateUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CustomTableDefinitionDomainServiceImpl implements CustomTableDefinitionDomainService {

    @Resource
    private ICustomTableDefinitionRepository customTableDefinitionRepository;

    @Resource
    private ICustomFieldDefinitionRepository customFieldDefinitionRepository;

    @Resource
    private CustomDorisTableDomainService customDorisTableDomainService;

    @Resource
    private ICustomDorisTableRepository customDorisTableRepository;

    @Resource
    private TableDbWrapperUtil tableDbWrapperUtil;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Lazy
    @Resource
    private CustomTableDefinitionDomainService self;

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public Long addInfo(CustomEmptyTableVo model, UserContext userContext) {
        log.debug("Create empty table definition start, params: {}", model);

        // 1. 创建表定义模型
        CustomTableDefinitionModel tableModel = CustomEmptyTableVo.convertToModel(model, userContext);

        // 从配置中获取Doris数据库名称
        String dorisDatabase = tableDbWrapperUtil.getDorisDatabase();
        tableModel.setDorisDatabase(dorisDatabase);
        log.debug("Using Doris database: {}", dorisDatabase);

        // 2. 保存表定义
        Long tableId = customTableDefinitionRepository.addInfo(tableModel);

        // 空表结构添加,新增默认的系统字段
        var fieldList = CustomFieldDefinitionModel.obatinTableSystemFields(tableId, model.getSpaceId(), userContext);
        customFieldDefinitionRepository.batchAddInfo(fieldList, userContext);
        log.debug("Empty table definition created, tableId: {}", tableId);

        return tableId;
    }

    @Override
    @RedisLock(prefix = "lock:table_definition:", key = "#model.id", waitTime = 3, leaseTime = 30)
    public void updateInfo(CustomTableDefinitionModel model, List<CustomFieldDefinitionModel> fieldList,
                           UserContext userContext) {
        log.debug("Updating table definition, tableId: {}", model.getId());

        // 校验所有新增/更新字段的字段名是否合法
        if (fieldList != null) {
            for (CustomFieldDefinitionModel field : fieldList) {
                String fieldName = field.getFieldName();
                if (!StringUtils.hasText(fieldName)) {
                    throw ComposeException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "字段名");
                }
                if (fieldName.length() > 64) {
                    throw ComposeException.build(BizExceptionCodeEnum.composeFieldNameTooLong);
                }
                if (!Character.isLowerCase(fieldName.charAt(0)) && !Character.isUpperCase(fieldName.charAt(0))) {
                    throw ComposeException.build(BizExceptionCodeEnum.composeFieldNameMustStartWithLetter);
                }
                if (!fieldName.matches("^[a-zA-Z][a-zA-Z0-9_]*$")) {
                    throw ComposeException.build(BizExceptionCodeEnum.composeFieldNameInvalidChars);
                }
                // 新增：数值型字段的默认值校验
                Integer fieldType = field.getFieldType();
                String defaultValue = field.getDefaultValue();
                if ((TableFieldTypeEnum.INTEGER.getCode().equals(fieldType)
                        || TableFieldTypeEnum.NUMBER.getCode().equals(fieldType))
                        && StringUtils.hasText(defaultValue)) {
                    try {
                        new java.math.BigDecimal(defaultValue);
                    } catch (Exception e) {
                        log.warn("Field [{}] default [{}] is not numeric", fieldName, defaultValue);
                        throw ComposeException.build(BizExceptionCodeEnum.composeDefaultValueMustBeNumber, fieldName);
                    }
                }
                if (TableFieldTypeEnum.BOOLEAN.getCode().equals(fieldType) && StringUtils.hasText(defaultValue)) {
                    if (!(defaultValue.equals("true") || defaultValue.equals("false"))) {
                        log.warn("Field [{}] default [{}] is not boolean", fieldName, defaultValue);
                        throw ComposeException.build(BizExceptionCodeEnum.composeDefaultValueMustBeBoolean, fieldName);
                    }
                }
                if (TableFieldTypeEnum.STRING.getCode().equals(fieldType) && StringUtils.hasText(defaultValue)) {
                    if (defaultValue.length() > DorisConfigContants.DEFAULT_STRING_LENGTH) {
                        log.warn("Field [{}] default [{}] exceeds max length {}", fieldName, defaultValue,
                                DorisConfigContants.DEFAULT_STRING_LENGTH);
                        throw ComposeException.build(BizExceptionCodeEnum.composeDefaultValueTooLong, fieldName);
                    }
                }
                // if (TableFieldTypeEnum.MEDIUMTEXT.getCode().equals(fieldType) &&
                // StringUtils.hasText(defaultValue)) {
                // log.warn("Field [{}] is MEDIUMTEXT, default not allowed [{}]", fieldName, defaultValue);
                // throw ComposeException.build(BizExceptionCodeEnum.composeMediumtextNoDefault,
                // fieldName);
                // }
                // check 默认值的范围,如果字段类型是 int,范围限制在: [-2147483648,2147483647] 区间
                if (field.getFieldType() == TableFieldTypeEnum.INTEGER.getCode()) {
                    if (StringUtils.hasText(field.getDefaultValue())) {
                        // 这里通过数值字符串,来判断数值范围,不然字符串数值转 int等类型,如果数值范围过大,会报错
                        var defaultValueInt = Long.parseLong(defaultValue);
                        Long minValue = -2147483648L;
                        Long maxValue = 2147483647L;
                        if (defaultValueInt < minValue || defaultValueInt > maxValue) {
                            throw ComposeException.build(BizExceptionCodeEnum.composeDefaultValueOutOfRange, field.getFieldName(),
                                    minValue.toString(), maxValue.toString());
                        }
                    }
                } else if (field.getFieldType() == TableFieldTypeEnum.NUMBER.getCode()) {
                    // NUMBER 类型,对应 DECIMAL(28,6),检查默认值范围,如果超出范围,则抛出异常;
                    if (StringUtils.hasText(field.getDefaultValue())) {
                        try {
                            BigDecimal defaultValueBigDecimal = new BigDecimal(field.getDefaultValue());
                            // DECIMAL(28,6) 的范围是: 整数部分最多22位,小数部分最多6位
                            // 最大值: 9999999999999999999999.999999
                            // 最小值: -9999999999999999999999.999999
                            BigDecimal maxValue = new BigDecimal("9999999999999999999999.999999");
                            BigDecimal minValue = new BigDecimal("-9999999999999999999999.999999");

                            if (defaultValueBigDecimal.compareTo(maxValue) > 0
                                    || defaultValueBigDecimal.compareTo(minValue) < 0) {
                                throw ComposeException.build(BizExceptionCodeEnum.composeDefaultValueOutOfRange,
                                        field.getFieldName(), minValue.toString(), maxValue.toString());
                            }
                        } catch (NumberFormatException e) {
                            log.warn("Field [{}] default [{}] is not a valid number", field.getFieldName(), field.getDefaultValue());
                            throw ComposeException.build(BizExceptionCodeEnum.composeDefaultValueMustBeNumber,
                                    field.getFieldName());
                        }
                    }
                }

            }
        }

        this.self.updateTableDefinitionInTransaction(model, fieldList, userContext);
        log.debug("Table definition update done, tableId: {}", model.getId());
    }

    @Override
    @DSTransactional(rollbackFor = Exception.class, propagation = DsPropagation.REQUIRED)
    public void updateTableDefinitionInTransaction(CustomTableDefinitionModel model,
                                                   List<CustomFieldDefinitionModel> fieldList, UserContext userContext) {
        log.debug("Table definition tx start, params: {}", model);

        // 1. 获取原有表定义
        final CustomTableDefinitionModel existingTable = customTableDefinitionRepository
                .queryOneInfoById(model.getId());
        if (existingTable == null) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "表定义不存在");
        }

        final List<CustomFieldDefinitionModel> fieldsToAdd = new ArrayList<>();
        final List<CustomFieldDefinitionModel> fieldsToUpdate = new ArrayList<>();
        final List<CustomFieldDefinitionModel> fieldsToDelete = new ArrayList<>();
        final List<String> dorisAlterSqls = new ArrayList<>();
        final Map<String, CustomFieldDefinitionModel> existingFieldMapFinal;
        final boolean hasDorisDataFinal;

        // 检查Doris数据状态，只需要检查一次
        hasDorisDataFinal = customDorisTableDomainService.hasData(
                existingTable.getDorisDatabase(),
                existingTable.getDorisTable());

        // 2. 处理字段变更 (MySQL),fieldList是前端给的字段定义
        if (!CollectionUtils.isEmpty(fieldList)) {
            // 获取现有字段列表并设为 final,model.getFieldList()是当前库里的字段定义
            List<CustomFieldDefinitionModel> existingFields = model.getFieldList();
            existingFieldMapFinal = existingFields.stream()
                    .collect(Collectors.toMap(CustomFieldDefinitionModel::getFieldName, field -> field));

            Set<String> processedFields = new HashSet<>();

            // 处理新增和更新的字段,新增的字段,如果 sortIndex字段没有值,则检查库里已有字段的sortIndex,在最大的 sortIndex基础上 +1
            // ,是字段有序;一般是用前端的sortIndex字段排序,越小越靠前
            for (CustomFieldDefinitionModel newField : fieldList) {
                // 检查是否是系统默认字段
                DefaultTableFieldEnum defaultField = DefaultTableFieldEnum.getEnumByFieldName(newField.getFieldName());
                if (defaultField != null) {
                    // 如果是系统默认字段，使用默认配置覆盖用户的修改
                    overrideWithDefaultFieldDefinition(defaultField, newField);
                    // 如果字段已存在，则更新；否则添加
                    CustomFieldDefinitionModel existingField = existingFieldMapFinal.get(newField.getFieldName());
                    if (existingField != null) {
                        newField.setId(existingField.getId());
                        newField.setTableId(model.getId());
                        fieldsToUpdate.add(newField);
                    } else {
                        newField.setTableId(model.getId());
                        fieldsToAdd.add(newField);
                    }
                    processedFields.add(newField.getFieldName());
                    continue;
                }

                CustomFieldDefinitionModel existingField = existingFieldMapFinal.get(newField.getFieldName());
                processedFields.add(newField.getFieldName());

                if (existingField == null) {
                    // 新增字段
                    newField.setTableId(model.getId());
                    fieldsToAdd.add(newField);
                } else {
                    // 更新字段
                    if (hasDorisDataFinal) {
                        // 如果Doris表有数据,则需要检查字段约束变更
                        validateFieldConstraintChanges(existingField, newField);
                    }
                    newField.setId(existingField.getId());
                    newField.setTableId(model.getId());
                    fieldsToUpdate.add(newField);
                }
            }

            // 处理删除的字段（排除系统默认字段）
            List<CustomFieldDefinitionModel> tempFieldsToDelete = existingFields.stream()
                    .filter(field -> !processedFields.contains(field.getFieldName()))
                    .filter(field -> DefaultTableFieldEnum.getEnumByFieldName(field.getFieldName()) == null) // 排除系统默认字段
                    .collect(Collectors.toList());
            fieldsToDelete.addAll(tempFieldsToDelete);

            // 如果Doris表有数据且有字段要删除，抛出异常
            if (hasDorisDataFinal && !fieldsToDelete.isEmpty()) {
                log.error("Doris has data and columns to drop, abort, table: {}.{}, columns:{}", existingTable.getDorisDatabase(),
                        existingTable.getDorisTable(), JSON.toJSON(fieldsToDelete));
                throw ComposeException.build(BizExceptionCodeEnum.composeCannotDropFieldWithData);
            }

            // 执行MySQL字段变更
            if (!fieldsToAdd.isEmpty()) {
                customFieldDefinitionRepository.batchAddInfo(fieldsToAdd, userContext);
            }
            if (!fieldsToUpdate.isEmpty()) {
                customFieldDefinitionRepository.batchUpdateInfo(fieldsToUpdate, userContext);
            }
            if (!fieldsToDelete.isEmpty()) {
                for (CustomFieldDefinitionModel field : fieldsToDelete) {
                    customFieldDefinitionRepository.deleteById(field.getId(), userContext);
                }
            }
        } else {
            // 如果传入的 fieldList 为空，则 existingFieldMap 需要初始化为空 Map
            existingFieldMapFinal = Collections.emptyMap();
        }

        // 3. 更新MySQL表定义基本信息 (先更新MySQL，确保事务一致性)
        customTableDefinitionRepository.updateInfo(model, userContext);

        // 4. 处理Doris表结构
        if (!hasDorisDataFinal) {
            // Doris无数据: 重建表
            log.info("Doris empty, will recreate schema, table: {}.{}", existingTable.getDorisDatabase(),
                    existingTable.getDorisTable());
            // 库表字段更新后,需要重新插最新的库表结构定义
            var tableModel = customTableDefinitionRepository.queryOneInfoById(model.getId());
            customDorisTableDomainService.rebuildTable(tableModel);
        } else {
            // Doris有数据: 生成并执行 ALTER TABLE 语句
            var sqlList = tableDbWrapperUtil.generateDorisAlterSqls(existingTable, fieldsToAdd, fieldsToUpdate,
                    existingFieldMapFinal);
            log.info("Doris has data, will ALTER, tableName:{}, sqlList:{}", existingTable.getTableName(),
                    JSON.toJSONString(sqlList));
            dorisAlterSqls
                    .addAll(sqlList);
            if (!dorisAlterSqls.isEmpty()) {
                log.info("Doris has data, will ALTER, table: {}.{}", existingTable.getDorisDatabase(),
                        existingTable.getDorisTable());
                customDorisTableDomainService.alterTable(existingTable.getDorisDatabase(),
                        existingTable.getDorisTable(), dorisAlterSqls);
            }
        }

        log.debug("Table definition tx complete");
    }

    @Override
    @DSTransactional(rollbackFor = Exception.class)
    public CustomTableDefinitionModel deleteById(Long tableId, UserContext userContext) {
        log.debug("Delete table definition start, tableId: {}", tableId);
        // 1. 获取表定义
        CustomTableDefinitionModel tableModel = customTableDefinitionRepository.queryOneInfoById(tableId);
        if (tableModel == null) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "表定义不存在");
        }

        // 3. 删除表定义
        customTableDefinitionRepository.deleteById(tableId);
        // 删除关联的字段定义
        customFieldDefinitionRepository.deleteByTableId(tableId);
        log.debug("Delete table definition done");
        return tableModel;
    }

    /**
     * 验证字段约束变更
     */
    private void validateFieldConstraintChanges(CustomFieldDefinitionModel existingField,
                                                CustomFieldDefinitionModel newField) {
        // 检查唯一性约束变更
        if (!Objects.equals(existingField.getUniqueFlag(), newField.getUniqueFlag())) {
            log.warn("Field [{}] uniqueness changed; abort if Doris has data", existingField.getFieldName());
            throw ComposeException.build(BizExceptionCodeEnum.composeCannotChangeUniqueWithData, existingField.getFieldName());
        }

        // 检查非空约束变更,如果字段是从可空变更为不可空,则需要检查Doris表中是否存在数据,如果存在数据,则抛出异常
        // if (!Objects.equals(existingField.getNullableFlag(),
        // newField.getNullableFlag())) {
        // if (DorisConfigContants.FIXED_FIELD_NULLABLE) {
        // log.warn("Field [{}] nullability tightened; abort if Doris has data",
        // existingField.getFieldName());
        // throw ComposeException.build(BizExceptionCodeEnum.composeCannotChangeNullableWithData,
        // existingField.getFieldName());
        // }
        // }
        if (!Objects.equals(existingField.getFieldType(), newField.getFieldType())) {
            log.warn("Field [{}] type changed; Doris ALTER may be risky");
        }
    }

    /**
     * 使用系统默认字段定义覆盖用户的修改
     */
    private void overrideWithDefaultFieldDefinition(DefaultTableFieldEnum defaultField,
                                                    CustomFieldDefinitionModel field) {
        TableFieldDefineVo defaultFieldVo = defaultField.toFieldDefineVo();

        // 使用默认配置覆盖用户的修改
        field.setFieldType(defaultFieldVo.getFieldType());
        field.setNullableFlag(defaultFieldVo.getNullableFlag());
        field.setUniqueFlag(defaultFieldVo.getUniqueFlag());
        field.setEnabledFlag(defaultFieldVo.getEnabledFlag());
        field.setDefaultValue(defaultFieldVo.getDefaultValue());
        field.setFieldDescription(defaultField.getFieldDescription());

        log.debug("System field [{}] reset to default config", defaultField.getFieldName());
    }

    @Override
    public void exportTableDataToExcel(Long tableId, OutputStream outputStream, UserContext userContext) {
        log.info("Export table to Excel start, tableId: {}", tableId);

        if (tableId == null) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "表ID不能为空");
        }

        // 提前获取并检查 tableModel
        CustomTableDefinitionModel tableModel = this.customTableDefinitionRepository.queryOneInfoById(tableId);
        if (tableModel == null) {
            log.error("Table definition not found for export, tableId: {}", tableId);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "表定义不存在");
        }
        String database = tableModel.getDorisDatabase();
        String table = tableModel.getDorisTable();
        if (StrUtil.hasBlank(database, table)) {
            log.error("Table definition has empty database or table name, tableId: {}, database: {}, table: {}", tableId, database, table);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "表定义中数据库名或表名不能为空");
        }
        // 2. 获取字段定义列表 (仍然可能抛异常或返回空，留在try里)
        List<CustomFieldDefinitionModel> fields = tableModel.getFieldList();
        if (CollUtil.isEmpty(fields)) {
            log.warn("Field definitions missing or empty, tableId: {}", tableId);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        try {

            // 3. 查询表数据 (留在try里)
            List<Map<String, Object>> tableData = customDorisTableDomainService.queryAllTableData(database, table, null,
                    "id ASC", null);

            // 长数值字段处理：对可能超过Integer范围的数值进行字符串转换，避免Excel中的科学计数法问题
            // 创建字段类型映射，用于判断哪些字段需要特殊处理
            Map<String, Integer> fieldTypeMap = new HashMap<>();
            for (CustomFieldDefinitionModel field : fields) {
                fieldTypeMap.put(field.getFieldName(), field.getFieldType());
            }

            // 对数据进行预处理：强制将所有数值类型字段转换为字符串，确保Excel整列按字符串格式处理
            for (Map<String, Object> rowData : tableData) {
                if (rowData == null) {
                    continue; // 防御性检查：跳过空行
                }

                for (Map.Entry<String, Object> entry : rowData.entrySet()) {
                    if (entry == null) {
                        continue; // 防御性检查：跳过空条目
                    }

                    String fieldName = entry.getKey();
                    Object fieldValue = entry.getValue();

                    // 强制转换所有数值类型（包括BigDecimal）为字符串，确保Excel按字符串格式处理
                    if (fieldValue instanceof Number) {
                        String stringValue = formatNumberForExcel(fieldValue);
                        rowData.put(fieldName, stringValue);
                        log.debug("Field [{}] value [{}] ({}) coerced to string [{}] for Excel",
                                fieldName, fieldValue, fieldValue.getClass().getSimpleName(), stringValue);
                    }
                }
            }

            // 4. 转换数据格式为Excel写入格式 (留在try里)
            List<List<Object>> excelData = new ArrayList<>();
            List<Object> titleRow = new ArrayList<>();
            for (CustomFieldDefinitionModel field : fields) {
                if (field.getEnabledFlag() != null && field.getEnabledFlag() == 1) {
                    // 这里使用英文字段和中文名称,格式: 中文名称(英文字段名)
                    String header = String.format("%s(%s)",
                            StringUtils.hasText(field.getFieldDescription()) ? field.getFieldDescription()
                                    : field.getFieldName(),
                            field.getFieldName());
                    titleRow.add(header);
                }
            }
            excelData.add(titleRow);

            for (Map<String, Object> rowData : tableData) {
                List<Object> excelRow = new ArrayList<>();
                for (CustomFieldDefinitionModel field : fields) {
                    if (field.getEnabledFlag() != null && field.getEnabledFlag() == 1) {
                        String fieldName = field.getFieldName();
                        Object fieldValue = rowData.get(fieldName);
                        if (fieldValue instanceof Date date) {
                            fieldValue = DateUtil.format(date, "yyyy-MM-dd HH:mm:ss");
                        } else if (fieldValue instanceof LocalDateTime localDateTime) {
                            fieldValue = localDateTime.format(DATE_TIME_FORMATTER);
                        }
                        excelRow.add(fieldValue);
                    }
                }
                excelData.add(excelRow);
            }

            // 5. 使用FastExcel写入数据 (留在try里)
            FastExcel.write(outputStream)
                    .sheet(table)
                    .doWrite(excelData);

            outputStream.flush();

            log.info("Export to Excel done, tableId: {}, database: {}, table: {}, rows: {}",
                    tableId, database, table, excelData.size());

        } catch (Exception e) {
            // 记录完整的异常堆栈信息
            log.error("Export to Excel error, tableId: {}, database: {}, table: {}",
                    tableId, database, table, e); // Can use variables defined outside try
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "导出Excel时发生异常: " + e.getMessage());
        }
    }

    @Override
    public CustomTableDefinitionModel queryOneTableInfoById(Long tableId) {
        log.debug("Query table definition, tableId: {}", tableId);
        return this.customTableDefinitionRepository.queryOneInfoById(tableId);
    }

    @Override
    public List<CustomTableDefinitionModel> queryAllTableDefineList(DorisToolTableDefineRequest request) {
        log.debug("Query table definition list, request: {}", request);

        if (request == null) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "请求参数不能为空");
        }

        // 必须传入租户ID
        if (request.getTenantId() == null) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "租户ID不能为空");
        }

        // 构建查询条件
        CustomTableDefinitionModel queryModel = new CustomTableDefinitionModel();
        queryModel.setTenantId(request.getTenantId());

        // 可选条件：空间ID
        if (request.getSpaceId() != null) {
            queryModel.setSpaceId(request.getSpaceId());
        }

        // 可选条件：用户ID（如果需要根据用户ID过滤的话）
        // 注意：这里需要根据实际业务需求来决定如何使用userId进行过滤
        // 如果用户ID是用来过滤用户有权限访问的表，可能需要额外的权限表查询

        // 调用 repository 层查询
        List<CustomTableDefinitionModel> tableList = customTableDefinitionRepository.queryListByCondition(queryModel);

        // 如果列表为空，返回空列表而不是 null
        if (CollectionUtils.isEmpty(tableList)) {
            return Collections.emptyList();
        }

        log.debug("Found {} table definitions", tableList.size());
        return tableList;
    }

    @Override
    public String getCreateTableDdl(Long tableId, CustomTableDefinitionModel tableDefine) {

        var database = tableDefine.getDorisDatabase();
        var table = tableDefine.getDorisTable();

        var ddl = this.customDorisTableRepository.getCreateTableDdl(database, table);

        return ddl;
    }

    @Override
    public SuperPage<CustomTableDefinitionModel> queryPageTableDefine(QueryDorisTableDefinePageRequest request) {

        // 构建查询条件
        CustomTableDefinitionModel queryModel = new CustomTableDefinitionModel();
        queryModel.setSpaceId(request.getSpaceId());
        // 调用 repository 层查询
        List<CustomTableDefinitionModel> tableList = customTableDefinitionRepository.queryListByCondition(queryModel);

        // 将列表转换为分页对象
        SuperPage<CustomTableDefinitionModel> page = new SuperPage<>();
        page.setRecords(tableList);
        page.setTotal(tableList.size());
        page.setSize(request.getPageSize());
        page.setCurrent(request.getPageNo());

        return page;
    }

    @Override
    public void updateTableName(CustomTableDefinitionModel model, UserContext userContext) {
        this.customTableDefinitionRepository.updateTableName(model, userContext);
    }

    @Override
    public Long copyTableDefinition(Long tableId, UserContext userContext) {
        var tableModel = this.customTableDefinitionRepository.queryOneInfoById(tableId);
        if (tableModel == null) {
            log.error("Table definition not found, tableId: {}", tableId);
            throw ComposeException.build(BizExceptionCodeEnum.composeTableDefinitionNotFound);
        }
        var newTableId = this.customTableDefinitionRepository.copyTableDefinition(tableId, userContext);
        return newTableId;
    }

    @Override
    public void updateTableLastModified(Long tableId, UserContext userContext) {

        this.customTableDefinitionRepository.updateTableLastModified(tableId, userContext);

    }

    @Override
    public FieldCreationResult handleNewFieldsFromExcel(CustomTableDefinitionModel tableModel,
                                                        List<String> newFieldsToCreate,
                                                        UserContext userContext) {

        List<CustomFieldDefinitionModel> existingFields = new ArrayList<>(tableModel.getFieldList());
        Map<String, String> newFieldMapping = new java.util.HashMap<>();
        List<CustomFieldDefinitionModel> newFieldModels = new ArrayList<>();

        // 找出当前自定义字段名中数字后缀的最大值
        int maxCustomIndex = existingFields.stream()
                .map(CustomFieldDefinitionModel::getFieldName)
                .filter(name -> name.matches("c_\\d+"))
                .map(name -> name.substring(2))
                .mapToInt(Integer::parseInt)
                .max()
                .orElse(0);

        // 找出当前最大的排序索引
        int maxSortIndex = existingFields.stream()
                .mapToInt(CustomFieldDefinitionModel::getSortIndex)
                .max()
                .orElse(-1);

        // 根据excel的表头，调用大模型，生成英文字段名
        Map<String, String> englishFieldNameMap = this.getEnglishFieldNameFromExcelHeader(newFieldsToCreate);

        // 获取现有字段的英文名集合，用于冲突检测
        Set<String> existingFieldNames = existingFields.stream()
                .map(CustomFieldDefinitionModel::getFieldName)
                .collect(Collectors.toSet());

        // 用于存储本次循环中已经分配的英文名，防止内部冲突（例如多个中文翻译成同一个英文）
        Set<String> newlyAssignedEnglishNames = new HashSet<>();

        for (String chineseHeader : newFieldsToCreate) {
            String proposedEnglishName = englishFieldNameMap.get(chineseHeader);
            // 清理字段名中的空白字符
            proposedEnglishName = ExcelUtils.cleanWhitespace(proposedEnglishName);
            String finalFieldName;

            // 检查翻译的英文名是否可用 (不与现有字段冲突，也不与本次已分配的字段冲突)
            if (StringUtils.hasText(proposedEnglishName) &&
                    !existingFieldNames.contains(proposedEnglishName) &&
                    !newlyAssignedEnglishNames.contains(proposedEnglishName)) {
                finalFieldName = proposedEnglishName;
                newlyAssignedEnglishNames.add(finalFieldName); // 记录已分配的英文名
            } else {
                // 如果冲突或翻译为空，则使用 c_X 格式
                maxCustomIndex++; // maxCustomIndex 是之前计算得到的，这里继续递增
                finalFieldName = "c_" + maxCustomIndex;
                // 确保 c_X 格式的名称也不会与现有字段名或本次已分配的字段名冲突
                while (existingFieldNames.contains(finalFieldName) || newlyAssignedEnglishNames.contains(finalFieldName)) {
                    maxCustomIndex++;
                    finalFieldName = "c_" + maxCustomIndex;
                }
                newlyAssignedEnglishNames.add(finalFieldName); // 记录已分配的 c_X 格式的英文名
            }

            CustomFieldDefinitionModel newField = CustomFieldDefinitionModel.createFromExcelHeader(
                    tableModel,
                    chineseHeader,
                    finalFieldName, // 使用最终确定的字段名
                    ++maxSortIndex,
                    userContext);

            newFieldModels.add(newField);
            // 更新 newFieldMapping，确保它反映的是最终使用的字段名
            newFieldMapping.put(chineseHeader, finalFieldName);
        }

        // 合并新旧字段
        List<CustomFieldDefinitionModel> allFields = new ArrayList<>(existingFields);
        allFields.addAll(newFieldModels);

        // 调用服务更新表定义
        this.self.updateInfo(tableModel, allFields, userContext);

        // 获取最新的表定义
        CustomTableDefinitionModel updatedTableModel = this.self.queryOneTableInfoById(tableModel.getId());

        return new FieldCreationResult(updatedTableModel, newFieldMapping);
    }

    /**
     * 通过excel表头，调用大模型，生成英文字段名
     *
     * @param newFieldsToCreate
     * @return
     */
    private Map<String, String> getEnglishFieldNameFromExcelHeader(List<String> newFieldsToCreate) {

        // 构建调用大模型的请求
        String sysPrompt = "你是一个专业的翻译引擎，专门负责将中文列表中的表头字段翻译成符合编程规范的英文变量名。\n" +
                "请遵循以下规则：\n" +
                "1.  **准确翻译**：确保英文翻译准确反映中文含义。\n" +
                "2.  **小写蛇形命名法 (snake_case)**：所有字母小写，单词间用下划线 `_` 连接。\n" +
                "3.  **简洁性**：翻译结果应尽可能简洁明了。\n" +
                "4.  **JSON输出**：请将结果组织成一个JSON对象，其中每个键是原始的中文表头，对应的值是翻译后的英文变量名。\n\n" +
                "例如，如果输入是：`[\"用户名称\", \"登录密码\"]`\n" +
                "期望的输出 (JSON字符串) 应该是：\n" +
                "`{\\\"用户名称\\\": \\\"user_name\\\", \\\"登录密码\\\": \\\"login_password\\\"}`";
        // 使用 fastjson2
        String jsonData = com.alibaba.fastjson2.JSON.toJSONString(newFieldsToCreate);
        String userPrompt = "请处理以下中文表头列表，并严格按照系统提示中定义的JSON格式返回结果：\n" + jsonData;

        // 调用大模型
        Map<String, String> result = modelApplicationService.call(sysPrompt, userPrompt,
                new org.springframework.core.ParameterizedTypeReference<Map<String, String>>() {
                });
        return result;
    }

    /**
     * 判断数值是否需要转换为字符串以避免Excel中的显示问题
     *
     * @param fieldValue 字段值
     * @param fieldType  字段类型
     * @return 是否需要转换为字符串
     */
    private boolean shouldConvertToString(Object fieldValue, Integer fieldType) {
        // 基础检查：字段值必须是非null的数值类型
        if (fieldValue == null || !(fieldValue instanceof Number)) {
            return false;
        }

        // 字段类型检查：如果字段类型为null，则不进行转换
        if (fieldType == null) {
            log.debug("Field type null, skip numeric conversion");
            return false;
        }

        // 配置检查：如果未启用智能转换，则不进行处理
        if (!DorisConfigContants.ENABLE_SMART_NUMBER_CONVERSION) {
            return false;
        }

        Number number = (Number) fieldValue;

        try {
            // 主键类型根据配置决定是否转换为字符串
            if (TableFieldTypeEnum.PRIMARY_KEY.getCode().equals(fieldType)) {
                return DorisConfigContants.FORCE_CONVERT_PRIMARY_KEY;
            }

            // INTEGER类型：超过Integer范围时转换为字符串
            if (TableFieldTypeEnum.INTEGER.getCode().equals(fieldType)) {
                long longValue = number.longValue();
                return longValue > Integer.MAX_VALUE || longValue < Integer.MIN_VALUE;
            }

            // NUMBER类型：处理大数值和高精度数值
            if (TableFieldTypeEnum.NUMBER.getCode().equals(fieldType)) {
                // 方案1：检查数值字符串长度是否超过Excel安全显示范围
                String numberStr = number.toString();
                if (numberStr != null) {
                    // 移除小数点、负号和科学计数法标记来计算纯数字位数
                    String digitsOnly = numberStr.replaceAll("[-.eE]", "");
                    if (digitsOnly.length() > DorisConfigContants.EXCEL_MAX_PRECISION_DIGITS) {
                        return true;
                    }
                }

                // 方案2：检查绝对值是否超过Excel精度范围
                double doubleValue = number.doubleValue();
                if (Math.abs(doubleValue) >= DorisConfigContants.EXCEL_MAX_SAFE_NUMBER) {
                    return true;
                }

                // 方案3：特殊处理BigDecimal类型的高精度数值
                if (fieldValue instanceof BigDecimal) {
                    BigDecimal bd = (BigDecimal) fieldValue;
                    // 如果整数部分超过Excel精度限制，则转换为字符串
                    if (bd.precision() - bd.scale() > DorisConfigContants.EXCEL_MAX_PRECISION_DIGITS) {
                        return true;
                    }
                }
            }

        } catch (Exception e) {
            // 如果在处理过程中发生任何异常，记录错误但不影响整体流程
            log.error("Numeric conversion check error, fieldValue: {}, fieldType: {}", fieldValue, fieldType, e);
            return false;
        }

        return false;
    }

    /**
     * 判断字段类型是否为数值类型
     *
     * @param fieldType 字段类型
     * @return 是否为数值类型
     */
    private boolean isNumericFieldType(Integer fieldType) {
        if (fieldType == null) {
            return false;
        }

        return TableFieldTypeEnum.PRIMARY_KEY.getCode().equals(fieldType) ||
                TableFieldTypeEnum.INTEGER.getCode().equals(fieldType) ||
                TableFieldTypeEnum.NUMBER.getCode().equals(fieldType);
    }

    /**
     * 格式化数值为适合Excel导出的字符串格式
     * 确保数值精度不丢失且Excel不会按科学计数法显示
     *
     * @param fieldValue 字段值
     * @return 格式化后的字符串
     */
    private String formatNumberForExcel(Object fieldValue) {
        if (fieldValue == null) {
            return "";
        }

        try {
            log.debug("Format numeric: [{}], type: [{}]", fieldValue, fieldValue.getClass().getSimpleName());

            // 优先处理BigDecimal类型，使用toPlainString()避免科学计数法
            if (fieldValue instanceof BigDecimal) {
                String result = ((BigDecimal) fieldValue).toPlainString();
                log.debug("BigDecimal [{}] -> string [{}]", fieldValue, result);
                return result;
            }

            // 对于Double类型，转换为BigDecimal以保持精度
            if (fieldValue instanceof Double) {
                Double doubleValue = (Double) fieldValue;
                BigDecimal bd = BigDecimal.valueOf(doubleValue);
                String result = bd.toPlainString();
                log.debug("Double [{}] -> string [{}]", fieldValue, result);
                return result;
            }

            // 对于Float类型，转换为BigDecimal以保持精度
            if (fieldValue instanceof Float) {
                Float floatValue = (Float) fieldValue;
                BigDecimal bd = BigDecimal.valueOf(floatValue.doubleValue());
                String result = bd.toPlainString();
                log.debug("Float [{}] -> string [{}]", fieldValue, result);
                return result;
            }

            // 对于Long、Integer等整数类型，直接转换
            if (fieldValue instanceof Long || fieldValue instanceof Integer ||
                    fieldValue instanceof Short || fieldValue instanceof Byte) {
                String result = fieldValue.toString();
                log.debug("Integer [{}] -> string [{}]", fieldValue, result);
                return result;
            }

            // 其他数值类型，统一使用BigDecimal处理
            if (fieldValue instanceof Number) {
                Number number = (Number) fieldValue;
                BigDecimal bd = new BigDecimal(number.toString());
                String result = bd.toPlainString();
                log.debug("Other numeric [{}] -> string [{}]", fieldValue, result);
                return result;
            }

            // 非数值类型，直接toString
            String result = fieldValue.toString();
            log.debug("Non-numeric [{}] -> string [{}]", fieldValue, result);
            return result;

        } catch (Exception e) {
            log.error("Format numeric [{}] to Excel string error", fieldValue, e);
            // 异常时使用默认转换
            return String.valueOf(fieldValue);
        }
    }

    @Override
    public List<CustomTableDefinitionModel> queryTableDefineBySpaceId(Long spaceId) {
        return this.customTableDefinitionRepository.queryListBySpaceId(spaceId);
    }

    @Override
    public Long countTotalTables() {
        return this.customTableDefinitionRepository.countTotalTables(null);
    }
}
