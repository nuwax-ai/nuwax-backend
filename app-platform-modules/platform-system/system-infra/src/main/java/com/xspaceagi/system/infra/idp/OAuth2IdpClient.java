package com.xspaceagi.system.infra.idp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.system.infra.dao.entity.AuthIdpConfig;
import com.xspaceagi.system.spec.enums.AuthIdpTypeEnum;
import com.xspaceagi.system.spec.enums.OAuth2ProviderEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.HttpClient;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 标准 OAuth2 授权码模式客户端。预设（飞书/企业微信/钉钉/GitHub）端点与用户信息格式内置，
 * 自定义（CUSTOM）要求填写完整端点，支持 PKCE（S256）与字段映射。
 * 所有 token/userinfo 交互均服务端完成，不信任前端传回的任何资料。
 */
@Slf4j
@Component
public class OAuth2IdpClient implements IdentityProviderClient {

    private static final String FEISHU_AUTHORIZE_URL = "https://open.feishu.cn/open-apis/authen/v1/index";
    private static final String FEISHU_TOKEN_URL = "https://open.feishu.cn/open-apis/authen/v2/oauth/token";
    private static final String FEISHU_USERINFO_URL = "https://open.feishu.cn/open-apis/authen/v1/user_info";

    private static final String WECOM_AUTHORIZE_URL = "https://login.work.weixin.qq.com/wwlogin/sso/login";
    private static final String WECOM_TOKEN_URL = "https://qyapi.weixin.qq.com/cgi-bin/gettoken";
    private static final String WECOM_CODE_EXCHANGE_URL = "https://qyapi.weixin.qq.com/cgi-bin/auth/getuserinfo";
    private static final String WECOM_USER_DETAIL_URL = "https://qyapi.weixin.qq.com/cgi-bin/user/get";

    private static final String DINGTALK_AUTHORIZE_URL = "https://login.dingtalk.com/oauth2/auth";
    private static final String DINGTALK_TOKEN_URL = "https://api.dingtalk.com/v1.0/oauth2/userAccessToken";
    private static final String DINGTALK_USERINFO_URL = "https://api.dingtalk.com/v1.0/contact/users/me";

    private static final String GITHUB_AUTHORIZE_URL = "https://github.com/login/oauth/authorize";
    private static final String GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String GITHUB_USERINFO_URL = "https://api.github.com/user";
    private static final String GITHUB_EMAILS_URL = "https://api.github.com/user/emails";

    /** 自定义提供方默认字段映射（OIDC 标准 claims） */
    private static final Map<String, String> CUSTOM_DEFAULT_MAPPING = Map.of(
            "externalId", "sub",
            "userName", "preferred_username",
            "nickName", "name",
            "email", "email",
            "avatar", "picture");

    @Resource
    private HttpClient httpClient;

    @Resource
    private RedisUtil redisUtil;

    @Override
    public AuthIdpTypeEnum type() {
        return AuthIdpTypeEnum.OAUTH2;
    }

    @Override
    public String buildAuthorizeUrl(AuthIdpConfig config, String redirectUri, String state, String codeVerifier) {
        OAuth2IdpConfig cfg = IdpConfigParser.parseOAuth2(config);
        OAuth2ProviderEnum provider = requireProvider(cfg);
        return switch (provider) {
            case FEISHU -> FEISHU_AUTHORIZE_URL
                    + "?app_id=" + urlEnc(cfg.getClientId())
                    + "&redirect_uri=" + urlEnc(redirectUri)
                    + "&state=" + urlEnc(state);
            case WECOM -> WECOM_AUTHORIZE_URL
                    + "?login_type=CorpApp"
                    + "&appid=" + urlEnc(cfg.getClientId())
                    + "&agentid=" + urlEnc(cfg.getAgentId())
                    + "&redirect_uri=" + urlEnc(redirectUri)
                    + "&state=" + urlEnc(state);
            case DINGTALK -> DINGTALK_AUTHORIZE_URL
                    + "?redirect_uri=" + urlEnc(redirectUri)
                    + "&response_type=code"
                    + "&client_id=" + urlEnc(cfg.getClientId())
                    + "&scope=" + urlEnc("openid")
                    + "&state=" + urlEnc(state)
                    + "&prompt=consent";
            case GITHUB -> GITHUB_AUTHORIZE_URL
                    + "?client_id=" + urlEnc(cfg.getClientId())
                    + "&redirect_uri=" + urlEnc(redirectUri)
                    + "&scope=" + urlEnc("read:user user:email")
                    + "&state=" + urlEnc(state);
            case CUSTOM -> buildCustomAuthorizeUrl(cfg, redirectUri, state, codeVerifier);
        };
    }

