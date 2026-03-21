package com.xspaceagi.im.web.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.lark.oapi.Client;
import com.lark.oapi.core.request.EventReq;
import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.*;
import com.xspaceagi.agent.core.adapter.application.IComputerFileApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.application.FeishuAgentApplicationService;
import com.xspaceagi.im.application.ImChannelConfigApplicationService;
import com.xspaceagi.im.application.dto.ImChannelConfigDto;
import com.xspaceagi.im.infra.enums.ImOutputModeEnum;
import com.xspaceagi.im.web.dto.FeishuChatDto;
import com.xspaceagi.im.web.dto.FeishuChatListRespDto;
import com.xspaceagi.im.web.dto.FeishuReplyContent;
import com.xspaceagi.im.web.service.FeishuAttachmentService;
import com.xspaceagi.im.web.service.ImFileShareService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.RedisUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/im/feishu")
@Slf4j
@Tag(name = "飞书 IM 集成")
public class IMFeishuController {

    @Resource
    private RedisUtil redisUtil;
    @Resource
    private FeishuAttachmentService feishuAttachmentService;
    @Resource
    private FeishuAgentApplicationService feishuAgentApplicationService;
    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;
    @Resource
    private ImChannelConfigApplicationService imChannelConfigApplicationService;
    @Resource
    private ImFileShareService imFileShareService;
    @Resource
    private IComputerFileApplicationService computerFileApplicationService;

    /**
     * 飞书事件去重：event_id 缓存 TTL，7.5 小时内重试窗口
     * 飞书要求 1–3 秒内返回 HTTP 200，否则会按 15s、5m、1h、6h 重试。
     * 当前 handleMessageReceive 同步执行（下载附件、调用智能体、流式 patch），可能耗时 10–30 秒，导致飞书重试并重复推送。
     * <p>
     * 重要说明：
     * - 这里的 event_id 去重只对“明文请求体”生效（能直接从 body 解析 header.event_id / event.uuid）
     * - 对“加密请求体”（仅含 encrypt）在入口阶段拿不到 event_id，因此不能依赖该去重覆盖重试
     * - 加密场景的重试去重由 handleMessageReceive 中的 messageId 幂等兜底
     */
    /**
     * 飞书事件幂等 Redis Key 前缀
     * 用于在 Redis 中存储已处理的事件标识，防止重复处理
     * 格式: "feishu:event:{eventId}"
     */
    private static final String FEISHU_EVENT_DEDUP_PREFIX = "feishu:event:";
    /**
     * 飞书消息幂等 Redis Key 前缀（按 messageId 去重，拦截飞书重试）
     */
    private static final String FEISHU_MSG_DEDUP_PREFIX = "feishu:msg:";

    /**
     * 飞书事件幂等 Redis Key 过期时间（秒）
     * 用于控制幂等 Key 的有效期，8 小时后自动删除
     * 钉钉可能因响应超时等原因重试事件，需要较长的幂等窗口
     */
    private static final int FEISHU_EVENT_DEDUP_TTL_SECONDS = 8 * 3600;
    /**
     * 飞书消息幂等 TTL（秒）
     */
    private static final int FEISHU_MSG_DEDUP_TTL_SECONDS = 8 * 3600;

    /**
     * 飞书历史消息过滤：消息 create_time 超过此秒数则跳过
     * 避免处理用户打开会话时同步推送的聊天记录
     */
    private static final int FEISHU_MESSAGE_MAX_AGE_SECONDS = 60;

    /**
     * 流式消息补丁间隔（毫秒）
     * 流式输出时，每隔 200ms 发送一次内容
     */
    private static final long STREAM_PATCH_INTERVAL_MS = 200;

    /**
     * 流式消息补丁最小字符数
     * 单词边界，避免单词被截断
     */
    private static final int STREAM_PATCH_MIN_CHARS = 60;

    private static final String FEISHU_TENANT_TOKEN_CACHE_PREFIX = "feishu:tenant_token:";
    private static final int FEISHU_TENANT_TOKEN_CACHE_SECONDS = 60 * 60; // 1h 缓存，失败自动回退

