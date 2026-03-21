package com.xspaceagi.im.web.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.StreamUtils;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 钉钉 OpenAPI 客户端，用于获取 AccessToken、发送/更新互动卡片。
 * 统一使用高级版接口：发送 POST /v1.0/im/interactiveCards/send，更新 PUT /v1.0/im/interactiveCards。
 */
@Slf4j
public class DingtalkOpenApiClient {

    private static final String API_BASE = "https://api.dingtalk.com";
    private static final String OAPI_BASE = "https://oapi.dingtalk.com";

    private final String clientId;
    private final String clientSecret;
    /** 机器人编码，在钉钉开放平台【消息推送】获取。不填则用 clientId。 */
    private final String robotCode;
    private String cachedAccessToken;
    private long tokenExpireAt;
    /** 最后一次失败原因，便于 Controller 层记录 */
    private volatile String lastError;

    public DingtalkOpenApiClient(String clientId, String clientSecret) {
        this(clientId, clientSecret, null);
    }

    public DingtalkOpenApiClient(String clientId, String clientSecret, String robotCode) {
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.robotCode = StringUtils.isNotBlank(robotCode) ? robotCode : clientId;
    }

    private synchronized String getAccessToken() {
        if (StringUtils.isNotBlank(cachedAccessToken) && System.currentTimeMillis() < tokenExpireAt) {
            return cachedAccessToken;
        }
        try {
            String body = JSON.toJSONString(Map.of("appKey", clientId, "appSecret", clientSecret));
            PostResult result = postWithStatus(API_BASE + "/v1.0/oauth2/accessToken", body, null);
            JSONObject resp = result.body;
            if (resp != null) {
                String token = resp.getString("accessToken");
                if (token == null) token = resp.getString("access_token");
                if (StringUtils.isNotBlank(token)) {
                    cachedAccessToken = token;
                    int expiresIn = resp.getIntValue("expire");
                    if (expiresIn <= 0) expiresIn = resp.getIntValue("expires_in");
                    if (expiresIn <= 0) expiresIn = 7200;
                    tokenExpireAt = System.currentTimeMillis() + (expiresIn - 300) * 1000L;
                    return cachedAccessToken;
                }
                // 获取 token 失败，记录钉钉返回的错误
                String errCode = resp.getString("code");
                String errMsg = resp.getString("message");
                if (errMsg == null) errMsg = resp.getString("msg");
                log.error("钉钉获取 AccessToken 失败: httpCode={}, code={}, message={}, 完整响应={}", result.httpCode, errCode, errMsg, resp);
            } else {
                log.error("钉钉获取 AccessToken 失败: 响应为空, httpCode={}", result.httpCode);
            }
        } catch (Exception e) {
            log.error("钉钉获取 AccessToken 异常: clientId={}", clientId, e);
        }
        return null;
    }

    /**
     * 发送互动卡片（高级版 /v1.0/im/interactiveCards/send）
     *
     * @param conversationType "1" 单聊，"2" 群聊
     * @param conversationId   会话 ID，群聊时作为 openConversationId
     * @param senderStaffId    单聊时的接收者 userId
     * @param outTrackId       卡片唯一标识，用于后续更新时定位同一张卡片
     * @param cardData         卡片数据 JSON 字符串（StandardCard 格式）
     * @param robotCodeOverride 群聊时使用的 robotCode，可为空则用配置的。优先用 webhook 回调中的 robotCode。
     */
    public boolean sendInteractiveCard(String conversationType, String conversationId,
                                       String senderStaffId, String outTrackId, String cardData, String robotCodeOverride) {
        String token = getAccessToken();
        if (StringUtils.isBlank(token)) {
            lastError = "AccessToken 为空";
            log.error("钉钉发送互动卡片失败: AccessToken 为空，请检查 clientId/clientSecret 或钉钉应用配置");
            return false;
        }

        try {
            // 高级版发送：/v1.0/im/interactiveCards/send
            Map<String, Object> body = new HashMap<>();
            body.put("cardTemplateId", "StandardCard");
            body.put("outTrackId", outTrackId);
            // 高级版 cardData：cardParamMap.sys_full_json_obj 包裹完整 StandardCard JSON
            Map<String, Object> cardDataObj = new HashMap<>();
            Map<String, String> cardParamMap = new HashMap<>();
            cardParamMap.put("sys_full_json_obj", StringUtils.isNotBlank(cardData) ? cardData : "{}");
            cardDataObj.put("cardParamMap", cardParamMap);
            body.put("cardData", cardDataObj);
            // Webhook: "1"=单聊 "2"=群聊；API: 0=单聊 1=群聊
            int apiConvType = "1".equals(conversationType) ? 0 : 1;
            body.put("conversationType", apiConvType);
            // 单聊不填 robotCode；群聊需填 robotCode（优先用 webhook 回调中的，其次配置的）
            if (apiConvType == 1) {
                String rc = StringUtils.isNotBlank(robotCodeOverride) ? robotCodeOverride : robotCode;
                body.put("robotCode", rc);
            }

            if (apiConvType == 1 && StringUtils.isNotBlank(conversationId)) {
                body.put("openConversationId", conversationId);
            } else {
                // 单聊：receiverUserIdList 填写接收者 userId 列表
                String userId = StringUtils.isNotBlank(senderStaffId) ? senderStaffId : conversationId;
                if (StringUtils.isNotBlank(userId)) {
                    body.put("receiverUserIdList", Collections.singletonList(userId));
                }
            }

            String jsonBody = JSON.toJSONString(body);
            PostResult postResult = postWithStatus(API_BASE + "/v1.0/im/interactiveCards/send", jsonBody, token);
            JSONObject resp = postResult.body;
            int httpCode = postResult.httpCode;

            // 成功：HTTP 2xx 且响应包含 processQueryKey（可能在 result 下）
            boolean hasSuccess = false;
            if (resp != null) {
                hasSuccess = resp.containsKey("processQueryKey");
                if (!hasSuccess) {
                    JSONObject result = resp.getJSONObject("result");
                    hasSuccess = result != null && result.containsKey("processQueryKey");
                }
            }
            if (httpCode >= 200 && httpCode < 300 && hasSuccess) {
                return true;
            }

            String errCode = resp != null ? resp.getString("errorCode") : null;
            if (errCode == null && resp != null) errCode = resp.getString("code");
            if (errCode == null && resp != null && resp.get("errcode") != null) errCode = String.valueOf(resp.get("errcode"));
            String errMsg = resp != null ? resp.getString("errorMessage") : null;
            if (errMsg == null && resp != null) errMsg = resp.getString("message");
            if (errMsg == null && resp != null) errMsg = resp.getString("errmsg");
            lastError = String.format("httpCode=%d, errorCode=%s, errorMessage=%s, resp=%s", httpCode, errCode, errMsg, resp);
            log.error("钉钉发送互动卡片失败: {}", lastError);
        } catch (Exception e) {
            lastError = "exception: " + e.getMessage();
            log.error("钉钉发送互动卡片异常: conversationType={}, conversationId={}", conversationType, conversationId, e);
        }
        return false;
    }