    @Override
    public ExternalIdentity exchange(AuthIdpConfig config, IdpExchangeContext ctx) {
        OAuth2IdpConfig cfg = IdpConfigParser.parseOAuth2(config);
        OAuth2ProviderEnum provider = requireProvider(cfg);
        return switch (provider) {
            case FEISHU -> exchangeFeishu(cfg, ctx);
            case WECOM -> exchangeWecom(cfg, ctx);
            case DINGTALK -> exchangeDingtalk(cfg, ctx);
            case GITHUB -> exchangeGithub(cfg, ctx);
            case CUSTOM -> exchangeCustom(cfg, ctx);
        };
    }

    // ==================== 飞书 ====================

    private ExternalIdentity exchangeFeishu(OAuth2IdpConfig cfg, IdpExchangeContext ctx) {
        JSONObject body = new JSONObject();
        body.put("grant_type", "authorization_code");
        body.put("client_id", cfg.getClientId());
        body.put("client_secret", cfg.getClientSecret());
        body.put("code", ctx.getCredential());
        body.put("redirect_uri", ctx.getRedirectUri());
        JSONObject tokenJson = mustJson(
                httpClient.post(FEISHU_TOKEN_URL, body.toJSONString(), jsonHeaders()),
                "飞书 token 接口响应异常");
        String accessToken = StringUtils.defaultIfBlank(
                tokenJson.getString("access_token"),
                tokenJson.getJSONObject("data") == null ? null : tokenJson.getJSONObject("data").getString("access_token"));
        if (StringUtils.isBlank(accessToken)) {
            throw new BizException("0001", "飞书授权失败: " + feishuError(tokenJson));
        }
        JSONObject info = mustJson(
                httpClient.get(FEISHU_USERINFO_URL, Map.of(), bearerHeaders(accessToken), Map.of()),
                "飞书用户信息接口响应异常");
        JSONObject data = info.getJSONObject("data");
        if (data == null) {
            throw new BizException("0001", "飞书用户信息获取失败: " + feishuError(info));
        }
        ExternalIdentity identity = new ExternalIdentity();
        identity.setExternalId(StringUtils.defaultIfBlank(data.getString("union_id"), data.getString("open_id")));
        identity.setUnionId(data.getString("union_id"));
        // user_id/open_id 为随机标识而非登录名，不进 userName（标识由 external_id 承载）
        identity.setNickName(data.getString("name"));
        identity.setAvatar(data.getString("avatar_url"));
        identity.setEmail(data.getString("email"));
        identity.setPhone(data.getString("mobile"));
        return requireExternalId(identity, "飞书");
    }

    // ==================== 企业微信 ====================

    private ExternalIdentity exchangeWecom(OAuth2IdpConfig cfg, IdpExchangeContext ctx) {
        String accessToken = wecomAccessToken(cfg);
        // code 换 userid
        JSONObject codeJson = mustJson(
                httpClient.get(WECOM_CODE_EXCHANGE_URL + "?access_token=" + urlEnc(accessToken)
                        + "&code=" + urlEnc(ctx.getCredential())),
                "企业微信 code 换取用户接口响应异常");
        checkWecomError(codeJson);
        String userid = StringUtils.defaultIfBlank(codeJson.getString("userid"), codeJson.getString("open_userid"));
        if (StringUtils.isBlank(userid)) {
            throw new BizException("0001", "企业微信授权未返回 userid");
        }
        ExternalIdentity identity = new ExternalIdentity();
        identity.setExternalId(userid);
        identity.setUserName(userid);
        identity.setUnionId(codeJson.getString("unionid"));
        // 拉取用户资料（通讯录权限不足时不阻断登录）
        try {
            JSONObject detail = JSON.parseObject(httpClient.get(WECOM_USER_DETAIL_URL
                    + "?access_token=" + urlEnc(accessToken) + "&userid=" + urlEnc(userid)));
            if (detail != null && (detail.getInteger("errcode") == null || detail.getInteger("errcode") == 0)) {
                identity.setNickName(detail.getString("name"));
                identity.setAvatar(detail.getString("avatar"));
                identity.setEmail(StringUtils.defaultIfBlank(detail.getString("biz_mail"), detail.getString("email")));
                identity.setPhone(detail.getString("mobile"));
            } else if (detail != null) {
                log.warn("企业微信拉取用户资料失败 errcode={} errmsg={}",
                        detail.getInteger("errcode"), detail.getString("errmsg"));
            }
        } catch (Exception e) {
            log.warn("企业微信拉取用户资料异常: {}", e.getMessage());
        }
        return identity;
    }

