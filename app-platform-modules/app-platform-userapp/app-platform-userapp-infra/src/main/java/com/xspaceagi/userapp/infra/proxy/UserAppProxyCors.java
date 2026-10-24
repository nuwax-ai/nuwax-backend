package com.xspaceagi.userapp.infra.proxy;

import java.net.URI;
import java.net.URISyntaxException;

import org.apache.commons.lang3.StringUtils;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AsciiString;

/**
 * 代理 CORS 处理：按租户 siteUrl 白名单放行（归一化后精确匹配，不回显任意 Origin）。
 * 命中则在回传响应上注入 Access-Control-* 头、并在代理侧终结预检请求；未命中一律不加头（fail-closed）。
 * 下游应用自身的 CORS 头会被覆盖——域名入口上代理是唯一 CORS 权威，应用配置不可控。
 */
final class UserAppProxyCors {

    private static final AsciiString ACCESS_CONTROL_ALLOW_ORIGIN = AsciiString.cached("Access-Control-Allow-Origin");

    private static final AsciiString ACCESS_CONTROL_ALLOW_CREDENTIALS =
            AsciiString.cached("Access-Control-Allow-Credentials");

    private static final AsciiString ACCESS_CONTROL_ALLOW_METHODS = AsciiString.cached("Access-Control-Allow-Methods");

    private static final AsciiString ACCESS_CONTROL_ALLOW_HEADERS = AsciiString.cached("Access-Control-Allow-Headers");

    private static final AsciiString ACCESS_CONTROL_REQUEST_METHOD =
            AsciiString.cached("Access-Control-Request-Method");

    private static final AsciiString ACCESS_CONTROL_REQUEST_HEADERS =
            AsciiString.cached("Access-Control-Request-Headers");

    private static final AsciiString ACCESS_CONTROL_MAX_AGE = AsciiString.cached("Access-Control-Max-Age");

    private UserAppProxyCors() {
    }

    /** 预检请求识别：OPTIONS 且带 Access-Control-Request-Method（应用自身的同源 OPTIONS 不带此头，不受影响） */
    static boolean isPreflight(HttpRequest request) {
        return HttpMethod.OPTIONS.equals(request.method())
                && request.headers().contains(ACCESS_CONTROL_REQUEST_METHOD);
    }

    /** 归一化为 scheme://host[:port]：host 小写、默认端口省略、丢弃路径部分（Origin 只含这三要素）；非法返回 null */
    static String normalizeOrigin(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        try {
            URI uri = new URI(url.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            scheme = scheme.toLowerCase();
            host = host.toLowerCase();
            int port = uri.getPort();
            boolean defaultPort = (port == 80 && "http".equals(scheme)) || (port == 443 && "https".equals(scheme));
            return port == -1 || defaultPort ? scheme + "://" + host : scheme + "://" + host + ":" + port;
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** 请求 Origin 与白名单精确匹配（两侧各自归一化）：命中返回归一化 Origin（用于回写 ACAO），否则 null */
    static String match(String originHeader, String allowedOrigin) {
        String normalized = normalizeOrigin(originHeader);
        String allowed = normalizeOrigin(allowedOrigin);
        if (normalized == null || allowed == null) {
            return null;
        }
        return normalized.equals(allowed) ? normalized : null;
    }

    /** 响应注入（透传响应与代理错误页共用）：覆盖下游可能已带的 CORS 头，避免双 ACAO 值被浏览器判失败 */
    static void apply(HttpHeaders headers, String origin) {
        headers.remove(ACCESS_CONTROL_ALLOW_ORIGIN);
        headers.remove(ACCESS_CONTROL_ALLOW_CREDENTIALS);
        headers.set(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        // 回写的是具体 Origin（非 *），带凭据放行使 ticket cookie 可随跨域 fetch 生效
        headers.set(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        appendVaryOrigin(headers);
    }

    /** 预检响应：方法放行全集，请求头按浏览器所问回显（Origin 已被白名单限定） */
    static FullHttpResponse preflightResponse(String origin, HttpRequest request) {
        FullHttpResponse resp = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT,
                Unpooled.EMPTY_BUFFER);
        HttpHeaders headers = resp.headers();
        headers.set(ACCESS_CONTROL_ALLOW_ORIGIN, origin);
        headers.set(ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
        headers.set(ACCESS_CONTROL_ALLOW_METHODS, "GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS");
        String requestHeaders = request.headers().get(ACCESS_CONTROL_REQUEST_HEADERS);
        if (requestHeaders != null) {
            headers.set(ACCESS_CONTROL_ALLOW_HEADERS, requestHeaders);
        }
        headers.set(ACCESS_CONTROL_MAX_AGE, "600");
        headers.set(HttpHeaderNames.VARY, "Origin, Access-Control-Request-Headers");
        return resp;
    }

    /** 响应内容随 Origin 区分，防止共享缓存串包 */
    private static void appendVaryOrigin(HttpHeaders headers) {
        String vary = headers.get(HttpHeaderNames.VARY);
        if (vary == null) {
            headers.set(HttpHeaderNames.VARY, "Origin");
        } else if (!containsToken(vary, "Origin")) {
            headers.set(HttpHeaderNames.VARY, vary + ", Origin");
        }
    }

    private static boolean containsToken(String headerValue, String token) {
        for (String part : headerValue.split(",")) {
            if (token.equalsIgnoreCase(part.trim())) {
                return true;
            }
        }
        return false;
    }
}