    /** 获取最后一次失败原因，用于 Controller 层记录 */
    public String getLastError() {
        return lastError;
    }

    /**
     * 更新互动卡片（高级版 PUT /v1.0/im/interactiveCards，实现打字机/流式效果）
     *
     * @param outTrackId 与发送时一致，用于标识要更新的卡片
     * @param cardData   卡片数据 JSON 字符串（StandardCard 格式）
     */
    public boolean updateInteractiveCard(String outTrackId, String cardData) {
        String token = getAccessToken();
        if (StringUtils.isBlank(token)) return false;

        try {
            // 高级版更新：outTrackId + cardData.cardParamMap.sys_full_json_obj
            Map<String, Object> body = new HashMap<>();
            body.put("outTrackId", outTrackId);
            Map<String, Object> cardDataObj = new HashMap<>();
            Map<String, String> cardParamMap = new HashMap<>();
            cardParamMap.put("sys_full_json_obj", StringUtils.isNotBlank(cardData) ? cardData : "{}");
            cardDataObj.put("cardParamMap", cardParamMap);
            body.put("cardData", cardDataObj);

            String jsonBody = JSON.toJSONString(body);
            PutResult putResult = putWithStatus(API_BASE + "/v1.0/im/interactiveCards", jsonBody, token);
            int code = putResult.httpCode;
            if (code >= 200 && code < 300) {
                return true;
            }
            String errCode = putResult.body != null ? putResult.body.getString("code") : null;
            if (errCode == null && putResult.body != null) errCode = putResult.body.getString("errorCode");
            String errMsg = putResult.body != null ? putResult.body.getString("message") : null;
            if (errMsg == null && putResult.body != null) errMsg = putResult.body.getString("errorMessage");
            log.error("钉钉更新互动卡片失败: httpCode={}, errorCode={}, errorMessage={}, resp={}",
                    code, errCode, errMsg, putResult.body);
        } catch (Exception e) {
            log.error("钉钉更新互动卡片异常: outTrackId={}", outTrackId, e);
        }
        return false;
    }