    /** 企微 access_token（应用凭证级），Redis 缓存避免频繁刷新触发频率限制 */
    private String wecomAccessToken(OAuth2IdpConfig cfg) {
        String cacheKey = "idp:wecom.token:" + StringUtils.defaultString(cfg.getClientId())
                + ":" + StringUtils.defaultString(cfg.getAgentId());
        Object cached = redisUtil.get(cacheKey);
        if (cached != null) {
            return cached.toString();
        }
        JSONObject json = mustJson(
                httpClient.get(WECOM_TOKEN_URL
                        + "?corpid=" + urlEnc(cfg.getClientId())
                        + "&corpsecret=" + urlEnc(cfg.getClientSecret())),
                "企业微信 gettoken 接口响应异常");
        checkWecomError(json);
        String accessToken = json.getString("access_token");
        if (StringUtils.isBlank(accessToken)) {
            throw new BizException("0001", "企业微信获取 access_token 失败");
        }
        Long expiresIn = json.getLong("expires_in");
        redisUtil.set(cacheKey, accessToken, expiresIn == null ? 6000 : Math.max(60, expiresIn - 600));
        return accessToken;
    }

    private static void checkWecomError(JSONObject json) {
        Integer errcode = json.getInteger("errcode");
        if (errcode != null && errcode != 0) {
            log.warn("企业微信接口返回错误 errcode={} errmsg={}", errcode, json.getString("errmsg"));
            throw new BizException("0001", "企业微信接口返回错误: "
                    + StringUtils.defaultIfBlank(json.getString("errmsg"), String.valueOf(errcode)));
        }
    }

    // ==================== 钉钉 ====================

    private ExternalIdentity exchangeDingtalk(OAuth2IdpConfig cfg, IdpExchangeContext ctx) {
        JSONObject body = new JSONObject();
        body.put("clientId", cfg.getClientId());
        body.put("clientSecret", cfg.getClientSecret());
        body.put("code", ctx.getCredential());
        body.put("grantType", "authorization_code");
        JSONObject tokenJson = mustJson(
                httpClient.post(DINGTALK_TOKEN_URL, body.toJSONString(), jsonHeaders()),
                "钉钉 userAccessToken 接口响应异常");
        String accessToken = tokenJson.getString("accessToken");
        if (StringUtils.isBlank(accessToken)) {
            throw new BizException("0001", "钉钉授权失败: "
                    + StringUtils.defaultIfBlank(tokenJson.getString("message"), tokenJson.toJSONString()));
        }
        JSONObject me = mustJson(
                httpClient.get(DINGTALK_USERINFO_URL, Map.of(), bearerHeaders(accessToken), Map.of()),
                "钉钉用户信息接口响应异常");
        ExternalIdentity identity = new ExternalIdentity();
        identity.setExternalId(StringUtils.defaultIfBlank(me.getString("unionId"), me.getString("openId")));
        identity.setUnionId(me.getString("unionId"));
        // openId 为随机标识而非登录名，不进 userName（标识由 external_id 承载）
        identity.setNickName(me.getString("nick"));
        identity.setAvatar(me.getString("avatarUrl"));
        identity.setEmail(me.getString("email"));
        identity.setPhone(me.getString("mobile"));
        return requireExternalId(identity, "钉钉");
    }

    // ==================== GitHub ====================

