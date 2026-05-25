package com.xspaceagi.system.sdk.permission;

import java.util.List;

/**
 * 权限缓存接口
 */
public interface IPermissionCacheRpcSerivce {

    /**
     * 清除指定租户下所有用户的权限缓存
     */
    void clearCacheAllByTenant(Long tenantId);

    /**
     * 清除指定租户下指定用户的权限缓存
     */
    void clearCacheByTenantAndUserIds(Long tenantId, List<Long> userIds);

}