    /**
     * 查询群名称
     * <p>
     * 当前优先尝试 v1.0 场景群接口（如不可用/无权限会返回错误，调用方需回退）。
     * 所需的权限：[qyapi_chat_read]，点击链接开通：https://open-dev.dingtalk.com/appscope/apply?content=dinguopbejtkgmzi1egt%23qyapi_chat_read
     */
    public String queryGroupName(String openConversationId) {
        if (StringUtils.isBlank(openConversationId)) {
            return null;
        }
        String token = getAccessToken();
        if (StringUtils.isBlank(token)) {
            return null;
        }
        try {
            // 使用旧版 topapi 查询群信息（文档体系稳定，且与你们现有 accessToken 获取方式一致）
            // 注意：旧版接口使用 access_token query 参数
            String url = OAPI_BASE + "/topapi/im/chat/scenegroup/get?access_token=" + token;
            String body = JSON.toJSONString(Map.of("open_conversation_id", openConversationId));
            PostResult topapi = postWithStatus(url, body, null);
            JSONObject resp = topapi != null ? topapi.body : null;
            if (resp == null) {
                return null;
            }
            // 旧版通常返回：{ "errcode":0, "errmsg":"ok", "result": { "title": "..."} }
            if (resp.getIntValue("errcode") != 0) {
                return null;
            }
            JSONObject result = resp.getJSONObject("result");
            String name = extractGroupName(result);
            return StringUtils.isNotBlank(name) ? name : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String extractGroupName(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        String name = obj.getString("name");
        if (StringUtils.isBlank(name)) {
            name = obj.getString("title");
        }
        if (StringUtils.isBlank(name)) {
            JSONObject r = obj.getJSONObject("result");
            if (r != null) {
                name = r.getString("name");
                if (StringUtils.isBlank(name)) {
                    name = r.getString("title");
                }
            }
        }
        return StringUtils.isNotBlank(name) ? name : null;
    }

    private static class PostResult {
        final int httpCode;
        final JSONObject body;

        PostResult(int httpCode, JSONObject body) {
            this.httpCode = httpCode;
            this.body = body;
        }
    }

    private PostResult postWithStatus(String url, String body, String accessToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        if (StringUtils.isNotBlank(accessToken)) {
            conn.setRequestProperty("x-acs-dingtalk-access-token", accessToken);
        }
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String respStr = readResponse(conn, code);
        return new PostResult(code, JSON.parseObject(respStr != null ? respStr : "{}"));
    }

    private static class PutResult {
        final int httpCode;
        final JSONObject body;

        PutResult(int httpCode, JSONObject body) {
            this.httpCode = httpCode;
            this.body = body;
        }
    }

    private PutResult putWithStatus(String url, String body, String accessToken) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) URI.create(url).toURL().openConnection();
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
        conn.setRequestProperty("x-acs-dingtalk-access-token", accessToken);
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(15000);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        String respStr = readResponse(conn, code);
        return new PutResult(code, JSON.parseObject(respStr != null ? respStr : "{}"));
    }

    private String readResponse(HttpURLConnection conn, int code) throws Exception {
        var is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        return is != null ? new String(StreamUtils.copyToByteArray(is), StandardCharsets.UTF_8) : "{}";
    }

    /**
     * 下载机器人接收消息中的文件
     *
     * @param downloadCode     消息中的 downloadCode / pictureDownloadCode / fileDownloadCode
     * @param robotCodeOverride 机器人编码，为空则用配置的
     * @return 文件字节数组，失败返回 null
     */
    public byte[] downloadMessageFile(String downloadCode, String robotCodeOverride) {
        String token = getAccessToken();
        if (StringUtils.isBlank(token) || StringUtils.isBlank(downloadCode)) {
            return null;
        }
        try {
            String rc = StringUtils.isNotBlank(robotCodeOverride) ? robotCodeOverride : robotCode;
            String body = JSON.toJSONString(Map.of("downloadCode", downloadCode, "robotCode", rc));
            PostResult result = postWithStatus(API_BASE + "/v1.0/robot/messageFiles/download", body, token);
            if (result.httpCode < 200 || result.httpCode >= 300 || result.body == null) {
                log.warn("钉钉获取文件下载链接失败: httpCode={}, robotCode={}, downloadCodePrefix={}, body={}. " +
                        "排查建议: 1) 在钉钉开放平台【消息推送】获取 robotCode 并配置; 2) 群聊不支持文件下载，请在单聊中测试",
                        result.httpCode, rc, downloadCode != null && downloadCode.length() > 8 ? downloadCode.substring(0, 8) + "..." : downloadCode, result.body);
                return null;
            }
            String downloadUrl = result.body.getString("downloadUrl");
            if (StringUtils.isBlank(downloadUrl)) {
                log.warn("钉钉 downloadUrl 为空: body={}", result.body);
                return null;
            }
            HttpURLConnection conn = (HttpURLConnection) new URL(downloadUrl).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(60000);
            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                try (InputStream is = conn.getInputStream()) {
                    return StreamUtils.copyToByteArray(is);
                }
            }
            log.warn("钉钉下载文件失败: url={}, httpCode={}", downloadUrl, code);
        } catch (Exception e) {
            log.warn("钉钉下载文件异常: downloadCode={}", downloadCode, e);
        }
        return null;
    }

    /**
     * 构建 StandardCard 格式的 cardData JSON，用于 sys_full_json_obj。
     * 无头部，仅展示内容。支持流式更新：通过 updateInteractiveCard 全量替换 contents 实现打字机效果。
     */
    public static String buildCardData(String content) {
        if (content == null) content = "";
        return "{\"config\":{\"autoLayout\":true,\"enableForward\":true},"
                + "\"contents\":[{\"type\":\"markdown\",\"text\":\"" + escapeJson(content) + "\",\"id\":\"streaming_content\"}]}";
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
