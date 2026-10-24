package com.xspaceagi.userapp.application.oauth2;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

/**
 * 平台作为 OAuth2 授权方的相关配置
 */
@Data
@ConfigurationProperties(prefix = "oauth2")
public class OAuth2Properties {

    /** access_token 有效期（秒），默认 2 小时；每次校验通过后滑动续期 */
    private long accessTokenTtl = 7200L;

    /** refresh_token 有效期（秒），默认 30 天；刷新时轮换 */
    private long refreshTokenTtl = 2592000L;

    /** 授权码有效期（秒），默认 5 分钟；一次性使用 */
    private long codeTtl = 300L;

    /** 授权关系（用户对客户端的授权）有效期（秒），默认 30 天 */
    private long grantTtl = 2592000L;

    /**
     * 是否跳过用户同意页直接签发授权码。
     * 首版无同意页（原型未覆盖、前端契约未定），故默认 true；接入同意页后置 false 即可强制显式授权。
     */
    private boolean autoConsent = true;
}
