package com.xspaceagi.pay.sdk.sign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Open API 签名专用 JSON（不对外作为通用工具暴露） */
final class OpenApiSignJson {

    private static final ObjectMapper MAPPER = new ObjectMapper().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private OpenApiSignJson() {}

    static ObjectMapper mapper() {
        return MAPPER;
    }

    static byte[] bodyBytesWithoutSignature(byte[] httpBody) {
        if (httpBody == null || httpBody.length == 0) {
            return new byte[0];
        }
        try {
            JsonNode root = MAPPER.readTree(httpBody);
            if (root.isObject()) {
                ((ObjectNode) root).remove("signature");
            }
            return MAPPER.writeValueAsBytes(root);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid OpenAPI JSON body for signing", e);
        }
    }
}
