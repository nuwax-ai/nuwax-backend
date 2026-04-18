package com.xspaceagi.compose.infra.respository;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.compose.domain.dto.ColumnDefinitionResult;
import com.xspaceagi.compose.sdk.vo.data.FrontColumnDefineVo;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Repository;
import org.springframework.util.CollectionUtils;

import com.baomidou.mybatisplus.core.metadata.OrderItem;
import com.xspaceagi.compose.spec.constants.DorisConfigContants;
import com.xspaceagi.compose.domain.dto.CustomEmptyTableVo;
import com.xspaceagi.compose.domain.model.CustomFieldDefinitionModel;
import com.xspaceagi.compose.domain.model.CustomTableDefinitionModel;
import com.xspaceagi.compose.domain.repository.ICustomFieldDefinitionRepository;
import com.xspaceagi.compose.domain.repository.ICustomTableDefinitionRepository;
import com.xspaceagi.compose.domain.util.TableDbWrapperUtil;
import com.xspaceagi.compose.infra.dao.entity.CustomTableDefinition;
import com.xspaceagi.compose.infra.dao.mapper.CustomTableDefinitionMapper;
import com.xspaceagi.compose.infra.dao.service.CustomFieldDefinitionService;
import com.xspaceagi.compose.infra.dao.service.CustomTableDefinitionService;
import com.xspaceagi.compose.infra.translator.CustomFieldDefinitionTranslator;
import com.xspaceagi.compose.infra.translator.CustomTableDefinitionTranslator;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.YnEnum;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Repository
public class CustomTableDefinitionRepository implements ICustomTableDefinitionRepository {

    @Resource
    private CustomFieldDefinitionService customFieldDefinitionService;

    @Resource
    private CustomTableDefinitionService customTableDefinitionService;

    @Resource
    private CustomTableDefinitionMapper customTableDefinitionMapper;

    @Resource
    private CustomTableDefinitionTranslator customTableDefinitionTranslator;

    @Resource
    private CustomFieldDefinitionTranslator customFieldDefinitionTranslator;

    @Resource
    private ICustomFieldDefinitionRepository customFieldDefinitionRepository;

    @Resource
    private TableDbWrapperUtil tableDbWrapperUtil;

    @Lazy
    @Resource
    private ICustomTableDefinitionRepository self;

    @Override
    public List<CustomTableDefinitionModel> queryListByIds(List<Long> ids) {
        List<CustomTableDefinitionModel> tableList = customTableDefinitionService.queryListByIds(ids)
                .stream()
                .map(customTableDefinitionTranslator::convertToModel)
                .collect(Collectors.toList());

        return fillFieldList(tableList);
    }

