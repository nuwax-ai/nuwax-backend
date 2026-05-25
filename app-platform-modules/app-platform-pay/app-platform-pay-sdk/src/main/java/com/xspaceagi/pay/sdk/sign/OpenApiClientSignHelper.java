package com.xspaceagi.pay.sdk.sign;

import java.util.UUID;

/**
 * 接入方 Open API 签名通用入口（自动生成 timestamp/nonce 的重载见各 {@code OpenApi*Sign}）。
 * <p>body 哈希仅包含 {@link OpenApiSignPolicies} 中为该 path 配置的业务字段；完整 JSON 仍可带 ext 等非签名字段。</p>
 */
public final class OpenApiClientSignHelper {

    private OpenApiClientSignHelper() {}

    public static String newNonce() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public static long nowTimestamp() {
        return System.currentTimeMillis();
    }

    public static byte[] buildSignedPostBody(
            String path, Object requestBody, long timestamp, String nonce, String clientSecret) {
        return OpenApiSignedRequestSupport.buildSignedPostBody(path, requestBody, timestamp, nonce, clientSecret);
    }

    public static String buildSignedPostJson(
            String path, Object requestBody, long timestamp, String nonce, String clientSecret) {
        return OpenApiSignedRequestSupport.buildSignedPostJson(path, requestBody, timestamp, nonce, clientSecret);
    }
}
