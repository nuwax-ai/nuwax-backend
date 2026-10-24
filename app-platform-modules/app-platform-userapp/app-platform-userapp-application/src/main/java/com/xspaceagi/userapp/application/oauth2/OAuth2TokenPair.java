package com.xspaceagi.userapp.application.oauth2;

import lombok.Data;

/**
 * 令牌签发结果（对应 RFC 6749 的 token 响应字段）
 */
@Data
public class OAuth2TokenPair {

    private String accessToken;

    /** client_credentials 不签发 refresh_token */
    private String refreshToken;

    /** access_token 有效期（秒） */
    private Long expiresIn;

    private String scope;

    /** 固定 Bearer */
    private String tokenType = "Bearer";
}
