package com.xspaceagi.im.wechat.ilink;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.im.wechat.ilink.dto.BaseInfo;
import com.xspaceagi.im.wechat.ilink.dto.GetConfigBody;
import com.xspaceagi.im.wechat.ilink.dto.GetConfigResp;
import com.xspaceagi.im.wechat.ilink.dto.GetUpdatesEnvelope;
import com.xspaceagi.im.wechat.ilink.dto.GetUpdatesResp;
import com.xspaceagi.im.wechat.ilink.dto.GetUploadUrlReq;
import com.xspaceagi.im.wechat.ilink.dto.GetUploadUrlResp;
import com.xspaceagi.im.wechat.ilink.dto.QrCodeStartResponse;
import com.xspaceagi.im.wechat.ilink.dto.QrCodeStatusResponse;
import com.xspaceagi.im.wechat.ilink.dto.SendMessageEnvelope;
import com.xspaceagi.im.wechat.ilink.dto.SendTypingBody;
import com.xspaceagi.im.wechat.ilink.dto.WeixinMessage;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import javax.net.ssl.SSLException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;

/**
 * iLink 网关 HTTP 客户端（对齐 openclaw-weixin src/api/api.ts）
 */
public class IlinkHttpClient {

    private static final Logger log = LoggerFactory.getLogger(IlinkHttpClient.class);

    private static final String CHANNEL_VERSION = "1.0.3";

    private final HttpClient httpClient;
    private final String skRouteTag;

    public IlinkHttpClient() {
        this(null);
    }

