package com.xspaceagi.im.application.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.im.application.ImNotifyOauthCallbackApplicationService;
import com.xspaceagi.im.application.ImUserBindingApplicationService;
import com.xspaceagi.im.application.dto.ImChannelConfigDto;
import com.xspaceagi.im.application.dto.ImNotifyChannel;
import com.xspaceagi.im.application.dto.ImNotifyOauthCallbackResult;
import com.xspaceagi.im.application.support.ImBindSessionKeys;
import com.xspaceagi.im.application.support.ImNotifyChannelConfigResolver;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Slf4j
@Service
public class ImNotifyOauthCallbackApplicationServiceImpl implements ImNotifyOauthCallbackApplicationService {

    @Resource
    private RedisUtil redisUtil;
    @Resource
    private ImUserBindingApplicationService imUserBindingApplicationService;
    @Resource
    private ImNotifyChannelConfigResolver channelConfigResolver;

    @Override
    public ImNotifyOauthCallbackResult handleCallback(String channelRaw, String code, String state, String error) {
        if (StringUtils.isNotBlank(error)) {
            return ImNotifyOauthCallbackResult.builder().html(html("授权失败", error)).build();
        }
        if (StringUtils.isBlank(code) || StringUtils.isBlank(state)) {
            return ImNotifyOauthCallbackResult.builder().html(html("授权失败", "缺少 code 或 state")).build();
        }
        String channel;
        try {
            channel = ImNotifyChannel.normalize(channelRaw);
        } catch (BizException ex) {
            return ImNotifyOauthCallbackResult.builder().html(html("授权失败", "不支持的渠道")).build();
        }

        String raw = imUserBindingApplicationService.getBindSessionRaw(state);
        if (raw == null) {
            return ImNotifyOauthCallbackResult.builder()
                    .html(html("授权失败", "绑定会话已过期，请返回应用重新发起绑定"))
                    .build();
        }
        JSONObject session = JSON.parseObject(raw);
        if (!channel.equals(session.getString("channel"))) {
            return ImNotifyOauthCallbackResult.builder().html(html("授权失败", "渠道不匹配")).build();
        }
        if ("bound".equals(session.getString("status"))) {
            return ImNotifyOauthCallbackResult.builder()
                    .html(html("绑定已完成", "可以关闭此页面，返回应用继续操作"))
                    .build();
        }
        if ("authorized".equals(session.getString("status"))) {
            return ImNotifyOauthCallbackResult.builder()
                    .html(html("确认成功", "请返回 App 完成绑定。若未自动跳转，请手动切回应用。"))
                    .build();
        }
        if ("awaiting_consent".equals(session.getString("status"))) {
            // 确认页 URL 即 OAuth callback：刷新时勿再换码，直接重出确认页
            return resumeConsentPage(session, state, channel);
        }
        Long channelConfigId = session.getLong("channelConfigId");
        Long tenantId = session.getLong("tenantId");
        Long sessionUserId = session.getLong("userId");
        if (tenantId != null) {
            RequestContext.setThreadTenantId(tenantId);
        }
        try {
            ImChannelConfigDto config = channelConfigResolver.resolve(channel, channelConfigId, sessionUserId);
            String[] identity = exchangeCode(channel, config, code);
            String consentToken = imUserBindingApplicationService.stashOauthPendingConsent(
                    state, identity[0], identity[1]);
            String platformLabel = StringUtils.defaultIfBlank(session.getString("platformUserLabel"), "当前平台账号");
            String imLabel = StringUtils.defaultIfBlank(identity[1], identity[0]);
            return ImNotifyOauthCallbackResult.builder()
                    .html(consentHtml(state, consentToken, platformLabel, imLabel, channel))
                    .build();
        } catch (BizException ex) {
            log.warn("IM OAuth callback business error channel={} state={}", channel, state, ex);
            ImNotifyOauthCallbackResult resumed = tryResumeAfterCallbackError(state, channel);
            if (resumed != null) {
                return resumed;
            }
            markFailed(state, ex.getMessage());
            return ImNotifyOauthCallbackResult.builder()
                    .html(html("授权失败", "授权未完成，请返回应用重试"))
                    .build();
        } catch (Exception e) {
            log.error("IM OAuth callback error channel={} state={}", channel, state, e);
            ImNotifyOauthCallbackResult resumed = tryResumeAfterCallbackError(state, channel);
            if (resumed != null) {
                return resumed;
            }
            markFailed(state, e.getMessage());
            return ImNotifyOauthCallbackResult.builder()
                    .html(html("授权失败", "系统繁忙，请返回应用重试"))
                    .build();
        }
    }

