package com.xspaceagi.system.application.dto;

import lombok.Data;

/**
 * authorize 发起时写入 Redis 的 state 数据（auth:idp:state:{state}，一次性、短时效）
 */
@Data
public class AuthIdpStateData {

    private Long tenantId;

    private Long providerId;

    /**
     * login-登录、bind-绑定当前登录用户
     */
    private String mode;

    /**
     * 登录完成后的回跳目标（已校验同站）
     */
    private String redirect;

    /**
     * bind 模式下发起绑定的用户ID（不依赖回跳时的 cookie）
     */
    private Long userId;

    /**
     * PKCE code_verifier（仅自定义 OAuth2 启用 PKCE 时有值）
     */
    private String codeVerifier;
}
