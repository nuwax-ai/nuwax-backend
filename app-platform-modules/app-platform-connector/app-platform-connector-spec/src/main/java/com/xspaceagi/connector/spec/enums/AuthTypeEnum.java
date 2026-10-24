package com.xspaceagi.connector.spec.enums;

/**
 * 连接器认证类型（对应设计文档 §3.1 块2）。
 * custom 必须保留：多字段组合（accessKey+secretKey+region）、HMAC 签名等自定义凭证。
 * <p>
 * OAuth 有两种建连机制（授权码 / 设备码扫码），但<b>凭证形态完全相同</b>：都产出
 * {@code {accessToken, refreshToken, tokenType}}、都存为 {@code oauth2_token}、
 * 都需要过期刷新、都以 {@code Authorization: Bearer} 注入。凡是判断「这个连接器是否有
 * 一张需要刷新和注入的 OAuth 令牌」的地方，一律用 {@link #isOauth2TokenFlow()}，
 * 不要写 {@code == OAUTH2}——漏掉 {@link #OAUTH2_DEVICE} 会导致令牌 7200 秒后僵死
 * 或注入落到 UNSUPPORTED_AUTH_TYPE。只有「怎么建连」的分派才区分两者。
 */
public enum AuthTypeEnum {

    /** 无需认证 */
    NO_AUTH("no_auth"),

    /** API Key（注入位置由 provider auth_config 决定：header/query/body） */
    API_KEY("api_key"),

    /** Bearer Token */
    BEARER("bearer"),

    /** OAuth2 授权码流程（需登记 redirect_uri，IdP 回调建连） */
    OAUTH2("oauth2"),

    /**
     * OAuth2 设备码流程（RFC 8628）：出二维码让用户在 App 内确认，服务端轮询令牌端点建连。
     * 不需要 redirect_uri，也不依赖公网回调地址。按机制命名而非按厂商命名，
     * 将来钉钉/企微等扫码建连可复用同一枚举值。
     */
    OAUTH2_DEVICE("oauth2_device"),

    /** 自定义凭证（多字段组合/签名类） */
    CUSTOM("custom");

    private final String code;

    AuthTypeEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /**
     * 是否走 OAuth 令牌体系（授权码或设备码）：凭证类型为 {@code oauth2_token}，
     * 需要过期刷新，并以 {@code Authorization: Bearer} 注入。
     * <p>
     * 与 {@code != NO_AUTH} 的区别：后者只表示「需要建连接」，前者进一步表示
     * 「连接里装的是一张会过期的 OAuth 令牌」。
     */
    public boolean isOauth2TokenFlow() {
        return this == OAUTH2 || this == OAUTH2_DEVICE;
    }

    public static AuthTypeEnum fromCode(String code) {
        for (AuthTypeEnum type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown auth_type: " + code);
    }
}