    public IlinkHttpClient(String skRouteTag) {
        this.skRouteTag = skRouteTag;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    public BaseInfo buildBaseInfo() {
        return BaseInfo.builder().channelVersion(CHANNEL_VERSION).build();
    }

    /**
     * X-WECHAT-UIN：随机 uint32 的十进制字符串 UTF-8 字节再 Base64（与 Node 插件一致）
     */
    public static String randomWechatUinHeader() {
        SecureRandom r = new SecureRandom();
        byte[] b = new byte[4];
        r.nextBytes(b);
        int u = ByteBuffer.wrap(b).order(ByteOrder.BIG_ENDIAN).getInt();
        long unsigned = u & 0xffffffffL;
        return Base64.getEncoder().encodeToString(String.valueOf(unsigned).getBytes(StandardCharsets.UTF_8));
    }

    public GetUpdatesResp getUpdates(String baseUrl, String botToken, String getUpdatesBuf, int timeoutMs) throws IOException, InterruptedException {
        GetUpdatesEnvelope env = GetUpdatesEnvelope.builder()
                .getUpdatesBuf(StringUtils.defaultString(getUpdatesBuf))
                .baseInfo(buildBaseInfo())
                .build();
        String body = JSON.toJSONString(env);
        String raw = postJson(baseUrl, "ilink/bot/getupdates", body, botToken, timeoutMs);
        return JSON.parseObject(raw, GetUpdatesResp.class);
    }

    /**
     * 客户端超时、瞬时网络/TLS 错误视为空包（与插件 AbortError 行为一致），下一轮轮询重试。
     */
    public GetUpdatesResp getUpdatesLenient(String baseUrl, String botToken, String getUpdatesBuf, int timeoutMs) {
        return getUpdatesLenient(baseUrl, botToken, getUpdatesBuf, timeoutMs, null);
    }

    /**
     * @param ilinkAccountId 可选，用于日志区分渠道（规范化后的 iLink 账号 id）
     */
    public GetUpdatesResp getUpdatesLenient(String baseUrl, String botToken, String getUpdatesBuf, int timeoutMs,
                                            String ilinkAccountId) {
        try {
            return getUpdates(baseUrl, botToken, getUpdatesBuf, timeoutMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return emptyGetUpdates(getUpdatesBuf);
        } catch (Exception e) {
            if (isTimeout(e)) {
                log.debug("getUpdates timeout after {}ms, ilinkAccountId={}, retry buf", timeoutMs,
                        StringUtils.isNotBlank(ilinkAccountId) ? ilinkAccountId : "-");
                return emptyGetUpdates(getUpdatesBuf);
            }
            if (isTransientNetworkOrSslError(e)) {
                log.warn("getUpdates transient error (will retry next poll), ilinkAccountId={}, err={}",
                        StringUtils.isNotBlank(ilinkAccountId) ? ilinkAccountId : "-", e.getMessage());
                return emptyGetUpdates(getUpdatesBuf);
            }
            throw new RuntimeException(e.getMessage(), e);
        }
    }

    private static boolean isTimeout(Throwable e) {
        Throwable c = e;
        while (c != null) {
            if (c instanceof java.net.http.HttpTimeoutException) {
                return true;
            }
            String m = c.getMessage();
            if (m != null && (m.contains("timed out") || m.contains("Timeout"))) {
                return true;
            }
            c = c.getCause();
        }
        return false;
    }

    /**
     * 连接在收到响应头前被关闭、TLS 握手异常、对端返回明文等，长轮询侧按空包处理并继续。
     */
    private static boolean isTransientNetworkOrSslError(Throwable e) {
        Throwable c = e;
        while (c != null) {
            if (c instanceof SSLException) {
                return true;
            }
            String m = c.getMessage();
            if (m != null) {
                if (m.contains("header parser received no bytes")) {
                    return true;
                }
                if (m.contains("Unrecognized record version") || m.contains("plaintext connection")) {
                    return true;
                }
                if (m.contains("Connection reset") || m.contains("Broken pipe")) {
                    return true;
                }
                if (m.contains("unexpected end of stream") || m.contains("EOF")) {
                    return true;
                }
            }
            c = c.getCause();
        }
        return false;
    }

    private static GetUpdatesResp emptyGetUpdates(String buf) {
        GetUpdatesResp r = new GetUpdatesResp();
        r.setRet(0);
        r.setMsgs(java.util.Collections.emptyList());
        r.setGetUpdatesBuf(buf);
        return r;
    }

    public void sendMessage(String baseUrl, String botToken, WeixinMessage msg, int timeoutMs) throws IOException, InterruptedException {
        SendMessageEnvelope env = SendMessageEnvelope.builder()
                .msg(msg)
                .baseInfo(buildBaseInfo())
                .build();
        String body = JSON.toJSONString(env);
        String raw = postJson(baseUrl, "ilink/bot/sendmessage", body, botToken, timeoutMs);
        if (log.isDebugEnabled()) {
            String s = raw == null ? "" : WechatIlinkLogRedaction.redactJsonBodyForLog(raw);
            log.debug("ilink sendmessage response: {}", s.length() > 800 ? s.substring(0, 800) + "..." : s);
        }
    }

    public GetUploadUrlResp getUploadUrl(String baseUrl, String botToken, GetUploadUrlReq req, int timeoutMs) throws IOException, InterruptedException {
        JSONObject o = (JSONObject) JSON.toJSON(req);
        o.put("base_info", JSON.parseObject(JSON.toJSONString(buildBaseInfo())));
        String body = o.toJSONString();
        String raw = postJson(baseUrl, "ilink/bot/getuploadurl", body, botToken, timeoutMs);
        return JSON.parseObject(raw, GetUploadUrlResp.class);
    }

    public GetConfigResp getConfig(String baseUrl, String botToken, String ilinkUserId, String contextToken, int timeoutMs) throws IOException, InterruptedException {
        GetConfigBody body = GetConfigBody.builder()
                .ilinkUserId(ilinkUserId)
                .contextToken(contextToken)
                .baseInfo(buildBaseInfo())
                .build();
        String raw = postJson(baseUrl, "ilink/bot/getconfig", JSON.toJSONString(body), botToken, timeoutMs);
        return JSON.parseObject(raw, GetConfigResp.class);
    }

    public void sendTyping(String baseUrl, String botToken, SendTypingBody body, int timeoutMs) throws IOException, InterruptedException {
        SendTypingBody b = SendTypingBody.builder()
                .ilinkUserId(body.getIlinkUserId())
                .typingTicket(body.getTypingTicket())
                .status(body.getStatus() != null ? body.getStatus() : 1)
                .baseInfo(buildBaseInfo())
                .build();
        postJson(baseUrl, "ilink/bot/sendtyping", JSON.toJSONString(b), botToken, timeoutMs);
    }

    public QrCodeStartResponse getBotQrCode(String apiBaseUrl, String botType) throws IOException, InterruptedException {
        String base = ensureTrailingSlash(apiBaseUrl);
        String url = base + "ilink/bot/get_bot_qrcode?bot_type=" + URLEncoder.encode(botType, StandardCharsets.UTF_8);
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (StringUtils.isNotBlank(skRouteTag)) {
            rb.header("SKRouteTag", skRouteTag);
        }
        HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("get_bot_qrcode HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return JSON.parseObject(resp.body(), QrCodeStartResponse.class);
    }

    public QrCodeStatusResponse getQrCodeStatus(String apiBaseUrl, String qrcode, int timeoutMs) throws IOException, InterruptedException {
        String base = ensureTrailingSlash(apiBaseUrl);
        String url = base + "ilink/bot/get_qrcode_status?qrcode=" + URLEncoder.encode(qrcode, StandardCharsets.UTF_8);
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(timeoutMs, 1)))
                .header("iLink-App-ClientVersion", "1")
                .GET();
        if (StringUtils.isNotBlank(skRouteTag)) {
            rb.header("SKRouteTag", skRouteTag);
        }
        HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IOException("get_qrcode_status HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return JSON.parseObject(resp.body(), QrCodeStatusResponse.class);
    }

    private String postJson(String baseUrl, String path, String jsonBody, String bearerToken, int timeoutMs) throws IOException, InterruptedException {
        String url = ensureTrailingSlash(baseUrl) + path;
        HttpRequest.Builder rb = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(Math.max(timeoutMs, 1)))
                .header("Content-Type", "application/json")
                .header("AuthorizationType", IlinkConstants.AUTH_TYPE_ILINK_BOT_TOKEN)
                .header("X-WECHAT-UIN", randomWechatUinHeader())
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
        if (StringUtils.isNotBlank(bearerToken)) {
            rb.header("Authorization", "Bearer " + bearerToken.trim());
        }
        if (StringUtils.isNotBlank(skRouteTag)) {
            rb.header("SKRouteTag", skRouteTag);
        }
        HttpResponse<String> resp = httpClient.send(rb.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String errBody = resp.body();
            String safe = WechatIlinkLogRedaction.redactJsonBodyForLog(errBody != null ? errBody : "");
            throw new IOException(path + " HTTP " + resp.statusCode() + ": " + safe);
        }
        return resp.body();
    }

    private static String ensureTrailingSlash(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return IlinkConstants.DEFAULT_BASE_URL + "/";
        }
        return baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }
}
