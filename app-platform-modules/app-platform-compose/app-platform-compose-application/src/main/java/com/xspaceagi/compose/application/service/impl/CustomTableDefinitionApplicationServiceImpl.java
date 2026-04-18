package com.xspaceagi.compose.application.service.impl;

import com.alibaba.fastjson2.JSON;
import com.google.common.collect.Lists;
import com.xspaceagi.agent.core.sdk.dto.AgentInfoDto;
import com.xspaceagi.compose.adapter.IAgentRpcServiceAdapter;
import com.xspaceagi.compose.application.model.QueryDorisTableDefinePageRequestVo;
import com.xspaceagi.compose.application.service.CustomTableDefinitionApplicationService;
import com.xspaceagi.compose.domain.dto.CustomAddBusinessRowDataVo;
import com.xspaceagi.compose.domain.dto.CustomDeleteBusinessRowDataVo;
import com.xspaceagi.compose.domain.dto.CustomEmptyTableVo;
import com.xspaceagi.compose.domain.dto.CustomUpdateBusinessRowDataVo;
import com.xspaceagi.compose.domain.model.CustomDorisDataRequest;
import com.xspaceagi.compose.domain.model.CustomFieldDefinitionModel;
import com.xspaceagi.compose.domain.model.CustomTableDefinitionModel;
import com.xspaceagi.compose.domain.repository.ICustomTableDefinitionRepository;
import com.xspaceagi.compose.domain.service.CustomDorisTableDomainService;
import com.xspaceagi.compose.domain.service.CustomTableDefinitionDomainService;
import com.xspaceagi.compose.domain.util.excel.ExcelUtils;
import com.xspaceagi.compose.domain.util.excel.ReadExcelDataVo;
import com.xspaceagi.compose.sdk.DorisDataPage;
import com.xspaceagi.compose.sdk.enums.DefaultTableFieldEnum;
import com.xspaceagi.compose.sdk.vo.define.CreateTableDefineVo;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.domain.log.LogPrint;
import com.xspaceagi.system.domain.track.reporter.OperateLogExtraData;
import com.xspaceagi.system.infra.service.QueryVoListDelegateService;
import com.xspaceagi.system.sdk.operate.ActionType;
import com.xspaceagi.system.sdk.operate.OperationLogReporter;
import com.xspaceagi.system.sdk.operate.SystemEnum;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.server.IUserRpcService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;
import com.xspaceagi.system.spec.page.PageQueryParamVo;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class CustomTableDefinitionApplicationServiceImpl implements CustomTableDefinitionApplicationService {

    @Resource
    private CustomTableDefinitionDomainService customTableDefinitionDomainService;

    @Resource
    private CustomDorisTableDomainService customDorisTableDomainService;

    @Resource
    private ICustomTableDefinitionRepository customTableDefinitionRepository;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private QueryVoListDelegateService queryVoListDelegateService;

    /**
     *
     */
    @Resource
    private UserApplicationService userApplicationService;

    /**
     * 智能体服务适配器
     */
    @Resource
    private IAgentRpcServiceAdapter agentRpcServiceAdapter;

    @Lazy
    @Resource
    private CustomTableDefinitionApplicationService self;


    @Resource
    private IUserRpcService userRpcService;


    @Override
    public Long addInfo(CustomEmptyTableVo model, UserContext userContext) {
        // 校验用户和空间对应权限,校验用户查询表业务数据的权限,不能超出用户有权限的空间(spaceId)
        var spaceId = model.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        return this.myAddInfoNoCheckSpace(model, userContext);

    }

    @Override
    public Long myAddInfoNoCheckSpace(CustomEmptyTableVo model, UserContext userContext) {
        // 新增 表结构
        var tableId = customTableDefinitionDomainService.addInfo(model, userContext);

        var tableModel = customTableDefinitionDomainService.queryOneTableInfoById(tableId);

        // 新增 doris 表结构
        customDorisTableDomainService.createTable(tableModel);

        return tableId;
    }

    @Override
    public void updateInfo(Long tableId, List<CustomFieldDefinitionModel> fieldList, UserContext userContext) {
        CustomTableDefinitionModel tableModel = customTableDefinitionDomainService.queryOneTableInfoById(tableId);
        if (Objects.isNull(tableModel)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 校验用户和空间对应权限,校验用户查询表业务数据的权限,不能超出用户有权限的空间(spaceId)
        var spaceId = tableModel.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        this.myUpdateInfoNoCheckSpace(tableId, fieldList, userContext);
    }

    @Override
    public void myUpdateInfoNoCheckSpace(Long tableId, List<CustomFieldDefinitionModel> fieldList,
                                         UserContext userContext) {
        CustomTableDefinitionModel tableModel = customTableDefinitionDomainService.queryOneTableInfoById(tableId);
        if (Objects.isNull(tableModel)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        var spaceId = tableModel.getSpaceId();

        // 不全字段的 spaceId 等字段,从主表上获取补充,避免新增字段缺失
        fieldList.forEach(field -> {
            field.setSpaceId(spaceId);
            field.setTenantId(tableModel.getTenantId());
            field.setTableId(tableModel.getId());
            field.setCreatorId(userContext.getUserId());
            field.setCreatorName(userContext.getUserName());
            field.setModifiedId(userContext.getUserId());
            field.setModifiedName(userContext.getUserName());
            field.setModified(LocalDateTime.now());
            if (Objects.nonNull(field.getFieldName())) {
                //去掉两边空格
                field.setFieldName(field.getFieldName().trim());
            }

        });
        // check fieldList 是否存在重复的 field_name,把所有重复字段列出来,提示给用户
        var fieldMap = fieldList.stream()
                .collect(Collectors.groupingBy(CustomFieldDefinitionModel::getFieldName));
        // 把重复的字段列出来,提示给用户
        var duplicateFieldList = fieldMap.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
        if (!duplicateFieldList.isEmpty()) {
            var duplicateFieldNames = String.join(",", duplicateFieldList);
            log.info("Field [{}] already exists", duplicateFieldNames);
            throw ComposeException.build(BizExceptionCodeEnum.composeFieldAlreadyExists, duplicateFieldNames);
        }

        customTableDefinitionDomainService.updateInfo(tableModel, fieldList, userContext);

        // 修改 TableModel的最后更新时间
        customTableDefinitionDomainService.updateTableLastModified(tableId, userContext);
    }

    @Override
    public void deleteById(Long tableId, UserContext userContext) {

        CustomTableDefinitionModel tableModel = customTableDefinitionDomainService.queryOneTableInfoById(tableId);
        if (Objects.isNull(tableModel)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 校验用户和空间对应权限,校验用户查询表业务数据的权限,不能超出用户有权限的空间(spaceId)
        var spaceId = tableModel.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);
        // 2. 删除Doris表
        customDorisTableDomainService.dropTable(tableModel.getDorisDatabase(), tableModel.getDorisTable());

        // 1. 删除表定义
        customTableDefinitionDomainService.deleteById(tableId, userContext);

    }

    @Override
    public void exportTableDataToExcel(Long tableId, OutputStream outputStream, UserContext userContext) {
        CustomTableDefinitionModel tableDefinitionModel = this.customTableDefinitionRepository
                .queryOneInfoById(tableId);
        if (Objects.isNull(tableDefinitionModel)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 校验用户和空间对应权限,校验用户查询表业务数据的权限,不能超出用户有权限的空间(spaceId)
        var spaceId = tableDefinitionModel.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        customTableDefinitionDomainService.exportTableDataToExcel(tableId, outputStream, userContext);
    }

    @Override
    public DorisDataPage<Map<String, Object>> queryPageDorisTableDataForWeb(CustomDorisDataRequest request) {

        var tableId = request.getTableId();
        CustomTableDefinitionModel tableDefinitionModel = this.customTableDefinitionRepository
                .queryOneInfoById(tableId);
        if (Objects.isNull(tableDefinitionModel)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 校验用户和空间对应权限,校验用户查询表业务数据的权限,不能超出用户有权限的空间(spaceId)
        var spaceId = tableDefinitionModel.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        var data = customDorisTableDomainService.queryPageDorisTableDataForWeb(request, tableDefinitionModel);
        // 对 agent_name,nick_name,user_name
        // 字段,根据id,读取用户信息,以及智能体信息,替换中文名称,因为中文名称可能修改,所以需要根据id来读取
        // 查询所有的 uid 字段,即用户id
        List<String> uidFieldList = data.getRecords().stream()
                .map(record -> {
                    var uid = record.get(DefaultTableFieldEnum.UID.getFieldName());
                    return uid;
                })
                .filter(Objects::nonNull)
                .distinct()
                .map(item -> item.toString())
                .collect(Collectors.toList());

        var userList = this.userApplicationService.queryUserListByUids(uidFieldList);
        // 将 userList 转换为 map,key为id,value为user
        Map<String, UserDto> userMap = userList.stream()
                .collect(Collectors.toMap(UserDto::getUid, Function.identity()));

        // 查询所有的 agent_id 字段,即智能体ID
        List<Long> agentIdFieldList = data.getRecords().stream()
                .map(record -> {
                    var agentId = record.get(DefaultTableFieldEnum.AGENT_ID.getFieldName());
                    return agentId;
                })
                .filter(Objects::nonNull)
                .distinct()
                .map(item -> {
                    try {
                        return Long.parseLong(item.toString());
                    } catch (NumberFormatException e) {
                        log.warn("agentId not Long, skip row: {}", item);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        // 等agent智能体提供sdk接口,读取智能体信息,补充智能体名称
        List<AgentInfoDto> agentListResult = agentRpcServiceAdapter.queryAgentInfoList(agentIdFieldList);
        // 将agentList转换为map,key为id,value为agent

        Map<Long, AgentInfoDto> agentMap = agentListResult.stream()
                .collect(Collectors.toMap(AgentInfoDto::getId, Function.identity()));

        // 补充用户名称:nick_name,user_name
        data.getRecords().forEach(record -> {
            var uid = record.get(DefaultTableFieldEnum.UID.getFieldName());
            if (Objects.nonNull(uid)) {
                var user = userMap.get(uid);
                // 使用optional来判断
                Optional.ofNullable(user).ifPresent(userDto -> {
                    record.put(DefaultTableFieldEnum.NICK_NAME.getFieldName(), userDto.getNickName());
                    record.put(DefaultTableFieldEnum.USER_NAME.getFieldName(), userDto.getUserName());
                });
            }
            var agentId = record.get(DefaultTableFieldEnum.AGENT_ID.getFieldName());
            if (Objects.nonNull(agentId)) {
                try {
                    var agent = agentMap.get(Long.parseLong(agentId.toString()));
                    Optional.ofNullable(agent).ifPresent(agentInfoDto -> {
                        record.put(DefaultTableFieldEnum.AGENT_NAME.getFieldName(), agentInfoDto.getName());
                    });
                } catch (NumberFormatException e) {
                    log.warn("agentId not Long, skip agentName: {}", agentId);
                }
            }
        });

        return data;
    }

    @Override
    public CustomTableDefinitionModel queryOneTableDefineVo(Long tableId) {
        CustomTableDefinitionModel tableInfo = customTableDefinitionDomainService.queryOneTableInfoById(tableId);
        if (Objects.isNull(tableInfo)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 校验用户和空间对应权限,校验用户查询表业务数据的权限,不能超出用户有权限的空间(spaceId)
        var spaceId = tableInfo.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 获取表的DDL语句
        try {
            String createTableDdl = customTableDefinitionDomainService.getCreateTableDdl(tableId, tableInfo);
            // 设置原始建表DDL
            tableInfo.setCreateTableDdl(createTableDdl);
        } catch (Exception e) {
            log.error("Get table DDL failed, tableId: {}", tableId, e);
        }

        return tableInfo;
    }

    @Override
    public Map<String, Object> queryRowDataByTableIdAndRowId(Long tableId, Long rowId) {
        // 1. 查询表定义
        CustomTableDefinitionModel tableDefinitionModel = this.customTableDefinitionRepository
                .queryOneInfoById(tableId);
        if (Objects.isNull(tableDefinitionModel)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "表定义不存在，tableId=" + tableId);
        }

        // 2. 校验空间权限
        var spaceId = tableDefinitionModel.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 3. 构建查询条件
        Map<String, Object> conditions = new java.util.HashMap<>();
        conditions.put("id", rowId);

        // 4. 查询单行数据
        List<Map<String, Object>> rowDataList = customDorisTableDomainService.queryAllTableData(
                tableDefinitionModel.getDorisDatabase(),
                tableDefinitionModel.getDorisTable(),
                conditions,
                null,
                1  // 只查询1条
        );

        // 5. 返回结果
        if (CollectionUtils.isEmpty(rowDataList)) {
            return null;
        }

        return rowDataList.get(0);
    }

    @Override
    public void addBusinessData(CustomAddBusinessRowDataVo request, UserContext userContext) {
        // 1. 查询表定义
        CustomTableDefinitionModel tableDefinitionModel = this.customTableDefinitionRepository
                .queryOneInfoById(request.getTableId());
        if (Objects.isNull(tableDefinitionModel)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 校验用户和空间对应权限,校验用户查询表业务数据的权限,不能超出用户有权限的空间(spaceId)
        var spaceId = tableDefinitionModel.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 2. 新增业务数据
        customDorisTableDomainService.addBusinessData(request, tableDefinitionModel, userContext);
    }

    @Override
    public void updateBusinessData(CustomUpdateBusinessRowDataVo request, UserContext userContext) {
        // 1. 查询表定义
        CustomTableDefinitionModel tableDefinitionModel = this.customTableDefinitionRepository
                .queryOneInfoById(request.getTableId());
        if (Objects.isNull(tableDefinitionModel)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 校验用户和空间对应权限,校验用户查询表业务数据的权限,不能超出用户有权限的空间(spaceId)
        var spaceId = tableDefinitionModel.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);
        // 根据主键id,查询对应数据是否存在,如果存在,在进行根据主键id来更新
        var isExist = customDorisTableDomainService.queryDorisTableRowDataById(tableDefinitionModel.getDorisDatabase(),
                tableDefinitionModel.getDorisTable(), request.getRowId());
        // 如果不存在,则主动抛异常
        if (!isExist) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 2. 修改业务数据
        customDorisTableDomainService.updateBusinessData(request, tableDefinitionModel, userContext);
    }

    @OperationLogReporter(
            actionType = ActionType.DELETE,
            action = "删除业务数据",
            objectName = "业务数据操作",
            systemCode = SystemEnum.DB_TABLE,
            spelExpression = "{'request': #request, 'rowData': #rowData}"  // 记录删除请求和完整行数据
    )
    @LogPrint(step = "业务数据操作-删除业务数据")
    @Override
    public void deleteBusinessData(CustomDeleteBusinessRowDataVo request, Map<String, Object> rowData, UserContext userContext) {
        // 1. 查询表定义

        CustomTableDefinitionModel tableDefinitionModel = this.customTableDefinitionRepository
                .queryOneInfoById(request.getTableId());
        if (Objects.isNull(tableDefinitionModel)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 校验用户和空间对应权限,校验用户查询表业务数据的权限,不能超出用户有权限的空间(spaceId)
        var spaceId = tableDefinitionModel.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 根据主键id,查询对应数据是否存在,如果存在,在进行根据主键id来删除
        var isExist = customDorisTableDomainService.queryDorisTableRowDataById(tableDefinitionModel.getDorisDatabase(),
                tableDefinitionModel.getDorisTable(), request.getRowId());
        // 如果不存在,则主动抛异常
        if (!isExist) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        // 2. 删除业务数据
        customDorisTableDomainService.deleteBusinessData(request, tableDefinitionModel, userContext);
    }

    @Override
    public void clearBusinessData(Long tableId, UserContext userContext) {
        // 1. 查询表定义
        CustomTableDefinitionModel tableDefinitionModel = this.customTableDefinitionRepository
                .queryOneInfoById(tableId);
        if (Objects.isNull(tableDefinitionModel)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 校验用户和空间对应权限,校验用户查询表业务数据的权限,不能超出用户有权限的空间(spaceId)
        var spaceId = tableDefinitionModel.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 2. 清空业务数据
        customDorisTableDomainService.clearBusinessData(tableId, tableDefinitionModel, userContext);
    }

    @Override
    public void updateTableName(CustomTableDefinitionModel model, UserContext userContext) {
        // 1. 查询表定义
        CustomTableDefinitionModel tableDefinitionModel = this.customTableDefinitionRepository
                .queryOneInfoById(model.getId());
        if (Objects.isNull(tableDefinitionModel)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 校验用户和空间对应权限,校验用户查询表业务数据的权限,不能超出用户有权限的空间(spaceId)
        var spaceId = tableDefinitionModel.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 2. 更新表名称
        customTableDefinitionDomainService.updateTableName(model, userContext);

    }

    @Override
    public SuperPage<CustomTableDefinitionModel> queryPageTableDefine(
            PageQueryVo<QueryDorisTableDefinePageRequestVo> pageQueryVo) {

        var filter = pageQueryVo.getQueryFilter();
        pageQueryVo.setQueryFilter(filter);

        PageQueryParamVo pageQueryParamVo = new PageQueryParamVo(pageQueryVo);

        SuperPage<CustomTableDefinitionModel> superPage = this.queryVoListDelegateService.queryVoList(
                this.customTableDefinitionRepository,
                pageQueryParamVo, null);

        return superPage;

    }


    @Override
    public List<CustomTableDefinitionModel> queryTableDefineBySpaceId(Long spaceId) {

        var data = this.customTableDefinitionDomainService.queryTableDefineBySpaceId(spaceId);

        return data;
    }

    @Override
    public Boolean existTableData(Long tableId) {
        CustomTableDefinitionModel tableDefinitionModel = this.customTableDefinitionRepository
                .queryOneInfoById(tableId);
        if (Objects.isNull(tableDefinitionModel)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        return customDorisTableDomainService.existTableData(tableDefinitionModel.getDorisDatabase(),
                tableDefinitionModel.getDorisTable());
    }

    @Override
    public Long copyTableDefinition(Long tableId, UserContext userContext) {
        var tableModel = this.customTableDefinitionRepository.queryOneInfoById(tableId);
        if (tableModel == null) {
            throw ComposeException.build(BizExceptionCodeEnum.composeTableDefinitionNotFound);
        }
        // 校验用户和空间对应权限,校验用户查询表业务数据的权限,不能超出用户有权限的空间(spaceId)
        var spaceId = tableModel.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);
        // 复制表结构定义
        var newTableId = this.customTableDefinitionDomainService.copyTableDefinition(tableId, userContext);

        // 创建doris库表,需要重新查询表定义
        var newTableModel = this.customTableDefinitionRepository.queryOneInfoById(newTableId);
        customDorisTableDomainService.createTable(newTableModel);

        return newTableId;
    }

    @Override
    public void importTableDataFromExcel(Long tableId, InputStream inputStream, UserContext userContext) {
        // 1. 查询表定义
        CustomTableDefinitionModel tableDefinitionModel = this.customTableDefinitionRepository
                .queryOneInfoById(tableId);
        if (Objects.isNull(tableDefinitionModel)) {
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }

        // 校验用户和空间对应权限
        var spaceId = tableDefinitionModel.getSpaceId();
        spacePermissionService.checkSpaceUserPermission(spaceId);

        // 2. 读取并解析Excel
        ReadExcelDataVo readExcelDataVo = ExcelUtils.readExcel(inputStream, tableDefinitionModel);
        List<Map<String, Object>> excelData = readExcelDataVo.getData();
        List<String> newFieldsToCreate = readExcelDataVo.getNewFieldsToCreate();

        // 3. 如果有新字段，先创建字段，并获取更新后的表定义和字段映射
        if (!CollectionUtils.isEmpty(newFieldsToCreate)) {
            log.info("Detected {} new fields, auto-creating in domain...", newFieldsToCreate.size());
            var fieldCreationResult = customTableDefinitionDomainService.handleNewFieldsFromExcel(
                    tableDefinitionModel, newFieldsToCreate, userContext);

            tableDefinitionModel = fieldCreationResult.getUpdatedTableModel();
            ExcelUtils.remapExcelDataKeys(excelData, fieldCreationResult.getNewFieldMapping());
            log.info("New fields created; table def refreshed and data remapped.");
        }

        // 4. 导入业务数据
        if (!CollectionUtils.isEmpty(excelData)) {
            //针对 excelData 表格数据,根据 userContext 的用户信息,插入 uid,user_name,nick_name 字段
            excelData.forEach(record -> {
                record.put(DefaultTableFieldEnum.UID.getFieldName(), userContext.getUid());
                record.put(DefaultTableFieldEnum.USER_NAME.getFieldName(), userContext.getUserName());
                record.put(DefaultTableFieldEnum.NICK_NAME.getFieldName(), userContext.getNickName());
            });
            customDorisTableDomainService.batchInsertData(tableDefinitionModel, excelData, userContext);
        } else {
            log.warn("No valid Excel rows, skip import, tableId: {}", tableId);
        }
    }

    @Override
    public Long createTable(CreateTableDefineVo tableDefineVo) {
        // 新增 表结构
        var userList = this.userRpcService.queryUserListByIds(Lists.newArrayList(tableDefineVo.getCreatorId()));
        var userContext = userList.stream()
                .findFirst()
                .orElse(UserContext.builder()
                        .userId(tableDefineVo.getCreatorId())
                        .userName("未知用户")
                        .nickName("未知用户")
                        .build());

        var model = CustomEmptyTableVo.convertToVo(tableDefineVo);
        var tableId = this.self.myAddInfoNoCheckSpace(model, userContext);
        // 表字段,组装更新数据表定义 List<CustomFieldDefinitionModel>

        // 组装表字段
        List<CustomFieldDefinitionModel> fieldList = tableDefineVo.getFieldList().stream()
                .map(CustomFieldDefinitionModel::convertToModelForCreate)
                .collect(Collectors.toList());

        this.self.myUpdateInfoNoCheckSpace(tableId, fieldList, userContext);
        return tableId;
    }

    @Override
    public String restoreBusinessDataByExtraContent(String extraContent, Boolean forceRestore, UserContext userContext) {
        try {
            // 1. 解析 extraContent JSON
            if (!StringUtils.hasText(extraContent)) {
                throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "extraContent 不能为空");
            }

            OperateLogExtraData extraData = JSON.parseObject(extraContent, OperateLogExtraData.class);
            if (extraData == null || extraData.getSpelData() == null) {
                throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "日志中没有记录删除的数据信息");
            }

            // 2. 解析 spelData（包含 request 和 rowData）
            @SuppressWarnings("unchecked")
            Map<String, Object> spelDataMap = JSON.parseObject(
                    JSON.toJSONString(extraData.getSpelData()),
                    Map.class
            );

            if (spelDataMap == null) {
                throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "无法解析日志中的 spelData");
            }

            // 3. 提取 request 信息
            @SuppressWarnings("unchecked")
            Map<String, Object> requestMap = (Map<String, Object>) spelDataMap.get("request");
            if (requestMap == null) {
                throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "日志中缺少 request 信息");
            }

            Object tableIdObj = requestMap.get("tableId");
            Object rowIdObj = requestMap.get("rowId");

            if (tableIdObj == null || rowIdObj == null) {
                throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "日志中缺少 tableId 或 rowId");
            }

            Long tableId = Long.valueOf(tableIdObj.toString());
            Long rowId = Long.valueOf(rowIdObj.toString());

            // 4. 提取完整的行数据
            @SuppressWarnings("unchecked")
            Map<String, Object> rowData = (Map<String, Object>) spelDataMap.get("rowData");
            if (rowData == null || rowData.isEmpty()) {
                throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "日志中没有记录完整的行数据");
            }

            log.info("Restore data start: tableId={}, rowId={}, forceRestore={}", tableId, rowId, forceRestore);

            // 5. 查询表定义
            CustomTableDefinitionModel tableModel = this.customTableDefinitionRepository.queryOneInfoById(tableId);
            if (tableModel == null) {
                throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "表定义不存在，tableId=" + tableId);
            }

            // 6. 校验空间权限
            spacePermissionService.checkSpaceUserPermission(tableModel.getSpaceId());

            // 7. 检查数据是否已存在
            boolean dataExists = customDorisTableDomainService.queryDorisTableRowDataById(
                    tableModel.getDorisDatabase(),
                    tableModel.getDorisTable(),
                    rowId
            );

            if (dataExists) {
                if (!Boolean.TRUE.equals(forceRestore)) {
                    return "数据已存在（rowId=" + rowId + "），如需覆盖请开启强制恢复";
                }
                // 强制恢复：先删除已存在的数据
                log.info("Force restore: delete existing row, rowId={}", rowId);
                customDorisTableDomainService.deleteDorisTableRowDataById(
                        tableModel.getDorisDatabase(),
                        tableModel.getDorisTable(),
                        rowId
                );
            }

            // 8. 准备恢复数据
            // 确保 rowData 中包含正确的 id
            rowData.put("id", rowId);

            // 构建新增请求
            CustomAddBusinessRowDataVo addRequest = new CustomAddBusinessRowDataVo();
            addRequest.setTableId(tableId);
            addRequest.setRowData(rowData);

            // 9. 执行数据恢复（本质上是重新插入）
            customDorisTableDomainService.addBusinessData(addRequest, tableModel, userContext);

            log.info("Restore OK: tableId={}, rowId={}", tableId, rowId);
            return "数据恢复成功，rowId=" + rowId;

        } catch (ComposeException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to restore data", e);
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound, "恢复数据失败：" + e.getMessage());
        }
    }

    @Override
    public Long countUserTotalTable(Long userId) {
        return customTableDefinitionRepository.countTotalTables(userId);
    }
}
