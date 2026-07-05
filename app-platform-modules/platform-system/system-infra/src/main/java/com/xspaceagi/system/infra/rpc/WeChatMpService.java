package com.xspaceagi.system.infra.rpc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.system.infra.dao.entity.TenantConfig;
import com.xspaceagi.system.infra.dao.service.TenantConfigService;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.HttpClient;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class WeChatMpService {

    private static final String WECHAT_MP_URL = "https://api.weixin.qq.com/cgi-bin/";
    private static final String WECHAT_JSCODE2SESSION_URL = "https://api.weixin.qq.com/sns/jscode2session";
    private static final String WECHAT_MP_APP_ID_KEY = "mpAppId";
    private static final String WECHAT_MP_APP_SECRET_KEY = "mpAppSecret";

    @Resource
    private TenantConfigService tenantConfigService;

    @Resource
    private HttpClient httpClient;

    @Resource
    private RedisUtil redisUtil;

    /**
     * 小程序登录码 换取 openId
     */
    public String getOpenIdByLoginCode(String jsCode) {
        if (!StringUtils.hasText(jsCode)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemWeChatOpenIdFetchFailed);
        }
        MpCredentials credentials = resolveMpCredentials();
        String url = WECHAT_JSCODE2SESSION_URL
                + "?appid=" + credentials.appId()
                + "&secret=" + credentials.appSecret()
                + "&js_code=" + URLEncoder.encode(jsCode.trim(), StandardCharsets.UTF_8)
                + "&grant_type=authorization_code";
        String response = httpClient.get(url);
        if (!JSON.isValid(response)) {
            log.warn("微信 jscode2session 响应非 JSON");
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.systemWeChatOpenIdFetchFailed);
        }
        JSONObject jsonObject = JSONObject.parseObject(response);
        if (jsonObject.containsKey("errcode") && jsonObject.getInteger("errcode") != 0) {
            log.warn("微信 jscode2session 失败 errcode={} errmsg={}", jsonObject.getInteger("errcode"), jsonObject.getString("errmsg"));
            throw BizException.of(
                    ErrorCodeEnum.ERROR_REQUEST,
                    BizExceptionCodeEnum.systemWeChatApiReturnedError,
                    jsonObject.getString("errmsg"));
        }
        String openId = jsonObject.getString("openid");
        if (!StringUtils.hasText(openId)) {
            log.warn("微信 jscode2session 未返回 openid");
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.systemWeChatOpenIdFetchFailed);
        }
        log.info("微信 jscode2session 成功 openid={}", maskOpenId(openId));
        return openId;
    }

    public String getAccessToken() {
        MpCredentials credentials = resolveMpCredentials();
        String mpAppId = credentials.appId();
        String mpAppSecret = credentials.appSecret();
        Object accessToken = redisUtil.get("mp.accessToken");
        if (accessToken != null) {
            return accessToken.toString();
        }
        String url = WECHAT_MP_URL + "token?grant_type=client_credential&appid=" + mpAppId + "&secret=" + mpAppSecret;
        String response = httpClient.get(url);
        log.info("微信 access_token 响应: {}", response);
        JSONObject jsonObject = JSONObject.parseObject(response);
        if (jsonObject.containsKey("access_token")) {
            redisUtil.set("mp.accessToken", jsonObject.getString("access_token"), jsonObject.getLong("expires_in") - 10);
            return jsonObject.getString("access_token");
        }
        throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.systemWeChatAccessTokenFetchFailed);
    }

    public String getPhoneNumber(String code) {
        return getPhoneNumber(code, true);
    }

    private String getPhoneNumber(String code, boolean tryAgainWhenExpired) {
        String url = "https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=" + getAccessToken();
        String response = httpClient.post(url, "{\"code\":\"" + code + "\"}", null);
        log.info("微信手机号响应: {}", response);
        if (JSON.isValid(response)) {
            JSONObject jsonObject = JSONObject.parseObject(response);
            if (jsonObject.containsKey("errcode") && jsonObject.getInteger("errcode") != 0) {
                redisUtil.expire("mp.accessToken", 0);

                if (jsonObject.getInteger("errcode") == 40001) {
                    if (tryAgainWhenExpired) {
                        return getPhoneNumber(code, false);
                    }
                }

                throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.systemWeChatApiReturnedError,
                        jsonObject.getString("errmsg"));
            }
            if (jsonObject.containsKey("phone_info")) {
                JSONObject phoneInfo = jsonObject.getJSONObject("phone_info");
                if (phoneInfo.containsKey("purePhoneNumber")) {
                    return phoneInfo.getString("purePhoneNumber");
                }
            }
        }
        throw BizException.of(ErrorCodeEnum.ERROR_REQUEST, BizExceptionCodeEnum.systemWeChatPhoneNumberFetchFailed);
    }

    private MpCredentials resolveMpCredentials() {
        List<TenantConfig> tenantConfigList = tenantConfigService.getTenantConfigList();
        String mpAppId = "";
        String mpAppSecret = "";
        for (TenantConfig tenantConfig : tenantConfigList) {
            if (WECHAT_MP_APP_ID_KEY.equals(tenantConfig.getName())) {
                mpAppId = tenantConfig.getValue().toString();
            }
            if (WECHAT_MP_APP_SECRET_KEY.equals(tenantConfig.getName())) {
                mpAppSecret = tenantConfig.getValue().toString();
            }
        }
        if (!StringUtils.hasText(mpAppId) || !StringUtils.hasText(mpAppSecret)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemWeChatMpNotConfigured);
        }
        return new MpCredentials(mpAppId, mpAppSecret);
    }

    private record MpCredentials(String appId, String appSecret) {}

    private static String maskOpenId(String openId) {
        if (!StringUtils.hasText(openId) || openId.length() <= 8) {
            return "***";
        }
        return openId.substring(0, 4) + "***" + openId.substring(openId.length() - 4);
    }
}