    private ExternalIdentity exchangeGithub(OAuth2IdpConfig cfg, IdpExchangeContext ctx) {
        Map<String, String> form = new HashMap<>();
        form.put("client_id", cfg.getClientId());
        form.put("client_secret", cfg.getClientSecret());
        form.put("code", ctx.getCredential());
        form.put("redirect_uri", ctx.getRedirectUri());
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        JSONObject tokenJson = mustJson(httpClient.post(GITHUB_TOKEN_URL, form, headers), "GitHub token 接口响应异常");
        String accessToken = tokenJson.getString("access_token");
        if (StringUtils.isBlank(accessToken)) {
            throw new BizException("0001", "GitHub 授权失败: "
                    + StringUtils.defaultIfBlank(tokenJson.getString("error_description"), "未返回 access_token"));
        }
        Map<String, Object> userHeaders = bearerHeaders(accessToken);
        // GitHub API 要求必须携带 User-Agent
        userHeaders.put("User-Agent", "nuwax-agent-platform");
        JSONObject user = mustJson(httpClient.get(GITHUB_USERINFO_URL, Map.of(), userHeaders, Map.of()), "GitHub 用户信息接口响应异常");
        Long id = user.getLong("id");
        if (id == null) {
            throw new BizException("0001", "GitHub 用户信息缺少 id");
        }
        ExternalIdentity identity = new ExternalIdentity();
        identity.setExternalId(String.valueOf(id));
        identity.setUserName(user.getString("login"));
        identity.setNickName(StringUtils.defaultIfBlank(user.getString("name"), user.getString("login")));
        identity.setAvatar(user.getString("avatar_url"));
        identity.setEmail(resolveGithubEmail(userHeaders, user.getString("email")));
        return identity;
    }

    /** 主 email 为空或未公开时调 /user/emails 取已验证邮箱 */
    private String resolveGithubEmail(Map<String, Object> headers, String publicEmail) {
        if (StringUtils.isNotBlank(publicEmail)) {
            return publicEmail;
        }
        try {
            String body = httpClient.get(GITHUB_EMAILS_URL, Map.of(), headers, Map.of());
            if (JSON.isValid(body)) {
                JSONArray emails = JSON.parseArray(body);
                String firstVerified = null;
                for (int i = 0; i < emails.size(); i++) {
                    JSONObject item = emails.getJSONObject(i);
                    if (Boolean.TRUE.equals(item.getBoolean("verified"))) {
                        if (Boolean.TRUE.equals(item.getBoolean("primary"))) {
                            return item.getString("email");
                        }
                        if (firstVerified == null) {
                            firstVerified = item.getString("email");
                        }
                    }
                }
                return firstVerified;
            }
        } catch (Exception e) {
            log.warn("GitHub 获取已验证邮箱失败: {}", e.getMessage());
        }
        return null;
    }

    // ==================== 自定义 ====================

    private String buildCustomAuthorizeUrl(OAuth2IdpConfig cfg, String redirectUri, String state, String codeVerifier) {
        if (StringUtils.isBlank(cfg.getAuthorizeUrl())) {
            throw new BizException("0001", "自定义 OAuth2 未配置授权端点");
        }
        StringBuilder url = new StringBuilder(cfg.getAuthorizeUrl().trim())
                .append(cfg.getAuthorizeUrl().contains("?") ? "&" : "?")
                .append("client_id=").append(urlEnc(cfg.getClientId()))
                .append("&redirect_uri=").append(urlEnc(redirectUri))
                .append("&response_type=code");
        if (StringUtils.isNotBlank(cfg.getScope())) {
            url.append("&scope=").append(urlEnc(cfg.getScope()));
        }
        url.append("&state=").append(urlEnc(state));
        if (Boolean.TRUE.equals(cfg.getUsePkce()) && StringUtils.isNotBlank(codeVerifier)) {
            url.append("&code_challenge=").append(urlEnc(codeChallenge(codeVerifier)))
                    .append("&code_challenge_method=S256");
        }
        return url.toString();
    }

