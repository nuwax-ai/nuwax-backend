package com.xspaceagi.userapp.application.service;

import java.util.List;

import com.xspaceagi.userapp.application.oauth2.OAuth2GrantContext;
import com.xspaceagi.userapp.application.oauth2.OAuth2TokenPair;

/**
 * OAuth2 授权码与令牌的存取（全部落 Redis，不新增表）。
 * <p>
 * 令牌为不可猜测的不透明随机串，非 JWT：校验时以 Redis 为准，撤销即时生效。
 */
public interface IOAuth2AuthorizationService {

    /** 是否为平台签发的 OAuth2 access_token（拦截器据此分派） */
    boolean isAccessToken(String token);

    /** 是否为平台签发的 refresh_token */
    boolean isRefreshToken(String token);

    /** 签发授权码（一次性，TTL 见 oauth2.code-ttl） */
    String createAuthorizationCode(OAuth2GrantContext context);

    /** 取用授权码：取到即删除；取不到抛 oauth2InvalidGrant */
    OAuth2GrantContext consumeAuthorizationCode(String code);

    /** 读取 access_token 上下文并滑动续期；无效返回 null */
    OAuth2GrantContext loadAccessToken(String accessToken);

    /** 读取 refresh_token 上下文；无效返回 null */
    OAuth2GrantContext loadRefreshToken(String refreshToken);

    /** 签发令牌对：withRefresh=false 时不签发 refresh_token（client_credentials） */
    OAuth2TokenPair issueTokens(OAuth2GrantContext context, boolean withRefresh);

    /** 记录 / 覆盖用户对客户端的常驻授权 */
    void saveGrant(String clientId, Long userId, List<String> scopes);

    /** 用户对客户端是否仍有有效授权（刷新令牌的必要条件） */
    boolean hasGrant(String clientId, Long userId);

    /** 刷新令牌轮换：作废已使用的 refresh_token */
    void discardRefreshToken(String refreshToken);

    /** 撤销该用户在该客户端下的全部令牌与授权（即时生效） */
    void revoke(String clientId, Long userId);

    /** 用户级撤销时间戳（epoch 秒），从未撤销返回 null */
    Long revokedAt(String clientId, Long userId);
}
