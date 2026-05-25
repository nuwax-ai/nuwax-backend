package com.xspaceagi.pay.sdk.sign;

import com.fasterxml.jackson.databind.node.ObjectNode;

/** 将 {@code timestamp}/{@code nonce} 写回请求 POJO（含 spec 模块 DTO，运行时由 Jackson 更新）。 */
final class OpenApiSignTimestamps {

    private OpenApiSignTimestamps() {}

    static void apply(Object request, long timestamp, String nonce) {
        if (request == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        try {
            ObjectNode patch = OpenApiSignJson.mapper().createObjectNode();
            patch.put("timestamp", timestamp);
            patch.put("nonce", nonce);
            OpenApiSignJson.mapper().readerForUpdating(request).readValue(patch);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to apply OpenAPI timestamp/nonce", e);
        }
    }
}
