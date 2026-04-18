package com.xspaceagi.system.spec.common;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Scope;
import org.springframework.context.annotation.ScopedProxyMode;
import org.springframework.stereotype.Component;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Component
@Scope(value = "request", proxyMode = ScopedProxyMode.TARGET_CLASS)
@Getter
@Setter
public class RequestContext<T> implements Serializable {

    private static ThreadLocal<RequestContext> threadLocal = new ThreadLocal<>();
    private static ThreadLocal<Set<String>> tenantIgnoreTablesLocal = new ThreadLocal<>();

    private Long userId;
    private Long tenantId;
    private Object tenantConfig;
    private String clientIp;
    private String clientLang;
    private String clientCookieLang;
    private String lang;//最终的语言
    private Map<String, String> langMap;
    private boolean isLogin;
    private T user;
    private UserContext userContext;
    private String token;
    private String requestId;
    private Object akTarget;
    private Object userAccessKey;

    public static <T> void set(RequestContext<T> requestContext) {
        requestContext.setRequestId(UUID.randomUUID().toString().replace("-", ""));
        threadLocal.set(requestContext);
        log.debug("RequestContext set, userId:{}, tenantId: {}", requestContext.getUserId(), requestContext.getTenantId());
    }

    public static <T> RequestContext<T> get() {
        return threadLocal.get();
    }

    public static <T> void remove() {
        RequestContext requestContext = get();
        log.debug("RequestContext remove, userId:{}, tenantId: {}", requestContext == null ? null : requestContext.getUserId(),
                requestContext == null ? null : requestContext.getTenantId());
        threadLocal.remove();
        tenantIgnoreTablesLocal.remove();
    }

    /**
     * 跨线程运行,设置一个新的租户ID,用于数据库租户隔离使用
     *
     * @param tenantId 租户ID
     */
    public static void setThreadTenantId(Long tenantId) {
        log.debug("RequestContext setThreadTenantId, tenantId: {}", tenantId);
        RequestContext<?> requestContext = new RequestContext<>();
        requestContext.setTenantId(tenantId);
        threadLocal.set(requestContext);
    }


    /**
     * 获取租户忽略表
     *
     * @return
     */
    public static Set<String> getTenantIgnoreTables() {
        Set<String> tenantIgnoreTables = RequestContext.tenantIgnoreTablesLocal.get();
        if (tenantIgnoreTables == null) {
            RequestContext.tenantIgnoreTablesLocal.set(new HashSet<>());
        }
        return tenantIgnoreTables == null ? RequestContext.tenantIgnoreTablesLocal.get() : tenantIgnoreTables;
    }

    /**
     * 添加忽略表
     *
     * @param clazz
     */
    public static void addTenantIgnoreEntity(Class<?> clazz) {
        TableName tableName = clazz.getAnnotation(TableName.class);
        if (tableName != null) {
            getTenantIgnoreTables().add(tableName.value());
        }
    }

    /**
     * 移除忽略表
     *
     * @param clazz
     */
    public static void removeTenantIgnoreEntity(Class<?> clazz) {
        TableName tableName = clazz.getAnnotation(TableName.class);
        if (tableName != null) {
            getTenantIgnoreTables().remove(tableName.value());
        }
    }

    public void setUserId(Long userId) {
        log.debug("RequestContext setUserId, userId: {}", userId);
        this.userId = userId;
    }
}