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
    /**
     * 实际付费方覆盖（可空）。
     * <p>
     * 默认由计费链路自行推导——使用者本人，或（包干订阅 / 试用时）Agent 创建者；
     * 仅内部调用接口「代指定用户发起会话」时由调用方显式指定（见
     * {@code ChatApplicationServiceImpl#estimatePrice}）。
     * <p>
     * 为空时对既有计费行为完全无影响。
     */
    private Long billUserId;
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
    /**
     * OAuth2 access_token 的<b>有效</b> scope（逗号分隔，已按接入应用当前允许范围收窄）。
     * <p>
     * 仅当请求携带 {@code oa-} 令牌时由 {@code AuthInterceptor} 填充；普通登录态为 null。
     * 用于门面端点的字段级权限判定（如 {@code user:phone} 决定 /userinfo 是否返回手机号）。
     */
    private String oauth2Scope;
    private String requestId;
    private Object akTarget;
    private Object userAccessKey;
    private String from;

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