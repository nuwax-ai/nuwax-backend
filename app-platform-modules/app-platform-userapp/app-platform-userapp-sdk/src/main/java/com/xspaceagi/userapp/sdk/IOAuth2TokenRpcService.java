package com.xspaceagi.userapp.sdk;

import com.xspaceagi.userapp.sdk.dto.OAuth2TokenContextDto;

/**
 * OAuth2 access_token 校验（跨模块调用：鉴权拦截器使用）
 */
public interface IOAuth2TokenRpcService {

    /**
     * 校验 access_token 并做 scope 白名单校验。
     * <p>
     * 返回值语义：
     * <ul>
     * <li>非平台签发的 OAuth2 令牌（前缀不符）→ 返回 null，调用方落回原有鉴权逻辑；</li>
     * <li>令牌不存在 / 已过期 / 已被撤销 / 客户端已删除或停用 → 返回 null，最终表现为未登录；</li>
     * <li>令牌有效但请求路径不在其 scope 放行范围内 → 抛 oauth2InsufficientScope。</li>
     * </ul>
     *
     * @param accessToken 原始令牌（含 oa- 前缀）
     * @param requestUri  原始请求路径（含查询串时由实现自行截断）
     */
    OAuth2TokenContextDto validateAccessToken(String accessToken, String requestUri);
}