    /** 换码失败时若会话已进入待确认/已确认，继续展示可用页面而非失败文案。 */
    private ImNotifyOauthCallbackResult tryResumeAfterCallbackError(String bindId, String channel) {
        String raw = imUserBindingApplicationService.getBindSessionRaw(bindId);
        if (raw == null) {
            return null;
        }
        JSONObject session = JSON.parseObject(raw);
        String status = session.getString("status");
        if ("awaiting_consent".equals(status)) {
            return resumeConsentPage(session, bindId, channel);
        }
        if ("authorized".equals(status)) {
            return ImNotifyOauthCallbackResult.builder()
                    .html(html("确认成功", "请返回 App 完成绑定。若未自动跳转，请手动切回应用。"))
                    .build();
        }
        if ("bound".equals(status)) {
            return ImNotifyOauthCallbackResult.builder()
                    .html(html("绑定已完成", "可以关闭此页面，返回应用继续操作"))
                    .build();
        }
        return null;
    }

    private static ImNotifyOauthCallbackResult resumeConsentPage(JSONObject session, String bindId, String channel) {
        String consentToken = session.getString("consentToken");
        if (StringUtils.isBlank(consentToken)) {
            return ImNotifyOauthCallbackResult.builder()
                    .html(html("授权失败", "确认凭证缺失，请返回应用重新绑定"))
                    .build();
        }
        String platformLabel = StringUtils.defaultIfBlank(session.getString("platformUserLabel"), "当前平台账号");
        String imLabel = StringUtils.defaultIfBlank(session.getString("displayName"), session.getString("imUserId"));
        return ImNotifyOauthCallbackResult.builder()
                .html(consentHtml(bindId, consentToken, platformLabel, imLabel, channel))
                .build();
    }

    @Override
    public ImNotifyOauthCallbackResult handleConsent(String bindId, String consentToken) {
        if (StringUtils.isBlank(bindId) || StringUtils.isBlank(consentToken)) {
            return ImNotifyOauthCallbackResult.builder()
                    .html(html("确认失败", "缺少确认参数，请返回应用重新绑定"))
                    .build();
        }
        try {
            boolean newlyAuthorized = imUserBindingApplicationService.acceptOauthConsent(bindId, consentToken);
            if (!newlyAuthorized) {
                // 已同意过：不带 confirmToken 重定向，避免任意 consentToken 套出凭据
                return ImNotifyOauthCallbackResult.builder()
                        .html(html("确认成功", "绑定确认已完成，请返回 App 继续操作。"))
                        .build();
            }
            String appTarget = imUserBindingApplicationService.buildAppReturnRedirect(bindId);
            if (StringUtils.isBlank(appTarget)) {
                return ImNotifyOauthCallbackResult.builder()
                        .html(html("确认成功", "请返回 App 完成绑定。若未自动跳转，请手动切回应用。"))
                        .build();
            }
            if (isHttpOrHttps(appTarget)) {
                return ImNotifyOauthCallbackResult.builder().redirectUrl(appTarget).build();
            }
            return ImNotifyOauthCallbackResult.builder().html(appOpenBridgeHtml(appTarget)).build();
        } catch (BizException ex) {
            log.warn("IM OAuth consent business error bindId={}", bindId, ex);
            return ImNotifyOauthCallbackResult.builder()
                    .html(html("确认失败", "确认无效或已过期，请返回应用重新绑定"))
                    .build();
        } catch (Exception e) {
            log.error("IM OAuth consent error bindId={}", bindId, e);
            return ImNotifyOauthCallbackResult.builder()
                    .html(html("确认失败", "系统繁忙，请返回应用重试"))
                    .build();
        }
    }