    @PostMapping("/webhook")
    @Operation(summary = "飞书事件订阅 Webhook", hidden = true)
    public void webhookEvent(HttpServletRequest request, HttpServletResponse response) throws Throwable {
        byte[] bodyBytes = StreamUtils.copyToByteArray(request.getInputStream());
        String appIdFromEvent = extractAppIdFromBody(bodyBytes);

        // 如果从请求体中无法提取 appId，尝试从 URL 参数获取
        if (StringUtils.isBlank(appIdFromEvent)) {
            appIdFromEvent = request.getParameter("appId");
        }

        log.info("飞书 Webhook 请求体: appId={}, contentLength={}, body={}",
                appIdFromEvent, bodyBytes.length,
                bodyBytes.length > 200 ? new String(bodyBytes, 0, 200, StandardCharsets.UTF_8) + "..." : new String(bodyBytes, StandardCharsets.UTF_8));

        // 验证 appId 是否存在
        if (StringUtils.isBlank(appIdFromEvent)) {
            log.error("飞书 Webhook 缺少必要参数: appId，无法处理请求");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(JSON.toJSONString(Map.of(
                    "code", "0001",
                    "message", "飞书 Webhook 缺少必要参数: appId。请确保：1) 在 Webhook URL 中添加 appId 参数（如 /webhook?appId=cli_xxxxx），2) 或使用非加密模式"
            )).getBytes(StandardCharsets.UTF_8));
            return;
        }

        if (isUrlVerification(bodyBytes)) {
            String challenge = parseChallenge(bodyBytes);
            if (challenge != null) {
                log.info("手动处理飞书 URL 验证");
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType("application/json;charset=UTF-8");
                response.getOutputStream().write(JSON.toJSONString(Map.of("challenge", challenge)).getBytes(StandardCharsets.UTF_8));
                return;
            }
        }

        // 入口事件去重：仅对“可从 body 解析出 event_id 的明文请求”生效；
        // 加密请求体无法在此阶段获取 event_id，需依赖 handleMessageReceive 里的 messageId 去重。
        String eventId = extractEventIdFromBody(bodyBytes);
        if (StringUtils.isNotBlank(eventId)) {
            String dedupKey = FEISHU_EVENT_DEDUP_PREFIX + eventId;
            if (redisUtil.get(dedupKey) != null) {
                log.info("飞书事件去重跳过: eventId={}", eventId);
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType("application/json;charset=UTF-8");
                return;
            }
            redisUtil.set(dedupKey, "1", FEISHU_EVENT_DEDUP_TTL_SECONDS);
        }

        // 检查是否为加密请求，如果是则验证 encryptKey 是否已配置
        boolean isEncrypted = isEncryptedRequest(bodyBytes);
        if (isEncrypted) {
            FeishuBotConfig config = getBotConfig(appIdFromEvent);
            if (StringUtils.isBlank(config.getEncryptKey())) {
                log.error("飞书 Webhook 收到加密消息，但配置中缺少 encryptKey。appId={}", appIdFromEvent);
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                response.setContentType("application/json;charset=UTF-8");
                response.getOutputStream().write(JSON.toJSONString(Map.of(
                        "code", "0002",
                        "message", "飞书 Webhook 收到加密消息，但配置中缺少 encryptKey。请在飞书机器人配置中填写 EncryptKey"
                )).getBytes(StandardCharsets.UTF_8));
                return;
            }
        }

        EventReq eventReq = buildEventReq(request, bodyBytes);
        var eventResp = getEventDispatcher(appIdFromEvent).handle(eventReq);

        // 检查 EventResp 状态码，非 200 表示处理失败
        if (eventResp.getStatusCode() != HttpServletResponse.SC_OK) {
            log.error("飞书 Webhook 处理失败: appId={}, statusCode={}", appIdFromEvent, eventResp.getStatusCode());

            // 尝试从响应体中提取错误信息
            String errorMsg = "飞书 Webhook 处理失败";
            String detailMsg = "";

            if (eventResp.getBody() != null && eventResp.getBody().length > 0) {
                try {
                    String bodyStr = new String(eventResp.getBody(), StandardCharsets.UTF_8);
                    log.error("飞书 Webhook 错误响应: appId={}, body={}", appIdFromEvent,
                            bodyStr.length() > 500 ? bodyStr.substring(0, 500) + "..." : bodyStr);

                    // 根据响应体判断错误类型
                    if (isEncryptedRequest(bodyBytes)) {
                        // 加密请求失败，通常是 encryptKey 或 verificationToken 错误
                        if (bodyStr.contains("decrypt") || bodyStr.contains("cipher") || bodyStr.contains("aes")) {
                            errorMsg = "EncryptKey 配置错误";
                            detailMsg = "消息解密失败，请检查 EncryptKey 是否正确填写";
                        } else if (bodyStr.contains("signature") || bodyStr.contains("verify") || bodyStr.contains("token")) {
                            errorMsg = "VerificationToken 配置错误";
                            detailMsg = "签名验证失败，请检查 VerificationToken 是否正确填写";
                        } else {
                            errorMsg = "EncryptKey 或 VerificationToken 配置错误";
                            detailMsg = "加密消息处理失败，请检查 EncryptKey 和 VerificationToken 是否正确";
                        }
                    }
                } catch (Exception e) {
                    log.debug("解析飞书错误响应失败", e);
                }
            } else if (isEncryptedRequest(bodyBytes)) {
                // 加密请求但没有响应体，很可能是 encryptKey 错误导致解密失败
                errorMsg = "EncryptKey 配置错误";
                detailMsg = "消息解密失败，请检查 EncryptKey 是否正确填写（43字符的 base64 编码字符串）";
            }

            // 记录详细的错误日志
            log.error("飞书 Webhook 错误: {} - {}。appId={}", errorMsg, detailMsg, appIdFromEvent);
        }

        writeEventResp(response, eventResp);
    }

