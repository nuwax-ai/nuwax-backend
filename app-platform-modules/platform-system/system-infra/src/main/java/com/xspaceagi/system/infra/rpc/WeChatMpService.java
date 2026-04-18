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

import java.util.List;

@Slf4j
@Service
public class WeChatMpService {

    private static final String WECHAT_MP_URL = "https://api.weixin.qq.com/cgi-bin/";
    private static final String WECHAT_MP_APP_ID_KEY = "mpAppId";
    private static final String WECHAT_MP_APP_SECRET_KEY = "mpAppSecret";

    @Resource
    private TenantConfigService tenantConfigService;

    @Resource
    private HttpClient httpClient;

    @Resource
    private RedisUtil redisUtil;

    public String getAccessToken() {
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
                if (jsonObject.getInteger("errcode") == 40001) {
                    redisUtil.expire("mp.accessToken", -1);
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
}