    private void markFailed(String bindId, String message) {
        Object raw = redisUtil.get(ImBindSessionKeys.key(bindId));
        if (raw == null) {
            return;
        }
        JSONObject session = JSON.parseObject(String.valueOf(raw));
        String status = session.getString("status");
        // 勿覆盖已换码待确认 / 已授权 / 已绑定，避免回调 URL 刷新或废 code 二次回调把可用会话打挂
        if ("awaiting_consent".equals(status) || "authorized".equals(status) || "bound".equals(status)) {
            log.info("IM OAuth markFailed ignored bindId={} status={}", bindId, status);
            return;
        }
        session.put("status", "failed");
        session.put("message", message);
        redisUtil.set(ImBindSessionKeys.key(bindId), session.toJSONString(), ImBindSessionKeys.TTL_SECONDS);
    }

    /** @return [imUserId, displayName] */
    private String[] exchangeCode(String channel, ImChannelConfigDto config, String code) throws Exception {
        if (ImChannelEnum.WEWORK.getCode().equals(channel)) {
            return exchangeWework(config, code);
        }
        if (ImChannelEnum.FEISHU.getCode().equals(channel)) {
            return exchangeFeishu(config, code);
        }
        if (ImChannelEnum.DINGTALK.getCode().equals(channel)) {
            return exchangeDingtalk(config, code);
        }
        throw BizException.of(BizExceptionCodeEnum.imNotifyChannelUnsupported, channel);
    }