    /**
     * 从事件请求体中解析 event_id/uuid，用于去重。
     * v2.0：header.event_id；v1.0：event.uuid
     */
    private String extractEventIdFromBody(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            JSONObject obj = JSON.parseObject(new String(body, StandardCharsets.UTF_8));
            if (obj == null || obj.containsKey("encrypt")) return null;
            JSONObject header = obj.getJSONObject("header");
            if (header != null) {
                String id = header.getString("event_id");
                if (StringUtils.isNotBlank(id)) return id;
            }
            JSONObject event = obj.getJSONObject("event");
            if (event != null) {
                String uuid = event.getString("uuid");
                if (StringUtils.isNotBlank(uuid)) return uuid;
            }
        } catch (Exception e) {
            log.debug("解析飞书 event_id 失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 从事件请求体中解析 app_id，用于区分飞书机器人。
     * 支持格式：v2.0 的 header.app_id、v1.0 的 event.app_id。
     * 加密请求体无法解析，返回 null。
     */
    private String extractAppIdFromBody(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            JSONObject obj = JSON.parseObject(new String(body, StandardCharsets.UTF_8));
            if (obj == null) {
                return null;
            }
            if (obj.containsKey("encrypt")) {
                return null;
            }
            JSONObject header = obj.getJSONObject("header");
            if (header != null) {
                String id = header.getString("app_id");
                if (StringUtils.isNotBlank(id)) {
                    return id;
                }
            }
            JSONObject event = obj.getJSONObject("event");
            if (event != null) {
                String id = event.getString("app_id");
                if (StringUtils.isNotBlank(id)) {
                    return id;
                }
            }
        } catch (Exception e) {
            log.debug("解析飞书事件 app_id 失败: {}", e.getMessage());
        }
        return null;
    }

    private boolean isUrlVerification(byte[] body) {
        if (body == null || body.length == 0) {
            return false;
        }
        try {
            JSONObject obj = JSON.parseObject(new String(body, StandardCharsets.UTF_8));
            return "url_verification".equals(obj.getString("type"));
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 检查请求体是否为加密格式（包含 encrypt 字段）
     */
    private boolean isEncryptedRequest(byte[] body) {
        if (body == null || body.length == 0) {
            return false;
        }
        try {
            JSONObject obj = JSON.parseObject(new String(body, StandardCharsets.UTF_8));
            return obj != null && obj.containsKey("encrypt");
        } catch (Exception e) {
            return false;
        }
    }

    private String parseChallenge(byte[] body) {
        if (body == null || body.length == 0) return null;
        try {
            JSONObject obj = JSON.parseObject(new String(body, StandardCharsets.UTF_8));
            return obj.getString("challenge");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取机器人所在的群组列表。
     * 返回机器人已加入的群组，可通过 chat_id 调用发送消息接口向对应群组发消息。
     * 支持分页，首次请求不传 pageToken，后续用返回的 pageToken 获取下一页。
     *
     * @param pageToken 分页标记，首次不传
     * @param pageSize  每页数量，默认 20，最大 100
     */
    @GetMapping("/chat/list")
    @Operation(summary = "获取机器人所在的群组列表", hidden = true)
    public ReqResult<FeishuChatListRespDto> listChats(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String pageToken,
            @RequestParam(required = false, defaultValue = "20") Integer pageSize) {
        try {
            FeishuBotConfig config = getBotConfig(appId);
            Client client = Client.newBuilder(config.getAppId(), config.getAppSecret()).build();
            var reqBuilder = ListChatReq.newBuilder().pageSize(Math.min(pageSize, 100));
            if (pageToken != null && !pageToken.isEmpty()) {
                reqBuilder.pageToken(pageToken);
            }
            ListChatReq req = reqBuilder.build();
            ListChatResp resp = client.im().v1().chat().list(req);

            if (!resp.success()) {
                log.warn("获取飞书群列表失败: code={}, msg={}", resp.getCode(), resp.getMsg());
                return ReqResult.error(resp.getMsg());
            }
            ListChatRespBody body = resp.getData();
            List<FeishuChatDto> items = body.getItems() == null ? List.of() :
                    Arrays.stream(body.getItems())
                            .map(c -> FeishuChatDto.builder()
                                    .chatId(c.getChatId())
                                    .name(c.getName())
                                    .avatar(c.getAvatar())
                                    .description(c.getDescription())
                                    .ownerId(c.getOwnerId())
                                    .build())
                            .collect(Collectors.toList());
            FeishuChatListRespDto result = FeishuChatListRespDto.builder()
                    .items(items)
                    .pageToken(body.getPageToken())
                    .hasMore(body.getHasMore())
                    .build();
            return ReqResult.success(result);
        } catch (Exception e) {
            log.error("获取飞书群列表异常", e);
            return ReqResult.error(e.getMessage());
        }
    }

    private EventReq buildEventReq(HttpServletRequest request, byte[] body) throws IOException {
        Map<String, List<String>> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        if (headerNames != null) {
            while (headerNames.hasMoreElements()) {
                String name = headerNames.nextElement();
                headers.put(name, Collections.list(request.getHeaders(name)));
            }
        }
        EventReq eventReq = new EventReq();
        eventReq.setBody(body != null ? body : new byte[0]);
        eventReq.setHeaders(headers);
        eventReq.setHttpPath(request.getRequestURI());
        return eventReq;
    }

    private void writeEventResp(HttpServletResponse response, com.lark.oapi.core.response.EventResp eventResp) throws IOException {
        response.setStatus(eventResp.getStatusCode());
        if (eventResp.getHeaders() != null) {
            eventResp.getHeaders().forEach((name, values) -> {
                if (values != null) {
                    values.forEach(v -> response.addHeader(name, v));
                }
            });
        }
        if (eventResp.getBody() != null && eventResp.getBody().length > 0) {
            response.getOutputStream().write(eventResp.getBody());
        }
    }

    private EventDispatcher getEventDispatcher(String appId) {
        FeishuBotConfig config = getBotConfig(appId);
        String vt = config.getVerificationToken() != null ? config.getVerificationToken() : "";
        String ek = config.getEncryptKey() != null ? config.getEncryptKey() : "";

        // 通过闭包捕获 appId，避免使用 ThreadLocal
        String finalAppId = appId;

        return EventDispatcher.newBuilder(vt, ek)
                .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                    @Override
                    public void handle(P2MessageReceiveV1 event) throws Exception {
                        handleMessageReceive(event, finalAppId);
                    }
                })
                .build();
    }

    private void handleMessageReceive(P2MessageReceiveV1 event, String appId) {
        P2MessageReceiveV1Data data = event.getEvent();
        if (data == null) {
            return;
        }
        EventMessage message = data.getMessage();
        EventSender sender = data.getSender();
        if (message == null) {
            return;
        }
        // 过滤历史消息：用户打开会话时可能同步推送聊天记录，仅处理近期消息
        String createTimeStr = message.getCreateTime();
        if (StringUtils.isNotBlank(createTimeStr)) {
            try {
                long createTimeMs = Long.parseLong(createTimeStr);
                long ageSeconds = (System.currentTimeMillis() - createTimeMs) / 1000;
                if (ageSeconds > FEISHU_MESSAGE_MAX_AGE_SECONDS) {
                    log.info("飞书历史消息跳过: messageId={}, createTime={}, ageSeconds={}", message.getMessageId(), createTimeStr, ageSeconds);
                    return;
                }
            } catch (NumberFormatException e) {
                log.debug("解析消息 create_time 失败: {}", createTimeStr);
            }
        }
        String messageId = message.getMessageId();
        String chatId = message.getChatId();
        String content = message.getContent();
        String chatType = message.getChatType();

        // 幂等：飞书会对同一 message 重试推送（尤其是处理耗时较长时）
        // 优先用 messageId 去重；兜底使用 chatId + createTime
        String dedupKey = StringUtils.isNotBlank(messageId)
                ? FEISHU_MSG_DEDUP_PREFIX + messageId
                : FEISHU_MSG_DEDUP_PREFIX + StringUtils.defaultString(chatId) + ":" + StringUtils.defaultString(message.getCreateTime());
        if (redisUtil != null && redisUtil.get(dedupKey) != null) {
            log.info("飞书消息去重跳过: messageId={}, chatId={}", messageId, chatId);
            return;
        }
        if (redisUtil != null) {
            redisUtil.set(dedupKey, "1", FEISHU_MSG_DEDUP_TTL_SECONDS);
        }

        FeishuBotConfig botConfig = getBotConfig(appId);

        log.info("处理飞书消息: appId={}, messageId={}, chatId={}, chatType={}, content={}",
                appId, messageId, chatId, chatType, content);

        String userMessage = parseTextContent(content);
        List<Object[]> attachmentKeys = parseAttachmentKeys(content);

        // 无文本且无附件时提示（若 content 非空但解析失败，记录便于排查）
        if (StringUtils.isBlank(userMessage) && attachmentKeys.isEmpty()) {
            if (StringUtils.isNotBlank(content)) {
                log.warn("飞书消息解析失败，未提取到文本或附件: messageId={}, content={}", messageId, content);
            }
            reply(messageId, buildCard("抱歉，暂不支持此消息格式的处理", false), botConfig);
            return;
        }
        if (StringUtils.isBlank(userMessage)) {
            userMessage = "[用户发送了附件]";
        }

        // 下载飞书附件并上传到项目存储，获取可访问的 URL
        List<AttachmentDto> attachments = new ArrayList<>();
        List<String> unsupportedKeys = new ArrayList<>();
        if (!attachmentKeys.isEmpty()) {
            List<String> fileKeys = new ArrayList<>();
            List<String> types = new ArrayList<>();
            for (Object[] pair : attachmentKeys) {
                fileKeys.add((String) pair[0]);
                types.add((String) pair[1]);
            }
            var tenantConfig = tenantConfigApplicationService.getTenantConfig(botConfig.getTenantId());
            var attachmentResult = feishuAttachmentService.downloadAndUpload(
                    botConfig.getAppId(), botConfig.getAppSecret(), messageId,
                    fileKeys, types, botConfig.getTenantId(), tenantConfig);
            attachments = attachmentResult.getAttachments();
            unsupportedKeys = attachmentResult.getUnsupportedKeys();
        }
        if (!unsupportedKeys.isEmpty()) {
            userMessage = userMessage + "\n\n[系统提示：你发送的附件类型不支持下载，请发送具体文件。]";
        }

        // 仅不支持的附件（无文本、无成功附件）时直接回复，避免调用智能体导致“正在思考...”无后续
        boolean onlyUnsupported = attachments.isEmpty() && !unsupportedKeys.isEmpty()
                && userMessage.startsWith("[用户发送了附件]");
        if (onlyUnsupported) {
            reply(messageId, buildCard("你发送的附件类型不支持下载，请发送具体文件。", false), botConfig);
            return;
        }

        // 会话 ID：群消息用群 chatId，私聊用用户 openId
        String sessionId;
        if ("p2p".equals(chatType)) {
            sessionId = extractOpenId(sender);
            if (StringUtils.isBlank(sessionId) && StringUtils.isNotBlank(chatId)) {
                sessionId = chatId;
            }
        } else {
            // 群消息（group 或 topic_group）：用群 chatId 创建会话 ID
            sessionId = StringUtils.isNotBlank(chatId) ? chatId : extractOpenId(sender);
        }
        if (StringUtils.isBlank(sessionId)) {
            sessionId = "unknown_" + messageId;
        }

        // topic 展示名：优先用群名/用户名（拿不到则置空，回退到 sessionKey）
        String sessionName = resolveSessionName(chatType, chatId, sender, botConfig);

        try {
            if (ImOutputModeEnum.ONCE == ImOutputModeEnum.fromCode(botConfig.getOutputMode())) {
                // 一次性输出：非流式、普通 Markdown（非互动卡片）
                FeishuAgentApplicationService.AgentExecuteResultWithConv result = feishuAgentApplicationService.executeAgentWithConv(sessionId, chatType, userMessage, attachments,
                        botConfig.getTenantId(), botConfig.getUserId(), botConfig.getAgentId(), sessionName);
                reply(messageId, buildMarkdown(result.getText(), result.getConversationId(), botConfig), botConfig);
                return;
            }

            // 流式输出：保持现有输出方式（互动卡片 + patch）
            String replyMsgId = replyAndGetMessageId(messageId, buildCard("正在思考...", true), botConfig);
            if (replyMsgId != null) {
                streamAndPatch(messageId, replyMsgId, sessionId, chatType, userMessage, attachments, botConfig, sessionName);
                return;
            }
            // 流式失败时回退到同步模式（仍走卡片，保持兼容）
            String replyText = feishuAgentApplicationService.executeAgent(sessionId, chatType, userMessage, attachments,
                    botConfig.getTenantId(), botConfig.getUserId(), botConfig.getAgentId());
            reply(messageId, buildCard(replyText, false), botConfig);
        } catch (Exception e) {
            String msg = e.getMessage();
            if (StringUtils.isNotBlank(msg) && msg.contains("Agent 正在执行任务")) {
                // busy 场景给用户明确提示；重复重试会被 messageId 幂等拦截
                reply(messageId, buildCard("Agent 正在执行任务，请等待当前任务完成后再发送新请求", false), botConfig);
                return;
            }
            log.error("处理飞书消息异常: messageId={}, chatId={}", messageId, chatId, e);
            reply(messageId, buildCard("执行异常，请稍后重试", false), botConfig);
        }
    }

    private FeishuBotConfig getBotConfig(String appId) {
        if (StringUtils.isBlank(appId)) {
            throw new BizException("飞书 Webhook 缺少必要参数: appId");
        }
        // 飞书回调无登录上下文，按 appId 查 ImChannelConfig 获取配置及租户ID
        ImChannelConfigDto cfg = imChannelConfigApplicationService.getFeishuConfigByAppId(appId);
        ImChannelConfigDto.FeishuConfig feishu = cfg != null ? cfg.getFeishu() : null;
        if (feishu == null || StringUtils.isBlank(feishu.getAppId())) {
            throw new BizException("飞书机器人未绑定");
        }
        FeishuBotConfig config = FeishuBotConfig.builder()
                .appId(feishu.getAppId())
                .appSecret(feishu.getAppSecret())
                .verificationToken(feishu.getVerificationToken())
                .encryptKey(feishu.getEncryptKey())
                .tenantId(cfg.getTenantId())
                .userId(cfg.getUserId())
                .agentId(cfg.getAgentId())
                .outputMode(cfg.getOutputMode())
                .build();
        return config;
    }

    /**
     * 从飞书消息 content 中解析文本。
     * 支持格式：{"text":"用户输入"}、post 富文本（zh_cn/en_us 或根级 content）、image 消息等。
     */
    private String parseTextContent(String content) {
        if (StringUtils.isBlank(content)) {
            return "";
        }
        try {
            JSONObject obj = JSON.parseObject(content);
            if (obj == null) return "";
            String text = obj.getString("text");
            if (StringUtils.isNotBlank(text)) return text;
            // post 富文本：zh_cn.content 或 en_us.content 中的文本
            JSONObject zhCn = obj.getJSONObject("zh_cn");
            if (zhCn != null) {
                text = extractTextFromPostContent(zhCn.get("content"));
                if (StringUtils.isNotBlank(text)) return text;
            }
            JSONObject enUs = obj.getJSONObject("en_us");
            if (enUs != null) {
                text = extractTextFromPostContent(enUs.get("content"));
                if (StringUtils.isNotBlank(text)) return text;
            }
            // post 富文本单语言：content 直接在根级（用户同时发文本+附件时常见）
            text = extractTextFromPostContent(obj.get("content"));
            if (StringUtils.isNotBlank(text)) return text;
            // post 包装结构：{"post": {"zh_cn": {...}} 或 {"post": {"content": [...]}}
            JSONObject post = obj.getJSONObject("post");
            if (post != null) {
                text = extractTextFromPostContent(post.getJSONObject("zh_cn") != null ? post.getJSONObject("zh_cn").get("content") : post.get("content"));
                if (StringUtils.isNotBlank(text)) return text;
            }
            // 日程消息：{"summary":"","start_time":"...","end_time":"...","open_calendar_id":"...","open_event_id":"..."}
            if (StringUtils.isNotBlank(obj.getString("open_event_id")) || StringUtils.isNotBlank(obj.getString("open_calendar_id"))) {
                return buildCalendarEventMessage(obj);
            }
            // 名片消息：{"user_id":"ou_xxx"}，按 null 处理，不查询用户信息
            if (StringUtils.isNotBlank(obj.getString("user_id"))) {
                return "";
            }
            return "";
        } catch (Exception e) {
            return content;
        }
    }

    private String buildCalendarEventMessage(JSONObject obj) {
        String summary = obj.getString("summary");
        String startTime = obj.getString("start_time");
        String endTime = obj.getString("end_time");
        StringBuilder sb = new StringBuilder("[用户发送了一个日程]");
        if (StringUtils.isNotBlank(summary)) {
            sb.append("：「").append(summary).append("」");
        }
        if (StringUtils.isNotBlank(startTime) || StringUtils.isNotBlank(endTime)) {
            sb.append("，");
            if (StringUtils.isNotBlank(startTime)) {
                sb.append("开始时间：").append(formatTimestamp(startTime));
            }
            if (StringUtils.isNotBlank(endTime)) {
                if (StringUtils.isNotBlank(startTime)) sb.append("，");
                sb.append("结束时间：").append(formatTimestamp(endTime));
            }
        }
        return sb.toString();
    }

    private String formatTimestamp(String ms) {
        if (StringUtils.isBlank(ms)) return "";
        try {
            long millis = Long.parseLong(ms);
            Instant instant = Instant.ofEpochMilli(millis);
            return LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        } catch (Exception e) {
            return ms;
        }
    }

    private String extractTextFromPostContent(Object content) {
        if (content == null) return "";
        if (content instanceof String) return (String) content;
        if (content instanceof com.alibaba.fastjson2.JSONArray) {
            com.alibaba.fastjson2.JSONArray arr = (com.alibaba.fastjson2.JSONArray) content;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                Object item = arr.get(i);
                if (item instanceof com.alibaba.fastjson2.JSONArray) {
                    com.alibaba.fastjson2.JSONArray inner = (com.alibaba.fastjson2.JSONArray) item;
                    for (int j = 0; j < inner.size(); j++) {
                        Object elem = inner.get(j);
                        if (elem instanceof JSONObject) {
                            String t = ((JSONObject) elem).getString("text");
                            if (StringUtils.isNotBlank(t)) sb.append(t);
                        }
                    }
                }
            }
            return sb.toString();
        }
        return "";
    }

    /**
     * 从飞书消息 content 中解析附件 key 列表（image_key、file_key）。
     * 返回 List<[fileKey, type]>，type 为 "image" 或 "file"。
     */
    private List<Object[]> parseAttachmentKeys(String content) {
        List<Object[]> result = new ArrayList<>();
        if (StringUtils.isBlank(content)) return result;
        try {
            JSONObject obj = JSON.parseObject(content);
            if (obj == null) return result;
            // image 消息：{"image_key":"xxx"}
            String imageKey = obj.getString("image_key");
            if (StringUtils.isNotBlank(imageKey)) {
                result.add(new Object[]{imageKey, "image"});
            }
            // file 消息：{"file_key":"xxx"}
            String fileKey = obj.getString("file_key");
            if (StringUtils.isNotBlank(fileKey)) {
                result.add(new Object[]{fileKey, "file"});
            }
            // post 富文本：zh_cn/en_us、根级 content 或 post 包装中的 img、media 标签
            collectKeysFromPost(obj.getJSONObject("zh_cn"), result);
            collectKeysFromPost(obj.getJSONObject("en_us"), result);
            collectKeysFromContent(obj.get("content"), result);
            JSONObject post = obj.getJSONObject("post");
            if (post != null) {
                collectKeysFromPost(post.getJSONObject("zh_cn"), result);
                collectKeysFromContent(post.get("content"), result);
            }
        } catch (Exception e) {
            log.debug("解析飞书附件 key 失败: {}", e.getMessage());
        }
        return result;
    }

    private void collectKeysFromContent(Object content, List<Object[]> result) {
        if (content == null) return;
        collectKeysFromPostContent(content, result);
    }

    private void collectKeysFromPost(JSONObject langObj, List<Object[]> result) {
        if (langObj == null) return;
        collectKeysFromPostContent(langObj.get("content"), result);
    }

    private void collectKeysFromPostContent(Object content, List<Object[]> result) {
        if (content == null) return;
        if (!(content instanceof com.alibaba.fastjson2.JSONArray)) return;
        com.alibaba.fastjson2.JSONArray arr = (com.alibaba.fastjson2.JSONArray) content;
        for (int i = 0; i < arr.size(); i++) {
            Object item = arr.get(i);
            if (!(item instanceof com.alibaba.fastjson2.JSONArray)) continue;
            com.alibaba.fastjson2.JSONArray inner = (com.alibaba.fastjson2.JSONArray) item;
            for (int j = 0; j < inner.size(); j++) {
                Object elem = inner.get(j);
                if (elem instanceof JSONObject) {
                    JSONObject jo = (JSONObject) elem;
                    String tag = jo.getString("tag");
                    if ("img".equals(tag)) {
                        String imageKey = jo.getString("image_key");
                        if (StringUtils.isNotBlank(imageKey)) result.add(new Object[]{imageKey, "image"});
                    } else if ("media".equals(tag)) {
                        String fk = jo.getString("file_key");
                        if (StringUtils.isNotBlank(fk)) result.add(new Object[]{fk, "file"});
                    }
                }
            }
        }
    }

    // 构建卡片回复
    private FeishuReplyContent buildCard(String text, boolean updateMulti) {
        return FeishuReplyContent.card(text, updateMulti, null);
    }

    // 构建卡片回复，支持将 &lt;file&gt; 标签替换为文件 URL，并追加会话链接
    private FeishuReplyContent buildCard(String text, boolean updateMulti, Long conversationId, Long agentId, FeishuBotConfig botConfig) {
        Long tenantId = botConfig != null ? botConfig.getTenantId() : null;
        TenantConfigDto tenantConfig = tenantId != null ? tenantConfigApplicationService.getTenantConfig(tenantId) : null;
        String domain = tenantConfig != null ? tenantConfig.getSiteUrl() : null;
        Long userId = botConfig != null ? botConfig.getUserId() : null;
        return FeishuReplyContent.card(text, updateMulti, conversationId, agentId, domain, userId, tenantId,
                imFileShareService, computerFileApplicationService);
    }

    private FeishuReplyContent buildMarkdown(String text, Long conversationId, FeishuBotConfig botConfig) {
        Long agentId = botConfig != null ? botConfig.getAgentId() : null;
        // 使用 interactive 卡片的 markdown 组件，保证完整 Markdown 渲染能力
        return buildCard(text, false, conversationId, agentId, botConfig);
    }

    // 从 EventSender 中提取 open_id
    private String extractOpenId(EventSender sender) {
        if (sender == null || sender.getSenderId() == null) {
            return null;
        }
        return sender.getSenderId().getOpenId();
    }

    // 回复到飞书，支持多种消息类型：text（文本）、post（富文本）、image（图片）、interactive（互动卡片）
    private void reply(String messageId, FeishuReplyContent reply, FeishuBotConfig botConfig) {
        replyAndGetMessageId(messageId, reply, botConfig);
    }

    // 回复到飞书并返回新消息 ID（用于后续 patch 更新）
    private String replyAndGetMessageId(String messageId, FeishuReplyContent reply, FeishuBotConfig botConfig) {
        if (botConfig == null) return null;
        if (reply == null || StringUtils.isBlank(reply.getMsgType()) || StringUtils.isBlank(reply.getContentJson())) {
            reply = buildCard("已收到", false);
        }
        try {
            Client client = Client.newBuilder(botConfig.getAppId(), botConfig.getAppSecret()).build();

            ReplyMessageReq req = ReplyMessageReq.newBuilder()
                    .messageId(messageId)
                    .replyMessageReqBody(ReplyMessageReqBody.newBuilder()
                            .content(reply.getContentJson())
                            .msgType(reply.getMsgType())
                            .build())
                    .build();
            ReplyMessageResp resp = client.im().v1().message().reply(req);
            if (!resp.success()) {
                log.warn("回复飞书消息失败: messageId={}, msgType={}, code={}, msg={}",
                        messageId, reply.getMsgType(), resp.getCode(), resp.getMsg());
                return null;
            }
            return resp.getData() != null ? resp.getData().getMessageId() : null;
        } catch (Exception e) {
            log.error("回复飞书消息异常: messageId={}, msgType={}", messageId, reply.getMsgType(), e);
            return null;
        }
    }

    /**
     * 流式执行智能体并 patch 更新卡片。
     * 飞书 patch 限制 5 QPS，所以需要采用如下策略，达到输出长内容时 patch 次数显著减少，整体输出更快：
     * - 采用 [按字符数 + 最小间隔] 策略减少 patch 次数
     * - 最终结果：立即 patch
     * - 中间结果：需同时满足[距上次 patch ≥ 200ms] 且 [新增字符 ≥ 60] 才 patch
     */
    private void streamAndPatch(String originalMessageId, String replyMessageId, String sessionId, String chatType, String userMessage,
                                List<AttachmentDto> attachments, FeishuBotConfig botConfig, String sessionName) {
        if (botConfig == null) return;
        AtomicLong lastPatchTime = new AtomicLong(0);
        AtomicInteger lastPatchLength = new AtomicInteger(0);

        feishuAgentApplicationService.executeAgentStream(sessionId, chatType, userMessage, attachments,
                        botConfig.getTenantId(), botConfig.getUserId(), botConfig.getAgentId(), sessionName)
                .filter(chunk -> chunk != null && chunk.getText() != null)
                .subscribe(
                        chunk -> {
                            long now = System.currentTimeMillis();
                            int currentLen = chunk.getText().length();
                            int newChars = currentLen - lastPatchLength.get();
                            boolean isFirstChunk = lastPatchLength.get() == 0 && currentLen > 0;
                            boolean shouldPatch = chunk.isFinal()
                                    || isFirstChunk
                                    || (newChars >= STREAM_PATCH_MIN_CHARS && (now - lastPatchTime.get()) >= STREAM_PATCH_INTERVAL_MS);
                            if (shouldPatch) {
                                patchMessage(replyMessageId, buildCard(chunk.getText(), true, chunk.getConversationId(), botConfig.getAgentId(), botConfig), botConfig);
                                lastPatchTime.set(now);
                                lastPatchLength.set(currentLen);
                            }
                        },
                        e -> {
                            log.error("飞书流式执行异常: messageId={}", originalMessageId, e);
                            patchMessage(replyMessageId, buildCard("执行异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误"), true), botConfig);
                        },
                        () -> log.debug("飞书流式执行完成: messageId={}", originalMessageId)
                );
    }

    private String resolveSessionName(String chatType, String chatId, EventSender sender, FeishuBotConfig botConfig) {
        // 取不到就回退到 sessionKey（open_id/chat_id）
        String openId = extractOpenId(sender);
        try {
            if (botConfig == null || StringUtils.isBlank(botConfig.getAppId()) || StringUtils.isBlank(botConfig.getAppSecret())) {
                return StringUtils.isNotBlank(openId) ? openId : chatId;
            }

            String token = fetchTenantAccessToken(botConfig);
            if (StringUtils.isBlank(token)) {
                return StringUtils.isNotBlank(openId) ? openId : chatId;
            }

            // 群聊：优先查群详情拿群名
            if (!"p2p".equals(chatType) && StringUtils.isNotBlank(chatId)) {
                String chatName = fetchChatName(chatId, token);
                if (StringUtils.isNotBlank(chatName)) {
                    return chatName;
                }
                return chatId;
            }

            // 单聊：查用户姓名/昵称
            if (StringUtils.isNotBlank(openId)) {
                String userName = fetchUserName(openId, token);
                if (StringUtils.isNotBlank(userName)) {
                    return userName;
                }
                return openId;
            }
        } catch (Exception e) {
            log.debug("飞书获取 sessionName 失败，回退 sessionKey: chatType={}, chatId={}, openId={}, err={}",
                    chatType, chatId, openId, e.getMessage());
        }
        return StringUtils.isNotBlank(openId) ? openId : chatId;
    }

    private String fetchTenantAccessToken(FeishuBotConfig botConfig) {
        try {
            String cacheKey = FEISHU_TENANT_TOKEN_CACHE_PREFIX + botConfig.getAppId();
            if (redisUtil != null) {
                Object cached = redisUtil.get(cacheKey);
                if (cached instanceof String && StringUtils.isNotBlank((String) cached)) {
                    return (String) cached;
                }
            }

            String url = "https://open.feishu.cn/open-apis/auth/v3/tenant_access_token/internal";
            JSONObject body = new JSONObject();
            body.put("app_id", botConfig.getAppId());
            body.put("app_secret", botConfig.getAppSecret());

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            ResponseEntity<String> resp = new RestTemplate().exchange(url, HttpMethod.POST, new HttpEntity<>(body.toJSONString(), headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || StringUtils.isBlank(resp.getBody())) {
                return null;
            }
            JSONObject obj = JSON.parseObject(resp.getBody());
            if (obj == null || obj.getIntValue("code") != 0) {
                return null;
            }
            String token = obj.getString("tenant_access_token");
            Integer expire = obj.getInteger("expire");
            if (StringUtils.isNotBlank(token) && redisUtil != null) {
                int ttl = expire != null && expire > 60 ? Math.min(expire - 60, FEISHU_TENANT_TOKEN_CACHE_SECONDS) : FEISHU_TENANT_TOKEN_CACHE_SECONDS;
                redisUtil.set(cacheKey, token, ttl);
            }
            return token;
        } catch (Exception e) {
            log.debug("获取飞书 tenant_access_token 失败: {}", e.getMessage());
            return null;
        }
    }

    private String fetchUserName(String openId, String tenantAccessToken) {
        try {
            String url = "https://open.feishu.cn/open-apis/contact/v3/users/" + openId + "?user_id_type=open_id";
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tenantAccessToken);
            ResponseEntity<String> resp = new RestTemplate().exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || StringUtils.isBlank(resp.getBody())) {
                return null;
            }
            JSONObject obj = JSON.parseObject(resp.getBody());
            if (obj == null || obj.getIntValue("code") != 0) {
                return null;
            }
            JSONObject data = obj.getJSONObject("data");
            if (data == null) {
                return null;
            }
            // 兼容不同返回结构：有的返回 data.name，有的返回 data.user.name
            String name = data.getString("name");
            if (StringUtils.isBlank(name)) {
                name = data.getString("display_name");
            }
            if (StringUtils.isBlank(name)) {
                JSONObject user = data.getJSONObject("user");
                if (user != null) {
                    name = user.getString("name");
                    if (StringUtils.isBlank(name)) {
                        name = user.getString("display_name");
                    }
                }
            }
            return StringUtils.isNotBlank(name) ? name : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String fetchChatName(String chatId, String tenantAccessToken) {
        try {
            String url = "https://open.feishu.cn/open-apis/im/v1/chats/" + chatId;
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(tenantAccessToken);
            ResponseEntity<String> resp = new RestTemplate().exchange(url, HttpMethod.GET, new HttpEntity<>(headers), String.class);
            if (!resp.getStatusCode().is2xxSuccessful() || StringUtils.isBlank(resp.getBody())) {
                return null;
            }
            JSONObject obj = JSON.parseObject(resp.getBody());
            if (obj == null || obj.getIntValue("code") != 0) {
                return null;
            }
            JSONObject data = obj.getJSONObject("data");
            if (data == null) {
                return null;
            }
            // 兼容不同返回结构：有的返回 data.name，有的返回 data.chat.name
            String name = data.getString("name");
            if (StringUtils.isBlank(name)) {
                JSONObject chat = data.getJSONObject("chat");
                if (chat != null) {
                    name = chat.getString("name");
                }
            }
            return StringUtils.isNotBlank(name) ? name : null;
        } catch (Exception e) {
            return null;
        }
    }

    // 更新（patch）飞书消息卡片内容
    private void patchMessage(String messageId, FeishuReplyContent reply, FeishuBotConfig botConfig) {
        if (botConfig == null || StringUtils.isBlank(messageId) || reply == null || StringUtils.isBlank(reply.getContentJson())) {
            return;
        }

        try {
            Client client = Client.newBuilder(botConfig.getAppId(), botConfig.getAppSecret()).build();
            PatchMessageReq req = PatchMessageReq.newBuilder()
                    .messageId(messageId)
                    .patchMessageReqBody(PatchMessageReqBody.newBuilder()
                            .content(reply.getContentJson())
                            .build())
                    .build();
            PatchMessageResp resp = client.im().v1().message().patch(req);
            if (!resp.success()) {
                log.warn("patch 飞书消息失败: messageId={}, code={}, msg={}", messageId, resp.getCode(), resp.getMsg());
            }
        } catch (Exception e) {
            log.error("patch 飞书消息异常: messageId={}", messageId, e);
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FeishuBotConfig {
        private String appId;
        private String appSecret;
        private String verificationToken;
        private String encryptKey;
        private Long tenantId;
        private Long userId;
        private Long agentId;
        private String outputMode;
    }

}