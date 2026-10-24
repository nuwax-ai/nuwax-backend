package com.xspaceagi.agent.web.ui.ws.speech;

import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.common.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * App WebSocket 握手鉴权：
 * 从 {@code Authorization} / Cookie {@code ticket} / query {@code access_token}|{@code _ticket}
 * 取登录 token，经 {@link AuthService#getLoginUserInfo(String)} 校验
 */
@Component
public class AppBearerHandshakeInterceptor implements HandshakeInterceptor {

    public static final String ATTR_APP_USER = "appUser";

    private static final Logger log = LoggerFactory.getLogger(AppBearerHandshakeInterceptor.class);

    private final AuthService authService;
    private final TenantConfigApplicationService tenantConfigApplicationService;

    public AppBearerHandshakeInterceptor(AuthService authService,
            TenantConfigApplicationService tenantConfigApplicationService) {
        this.authService = authService;
        this.tenantConfigApplicationService = tenantConfigApplicationService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String path = request.getURI() != null ? request.getURI().getPath() : null;
        log.info("App WebSocket handshake begin path={} remote={}", path, request.getRemoteAddress());
        try {
            String token = resolveToken(request);
            if (!StringUtils.hasText(token)) {
                log.warn("App WebSocket handshake rejected: missing token path={} remote={}",
                        path, request.getRemoteAddress());
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            UserDto user = authService.getLoginUserInfo(token.trim());
            if (user == null || user.getId() == null) {
                log.warn("App WebSocket handshake rejected: invalid token path={} remote={}",
                        path, request.getRemoteAddress());
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            if (!isEnabled(user)) {
                log.warn("App WebSocket handshake rejected: disabled user={} path={} remote={}",
                        user.getId(), path, request.getRemoteAddress());
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
            bindRequestContext(user, token.trim());
            attributes.put(ATTR_APP_USER, user);
            log.info("App WebSocket handshake ok user={} tenant={} path={} remote={}",
                    user.getId(), user.getTenantId(), path, request.getRemoteAddress());
            return true;
        } catch (RuntimeException ex) {
            log.warn("App WebSocket handshake failed path={} remote={}", path, request.getRemoteAddress(), ex);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }
    }

    private static boolean isEnabled(UserDto user) {
        return user.getStatus() == null || user.getStatus() == User.Status.Enabled;
    }

    /**
     * 与 Computer 代理一致：按登录用户租户写入 {@link RequestContext}，供后续多租户查询使用。
     */
    private void bindRequestContext(UserDto user, String token) {
        RequestContext<UserDto> ctx = new RequestContext<>();
        ctx.setTenantId(user.getTenantId());
        ctx.setUserId(user.getId());
        ctx.setUser(user);
        ctx.setUserContext(UserDto.convertToUserContext(user));
        ctx.setToken(token);
        ctx.setLogin(true);
        if (user.getTenantId() != null) {
            TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(user.getTenantId());
            ctx.setTenantConfig(tenantConfig);
        }
        RequestContext.set(ctx);
    }

    /**
     * 取登录 token。H5 无法设 Authorization，优先显式 query：
     * {@code access_token} → {@code _ticket} → Cookie {@code ticket} → {@code Authorization}。
     */
    private String resolveToken(ServerHttpRequest request) {
        String accessToken = queryParam(request, "access_token");
        if (StringUtils.hasText(accessToken)) {
            return accessToken.trim();
        }

        String ticket = queryParam(request, "_ticket");
        if (StringUtils.hasText(ticket)) {
            String token = authService.getTokenByTicket(ticket.trim());
            if (StringUtils.hasText(token)) {
                return token.trim();
            }
        }

        String cookieToken = cookieValue(request, "ticket");
        if (StringUtils.hasText(cookieToken)) {
            return cookieToken.trim();
        }

        List<String> headers = request.getHeaders().get(HttpHeaders.AUTHORIZATION);
        if (headers != null) {
            for (String header : headers) {
                if (!StringUtils.hasText(header)) {
                    continue;
                }
                // 与 Computer 代理一致：去掉 Basic/Bearer 前缀
                String stripped = header.replaceFirst("Basic", "").replaceFirst("Bearer", "").trim();
                if (StringUtils.hasText(stripped)) {
                    return stripped;
                }
            }
        }
        return null;
    }

    private static String cookieValue(ServerHttpRequest request, String name) {
        List<String> cookies = request.getHeaders().get(HttpHeaders.COOKIE);
        if (cookies == null || cookies.isEmpty()) {
            return null;
        }
        String prefix = name + "=";
        for (String header : cookies) {
            if (!StringUtils.hasText(header)) {
                continue;
            }
            for (String part : header.split(";")) {
                String item = part.trim();
                if (item.startsWith(prefix)) {
                    String value = item.substring(prefix.length()).trim();
                    if (StringUtils.hasText(value)) {
                        return value;
                    }
                }
            }
        }
        return null;
    }

    private String queryParam(ServerHttpRequest request, String name) {
        if (request.getURI() == null || !StringUtils.hasText(request.getURI().getQuery())) {
            return null;
        }
        for (String part : request.getURI().getQuery().split("&")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = URLDecoder.decode(part.substring(0, eq), StandardCharsets.UTF_8);
            if (!name.equals(key)) {
                continue;
            }
            return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8);
        }
        return null;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
            WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
