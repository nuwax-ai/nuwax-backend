package com.xspaceagi.system.infra.idp;

import lombok.Builder;
import lombok.Data;

/**
 * 回跳凭证交换上下文：redirectUri/state 必须与发起授权时逐字一致（CAS service 校验、OAuth2 redirect_uri 校验都要求）
 */
@Data
@Builder
public class IdpExchangeContext {

    /**
     * 平台回调地址（含 provider 参数；CAS 还含 state 参数）
     */
    private String redirectUri;

    /**
     * 回跳凭证：OAuth2 授权码 code 或 CAS ticket
     */
    private String credential;

    /**
     * 发起时生成的 state（CAS 通过 service 查询参数携带，交换时须原样还原）
     */
    private String state;

    /**
     * PKCE code_verifier（仅自定义 OAuth2 且启用 PKCE 时有值）
     */
    private String codeVerifier;
}
