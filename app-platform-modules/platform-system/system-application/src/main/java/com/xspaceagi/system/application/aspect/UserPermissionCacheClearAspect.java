package com.xspaceagi.system.application.aspect;

import com.xspaceagi.system.application.service.SysUserPermissionCacheService;
import com.xspaceagi.system.spec.annotation.ClearAllUserPermissionCache;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 用户权限缓存清理切面
 *
 * 对标记了 {@link ClearAllUserPermissionCache} 的应用层方法，
 * 在方法成功执行后统一清理所有用户的权限缓存。
 */
@Slf4j
@Aspect
@Component
public class UserPermissionCacheClearAspect {

    @Resource
    private SysUserPermissionCacheService sysUserPermissionCacheService;

    @AfterReturning("@annotation(clearAllUserPermissionCache)")
    public void clearAllCacheAfterSuccess(ClearAllUserPermissionCache clearAllUserPermissionCache) {
        try {
            sysUserPermissionCacheService.clearCacheAll();
        } catch (Exception e) {
            log.warn("Failed to clear all user permission caches", e);
        }
    }
}

