package com.xspaceagi.system.web.aspect;

import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SysUserPermissionCacheService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ResourceEnum;
import com.xspaceagi.system.spec.exception.ResourcePermissionException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 资源权限切面
 * 支持方法级别和类级别的 @RequireResource 注解，方法上的注解优先于类上的
 */
@Slf4j
@Aspect
@Component
public class ResourcePermissionAspect {
    
    @Resource
    private SysUserPermissionCacheService sysUserPermissionCacheService;
    
    /**
     * 环绕通知，拦截带有 @RequireResource 注解的方法或类
     * 方法级别：仅该方法受注解限制；类级别：该类的所有方法都受注解限制
     */
    @Around("@annotation(com.xspaceagi.system.spec.annotation.RequireResource) || @within(com.xspaceagi.system.spec.annotation.RequireResource)")
    public Object checkResourcePermission(ProceedingJoinPoint joinPoint) throws Throwable {
        RequireResource requireResource = resolveRequireResource(joinPoint);
        if (requireResource == null) {
            return joinPoint.proceed();
        }
        
        // 如果允许跳过权限检查，直接执行方法
        if (requireResource.skipCheck()) {
            log.debug("跳过资源权限检查: {}", joinPoint.getSignature().getName());
            return joinPoint.proceed();
        }
        
        try {
            UserDto currentUser = (UserDto) RequestContext.get().getUser();
            if (currentUser == null) {
                log.warn("User not logged in, access denied: {}", joinPoint.getSignature().getName());
                throw new ResourcePermissionException("用户未登录");
            }

            List<String> resourceCodes = collectResourceCodes(requireResource);
            if (CollectionUtils.isEmpty(resourceCodes)) {
                log.warn("资源编码为空，拒绝访问: {}", joinPoint.getSignature().getName());
                throw new ResourcePermissionException("资源编码不能为空");
            }

            sysUserPermissionCacheService.checkResourcePermissionAny(currentUser.getId(), resourceCodes);

            log.debug("资源权限验证通过，用户[{}]访问资源[{}]: {}", 
                    currentUser.getId(), resourceCodes, joinPoint.getSignature().getName());
            
            return joinPoint.proceed();
            
        } catch (ResourcePermissionException e) {
            log.warn("资源Permission check failed: {}", e.getMessage());
            throw e;
        } catch (Throwable e) {
            throw e;
        }
    }

    /**
     * 解析 @RequireResource 注解：优先取方法上的，其次取类上的
     */
    private RequireResource resolveRequireResource(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        RequireResource requireResource = AnnotationUtils.findAnnotation(method, RequireResource.class);
        if (requireResource != null) {
            return requireResource;
        }
        return AnnotationUtils.findAnnotation(method.getDeclaringClass(), RequireResource.class);
    }

    /**
     * 从注解中收集所有资源码（value 枚举 + resourceCodes）
     */
    @SuppressWarnings("deprecation")
    private List<String> collectResourceCodes(RequireResource requireResource) {
        List<String> allCodes = new ArrayList<>();
        if (requireResource.value() != null && requireResource.value().length > 0) {
            for (ResourceEnum e : requireResource.value()) {
                if (e != null && e.getCode() != null) {
                    allCodes.add(e.getCode());
                }
            }
        }
        if (requireResource.codes() != null && requireResource.codes().length > 0) {
            for (String s : requireResource.codes()) {
                if (StringUtils.isNotBlank(s)) {
                    allCodes.add(s.trim());
                }
            }
        }
        return allCodes;
    }
}

