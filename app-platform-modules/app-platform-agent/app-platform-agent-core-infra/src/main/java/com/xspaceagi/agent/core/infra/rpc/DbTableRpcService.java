package com.xspaceagi.agent.core.infra.rpc;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.repository.CopyIndexRecordRepository;
import com.xspaceagi.agent.core.spec.utils.CopyRelationCacheUtil;
import com.xspaceagi.compose.sdk.request.DorisTableDefineRequest;
import com.xspaceagi.compose.sdk.service.IComposeDbTableRpcService;
import com.xspaceagi.compose.sdk.vo.define.CreateTableDefineVo;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class DbTableRpcService {


    @Resource
    private IComposeDbTableRpcService iComposeDbTableRpcService;

    @Resource
    private CopyIndexRecordRepository copyIndexRecordRepository;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    public Long createNewTableDefinition(Long userId, Long targetSpaceId, Long originalTableId) {
        try {
            String key = generateTableKey();
            Object value = CopyRelationCacheUtil.get(key, targetSpaceId, originalTableId);
            if (value != null) {
                return (Long) value;
            }
            UserDataPermissionDto userDataPermission = TenantFunctions.callWithIgnoreCheck(() -> userDataPermissionRpcService.getUserDataPermission(userId));
            userDataPermission.checkMaxDataTableCount(TenantFunctions.callWithIgnoreCheck(() -> iComposeDbTableRpcService.countUserTotalTable(userId).intValue()));
            DorisTableDefineRequest dorisTableDefineRequest = new DorisTableDefineRequest();
            dorisTableDefineRequest.setTableId(originalTableId);
            CreateTableDefineVo createTableDefineVo = iComposeDbTableRpcService.queryCreateTableInfo(dorisTableDefineRequest);
            if (createTableDefineVo != null) {
                createTableDefineVo.setCreatorId(userId);
                createTableDefineVo.setSpaceId(targetSpaceId);
                String newName = copyIndexRecordRepository.newCopyName("table", targetSpaceId, createTableDefineVo.getTableName());
                createTableDefineVo.setTableName(newName);
                Long tableId = iComposeDbTableRpcService.createTable(createTableDefineVo);
                CopyRelationCacheUtil.put(key, targetSpaceId, originalTableId, tableId);
                return tableId;
            }
        } catch (Exception e) {
            if (e instanceof BizException) {
                throw (BizException) e;
            }
            // ignore
            log.warn("Failed to query table schema info", e);
        }
        return null;
    }

    private String generateTableKey() {
        String key = "Table";
        if (RequestContext.get() != null && RequestContext.get().getRequestId() != null) {
            key = key + ":" + RequestContext.get().getRequestId();
        }
        return key;
    }

    public Long createNewTableDefinition(Long userId, Long targetSpaceId, Long originalTableId, String config) {
        try {
            String key = generateTableKey();
            Object value = CopyRelationCacheUtil.get(key, targetSpaceId, originalTableId);
            if (value != null) {
                return (Long) value;
            }

            UserDataPermissionDto userDataPermission = TenantFunctions.callWithIgnoreCheck(() -> userDataPermissionRpcService.getUserDataPermission(userId));
            userDataPermission.checkMaxDataTableCount(TenantFunctions.callWithIgnoreCheck(() -> iComposeDbTableRpcService.countUserTotalTable(userId).intValue()));

            CreateTableDefineVo createTableDefineVo = JSON.parseObject(config, CreateTableDefineVo.class);
            if (createTableDefineVo != null && StringUtils.isNotBlank(createTableDefineVo.getTableName())) {
                createTableDefineVo.setSpaceId(targetSpaceId);
                createTableDefineVo.setCreatorId(userId);
                String newName = copyIndexRecordRepository.newCopyName("table", targetSpaceId, createTableDefineVo.getTableName());
                createTableDefineVo.setTableName(newName);
                Long tableId = iComposeDbTableRpcService.createTable(createTableDefineVo);
                CopyRelationCacheUtil.put(key, targetSpaceId, originalTableId, tableId);
                return tableId;
            }
        } catch (Exception e) {
            // ignore
            log.warn("创建表结构定义信息失败", e);
        }
        return null;
    }

    public CreateTableDefineVo queryCreateTableInfo(Long tableId) {
        try {
            return iComposeDbTableRpcService.queryCreateTableInfo(new DorisTableDefineRequest(tableId));
        } catch (Exception e) {
            log.warn("Failed to query table schema info", e);
            return null;
        }
    }
}