    @Override
    public CustomTableDefinitionModel queryOneInfoById(Long id) {
        var entity = customTableDefinitionService.queryOneInfoById(id);
        CustomTableDefinitionModel tableModel = customTableDefinitionTranslator.convertToModel(entity);
        if (tableModel == null) {
            return null;
        }
        return fillFieldList(Collections.singletonList(tableModel)).stream()
                .findFirst()
                .orElseThrow();
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public Long updateInfo(CustomTableDefinitionModel entity, UserContext userContext) {

        entity.setModified(null);
        entity.setModifiedId(userContext.getUserId());
        entity.setModifiedName(userContext.getUserName());

        // 通常更新操作不需要返回完整的字段列表，如果需要则在此处调用 fillFieldList
        var id = customTableDefinitionService.updateInfo(
                customTableDefinitionTranslator.convertToEntity(entity));

        // 批量修改 字段定义

        return id;
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public Long addInfo(CustomTableDefinitionModel entity) {

        // 通常新增操作不需要返回完整的字段列表，如果需要则在此处调用 fillFieldList
        entity.setDorisTable("");
        var id = customTableDefinitionService.addInfo(
                customTableDefinitionTranslator.convertToEntity(entity));

        // 更新doris表名
        var updateEntity = new CustomTableDefinition();
        updateEntity.setId(id);
        var dorisTableName = DorisConfigContants.obtainTableName(id);
        updateEntity.setDorisTable(dorisTableName);

        this.customTableDefinitionService.updateById(updateEntity);

        return id;
    }

    @Override
    public void deleteById(Long id) {
        customTableDefinitionService.deleteById(id);
    }

    @Override
    public List<CustomTableDefinitionModel> pageQuery(Map<String, Object> queryMap, List<OrderItem> orderColumns,
                                                      Long startIndex, Long pageSize) {
        List<CustomTableDefinitionModel> tableList = customTableDefinitionService
                .pageQuery(queryMap, orderColumns, startIndex, pageSize)
                .stream()
                .map(customTableDefinitionTranslator::convertToModel)
                .collect(Collectors.toList());

        return fillFieldList(tableList);
    }

    @Override
    public Long queryTotal(Map<String, Object> queryMap) {
        return customTableDefinitionService.queryTotal(queryMap);
    }

    /**
     * 为表定义模型列表填充字段列表信息
     *
     * @param tableList 表定义模型列表
     * @return 填充字段列表后的表定义模型列表
     */
    private List<CustomTableDefinitionModel> fillFieldList(List<CustomTableDefinitionModel> tableList) {
        if (CollectionUtils.isEmpty(tableList)) {
            return tableList;
        }

        // 提取表 ID
        List<Long> tableIds = tableList.stream().map(CustomTableDefinitionModel::getId).collect(Collectors.toList());

        // 查询表字段
        List<CustomFieldDefinitionModel> fieldList = customFieldDefinitionRepository.queryListByTableIds(tableIds);

        // 按 TableId 分组
        Map<Long, List<CustomFieldDefinitionModel>> tableFieldMap = fieldList.stream()
                .collect(Collectors.groupingBy(CustomFieldDefinitionModel::getTableId));

        // 将表字段添加到表模型中,且 字段需要根据 sortIndex排序,sortIndex越小越靠前
        tableList.forEach(table -> {
            List<CustomFieldDefinitionModel> fields = tableFieldMap.getOrDefault(table.getId(),
                    Collections.emptyList());
            fields.sort(Comparator.comparing(CustomFieldDefinitionModel::getSortIndex,
                    Comparator.nullsLast(Comparator.naturalOrder())));
            table.setFieldList(fields);
        });

        return tableList;
    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public Long addEmptyTableInfo(CustomEmptyTableVo vo, UserContext userContext) {

        var entity = new CustomTableDefinition();
        entity.setTenantId(userContext.getTenantId());
        entity.setSpaceId(vo.getSpaceId());
        entity.setTableName(vo.getTableName());
        entity.setTableDescription(vo.getTableDescription());
        entity.setStatus(YnEnum.Y.getKey());
        entity.setCreated(LocalDateTime.now());
        entity.setCreatorId(userContext.getUserId());
        entity.setCreatorName(userContext.getUserName());
        entity.setDorisDatabase(DorisConfigContants.DEFAULT_DORIS_DATABASE);

        var id = customTableDefinitionService.addInfo(entity);

        // 更新doris表名
        var updateEntity = new CustomTableDefinition();
        updateEntity.setId(id);
        var dorisTableName = DorisConfigContants.obtainTableName(id);
        updateEntity.setDorisTable(dorisTableName);

        this.customTableDefinitionService.updateById(updateEntity);

        return id;

    }

    @Override
    public List<CustomTableDefinitionModel> queryListByCondition(CustomTableDefinitionModel condition) {
        log.debug("Query table definitions by condition: {}", condition);

        // 将领域模型转换为实体对象
        CustomTableDefinition queryCondition = customTableDefinitionTranslator.convertToEntity(condition);

        // 调用 service 层查询
        List<CustomTableDefinition> entityList = customTableDefinitionService.queryListByCondition(queryCondition);

        // 转换为领域模型
        if (CollectionUtils.isEmpty(entityList)) {
            return Collections.emptyList();
        }

        List<CustomTableDefinitionModel> tableList = entityList.stream()
                .map(customTableDefinitionTranslator::convertToModel)
                .collect(Collectors.toList());

        // 填充字段列表信息
        return fillFieldList(tableList);
    }

    @Override
    public ColumnDefinitionResult getColumnDefinitions(Long tableId) {
        // 1. 查询表定义
        CustomTableDefinitionModel tableModel = this.self.queryOneInfoById(tableId);
        if (tableModel == null) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "表定义不存在: " + tableId);
        }
        if (tableModel.getDorisDatabase() == null || tableModel.getDorisTable() == null) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "表定义缺少Doris库名或表名: " + tableId);
        }

        // 2. 查询字段定义
        List<CustomFieldDefinitionModel> fieldModels = tableModel.getFieldList();

