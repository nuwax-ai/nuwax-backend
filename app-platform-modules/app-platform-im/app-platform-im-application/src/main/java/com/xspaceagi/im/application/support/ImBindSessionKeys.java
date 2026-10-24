package com.xspaceagi.im.application.support;

/**
 * IM OAuth 绑定会话在 Redis 中的 key / TTL。
 */
public final class ImBindSessionKeys {

    public static final String PREFIX = "im.bind.session.";
    /** 绑定会话有效期（秒） */
    public static final long TTL_SECONDS = 300L;

    private ImBindSessionKeys() {
    }

    public static String key(String bindId) {
        return PREFIX + bindId;
    }
}
