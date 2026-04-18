package com.xspaceagi.compose.api.service;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.dynamic.datasource.tx.DsPropagation;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.common.collect.Lists;
import com.xspaceagi.compose.api.tanslator.CustomTableDefinitionModelTranslator;
import com.xspaceagi.compose.application.model.QueryDorisTableDefinePageRequestVo;
import com.xspaceagi.compose.application.service.CustomTableDefinitionApplicationService;
import com.xspaceagi.compose.domain.model.CustomTableDefinitionModel;
import com.xspaceagi.compose.domain.service.CustomDorisTableDomainService;
import com.xspaceagi.compose.domain.service.CustomTableDefinitionDomainService;
import com.xspaceagi.compose.domain.util.SqlParserUtil;
import com.xspaceagi.compose.domain.util.SqlParserUtil.SqlType;
import com.xspaceagi.compose.infra.dao.entity.CustomTableDefinition;
import com.xspaceagi.compose.infra.dao.service.CustomTableDefinitionService;
import com.xspaceagi.compose.infra.translator.CustomTableDefinitionTranslator;
import com.xspaceagi.compose.sdk.DorisDataPage;
import com.xspaceagi.compose.sdk.request.DorisTableDataRequest;
import com.xspaceagi.compose.sdk.request.DorisTableDefineRequest;
import com.xspaceagi.compose.sdk.request.DorisToolTableDefineRequest;
import com.xspaceagi.compose.sdk.request.QueryDorisTableDefinePageRequest;
import com.xspaceagi.compose.sdk.response.DorisTableDataResponse;
import com.xspaceagi.compose.sdk.response.DorisToolTableDefineResponse;
import com.xspaceagi.compose.sdk.service.IComposeDbTableRpcService;
import com.xspaceagi.compose.sdk.vo.define.CreateTableDefineVo;
import com.xspaceagi.compose.sdk.vo.define.TableDefineVo;
import com.xspaceagi.compose.spec.utils.ComposeExceptionUtils;
import com.xspaceagi.system.domain.log.LogRecordPrint;
import com.xspaceagi.system.sdk.server.IUserRpcService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.ComposeException;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.utils.IPUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ComposeDbTableRpcServiceImpl implements IComposeDbTableRpcService {

    @Resource
    private CustomTableDefinitionDomainService customTableDefinitionDomainService;

    @Resource
    private CustomDorisTableDomainService customDorisTableDomainService;

    @Resource
    private CustomTableDefinitionApplicationService customTableDefinitionApplicationService;

    @Resource
    private CustomTableDefinitionService customTableDefinitionService;

    @Resource
    private CustomTableDefinitionTranslator customTableDefinitionTranslator;

    @Resource
    private IUserRpcService userRpcService;

    @LogRecordPrint(content = "查询数据表的结构定义信息")
    @DSTransactional(propagation = DsPropagation.NOT_SUPPORTED)
    @Override
    public TableDefineVo queryTableDefinition(DorisTableDefineRequest request) {
        log.info("Query table def, request: {}", request);
        var tableId = request.getTableId();
        // 先通过spaceId查询表定义信息
        var tableInfo = customTableDefinitionDomainService.queryOneTableInfoById(tableId);
        if (tableInfo == null) {
            return null;
        }
        // 获取表的DDL语句
        String createTableDdl = customTableDefinitionDomainService.getCreateTableDdl(tableId, tableInfo);
        // 设置原始建表DDL
        tableInfo.setCreateTableDdl(createTableDdl);

        // 将表定义信息转换为TableDefineVo
        var tableDefineVo = CustomTableDefinitionModelTranslator.translate(tableInfo);

        // 补充用户信息,比如:昵称
        var userId = tableInfo.getCreatorId();
        var userIds = Lists.newArrayList(userId);

        var userList = this.userRpcService.queryUserListByIds(userIds);
        var userInfoDto = userList.stream()
                .findFirst()
                .orElse(null);

        if (userInfoDto != null) {
            tableDefineVo.setCreatorName(userInfoDto.getUserName());
            tableDefineVo.setCreatorNickName(userInfoDto.getNickName());
            tableDefineVo.setCreatorAvatar(userInfoDto.getAvatar());
        }

        return tableDefineVo;
    }

    @LogRecordPrint(content = "查询数据表的业务数据")
    @DSTransactional(propagation = DsPropagation.NOT_SUPPORTED)
    @Override
    public DorisTableDataResponse queryTableData(DorisTableDataRequest request) {
        // 先通过tableId查询表定义信息
        CustomTableDefinitionModel tableInfo = customTableDefinitionDomainService
                .queryOneTableInfoById(request.getTableId());
        if (tableInfo == null) {
            log.error("Table definition empty, tableId: {}", request.getTableId());
            throw ComposeException.build(BizExceptionCodeEnum.resourceDataNotFound);
        }
        DorisTableDataResponse response = new DorisTableDataResponse();

        // 表定义
        var tableDefine = CustomTableDefinitionModelTranslator.translate(tableInfo);
        if (Objects.isNull(tableDefine)) {
            log.error("Table definition empty, tableId: {}", request.getTableId());
            return null;
        }

        try {
            var database = tableInfo.getDorisDatabase();

            // 设置执行的SQL,替换里面的表名,参数绑定
            String executeSql = customDorisTableDomainService.generateExecuteSql(request, tableInfo);

            // 使用SqlParserUtil验证和修改SQL
            // 1. 验证SQL语法和安全性
            SqlParserUtil.validateSql(executeSql);

            // 获取sql操作类型
            var sqlOperationType = SqlParserUtil.getSqlOperationType(executeSql);

            // 执行修改后的SQL
            var executeRawResultVo = customDorisTableDomainService.executeRawQuery(database, executeSql);
            response.setData(executeRawResultVo.getData());
            response.setRowId(executeRawResultVo.getRowId());

            // sql执行影响行数,如果是select查询,使用返回结果的size,如果是新增/修改/删除,使用执行sql的影响行数
            if (SqlType.SELECT.equals(sqlOperationType)) {
                var rowNum = executeRawResultVo.getData().size();
                response.setRowNum((long) rowNum);
            } else {
                var rowNum = executeRawResultVo.getRowNum();
                response.setRowNum(rowNum);
            }

            // 设置建表DDL
            String createTableDdl = customTableDefinitionDomainService.getCreateTableDdl(request.getTableId(),
                    tableInfo);
            tableDefine.setCreateTableDdl(createTableDdl);
            response.setTableDefineVo(tableDefine);

            return response;
        } catch (ComposeException ce) {
            log.error("DML SQL execution error", ce);
            throw ce;
        } catch (JSQLParserException e) {
            log.error("SQL parse failed: tableId={}", request.getTableId(), e);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlParseFailed, e.getMessage());
        } catch (Exception e) {
            log.error("SQL exec failed: tableId={}", request.getTableId(), e);
            var errorMessage = ComposeExceptionUtils.getRootErrorMessage(e);
            throw ComposeException.build(BizExceptionCodeEnum.composeSqlExecuteFailed, errorMessage);
        }
    }

    @LogRecordPrint(content = "根据条件,查询所有数据表的结构定义")
    @DSTransactional(propagation = DsPropagation.NOT_SUPPORTED)
    @Override
    public DorisToolTableDefineResponse queryUserToolTableDefine(DorisToolTableDefineRequest request) {
        log.info("Query user tool table def, request: {}", request);
        // 先通过tableId查询表定义信息
        var tableInfoList = customTableDefinitionDomainService.queryAllTableDefineList(request);

        DorisToolTableDefineResponse response = new DorisToolTableDefineResponse();
        var tableDefineList = tableInfoList.stream()
                .map(CustomTableDefinitionModelTranslator::translate)
                .collect(Collectors.toList());
        response.setTableDefineList(tableDefineList);
        return response;
    }

    @LogRecordPrint(content = "分页查询空间下的表定义")
    @DSTransactional(propagation = DsPropagation.NOT_SUPPORTED)
    @Override
    public DorisDataPage<TableDefineVo> queryTableDefineBySpaceId(QueryDorisTableDefinePageRequest request) {

        PageQueryVo<QueryDorisTableDefinePageRequestVo> pageQueryVo = new PageQueryVo<>();

        var current = 1L;
        if (Objects.nonNull(request.getPageNo())) {
            current = request.getPageNo();
        }
        var pageSize = 100L;
        if (Objects.nonNull(request.getPageSize())) {
            pageSize = request.getPageSize();
        }
        pageQueryVo.setCurrent(current);
        pageQueryVo.setPageSize(pageSize);

        var queryDorisTableDefinePageRequestVo = QueryDorisTableDefinePageRequestVo
                .convert(request);
        pageQueryVo.setQueryFilter(queryDorisTableDefinePageRequestVo);

        // 先通过spaceId查询表定义信息
        var tableInfoList = customTableDefinitionApplicationService.queryPageTableDefine(pageQueryVo);

        // 将表定义信息转换为TableDefineVo
        var tableDefineVoList = tableInfoList.getRecords().stream()
                .map(CustomTableDefinitionModelTranslator::translate)
                .collect(Collectors.toList());

        // 补充用户信息,比如:昵称
        var userIds = tableInfoList.getRecords().stream()
                .map(CustomTableDefinitionModel::getCreatorId)
                .filter(Objects::nonNull)
                .toList();

        var userList = this.userRpcService.queryUserListByIds(userIds);
        var userMap = userList.stream()
                .collect(Collectors.toMap(UserContext::getUserId, userContext -> userContext));

        tableDefineVoList.forEach(item -> {
            UserContext userContext = userMap.get(item.getCreatorId());
            if (userContext != null) {
                item.setCreatorName(userContext.getUserName());
                item.setCreatorNickName(userContext.getNickName());
                item.setCreatorAvatar(userContext.getAvatar());
            }
        });

        // 将TableDefineVo列表转换为DorisDataPage
        var dorisDataPage = new DorisDataPage<TableDefineVo>(request.getPageNo(), request.getPageSize(),
                tableDefineVoList.size(), tableDefineVoList);
        return dorisDataPage;
    }

    @Override
    public List<TableDefineVo> queryListBySpaceId(Long spaceId) {
        var tableInfoList = customTableDefinitionDomainService.queryTableDefineBySpaceId(spaceId);
        var tableDefineVoList = tableInfoList.stream()
                .map(CustomTableDefinitionModelTranslator::translate)
                .collect(Collectors.toList());

        // 补充用户信息,比如:昵称
        var userIds = tableInfoList.stream()
                .map(CustomTableDefinitionModel::getCreatorId)
                .filter(Objects::nonNull)
                .toList();

        var userList = this.userRpcService.queryUserListByIds(userIds);
        var userMap = userList.stream()
                .collect(Collectors.toMap(UserContext::getUserId, userContext -> userContext));

        tableDefineVoList.forEach(item -> {
            UserContext userContext = userMap.get(item.getCreatorId());
            if (userContext != null) {
                item.setCreatorName(userContext.getUserName());
                item.setCreatorNickName(userContext.getNickName());
                item.setCreatorAvatar(userContext.getAvatar());
            }
        });
        return tableDefineVoList;
    }

    @LogRecordPrint(content = "创建数据表")
    @DSTransactional(propagation = DsPropagation.NOT_SUPPORTED)
    @Override
    public Long createTable(CreateTableDefineVo tableDefineVo) {
        log.info("Create data table, tableDefineVo: {}", tableDefineVo.getTableName());

        // 检查创建人id是否为空
        if (tableDefineVo.getCreatorId() == null) {
            log.warn("Creator id required, tableDefineVo: {}", tableDefineVo);
            throw ComposeException.build(BizExceptionCodeEnum.fieldRequiredButEmpty, "创建人id");
        }
        var tableId = this.customTableDefinitionApplicationService.createTable(tableDefineVo);
        log.debug("Data table created, tableId: {}", tableId);
        return tableId;
    }

    @LogRecordPrint(content = "查询创建表的表结构定义信息")
    @DSTransactional(propagation = DsPropagation.NOT_SUPPORTED)
    @Override
    public CreateTableDefineVo queryCreateTableInfo(DorisTableDefineRequest request) {
        log.info("Query created table schema, request: {}", request);
        var tableId = request.getTableId();
        // 先通过spaceId查询表定义信息
        var tableInfo = customTableDefinitionDomainService.queryOneTableInfoById(tableId);
        if (tableInfo == null) {
            return null;
        }

        // 获取表的DDL语句
        String createTableDdl = customTableDefinitionDomainService.getCreateTableDdl(tableId, tableInfo);
        // 设置原始建表DDL
        tableInfo.setCreateTableDdl(createTableDdl);

        // 将表定义信息转换为TableDefineVo
        var tableDefineVo = CustomTableDefinitionModelTranslator.translate(tableInfo);

        // 补充用户信息,比如:昵称
        var userId = tableInfo.getCreatorId();
        var userIds = Lists.newArrayList(userId);

        var userList = this.userRpcService.queryUserListByIds(userIds);
        var userInfoDto = userList.stream()
                .findFirst()
                .orElse(null);

        if (userInfoDto != null) {
            tableDefineVo.setCreatorName(userInfoDto.getUserName());
            tableDefineVo.setCreatorNickName(userInfoDto.getNickName());
            tableDefineVo.setCreatorAvatar(userInfoDto.getAvatar());
        }
        try {
            var iconUrl = tableDefineVo.getIcon();
            if (StringUtils.isNotBlank(iconUrl)) {
                // 检查是否为内网URL
                if (IPUtil.isInternalAddress(iconUrl)) {
                    // 如果为内网URL,则设置为null
                    tableDefineVo.setIcon(null);
                }
            }
        } catch (Exception e) {
            log.error("iconUrl parse error: {}", tableDefineVo.getIcon(), e);
        }

        // 转换为CreateTableDefineVo
        var createTableDefineVo = CreateTableDefineVo.convert(tableDefineVo);

        return createTableDefineVo;
    }

    @Override
    public Long countTotalTables() {
        return customTableDefinitionDomainService.countTotalTables();
    }

    @Override
    public IPage<TableDefineVo> queryListForManage(Integer pageNo, Integer pageSize, String name, java.util.List<Long> creatorIds, Long spaceId) {
        LambdaQueryWrapper<CustomTableDefinition> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(spaceId != null && spaceId > 0, CustomTableDefinition::getSpaceId, spaceId);
        queryWrapper.like(StringUtils.isNotBlank(name), CustomTableDefinition::getTableName, name);
        queryWrapper.in(creatorIds != null && !creatorIds.isEmpty(), CustomTableDefinition::getCreatorId, creatorIds);
        queryWrapper.orderByDesc(CustomTableDefinition::getCreated);
        return customTableDefinitionService.page(new Page<>(pageNo, pageSize), queryWrapper).convert(tableInfo -> {
            return CustomTableDefinitionModel.convertToTableDefineVo(customTableDefinitionTranslator.convertToModel(tableInfo));
        });
    }

    @Override
    public void deleteForManage(Long id) {
        var tableInfo = customTableDefinitionDomainService.queryOneTableInfoById(id);
        if (tableInfo != null) {
            customTableDefinitionDomainService.deleteById(id, UserContext.builder()
                    .userId(0L)
                    .userName("admin")
                    .tenantId(tableInfo.getTenantId())
                    .build());
        }
    }

    @Override
    public Long countUserTotalTable(Long userId) {
        return customTableDefinitionApplicationService.countUserTotalTable(userId);
    }
}