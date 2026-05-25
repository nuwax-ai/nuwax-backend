package com.xspaceagi.pay.sdk.sign;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.TreeMap;

/** 仅提取参与签名的业务字段，按字段名字典序序列化为 JSON 后做 SHA256（避免全量 body 二次序列化漂移）。 */
final class OpenApiSignFieldCanonical {

    private OpenApiSignFieldCanonical() {}

    static byte[] canonicalBytes(byte[] httpBody, List<String> signFieldNames) {
        if (signFieldNames == null || signFieldNames.isEmpty()) {
            return OpenApiSignJson.bodyBytesWithoutSignature(httpBody);
        }
        try {
            JsonNode root = OpenApiSignJson.mapper().readTree(httpBody == null || httpBody.length == 0 ? "{}".getBytes() : httpBody);
            if (!root.isObject()) {
                throw new IllegalArgumentException("OpenAPI sign body must be a JSON object");
            }
            ObjectNode out = OpenApiSignJson.mapper().createObjectNode();
            TreeMap<String, JsonNode> sorted = new TreeMap<>();
            for (String name : signFieldNames) {
                if (name == null || name.isBlank()) {
                    continue;
                }
                JsonNode value = root.get(name);
                if (value != null && !value.isNull()) {
                    sorted.put(name, value);
                }
            }
            sorted.forEach(out::set);
            return OpenApiSignJson.mapper().writeValueAsBytes(out);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid OpenAPI JSON body for field signing", e);
        }
    }
}
