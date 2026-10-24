package com.xspaceagi.userapp.application.oauth2;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * OAuth2 凭证与令牌随机生成。
 * <p>
 * 令牌为不可猜测的不透明随机串（非 JWT），配合 Redis 存储与撤销，避免引入签名密钥的轮换问题。
 */
public final class OAuth2CredentialGenerator {

    /** Client ID 前缀 */
    public static final String CLIENT_ID_PREFIX = "nwxa_";

    /** Client Secret 前缀 */
    public static final String CLIENT_SECRET_PREFIX = "nwxa_s_";

    /** access_token 前缀：拦截器据此分派，避免每次请求先打一次 Redis */
    public static final String ACCESS_TOKEN_PREFIX = "oa-";

    /** refresh_token 前缀 */
    public static final String REFRESH_TOKEN_PREFIX = "or-";

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private OAuth2CredentialGenerator() {
    }

    /** Client ID：nwxa_ + 32 位小写 hex（16 字节熵） */
    public static String newClientId() {
        return CLIENT_ID_PREFIX + randomHex(16);
    }

    /** Client Secret：nwxa_s_ + 48 位小写 hex（24 字节熵），明文仅在生成时返回一次 */
    public static String newClientSecret() {
        return CLIENT_SECRET_PREFIX + randomHex(24);
    }

    /** access_token：oa- + 43 位 url-safe 随机串（32 字节熵） */
    public static String newAccessToken() {
        return ACCESS_TOKEN_PREFIX + randomUrlSafe(32);
    }

    /** refresh_token：or- + 43 位 url-safe 随机串 */
    public static String newRefreshToken() {
        return REFRESH_TOKEN_PREFIX + randomUrlSafe(32);
    }

    /** 授权码：32 位 url-safe 随机串（无前缀，与两类令牌区分） */
    public static String newAuthorizationCode() {
        return randomUrlSafe(24);
    }

    private static String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        char[] out = new char[bytes * 2];
        for (int i = 0; i < bytes; i++) {
            int value = buffer[i] & 0xFF;
            out[i * 2] = HEX[value >>> 4];
            out[i * 2 + 1] = HEX[value & 0x0F];
        }
        return new String(out);
    }

    private static String randomUrlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return URL_ENCODER.encodeToString(buffer);
    }
}
