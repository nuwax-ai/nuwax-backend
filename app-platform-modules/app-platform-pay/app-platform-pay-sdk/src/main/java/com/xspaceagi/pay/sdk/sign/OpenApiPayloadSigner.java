package com.xspaceagi.pay.sdk.sign;

import org.apache.commons.codec.binary.Hex;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Open API HMAC-SHA256 签名与验签（唯一实现，接入方与服务端共用）。
 * <p>接入方优先使用各 {@code OpenApi*Sign} 或 {@link OpenApiClientSignHelper}；服务端验签使用 {@link #verify}。</p>
 * <p>body 哈希字段见 {@link OpenApiSignPolicies}。</p>
 */
public final class OpenApiPayloadSigner {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private OpenApiPayloadSigner() {}

    /**
     * @param httpBody 可为最终 HTTP body（含 signature）或不含 signature；哈希前均会剔除 signature 字段
     */
    public static String sign(
            String httpMethod, String path, long timestamp, String nonce, byte[] httpBody, String clientSecret) {
        if (clientSecret == null) {
            throw new IllegalArgumentException("clientSecret must not be null");
        }
        String canonical = OpenApiSignCanonical.build(httpMethod, path, timestamp, nonce, httpBody);
        return hmacSha256Hex(clientSecret, canonical);
    }

    public static boolean verify(
            String httpMethod,
            String path,
            long timestamp,
            String nonce,
            byte[] rawBody,
            String clientSecret,
            String signature) {
        if (signature == null || signature.isBlank() || clientSecret == null) {
            return false;
        }
        String expected = sign(httpMethod, path, timestamp, nonce, rawBody, clientSecret);
        return constantTimeEqualsHex(expected, signature.trim());
    }

    private static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return Hex.encodeHexString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 not available", e);
        }
    }

    private static boolean constantTimeEqualsHex(String expectedHex, String actualHex) {
        try {
            byte[] expected = Hex.decodeHex(expectedHex.toCharArray());
            byte[] actual = Hex.decodeHex(actualHex.toLowerCase(Locale.ROOT).toCharArray());
            return expected.length == actual.length && MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }
}
