package com.xspaceagi.system.api;

import com.xspaceagi.system.application.service.SysUserPermissionCacheService;
import com.xspaceagi.system.sdk.permission.IPermissionCacheRpcSerivce;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 权限缓存 RPC 实现（供内部模块调用）
 */
@Slf4j
@Service
public class PermissionCacheRpcServiceImpl implements IPermissionCacheRpcSerivce {

    @Resource
    private SysUserPermissionCacheService sysUserPermissionCacheService;

    @Override
    public void clearCacheAllByTenant(Long tenantId) {
        sysUserPermissionCacheService.clearCacheAllByTenant(tenantId);
    }

    @Override
    public void clearCacheByTenantAndUserIds(Long tenantId, List<Long> userIds) {
        sysUserPermissionCacheService.clearCacheByTenantAndUserIds(tenantId, userIds);
    }
}
