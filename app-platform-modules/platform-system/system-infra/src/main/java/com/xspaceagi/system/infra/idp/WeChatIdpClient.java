package com.xspaceagi.system.infra.idp;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.system.infra.dao.entity.AuthIdpConfig;
import com.xspaceagi.system.spec.enums.AuthIdpTypeEnum;
import com.xspaceagi.system.spec.enums.WeChatIdpModeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.HttpClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * 微信登录客户端：网站扫码（开放平台）与公众号网页授权两种形态。
 * 两者 code 换取身份的 sns 接口一致，仅授权入口端点与 scope 不同；
 * external_id 优先取 unionid（同一开放平台主体下扫码/公众号/小程序为同一人）。
 */
@Slf4j
@Component
public class WeChatIdpClient implements IdentityProviderClient {

    private static final String QR_AUTHORIZE_URL = "https://open.weixin.qq.com/connect/qrconnect";
    private static final String OA_AUTHORIZE_URL = "https://open.weixin.qq.com/connect/oauth2/authorize";
    private static final String SNS_TOKEN_URL = "https://api.weixin.qq.com/sns/oauth2/access_token";
    private static final String SNS_USERINFO_URL = "https://api.weixin.qq.com/sns/userinfo";

    @Resource
    private HttpClient httpClient;

    @Override
    public AuthIdpTypeEnum type() {
        return AuthIdpTypeEnum.WECHAT;
    }

    @Override
    public String buildAuthorizeUrl(AuthIdpConfig config, String redirectUri, String state, String codeVerifier) {
        WeChatIdpConfig wechat = IdpConfigParser.parseWeChat(config);
        boolean oa = WeChatIdpModeEnum.OA == WeChatIdpModeEnum.of(wechat.getMode());
        return (oa ? OA_AUTHORIZE_URL : QR_AUTHORIZE_URL)
                + "?appid=" + urlEnc(wechat.getAppId())
                + "&redirect_uri=" + urlEnc(redirectUri)
                + "&response_type=code"
                + "&scope=" + urlEnc(oa ? "snsapi_userinfo" : "snsapi_login")
                + "&state=" + urlEnc(state)
                + "#wechat_redirect";
    }

    @Override
    public ExternalIdentity exchange(AuthIdpConfig config, IdpExchangeContext ctx) {
        WeChatIdpConfig wechat = IdpConfigParser.parseWeChat(config);
        String tokenUrl = SNS_TOKEN_URL
                + "?appid=" + urlEnc(wechat.getAppId())
                + "&secret=" + urlEnc(wechat.getAppSecret())
                + "&code=" + urlEnc(ctx.getCredential())
                + "&grant_type=authorization_code";
        JSONObject tokenJson = mustJson(httpClient.get(tokenUrl), "微信授权 code 换取 token 响应异常");
        checkError(tokenJson);
        String accessToken = tokenJson.getString("access_token");
        String openid = tokenJson.getString("openid");
        String unionid = tokenJson.getString("unionid");
        if (StringUtils.isBlank(accessToken) || StringUtils.isBlank(openid)) {
            throw new BizException("0001", "微信授权未返回 access_token/openid");
        }
        log.info("微信登录换取身份成功 openid={}, unionid={}", mask(openid), mask(unionid));

        ExternalIdentity identity = new ExternalIdentity();
        identity.setExternalId(StringUtils.defaultIfBlank(unionid, openid));
        identity.setUnionId(unionid);
        // openid 是随机标识、不是人类可用的登录名，不进 userName

        // 拉取昵称/头像；失败不阻断登录（资料为非关键信息）
        try {
            String userInfoUrl = SNS_USERINFO_URL
                    + "?access_token=" + urlEnc(accessToken)
                    + "&openid=" + urlEnc(openid)
                    + "&lang=zh_CN";
            String userResponse = httpClient.get(userInfoUrl);
            if (JSON.isValid(userResponse)) {
                JSONObject userJson = JSONObject.parseObject(userResponse);
                if (userJson.getInteger("errcode") == null || userJson.getInteger("errcode") == 0) {
                    identity.setNickName(userJson.getString("nickname"));
                    identity.setAvatar(userJson.getString("headimgurl"));
                } else {
                    log.warn("微信拉取用户资料失败 errcode={} errmsg={}",
                            userJson.getInteger("errcode"), userJson.getString("errmsg"));
                }
            }
        } catch (Exception e) {
            log.warn("微信拉取用户资料异常: {}", e.getMessage());
        }
        return identity;
    }

    private static void checkError(JSONObject json) {
        Integer errcode = json.getInteger("errcode");
        if (errcode != null && errcode != 0) {
            log.warn("微信接口返回错误 errcode={} errmsg={}", errcode, json.getString("errmsg"));
            throw new BizException("0001", "微信接口返回错误: " + StringUtils.defaultIfBlank(json.getString("errmsg"), String.valueOf(errcode)));
        }
    }

    private static JSONObject mustJson(String body, String message) {
        if (!JSON.isValid(body)) {
            log.warn("微信接口响应非 JSON: {}", StringUtils.abbreviate(body, 200));
            throw new BizException("0001", message);
        }
        return JSONObject.parseObject(body);
    }

    private static String mask(String value) {
        if (StringUtils.isBlank(value)) {
            return "";
        }
        if (value.length() <= 5) {
            return value.charAt(0) + "****";
        }
        return value.substring(0, 3) + "****" + value.substring(value.length() - 2);
    }

    private static String urlEnc(String value) {
        return URLEncoder.encode(StringUtils.defaultString(value), StandardCharsets.UTF_8);
    }
}