        // 按 sortIndex 排序 (假设 CustomFieldDefinitionModel 有 sortIndex 字段)
        fieldModels.sort(Comparator
                .comparing(CustomFieldDefinitionModel::getSortIndex, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(CustomFieldDefinitionModel::getId)); // 添加ID作为次要排序，保证稳定性

        // 3. 转换为 ExcelColumnDefineVo 并提取有序字段名
        List<FrontColumnDefineVo> columnDefines = new ArrayList<>();
        List<String> orderedFieldNames = new ArrayList<>();
        List<CustomFieldDefinitionModel> fields = new ArrayList<>();

        for (CustomFieldDefinitionModel field : fieldModels) {
            // 只包含启用的字段
            if (field.getEnabledFlag() != null && field.getEnabledFlag() == 1) {
                FrontColumnDefineVo vo = FrontColumnDefineVo.builder()
                        .fieldName(field.getFieldName())
                        .fieldDescription(field.getFieldDescription())
                        .fieldType(field.getFieldType())
                        .nullableFlag(field.getNullableFlag() != null && field.getNullableFlag() == 1)
                        .uniqueFlag(field.getUniqueFlag() != null && field.getUniqueFlag() == 1)
                        .enabledFlag(true) // 既然已经筛选了启用的，这里直接设为 true
                        .defaultValue(field.getDefaultValue())
                        .sortIndex(field.getSortIndex())
                        .build();
                columnDefines.add(vo);
                orderedFieldNames.add(field.getFieldName());
                fields.add(field);
            }
        }

        if (columnDefines.isEmpty()) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "表没有定义任何启用的字段: " + tableId);
        }

        return new ColumnDefinitionResult(tableModel, columnDefines, orderedFieldNames, fields);
    }

    @Override
    public Long updateTableName(CustomTableDefinitionModel entity, UserContext userContext) {
        // 1. 查询表定义
        CustomTableDefinitionModel tableModel = this.self.queryOneInfoById(entity.getId());
        if (tableModel == null) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 2. 更新表名称
        var updateEntity = new CustomTableDefinition();
        updateEntity.setId(entity.getId());
        updateEntity.setTableName(entity.getTableName());
        updateEntity.setTableDescription(entity.getTableDescription());
        updateEntity.setIcon(entity.getIcon());
        updateEntity.setModified(LocalDateTime.now());
        updateEntity.setModifiedId(userContext.getUserId());
        updateEntity.setModifiedName(userContext.getUserName());

        this.customTableDefinitionService.updateById(updateEntity);

        return entity.getId();

    }

    @DSTransactional(rollbackFor = Exception.class)
    @Override
    public Long copyTableDefinition(Long tableId, UserContext userContext) {
        // 1. 查询表定义
        CustomTableDefinitionModel tableModel = this.self.queryOneInfoById(tableId);
        if (tableModel == null) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 获取字段定义
        List<CustomFieldDefinitionModel> fieldModels = tableModel.getFieldList();

        // 2. 创建新表
        String newTableName = tableModel.getTableName() + "_copy";
        tableModel.setTableName(newTableName);
        tableModel.setId(null);

        // 3. 新增新表定义
        Long newTableId = this.addInfo(tableModel);

        // 4. 去掉主键id,设置绑定的新 newTableId,然后入库
        fieldModels.forEach(item -> {
            item.setId(null);
            item.setTableId(newTableId);
        });
        var fieldEntities = fieldModels.stream()
                .map(customFieldDefinitionTranslator::convertToEntity)
                .collect(Collectors.toList());
        this.customFieldDefinitionService.batchAddInfo(fieldEntities);

        // 5. 更新doris表名
        var updateEntity = new CustomTableDefinition();
        updateEntity.setId(newTableId);
        var dorisTableName = DorisConfigContants.obtainTableName(newTableId);
        updateEntity.setDorisTable(dorisTableName);

        this.customTableDefinitionService.updateById(updateEntity);

        return newTableId;

    }

    @Override
    public void updateTableLastModified(Long tableId, UserContext userContext) {

        //根据id查询,看数据是否存在
        var existEntity = this.customTableDefinitionService.queryOneInfoById(tableId);
        if (existEntity == null) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        var updateEntity = new CustomTableDefinition();
        updateEntity.setId(tableId);
        updateEntity.setModified(LocalDateTime.now());
        updateEntity.setModifiedId(userContext.getUserId());
        updateEntity.setModifiedName(userContext.getUserName());
        this.customTableDefinitionService.updateById(updateEntity);
    }

    @Override
    public List<CustomTableDefinitionModel> queryListBySpaceId(Long spaceId) {
        List<CustomTableDefinition> entityList = customTableDefinitionService.queryListBySpaceId(spaceId);
        if (CollectionUtils.isEmpty(entityList)) {
            return Collections.emptyList();
        }
        var data = entityList.stream()
                .map(customTableDefinitionTranslator::convertToModel)
                .collect(Collectors.toList());

        return fillFieldList(data);
    }

    @Override
    public Long countTotalTables(Long userId) {
        if (userId == null) {
            return customTableDefinitionService.count();
        }
        LambdaQueryWrapper<CustomTableDefinition> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(CustomTableDefinition::getCreatorId, userId);
        return customTableDefinitionService.count(queryWrapper);
    }

}
