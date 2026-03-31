package com.xspaceagi.im.wechat.ilink;

/**
 * iLink 网关常量（对齐 openclaw-weixin）
 */
public final class IlinkConstants {

    /** 默认网关基址 */
    public static final String DEFAULT_BASE_URL = "https://ilinkai.weixin.qq.com";

    /** 默认 get_bot_qrcode 的 bot_type */
    public static final String DEFAULT_BOT_TYPE = "3";

    /** CDN 基址（出站媒体等） */
    public static final String CDN_BASE_URL = "https://novac2c.cdn.weixin.qq.com/c2c";

    /** 默认长轮询超时（毫秒），用于扫码状态等需长时间挂起的上游请求 */
    public static final int DEFAULT_LONG_POLL_TIMEOUT_MS = 35_000;

    /** getUpdates 冷启动（buf 为空）默认超时（毫秒） */
    public static final int DEFAULT_GET_UPDATES_POLL_TIMEOUT_MS = 5_000;
    /** getUpdates 热态（buf 非空）默认超时（毫秒） */
    public static final int DEFAULT_GET_UPDATES_HOT_TIMEOUT_MS = 300;
    /** 轮询配置缓存默认 TTL（秒） */
    public static final int DEFAULT_POLL_CONFIG_CACHE_TTL_SECONDS = 86_400;
    /** 轮询调度默认 CRON */
    public static final String DEFAULT_POLL_SCHEDULE_CRON = "0/3 * * * * ?";
    /**
     * getUpdates 返回 session timeout：游标/会话过期，需清空 get_updates_buf 后重试（与上游 errcode 对齐）
     */
    public static final int GET_UPDATES_ERR_SESSION_TIMEOUT = -14;

    public static final String AUTH_TYPE_ILINK_BOT_TOKEN = "ilink_bot_token";

    private IlinkConstants() {
    }
}
