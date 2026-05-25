package com.xspaceagi.pay.sdk.sign;

import org.apache.commons.codec.digest.DigestUtils;

import java.util.List;
import java.util.Locale;

/** OpenAPI 待签名字符串：绑定 HTTP 方法、路径、时间戳、nonce 与请求体（不含 {@code signature} 字段）。 */
final class OpenApiSignCanonical {

    private OpenApiSignCanonical() {}

    static String bodySha256Hex(byte[] body) {
        return DigestUtils.sha256Hex(body == null ? new byte[0] : body);
    }

    static String build(String method, String path, long timestamp, String nonce, byte[] httpBody) {
        String m = method == null ? "" : method.trim().toUpperCase(Locale.ROOT);
        String p = OpenApiSignPolicies.normalizePath(path);
        String n = nonce == null ? "" : nonce.trim();
        List<String> signFields = OpenApiSignPolicies.signFieldsForPath(p);
        byte[] bodyForSign = OpenApiSignFieldCanonical.canonicalBytes(
                OpenApiSignJson.bodyBytesWithoutSignature(httpBody), signFields);
        return m + "\n" + p + "\n" + timestamp + "\n" + n + "\n" + bodySha256Hex(bodyForSign);
    }
}
