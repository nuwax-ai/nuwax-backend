package com.xspaceagi.userapp.infra.proxy;

/**
 * 转发上下文：随首个转发请求挂到后端通道上，响应侧回传时打印
 * "前端请求 → 后端 → 状态码/耗时"，用于区分响应由代理自身还是下游产生。
 * corsOrigin：请求 Origin 命中租户 siteUrl 白名单时的归一化值，响应侧据此注入 CORS 头（未命中为 null）。
 */
public record UserAppProxyRelayContext(String method, String uri, String backendKey, long startNanos,
        String corsOrigin) {
}
