package com.xspaceagi.system.web.aspect;

import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.annotation.SaasAdmin;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;

@Slf4j
@Aspect
@Component
public class SaasAdminAspect {

    private static final Long SAAS_ADMIN_TENANT_ID = 1L;

    @Around("@annotation(com.xspaceagi.system.spec.annotation.SaasAdmin) || @within(com.xspaceagi.system.spec.annotation.SaasAdmin)")
    public Object checkSaasAdminPermission(ProceedingJoinPoint joinPoint) throws Throwable {
        SaasAdmin saasAdmin = resolveSaasAdmin(joinPoint);
        if (saasAdmin == null) {
            return joinPoint.proceed();
        }

        if (saasAdmin.skipCheck()) {
            log.debug("跳过 SaaS 管理员权限检查: {}", joinPoint.getSignature().getName());
            return joinPoint.proceed();
        }

        if (RequestContext.get() == null || RequestContext.get().getTenantId() == null) {
            log.warn("上下文或租户ID为空，拒绝访问: {}", joinPoint.getSignature().getName());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }

        if (!SAAS_ADMIN_TENANT_ID.equals(RequestContext.get().getTenantId())) {
            log.warn("拒绝访问: tenantId={}, method={}", RequestContext.get().getTenantId(), joinPoint.getSignature().getName());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }

        UserDto userDto = (UserDto) RequestContext.get().getUser();
        if (userDto == null || userDto.getRole() != User.Role.Admin) {
            log.warn("拒绝访问: userId={}, method={}", userDto != null ? userDto.getId() : null, joinPoint.getSignature().getName());
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }

        log.debug("SaaS Admin check passed, user [{}] accessing: {}", userDto.getId(), joinPoint.getSignature().getName());
        return joinPoint.proceed();
    }

    private SaasAdmin resolveSaasAdmin(ProceedingJoinPoint joinPoint) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        SaasAdmin saasAdmin = AnnotationUtils.findAnnotation(method, SaasAdmin.class);
        if (saasAdmin != null) {
            return saasAdmin;
        }
        return AnnotationUtils.findAnnotation(method.getDeclaringClass(), SaasAdmin.class);
    }
}