    private ExternalIdentity exchangeCustom(OAuth2IdpConfig cfg, IdpExchangeContext ctx) {
        if (StringUtils.isAnyBlank(cfg.getTokenUrl(), cfg.getUserinfoUrl())) {
            throw new BizException("0001", "自定义 OAuth2 未配置 token/userinfo 端点");
        }
        Map<String, String> form = new HashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", ctx.getCredential());
        form.put("redirect_uri", ctx.getRedirectUri());
        Map<String, String> headers = new HashMap<>();
        boolean basicAuth = "basic".equalsIgnoreCase(cfg.getAuthMethod());
        if (basicAuth) {
            String credential = cfg.getClientId() + ":" + StringUtils.defaultString(cfg.getClientSecret());
            headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString(credential.getBytes(StandardCharsets.UTF_8)));
        } else {
            form.put("client_id", cfg.getClientId());
            form.put("client_secret", cfg.getClientSecret());
        }
        if (Boolean.TRUE.equals(cfg.getUsePkce()) && StringUtils.isNotBlank(ctx.getCodeVerifier())) {
            form.put("code_verifier", ctx.getCodeVerifier());
        }
        JSONObject tokenJson = mustJson(httpClient.post(cfg.getTokenUrl().trim(), form, headers), "OAuth2 token 端点响应异常");
        String accessToken = StringUtils.defaultIfBlank(tokenJson.getString("access_token"), tokenJson.getString("accessToken"));
        if (StringUtils.isBlank(accessToken)) {
            throw new BizException("0001", "OAuth2 换取 token 失败: "
                    + StringUtils.defaultIfBlank(tokenJson.getString("error_description"), tokenJson.getString("error")));
        }
        JSONObject userinfo = mustJson(
                httpClient.get(cfg.getUserinfoUrl().trim(), Map.of(), bearerHeaders(accessToken), Map.of()),
                "OAuth2 userinfo 端点响应异常");

        Map<String, String> mapping = new LinkedHashMap<>(CUSTOM_DEFAULT_MAPPING);
        if (cfg.getFieldMapping() != null) {
            mapping.putAll(cfg.getFieldMapping());
        }
        ExternalIdentity identity = new ExternalIdentity();
        String externalId = claimString(userinfo, mapping.get("externalId"));
        if (StringUtils.isBlank(externalId)) {
            // 非标准 IdP 的常见兜底字段
            for (String fallback : new String[]{"id", "open_id", "unionid", "userId", "uid"}) {
                externalId = claimString(userinfo, fallback);
                if (StringUtils.isNotBlank(externalId)) {
                    break;
                }
            }
        }
        identity.setExternalId(externalId);
        identity.setUnionId(claimString(userinfo, "unionid"));
        identity.setUserName(claimString(userinfo, mapping.get("userName")));
        identity.setNickName(StringUtils.defaultIfBlank(
                claimString(userinfo, mapping.get("nickName")), claimString(userinfo, mapping.get("userName"))));
        identity.setEmail(claimString(userinfo, mapping.get("email")));
        identity.setAvatar(claimString(userinfo, mapping.get("avatar")));
        return requireExternalId(identity, "自定义 OAuth2");
    }

    // ==================== 公共工具 ====================

    private static OAuth2ProviderEnum requireProvider(OAuth2IdpConfig cfg) {
        OAuth2ProviderEnum provider = OAuth2ProviderEnum.of(cfg.getProvider());
        if (provider == null) {
            throw new BizException("0001", "未知的 OAuth2 提供方: " + cfg.getProvider());
        }
        return provider;
    }

    private static ExternalIdentity requireExternalId(ExternalIdentity identity, String providerName) {
        if (StringUtils.isBlank(identity.getExternalId())) {
            throw new BizException("0001", providerName + "用户信息缺少唯一标识");
        }
        return identity;
    }

    /** OIDC picture 可能是字符串或 {url: ...} 对象 */
    private static String claimString(JSONObject json, String key) {
        if (StringUtils.isBlank(key)) {
            return null;
        }
        Object value = json.get(key);
        if (value == null) {
            return null;
        }
        if (value instanceof JSONObject object && object.getString("url") != null) {
            return object.getString("url");
        }
        return value.toString();
    }

    private static String codeChallenge(String verifier) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
        } catch (Exception e) {
            throw new IllegalStateException("PKCE code_challenge 计算失败", e);
        }
    }

    private static JSONObject mustJson(String body, String message) {
        if (!JSON.isValid(body)) {
            log.warn("OAuth2 接口响应非 JSON: {}", StringUtils.abbreviate(body, 200));
            throw new BizException("0001", message);
        }
        return JSON.parseObject(body);
    }

    private static String feishuError(JSONObject json) {
        Integer code = json.getInteger("code");
        if (code != null && code != 0) {
            return "code=" + code + " " + StringUtils.defaultString(json.getString("msg"));
        }
        return json.toJSONString();
    }

    private static Map<String, String> jsonHeaders() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json; charset=utf-8");
        return headers;
    }

    private static Map<String, Object> bearerHeaders(String accessToken) {
        Map<String, Object> headers = new HashMap<>();
        headers.put("Authorization", "Bearer " + accessToken);
        return headers;
    }

    private static String urlEnc(String value) {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8);
    }
}
