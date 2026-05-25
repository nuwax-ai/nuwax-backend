package com.xspaceagi.pay.sdk.sign;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;

/** 组装带签名的 Open API POST body（按路径策略仅对关键字段参与 body 哈希）。 */
final class OpenApiSignedRequestSupport {

    private OpenApiSignedRequestSupport() {}

    static byte[] buildSignedPostBody(
            String path, Object requestBody, long timestamp, String nonce, String clientSecret) {
        ObjectNode node = toObjectNode(requestBody);
        node.remove("signature");
        node.put("timestamp", timestamp);
        node.put("nonce", nonce);
        byte[] bodyForSign = writeBytes(node);
        String signature =
                OpenApiPayloadSigner.sign("POST", path, timestamp, nonce, bodyForSign, clientSecret);
        node.put("signature", signature);
        return writeBytes(node);
    }

    static String buildSignedPostJson(
            String path, Object requestBody, long timestamp, String nonce, String clientSecret) {
        return new String(buildSignedPostBody(path, requestBody, timestamp, nonce, clientSecret), StandardCharsets.UTF_8);
    }

    private static ObjectNode toObjectNode(Object requestBody) {
        if (requestBody instanceof ObjectNode on) {
            return on.deepCopy();
        }
        return OpenApiSignJson.mapper().valueToTree(requestBody);
    }

    private static byte[] writeBytes(JsonNode node) {
        try {
            return OpenApiSignJson.mapper().writeValueAsBytes(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialize OpenAPI JSON failed", e);
        }
    }
}
