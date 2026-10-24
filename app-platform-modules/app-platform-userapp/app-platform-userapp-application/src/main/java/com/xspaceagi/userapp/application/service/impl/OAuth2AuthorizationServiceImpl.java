package com.xspaceagi.userapp.application.service.impl;

import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.system.spec.utils.RedisUtil;
import com.xspaceagi.userapp.application.oauth2.OAuth2CredentialGenerator;
import com.xspaceagi.userapp.application.oauth2.OAuth2GrantContext;
import com.xspaceagi.userapp.application.oauth2.OAuth2Properties;
import com.xspaceagi.userapp.application.oauth2.OAuth2TokenPair;
import com.xspaceagi.userapp.application.service.IOAuth2AuthorizationService;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class OAuth2AuthorizationServiceImpl implements IOAuth2AuthorizationService {

    private static final String CODE_KEY = "oauth2:code:";

    private static final String ACCESS_KEY = "oauth2:access:";

    private static final String REFRESH_KEY = "oauth2:refresh:";

    private static final String GRANT_KEY = "oauth2:grant:";

    /** 用户级撤销时间戳：<clientId>:<userId> → epoch 秒 */
    private static final String REVOKED_KEY = "oauth2:revoked:";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private OAuth2Properties oAuth2Properties;

    @Override
    public boolean isAccessToken(String token) {
        return StringUtils.isNotBlank(token) && token.startsWith(OAuth2CredentialGenerator.ACCESS_TOKEN_PREFIX);
    }

    @Override
    public boolean isRefreshToken(String token) {
        return StringUtils.isNotBlank(token) && token.startsWith(OAuth2CredentialGenerator.REFRESH_TOKEN_PREFIX);
    }

    @Override
    public String createAuthorizationCode(OAuth2GrantContext context) {
        // client_credentials 无用户上下文，不会走授权码分支
        context.setGrantType("authorization_code");
        context.setIssuedAt(epochSecond());
        String code = OAuth2CredentialGenerator.newAuthorizationCode();
        write(CODE_KEY + code, context, oAuth2Properties.getCodeTtl());
        return code;
    }

    @Override
    public OAuth2GrantContext consumeAuthorizationCode(String code) {
        if (StringUtils.isBlank(code)) {
            return null;
        }
        String key = CODE_KEY + code;
        OAuth2GrantContext context = read(key);
        if (context == null) {
            return null;
        }
        // 授权码一次性：无论后续成败都作废，避免重放
        redisUtil.deleteKey(key);
        return context;
    }

    @Override
    public OAuth2GrantContext loadAccessToken(String accessToken) {
        if (StringUtils.isBlank(accessToken)) {
            return null;
        }
        String key = ACCESS_KEY + accessToken;
        OAuth2GrantContext context = read(key);
        if (context == null) {
            return null;
        }
        // 滑动续期：活跃调用不因 2 小时到期而中断
        redisUtil.expire(key, oAuth2Properties.getAccessTokenTtl());
        return context;
    }

    @Override
    public OAuth2GrantContext loadRefreshToken(String refreshToken) {
        if (StringUtils.isBlank(refreshToken)) {
            return null;
        }
        return read(REFRESH_KEY + refreshToken);
    }

    @Override
    public OAuth2TokenPair issueTokens(OAuth2GrantContext context, boolean withRefresh) {
        context.setIssuedAt(epochSecond());
        String accessToken = OAuth2CredentialGenerator.newAccessToken();
        write(ACCESS_KEY + accessToken, context, oAuth2Properties.getAccessTokenTtl());

        OAuth2TokenPair pair = new OAuth2TokenPair();
        pair.setAccessToken(accessToken);
        pair.setExpiresIn(oAuth2Properties.getAccessTokenTtl());
        pair.setScope(context.getScope());
        if (withRefresh) {
            String refreshToken = OAuth2CredentialGenerator.newRefreshToken();
            write(REFRESH_KEY + refreshToken, context, oAuth2Properties.getRefreshTokenTtl());
            pair.setRefreshToken(refreshToken);
        }
        return pair;
    }

    @Override
    public void saveGrant(String clientId, Long userId, List<String> scopes) {
        if (StringUtils.isBlank(clientId) || userId == null) {
            return;
        }
        redisUtil.set(grantKey(clientId, userId), scopes == null ? "" : String.join(",", scopes),
                oAuth2Properties.getGrantTtl());
    }

    @Override
    public boolean hasGrant(String clientId, Long userId) {
        if (StringUtils.isBlank(clientId) || userId == null) {
            return false;
        }
        return redisUtil.get(grantKey(clientId, userId)) != null;
    }

    @Override
    public void discardRefreshToken(String refreshToken) {
        if (StringUtils.isNotBlank(refreshToken)) {
            redisUtil.deleteKey(REFRESH_KEY + refreshToken);
        }
    }

    @Override
    public void revoke(String clientId, Long userId) {
        if (StringUtils.isBlank(clientId) || userId == null) {
            return;
        }
        // 令牌无法按键枚举，改以「撤销时间戳」判定：签发光早于撤销时间的令牌一律失效
        redisUtil.set(revokedKey(clientId, userId), String.valueOf(epochSecond()), oAuth2Properties.getGrantTtl());
        redisUtil.deleteKey(grantKey(clientId, userId));
        log.info("OAuth2 authorization revoked, clientId={}, userId={}", clientId, userId);
    }

    @Override
    public Long revokedAt(String clientId, Long userId) {
        if (StringUtils.isBlank(clientId) || userId == null) {
            return null;
        }
        Object value = redisUtil.get(revokedKey(clientId, userId));
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value.toString());
        } catch (NumberFormatException e) {
            log.warn("Invalid oauth2 revoked timestamp, clientId={}, userId={}, value={}", clientId, userId, value);
            return null;
        }
    }

    private String grantKey(String clientId, Long userId) {
        return GRANT_KEY + clientId + ":" + userId;
    }

    private String revokedKey(String clientId, Long userId) {
        return REVOKED_KEY + clientId + ":" + userId;
    }

    private OAuth2GrantContext read(String key) {
        Object value = redisUtil.get(key);
        if (value == null) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(value.toString(), OAuth2GrantContext.class);
        } catch (JsonProcessingException e) {
            log.warn("Invalid oauth2 context in redis, key={}", key, e);
            return null;
        }
    }

    private void write(String key, OAuth2GrantContext context, long ttl) {
        try {
            redisUtil.set(key, OBJECT_MAPPER.writeValueAsString(context), ttl);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Serialize oauth2 context failed, key=" + key, e);
        }
    }

    private long epochSecond() {
        return System.currentTimeMillis() / 1000L;
    }
}
