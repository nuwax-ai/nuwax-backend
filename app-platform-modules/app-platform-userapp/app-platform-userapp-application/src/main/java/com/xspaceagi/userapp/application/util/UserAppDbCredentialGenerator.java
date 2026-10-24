package com.xspaceagi.userapp.application.util;

import java.security.SecureRandom;

/**
 * 数据库账号密码随机生成
 */
public final class UserAppDbCredentialGenerator {

    /** 账号字符集：小写字母+数字（MySQL 账号安全字符） */
    private static final char[] USERNAME_CHARS = "abcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    /**
     * 密码字符集：仅大小写字母+数字。
     * 不含特殊符号，避免 $ @ : / # 等在连接串、URL、环境变量中被转义导致凭证错乱。
     */
    private static final char[] PASSWORD_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".toCharArray();

    private static final char[] PASSWORD_LOWER = "abcdefghijklmnopqrstuvwxyz".toCharArray();

    private static final char[] PASSWORD_UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    private static final char[] PASSWORD_DIGIT = "0123456789".toCharArray();

    /** MySQL user 字段上限 32 */
    private static final int USERNAME_LENGTH = 10;

    /** 加密后 Base64 约 64 字符，小于 varchar(512) */
    private static final int PASSWORD_LENGTH = 20;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private UserAppDbCredentialGenerator() {
    }

    /** 生成随机账号：小写字母开头 + 小写字母数字，共 10 位 */
    public static String randomUsername() {
        StringBuilder sb = new StringBuilder(USERNAME_LENGTH);
        // 首位固定小写字母，避免数字开头被部分数据库/中间件拒绝
        sb.append(PASSWORD_LOWER[SECURE_RANDOM.nextInt(PASSWORD_LOWER.length)]);
        for (int i = 1; i < USERNAME_LENGTH; i++) {
            sb.append(USERNAME_CHARS[SECURE_RANDOM.nextInt(USERNAME_CHARS.length)]);
        }
        return sb.toString();
    }

    /** 生成随机密码：20 位大小写字母+数字，保证至少各含一位小写、大写、数字 */
    public static String randomPassword() {
        char[] pwd = new char[PASSWORD_LENGTH];
        pwd[0] = PASSWORD_LOWER[SECURE_RANDOM.nextInt(PASSWORD_LOWER.length)];
        pwd[1] = PASSWORD_UPPER[SECURE_RANDOM.nextInt(PASSWORD_UPPER.length)];
        pwd[2] = PASSWORD_DIGIT[SECURE_RANDOM.nextInt(PASSWORD_DIGIT.length)];
        for (int i = 3; i < PASSWORD_LENGTH; i++) {
            pwd[i] = PASSWORD_CHARS[SECURE_RANDOM.nextInt(PASSWORD_CHARS.length)];
        }
        // Fisher-Yates 洗牌，避免固定类字符总在前三位
        for (int i = pwd.length - 1; i > 0; i--) {
            int j = SECURE_RANDOM.nextInt(i + 1);
            char tmp = pwd[i];
            pwd[i] = pwd[j];
            pwd[j] = tmp;
        }
        return new String(pwd);
    }
}
