package com.xspaceagi.userapp.application.oauth2;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * 一次 OAuth2 授权的上下文，序列化后存于 Redis（授权码 / access_token / refresh_token 共用）
 */
@Data
// ★必须容未知字段：读写用的是默认配置的 ObjectMapper（FAIL_ON_UNKNOWN_PROPERTIES = true），
// 而 OAuth2AuthorizationServiceImpl.read 把反序列化异常降级成「返回 null」= 令牌无效。
// 少了这一行，滚动发布期间老实例读到新实例签发、带新增字段的令牌会全部判为无效——
// 表现是「用户刚授权就掉线」，且日志只有一条 warn，极难定位。
// 加字段是本类的常态演进方式（codeChallenge 就是后加的），容未知字段是前向兼容的底线。
@JsonIgnoreProperties(ignoreUnknown = true)
public class OAuth2GrantContext {

    /** 接入应用（user_project 主键，project_type = ThirdApp） */
    private Long projectId;

    /** Client ID */
    private String clientId;

    /**
     * 授权用户。
     * client_credentials 无用户上下文，绑定接入应用的创建者（与平台 AK 的语义一致）。
     */
    private Long userId;

    /** 租户 id：令牌校验时以此为准，不依赖请求 Host 解析 */
    private Long tenantId;

    /** 授权码模式登记的回调地址（刷新 / 校验时用于核对） */
    private String redirectUri;

    /** 本令牌被授予的 scope（逗号分隔） */
    private String scope;

    /** 签发来源：authorization_code / refresh_token / client_credentials */
    private String grantType;

    /** 签发时间（epoch 秒），用户级撤销时据此判断令牌是否早于撤销时间 */
    private Long issuedAt;

    /**
     * ★PKCE（RFC 7636）：授权码请求携带的 code_challenge，token 端点用 code_verifier 校验。
     * <p>
     * null = 该授权码未启用 PKCE（授权请求没带 challenge）。这是<b>向后兼容</b>的关键：
     * 本字段是后加的，Redis 里的存量授权码反序列化出来就是 null，
     * token 端点据此跳过校验而不是判失败，避免升级瞬间正在走流程的授权码全部作废。
     * <p>
     * 只在授权码流转期间有意义：签发出的 access_token / refresh_token 上下文里可以不带它，
     * 因为 verifier 只在换 token 那一次用到。
     */
    private String codeChallenge;

    /**
     * ★PKCE 变换方式：{@code S256}（默认，推荐）或 {@code plain}。
     * <p>
     * {@code plain} 仅为兼容无法做 SHA-256 的老客户端保留（RFC 7636 §4.2 允许但不推荐）；
     * 授权端点在 challenge 非空但本字段为空时按 {@code plain} 处理，与 RFC 的缺省值一致。
     */
    private String codeChallengeMethod;
}