    private String[] exchangeWework(ImChannelConfigDto config, String code) throws Exception {
        ImChannelConfigDto.WeworkAppConfig app = config.getWeworkApp();
        if (app == null) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyChannelNotConfigured);
        }
        String tokenUrl = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=" + app.getCorpId()
                + "&corpsecret=" + app.getCorpSecret();
        JSONObject tokenResp = getJson(tokenUrl);
        if (tokenResp == null || tokenResp.getIntValue("errcode") != 0) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyOauthFailed,
                    tokenResp == null ? "token failed" : tokenResp.getString("errmsg"));
        }
        String accessToken = tokenResp.getString("access_token");
        JSONObject userResp = getJson("https://qyapi.weixin.qq.com/cgi-bin/auth/getuserinfo?access_token="
                + accessToken + "&code=" + code);
        if (userResp == null) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyOauthFailed, "empty user info");
        }
        String userId = StringUtils.firstNonBlank(userResp.getString("userid"), userResp.getString("UserId"));
        if (StringUtils.isBlank(userId)) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyOauthFailed,
                    StringUtils.defaultIfBlank(userResp.getString("errmsg"), "userid missing"));
        }
        return new String[]{userId, userId};
    }

    private String[] exchangeFeishu(ImChannelConfigDto config, String code) throws Exception {
        ImChannelConfigDto.FeishuConfig feishu = config.getFeishu();
        if (feishu == null) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyChannelNotConfigured);
        }
        JSONObject appTokenBody = new JSONObject();
        appTokenBody.put("app_id", feishu.getAppId());
        appTokenBody.put("app_secret", feishu.getAppSecret());
        JSONObject appTokenResp = postJson("https://open.feishu.cn/open-apis/auth/v3/app_access_token/internal",
                appTokenBody.toJSONString());
        if (appTokenResp == null || appTokenResp.getIntValue("code") != 0) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyOauthFailed,
                    appTokenResp == null ? "app_access_token failed" : appTokenResp.getString("msg"));
        }
        String appAccessToken = appTokenResp.getString("app_access_token");
        JSONObject userTokenBody = new JSONObject();
        userTokenBody.put("grant_type", "authorization_code");
        userTokenBody.put("code", code);
        JSONObject userTokenResp = postJson("https://open.feishu.cn/open-apis/authen/v1/oidc/access_token",
                userTokenBody.toJSONString(), Map.of("Authorization", "Bearer " + appAccessToken));
        if (userTokenResp == null || userTokenResp.getIntValue("code") != 0) {
            userTokenResp = postJson("https://open.feishu.cn/open-apis/authen/v1/access_token",
                    userTokenBody.toJSONString(), Map.of("Authorization", "Bearer " + appAccessToken));
        }
        if (userTokenResp == null || userTokenResp.getIntValue("code") != 0) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyOauthFailed,
                    userTokenResp == null ? "user_access_token failed" : userTokenResp.getString("msg"));
        }
        JSONObject data = userTokenResp.getJSONObject("data");
        if (data == null) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyOauthFailed, "empty token data");
        }
        String openId = data.getString("open_id");
        String name = StringUtils.defaultIfBlank(data.getString("name"), openId);
        if (StringUtils.isBlank(openId)) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyOauthFailed, "open_id missing");
        }
        return new String[]{openId, name};
    }

    private String[] exchangeDingtalk(ImChannelConfigDto config, String code) throws Exception {
        ImChannelConfigDto.DingtalkConfig ding = config.getDingtalk();
        if (ding == null) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyChannelNotConfigured);
        }
        JSONObject body = new JSONObject();
        body.put("clientId", ding.getClientId());
        body.put("clientSecret", ding.getClientSecret());
        body.put("code", code);
        body.put("grantType", "authorization_code");
        JSONObject resp = postJson("https://api.dingtalk.com/v1.0/oauth2/userAccessToken", body.toJSONString());
        if (resp == null || StringUtils.isBlank(resp.getString("accessToken"))) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyOauthFailed,
                    resp == null ? "userAccessToken failed" : StringUtils.defaultIfBlank(resp.getString("message"), resp.toJSONString()));
        }
        String userAccessToken = resp.getString("accessToken");
        JSONObject userResp = getJson("https://api.dingtalk.com/v1.0/contact/users/me",
                Map.of("x-acs-dingtalk-access-token", userAccessToken));
        if (userResp == null) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyOauthFailed, "user info empty");
        }
        String name = StringUtils.defaultIfBlank(userResp.getString("nick"), null);
        String userId = userResp.getString("userId");
        String unionId = userResp.getString("unionId");
        if (StringUtils.isBlank(userId) && StringUtils.isNotBlank(unionId)) {
            userId = resolveDingtalkUserIdByUnionId(ding, unionId);
        }
        if (StringUtils.isBlank(userId)) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyOauthFailed,
                    "dingtalk userid missing (need corp userid for robot push)");
        }
        if (StringUtils.isBlank(name)) {
            name = userId;
        }
        return new String[]{userId, name};
    }

    private String resolveDingtalkUserIdByUnionId(ImChannelConfigDto.DingtalkConfig ding, String unionId) throws Exception {
        String token = getDingtalkAppAccessToken(ding.getClientId(), ding.getClientSecret());
        if (StringUtils.isBlank(token)) {
            return null;
        }
        JSONObject body = new JSONObject();
        body.put("unionid", unionId);
        JSONObject resp = postJson("https://oapi.dingtalk.com/topapi/user/getbyunionid?access_token=" + token,
                body.toJSONString());
        if (resp == null || resp.getIntValue("errcode") != 0) {
            log.warn("DingTalk getbyunionid failed unionId={} resp={}", unionId, resp);
            return null;
        }
        JSONObject result = resp.getJSONObject("result");
        return result != null ? result.getString("userid") : null;
    }

    private String getDingtalkAppAccessToken(String clientId, String clientSecret) throws Exception {
        JSONObject body = new JSONObject();
        body.put("appKey", clientId);
        body.put("appSecret", clientSecret);
        JSONObject resp = postJson("https://api.dingtalk.com/v1.0/oauth2/accessToken", body.toJSONString());
        return resp != null ? resp.getString("accessToken") : null;
    }

    private JSONObject getJson(String url) throws Exception {
        return getJson(url, null);
    }

    private JSONObject getJson(String url, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        if (headers != null) {
            headers.forEach(conn::setRequestProperty);
        }
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        return read(conn);
    }

    private JSONObject postJson(String url, String body) throws Exception {
        return postJson(url, body, null);
    }

    private JSONObject postJson(String url, String body, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        if (headers != null) {
            headers.forEach(conn::setRequestProperty);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return read(conn);
    }

    private JSONObject read(HttpURLConnection conn) throws Exception {
        InputStream stream;
        try {
            stream = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        } catch (Exception e) {
            stream = conn.getErrorStream();
        }
        if (stream == null) {
            return null;
        }
        String resp = new String(StreamUtils.copyToByteArray(stream), StandardCharsets.UTF_8);
        return JSON.parseObject(resp);
    }

    private static boolean isHttpOrHttps(String url) {
        if (url == null) {
            return false;
        }
        String lower = url.toLowerCase();
        return lower.startsWith("https://") || lower.startsWith("http://");
    }

    /**
     * 自定义 scheme 深链中间页：脚本跳转 + 可点击链接（适配企微等内置浏览器）。
     * 仅当配置显式允许非 http(s) 且校验通过后才会走到这里；默认回跳为 https Universal Link。
     */
    private static String appOpenBridgeHtml(String appUrl) {
        String safeHref = escape(appUrl);
        String safeJs = escapeJs(appUrl);
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
                + "<title>正在返回 App</title>"
                + "<script>setTimeout(function(){window.location.href=\"" + safeJs + "\";},50);</script>"
                + "</head><body style=\"font-family:sans-serif;padding:40px;max-width:480px;margin:auto;line-height:1.6\">"
                + "<h2>确认成功</h2>"
                + "<p>正在返回 App 以完成绑定…</p>"
                + "<p><a href=\"" + safeHref + "\">若未自动跳转，请点击这里打开 App</a></p>"
                + "</body></html>";
    }

    private static String consentHtml(String bindId, String consentToken, String platformLabel,
                                      String imLabel, String channel) {
        String action = "/api/v1/im-notify/oauth/consent?bindId=" + urlEncode(bindId)
                + "&consentToken=" + urlEncode(consentToken);
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/>"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
                + "<title>确认绑定</title></head>"
                + "<body style=\"font-family:sans-serif;padding:40px;max-width:480px;margin:auto;line-height:1.6\">"
                + "<h2>确认绑定</h2>"
                + "<p>即将把您的 <b>" + escape(channelLabel(channel)) + "</b> 账号"
                + "（" + escape(imLabel) + "）绑定到平台账号：</p>"
                + "<p style=\"font-size:1.15em;font-weight:600\">" + escape(platformLabel) + "</p>"
                + "<p style=\"color:#666\">若不是您本人发起的绑定，请关闭此页，勿点击确认。</p>"
                + "<p style=\"margin-top:28px\"><a href=\"" + escape(action) + "\""
                + " style=\"display:inline-block;padding:12px 20px;background:#1677ff;color:#fff;"
                + "text-decoration:none;border-radius:6px\">确认绑定到上述账号</a></p>"
                + "</body></html>";
    }

    private static String channelLabel(String channel) {
        if (ImChannelEnum.FEISHU.getCode().equals(channel)) {
            return "飞书";
        }
        if (ImChannelEnum.WEWORK.getCode().equals(channel)) {
            return "企业微信";
        }
        if (ImChannelEnum.DINGTALK.getCode().equals(channel)) {
            return "钉钉";
        }
        return channel == null ? "IM" : channel;
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static String escapeJs(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("'", "\\'")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("<", "\\u003c");
    }

    private static String html(String title, String message) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"/><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"/>"
                + "<title>" + escape(title) + "</title></head>"
                + "<body style=\"font-family:sans-serif;padding:40px;max-width:480px;margin:auto;line-height:1.6\">"
                + "<h2>" + escape(title) + "</h2><p>" + escape(message) + "</p></body></html>";
    }

    private static String escape(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
