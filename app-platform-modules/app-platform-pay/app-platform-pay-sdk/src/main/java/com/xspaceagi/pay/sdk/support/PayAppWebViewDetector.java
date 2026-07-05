package com.xspaceagi.pay.sdk.support;

import java.util.Locale;
import java.util.Set;

/**
 * 识别 App 壳 / WebView 内发起的 HTTP 请求（非系统浏览器、非微信内置浏览器）。
 * 平台 App 请求会携带 {@value #HEADER_CLIENT_TYPE}；兜底解析 User-Agent 中的 Nuwax App 标记。
 */
public final class PayAppWebViewDetector {

    /** App 客户端标识，与 AuthInterceptor 续期逻辑一致：非空即视为 App 请求。 */
    public static final String HEADER_CLIENT_TYPE = "X-Client-Type";

    private static final Set<String> APP_CLIENT_TYPE_VALUES = Set.of(
            "app",
            "native",
            "ios",
            "android",
            "mobile",
            "mobile-app",
            "nuwax-app");

    private static final Set<String> WEB_CLIENT_TYPE_VALUES = Set.of("web", "h5", "browser", "wap");

    private PayAppWebViewDetector() {}

    /**
     * 是否为 App WebView 内请求。H5 支付在此环境下会被渠道判定违规，应改用 App 原生 SDK 支付。
     */
    public static boolean isAppWebView(String clientTypeHeader, String userAgent) {
        if (clientTypeHeader != null && !clientTypeHeader.isBlank()) {
            String normalized = clientTypeHeader.trim().toLowerCase(Locale.ROOT);
            if (WEB_CLIENT_TYPE_VALUES.contains(normalized)) {
                return false;
            }
            if (APP_CLIENT_TYPE_VALUES.contains(normalized) || normalized.startsWith("app")) {
                return true;
            }
            // 平台 App 统一注入 X-Client-Type；非 web 类取值视为 App 壳（与鉴权续期策略一致）
            return true;
        }
        return matchesAppWebViewUserAgent(userAgent);
    }

    private static boolean matchesAppWebViewUserAgent(String userAgent) {
        if (userAgent == null || userAgent.isBlank()) {
            return false;
        }
        String ua = userAgent;
        if (ua.contains("NuwaxApp") || ua.contains("NUWAX_APP") || ua.contains("nuwax-app")) {
            return true;
        }
        String lower = ua.toLowerCase(Locale.ROOT);
        if (lower.contains("nuwax") && (lower.contains("webview") || lower.contains("; wv)"))) {
            return true;
        }
        return false;
    }
}
