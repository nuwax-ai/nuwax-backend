package com.xspaceagi.system.application.constant;

import java.util.regex.Pattern;

/**
 * 用户个人 key-value 配置的约束：key 不设白名单（任意 key 都允许），但限制格式与容量
 */
public final class UserConfigConstraints {

    /** key 最大长度 */
    public static final int MAX_KEY_LENGTH = 128;

    /** 单个 key 的值序列化后的最大字节数 */
    public static final int MAX_VALUE_BYTES = 64 * 1024;

    /** 备注最大长度（与 user_config.description 列宽一致） */
    public static final int MAX_DESCRIPTION_LENGTH = 255;

    /** 单用户配置项数量上限 */
    public static final long MAX_KEYS_PER_USER = 200L;

    /**
     * key 会出现在 query string、日志与前端路由中，限定为字母、数字、下划线、点、冒号、短横线
     */
    private static final Pattern KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_.:\\-]+$");

    private UserConfigConstraints() {
    }

    public static boolean isValidKey(String key) {
        return key != null && key.length() <= MAX_KEY_LENGTH && KEY_PATTERN.matcher(key).matches();
    }
}
