package com.xspaceagi.system.infra.rpc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.system.infra.dao.entity.TenantConfig;
import com.xspaceagi.system.infra.dao.service.TenantConfigService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.HttpClient;
import jakarta.annotation.Resource;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 微信公众号 OAuth 与 JSAPI 支付凭证（按租户 oaAppId / oaAppSecret）。 */
@Slf4j
@Service
public class WeChatOaService {

    private static final String WECHAT_OAUTH_ACCESS_TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";
    private static final String WECHAT_OAUTH_AUTHORIZE_URL = "https://open.weixin.qq.com/connect/oauth2/authorize";

    @Resource
    private TenantConfigService tenantConfigService;

    @Resource
    private HttpClient httpClient;

    /**
     * 公众号网页授权 code 换取 openId（snsapi_base）。
     */
    public String getOpenIdByOAuthCode(String code, long tenantId) {
        if (!StringUtils.hasText(code)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemWeChatOpenIdFetchFailed);
        }
        OaCredentials credentials = resolveOaCredentials(tenantId);
        String url = WECHAT_OAUTH_ACCESS_TOKEN_URL
                + "?appid=" + credentials.appId()
                + "&secret=" + credentials.appSecret()
                + "&code=" + URLEncoder.encode(code.trim(), StandardCharsets.UTF_8)
                + "&grant_type=authorization_code";
        String response = httpClient.get(url);
        if (!JSON.isValid(response)) {
            log.warn("微信 OAuth access_token 响应非 JSON tenantId={}", tenantId);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.systemWeChatOpenIdFetchFailed);
        }
        JSONObject jsonObject = JSONObject.parseObject(response);
        if (jsonObject.containsKey("errcode") && jsonObject.getInteger("errcode") != 0) {
            log.warn(
                    "微信 OAuth access_token 失败 tenantId={} errcode={} errmsg={}",
                    tenantId,
                    jsonObject.getInteger("errcode"),
                    jsonObject.getString("errmsg"));
            throw BizException.of(
                    ErrorCodeEnum.ERROR_REQUEST,
                    BizExceptionCodeEnum.systemWeChatApiReturnedError,
                    jsonObject.getString("errmsg"));
        }
        String openId = jsonObject.getString("openid");
        if (!StringUtils.hasText(openId)) {
            log.warn("微信 OAuth access_token 未返回 openid tenantId={}", tenantId);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.systemWeChatOpenIdFetchFailed);
        }
        log.info("微信 OAuth 成功 tenantId={} openid={}", tenantId, maskOpenId(openId));
        return openId;
    }

    /** 构建 snsapi_base 授权页 URL，供微信内 H5 跳转。 */
    public String buildOAuthAuthorizeUrl(String redirectUri, String state, long tenantId) {
        if (!StringUtils.hasText(redirectUri)) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "redirectUri cannot be blank");
        }
        OaCredentials credentials = resolveOaCredentials(tenantId);
        String normalizedRedirectUri = redirectUri.trim();
        String encodedRedirect = URLEncoder.encode(normalizedRedirectUri, StandardCharsets.UTF_8);
        String stateParam = StringUtils.hasText(state) ? state.trim() : "";
        String redirectUriHost = extractHost(normalizedRedirectUri);
        log.info(
                "build wechat oauth authorize url tenantId={} appId={} redirectUriHost={} redirectUri={} state={}",
                tenantId,
                credentials.appId(),
                redirectUriHost,
                normalizedRedirectUri,
                stateParam);
        return WECHAT_OAUTH_AUTHORIZE_URL
                + "?appid=" + credentials.appId()
                + "&redirect_uri=" + encodedRedirect
                + "&response_type=code"
                + "&scope=snsapi_base"
                + "&state=" + URLEncoder.encode(stateParam, StandardCharsets.UTF_8)
                + "#wechat_redirect";
    }

    private static String extractHost(String uriText) {
        try {
            URI uri = URI.create(uriText);
            return StringUtils.hasText(uri.getHost()) ? uri.getHost() : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    public String resolveOaAppId(long tenantId) {
        return resolveOaCredentials(tenantId).appId();
    }

    private OaCredentials resolveOaCredentials(long tenantId) {
        boolean nullCtx = RequestContext.get() == null;
        try {
            if (nullCtx) {
                RequestContext.setThreadTenantId(tenantId);
            }
            List<TenantConfig> tenantConfigList = tenantConfigService.getTenantConfigList();
            String oaAppId = "";
            String oaAppSecret = "";
            for (TenantConfig tenantConfig : tenantConfigList) {
                if ("oaAppId".equals(tenantConfig.getName()) && tenantConfig.getValue() != null) {
                    oaAppId = tenantConfig.getValue().toString();
                }
                if ("oaAppSecret".equals(tenantConfig.getName()) && tenantConfig.getValue() != null) {
                    oaAppSecret = tenantConfig.getValue().toString();
                }
            }
            if (!StringUtils.hasText(oaAppId) || !StringUtils.hasText(oaAppSecret)) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemWeChatOaNotConfigured);
            }
            return new OaCredentials(oaAppId.trim(), oaAppSecret.trim());
        } finally {
            if (nullCtx) {
                RequestContext.remove();
            }
        }
    }

    private record OaCredentials(String appId, String appSecret) {}

    private static String maskOpenId(String openId) {
        if (!StringUtils.hasText(openId) || openId.length() <= 8) {
            return "***";
        }
        return openId.substring(0, 4) + "***" + openId.substring(openId.length() - 4);
    }
}
