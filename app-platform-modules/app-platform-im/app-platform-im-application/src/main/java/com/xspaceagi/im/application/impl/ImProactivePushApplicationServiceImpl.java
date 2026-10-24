package com.xspaceagi.im.application.impl;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.im.application.ImProactivePushApplicationService;
import com.xspaceagi.im.application.ImUserBindingApplicationService;
import com.xspaceagi.im.application.dto.ImChannelConfigDto;
import com.xspaceagi.im.application.dto.ImNotifyChannel;
import com.xspaceagi.im.application.support.ImNotifyChannelConfigResolver;
import com.xspaceagi.im.infra.dao.enitity.ImUserBinding;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ImProactivePushApplicationServiceImpl implements ImProactivePushApplicationService {

    @Resource
    private ImUserBindingApplicationService imUserBindingApplicationService;
    @Resource
    private ImNotifyChannelConfigResolver channelConfigResolver;

    @Override
    public void send(String channelRaw, Long userId, String title, String content, Long channelConfigId) {
        String channel = ImNotifyChannel.normalize(channelRaw);
        if (userId == null || userId <= 0) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "userId");
        }
        if (StringUtils.isBlank(content) && StringUtils.isBlank(title)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "content");
        }
        Long tenantId = RequestContext.get().getTenantId();
        if (tenantId == null) {
            throw BizException.of(BizExceptionCodeEnum.imTenantOrUserMissing);
        }

        ImUserBinding binding = imUserBindingApplicationService.requireBinding(tenantId, userId, channel);
        Long configId = channelConfigId != null ? channelConfigId : binding.getChannelConfigId();
        ImChannelConfigDto config = channelConfigResolver.resolve(channel, configId, userId);

        String text = buildText(title, content);
        if (ImChannelEnum.WEWORK.getCode().equals(channel)) {
            sendWework(config, binding.getImUserId(), text);
            return;
        }
        if (ImChannelEnum.FEISHU.getCode().equals(channel)) {
            sendFeishu(config, binding.getImUserId(), text);
            return;
        }
        if (ImChannelEnum.DINGTALK.getCode().equals(channel)) {
            sendDingtalk(config, binding.getImUserId(), text);
            return;
        }
        throw BizException.of(BizExceptionCodeEnum.imNotifyChannelUnsupported, channel);
    }

    private static String buildText(String title, String content) {
        String t = title == null ? "" : title.trim();
        String c = content == null ? "" : content.trim();
        if (t.isEmpty()) {
            return c;
        }
        if (c.isEmpty()) {
            return t;
        }
        return t + "\n" + c;
    }

    private void sendWework(ImChannelConfigDto config, String toUser, String content) {
        ImChannelConfigDto.WeworkAppConfig app = config.getWeworkApp();
        if (app == null || StringUtils.isBlank(app.getCorpId()) || StringUtils.isBlank(app.getCorpSecret())
                || StringUtils.isBlank(app.getAgentId())) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyChannelNotConfigured);
        }
        try {
            String accessToken = getWeworkAccessToken(app.getCorpId(), app.getCorpSecret());
            String url = "https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=" + accessToken;
            JSONObject payload = new JSONObject();
            payload.put("touser", toUser);
            payload.put("msgtype", "text");
            payload.put("agentid", Integer.parseInt(app.getAgentId()));
            JSONObject text = new JSONObject();
            text.put("content", content);
            payload.put("text", text);
            JSONObject resp = postJson(url, payload.toJSONString());
            if (resp == null || resp.getIntValue("errcode") != 0) {
                throw BizException.of(BizExceptionCodeEnum.imNotifySendFailed,
                        resp == null ? "empty response" : resp.getString("errmsg"));
            }
            log.info("WeWork proactive push OK toUser={} configId={}", toUser, config.getId());
        } catch (BizException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("WeWork proactive push failed toUser={}", toUser, e);
            throw BizException.of(BizExceptionCodeEnum.imNotifySendFailed,
                    e.getMessage() != null ? e.getMessage() : "unknown");
        }
    }

    private void sendFeishu(ImChannelConfigDto config, String openId, String content) {
        ImChannelConfigDto.FeishuConfig feishu = config.getFeishu();
        if (feishu == null || StringUtils.isBlank(feishu.getAppId()) || StringUtils.isBlank(feishu.getAppSecret())) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyChannelNotConfigured);
        }
        try {
            String token = getFeishuTenantAccessToken(feishu.getAppId(), feishu.getAppSecret());
            String url = "https://open.feishu.cn/open-apis/im/v1/messages?receive_id_type=open_id";
            JSONObject body = new JSONObject();
            body.put("receive_id", openId);
            body.put("msg_type", "text");
            body.put("content", JSON.toJSONString(Map.of("text", content)));
            JSONObject resp = postJson(url, body.toJSONString(), Map.of("Authorization", "Bearer " + token));
            if (resp == null || resp.getIntValue("code") != 0) {
                throw BizException.of(BizExceptionCodeEnum.imNotifySendFailed,
                        resp == null ? "empty response" : resp.getString("msg"));
            }
            log.info("Feishu proactive push OK openId={} configId={}", openId, config.getId());
        } catch (BizException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("Feishu proactive push failed openId={}", openId, e);
            throw BizException.of(BizExceptionCodeEnum.imNotifySendFailed,
                    e.getMessage() != null ? e.getMessage() : "unknown");
        }
    }

    private void sendDingtalk(ImChannelConfigDto config, String userId, String content) {
        ImChannelConfigDto.DingtalkConfig ding = config.getDingtalk();
        if (ding == null || StringUtils.isBlank(ding.getClientId()) || StringUtils.isBlank(ding.getClientSecret())
                || StringUtils.isBlank(ding.getRobotCode())) {
            throw BizException.of(BizExceptionCodeEnum.imNotifyChannelNotConfigured);
        }
        try {
            String token = getDingtalkAccessToken(ding.getClientId(), ding.getClientSecret());
            String url = "https://api.dingtalk.com/v1.0/robot/oToMessages/batchSend";
            Map<String, Object> body = new HashMap<>();
            body.put("robotCode", ding.getRobotCode());
            body.put("userIds", List.of(userId));
            body.put("msgKey", "sampleText");
            body.put("msgParam", JSON.toJSONString(Map.of("content", content)));
            JSONObject resp = postJson(url, JSON.toJSONString(body), Map.of("x-acs-dingtalk-access-token", token));
            // 钉钉成功必须返回非空 processQueryKey
            if (resp == null || StringUtils.isBlank(resp.getString("processQueryKey"))) {
                throw BizException.of(BizExceptionCodeEnum.imNotifySendFailed,
                        resp == null ? "empty response" : StringUtils.defaultIfBlank(resp.getString("message"), resp.toJSONString()));
            }
            log.info("DingTalk proactive push OK userId={} configId={}", userId, config.getId());
        } catch (BizException ex) {
            throw ex;
        } catch (Exception e) {
            log.error("DingTalk proactive push failed userId={}", userId, e);
            throw BizException.of(BizExceptionCodeEnum.imNotifySendFailed,
                    e.getMessage() != null ? e.getMessage() : "unknown");
        }
    }

    private String getWeworkAccessToken(String corpId, String corpSecret) throws Exception {
        String url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=" + corpId + "&corpsecret=" + corpSecret;
        JSONObject resp = getJson(url);
        if (resp == null || resp.getIntValue("errcode") != 0) {
            throw BizException.of(BizExceptionCodeEnum.imNotifySendFailed,
                    resp == null ? "access_token empty" : resp.getString("errmsg"));
        }
        return resp.getString("access_token");
    }

    private String getFeishuTenantAccessToken(String appId, String appSecret) throws Exception {
        JSONObject body = new JSONObject();
        body.put("app_id", appId);
        body.put("app_secret", appSecret);
        JSONObject resp = postJson("https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal", body.toJSONString());
        if (resp == null || resp.getIntValue("code") != 0) {
            throw BizException.of(BizExceptionCodeEnum.imNotifySendFailed,
                    resp == null ? "tenant_access_token empty" : resp.getString("msg"));
        }
        return resp.getString("tenant_access_token");
    }

    private String getDingtalkAccessToken(String clientId, String clientSecret) throws Exception {
        JSONObject body = new JSONObject();
        body.put("appKey", clientId);
        body.put("appSecret", clientSecret);
        JSONObject resp = postJson("https://api.dingtalk.com/v1.0/oauth2/accessToken", body.toJSONString());
        if (resp == null || StringUtils.isBlank(resp.getString("accessToken"))) {
            throw BizException.of(BizExceptionCodeEnum.imNotifySendFailed,
                    resp == null ? "accessToken empty" : StringUtils.defaultIfBlank(resp.getString("message"), resp.toJSONString()));
        }
        return resp.getString("accessToken");
    }

    private JSONObject getJson(String url) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        return readResponse(conn);
    }

    private JSONObject postJson(String url, String body) throws Exception {
        return postJson(url, body, null);
    }

    private JSONObject postJson(String url, String body, Map<String, String> headers) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                conn.setRequestProperty(e.getKey(), e.getValue());
            }
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(15000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return readResponse(conn);
    }

    private JSONObject readResponse(HttpURLConnection conn) throws Exception {
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
}
