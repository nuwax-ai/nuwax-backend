package com.xspaceagi.pay.sdk.sign;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Value;

/** 将已签名的 Open API JSON 元数据解析为 multipart 表单字段。 */
public final class OpenApiFileEntryMultipartSupport {

    private OpenApiFileEntryMultipartSupport() {}

    @Value
    @Builder
    public static class SignedUploadFields {
        String clientId;
        String bizType;
        String replaceFileKey;
        long timestamp;
        String nonce;
        String signature;
    }

    public static SignedUploadFields parseSignedMetadata(byte[] signedJson) {
        if (signedJson == null || signedJson.length == 0) {
            throw new IllegalArgumentException("signed metadata must not be empty");
        }
        try {
            JsonNode root = OpenApiSignJson.mapper().readTree(signedJson);
            JsonNode clientId = root.get("clientId");
            JsonNode bizType = root.get("bizType");
            JsonNode timestamp = root.get("timestamp");
            JsonNode nonce = root.get("nonce");
            JsonNode signature = root.get("signature");
            if (clientId == null
                    || !clientId.isTextual()
                    || bizType == null
                    || !bizType.isTextual()
                    || timestamp == null
                    || !timestamp.isNumber()
                    || nonce == null
                    || !nonce.isTextual()
                    || signature == null
                    || !signature.isTextual()) {
                throw new IllegalArgumentException("invalid signed file upload metadata");
            }
            String replaceFileKey = null;
            JsonNode replaceNode = root.get("replaceFileKey");
            if (replaceNode != null && replaceNode.isTextual() && !replaceNode.asText().isBlank()) {
                replaceFileKey = replaceNode.asText().trim();
            }
            return SignedUploadFields.builder()
                    .clientId(clientId.asText().trim())
                    .bizType(bizType.asText().trim())
                    .replaceFileKey(replaceFileKey)
                    .timestamp(timestamp.longValue())
                    .nonce(nonce.asText().trim())
                    .signature(signature.asText().trim())
                    .build();
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid signed file upload metadata", e);
        }
    }
}
