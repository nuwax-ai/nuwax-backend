package com.xspaceagi.system.infra.idp;

import com.xspaceagi.system.infra.dao.entity.AuthIdpConfig;
import com.xspaceagi.system.spec.enums.AuthIdpTypeEnum;

/**
 * IdP客户端（CAS/OAuth2/微信）
 */
public interface IdentityProviderClient {

    AuthIdpTypeEnum type();

    /**
     * 构造跳转到 IdP 的完整授权/登录 URL
     *
     * @param config       Idp配置（含 secret）
     * @param redirectUri  平台回调地址（服务端拼装，不接收前端传入）
     * @param state        平台生成的防 CSRF 随机串
     * @param codeVerifier PKCE code_verifier（可空，仅自定义 OAuth2 启用 PKCE 时使用）
     */
    String buildAuthorizeUrl(AuthIdpConfig config, String redirectUri, String state, String codeVerifier);

    /**
     * 用回跳凭证换取外部身份，失败抛 BizException
     */
    ExternalIdentity exchange(AuthIdpConfig config, IdpExchangeContext ctx);
}
