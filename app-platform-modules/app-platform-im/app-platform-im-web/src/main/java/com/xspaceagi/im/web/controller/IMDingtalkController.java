package com.xspaceagi.im.web.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.application.IComputerFileApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.application.DingtalkAgentApplicationService;
import com.xspaceagi.im.application.ImChannelConfigApplicationService;
import com.xspaceagi.im.application.ImSessionApplicationService;
import com.xspaceagi.im.application.dto.ImChannelConfigDto;
import com.xspaceagi.im.infra.dao.enitity.ImSession;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.infra.enums.ImChatTypeEnum;
import com.xspaceagi.im.infra.enums.ImTargetTypeEnum;
import com.xspaceagi.im.infra.enums.ImOutputModeEnum;
import com.xspaceagi.im.web.dto.DingtalkAttachmentCodeDto;
import com.xspaceagi.im.web.service.DingtalkAttachmentService;
import com.xspaceagi.im.web.service.DingtalkOpenApiClient;
import com.xspaceagi.im.web.service.ImFileShareService;
import com.xspaceagi.im.web.util.ImOutputProcessor;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
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
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/im/dingtalk")
@Slf4j
@Tag(name = "钉钉 IM 集成")
public class IMDingtalkController {

    @Resource
    private RedisUtil redisUtil;
    @Resource
    private DingtalkAgentApplicationService dingtalkAgentApplicationService;
    @Resource
    private DingtalkAttachmentService dingtalkAttachmentService;
    @Resource
    private ImSessionApplicationService imSessionApplicationService;
    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;
    @Resource
    private ImChannelConfigApplicationService imChannelConfigApplicationService;
    @Resource
    private ImFileShareService imFileShareService;
    @Resource
    private IComputerFileApplicationService computerFileApplicationService;

    /**
     * 钉钉 Webhook 时间戳容错范围（毫秒）
     * 用于验证请求中的 timestamp，防止重放攻击
     * 允许客户端时间与服务器时间偏差在 ±1 小时内
     */
    private static final long TIMESTAMP_TOLERANCE_MS = 60 * 60 * 1000;

    /**
     * 钉钉消息幂等 Redis Key 前缀
     * 用于在 Redis 中存储已处理的消息标识，防止重复处理
     */
    private static final String DINGTALK_MSG_PREFIX = "dingtalk:msg:";

    /**
     * 钉钉消息幂等 Redis Key 过期时间（秒）
     * 用于控制幂等 Key 的有效期，5 分钟后自动删除
     * 超过此时间的消息不再认为是重复消息
     */
    private static final int DINGTALK_MSG_TTL_SECONDS = 300;

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

    @RequestMapping(value = "/webhook", method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(summary = "钉钉机器人消息接收 Webhook", hidden = true)
    public void webhook(HttpServletRequest request, HttpServletResponse response) throws Throwable {
        byte[] bodyBytes = StreamUtils.copyToByteArray(request.getInputStream());
        String bodyStr = new String(bodyBytes, StandardCharsets.UTF_8);
        String timestamp = request.getHeader("timestamp");
        String sign = request.getHeader("sign");

        log.info("钉钉 Webhook 请求: method={}, contentLength={}, timestamp={}, sign={}, body={}",
                request.getMethod(), bodyBytes.length,
                timestamp != null ? "***" : null, sign != null ? "***" : null,
                bodyBytes.length > 500 ? bodyStr.substring(0, 500) + "..." : bodyStr);

        // 空 body 且无签名：可能是 URL 验证或测试请求，直接返回 200
        if (bodyBytes.length == 0 && (StringUtils.isBlank(timestamp) || StringUtils.isBlank(sign))) {
            log.info("钉钉 Webhook 收到空请求（无 timestamp/sign），可能是 URL 验证或测试，返回 200");
            response.setStatus(HttpServletResponse.SC_OK);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(JSON.toJSONString(Map.of(
                    "msg", "钉钉 Webhook 已就绪，请从钉钉客户端发送消息测试",
                    "url", "配置正确，需 POST 请求且 Header 含 timestamp、sign，Body 为 JSON")).getBytes(StandardCharsets.UTF_8));
            return;
        }

        JSONObject body = JSON.parseObject(bodyStr);
        DingtalkBotConfig config = resolveBotConfig(timestamp, sign, body);
        if (config == null) {
            log.warn("钉钉 Webhook 签名验证失败: timestamp={}, sign={}", timestamp, sign);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json;charset=UTF-8");
            response.getOutputStream().write(JSON.toJSONString(Map.of("error", "非法请求")).getBytes(StandardCharsets.UTF_8));
            return;
        }

        if (body == null) {
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        String msgtype = body.getString("msgtype");
        if (!"text".equals(msgtype) && !"richText".equals(msgtype) && !"picture".equals(msgtype) && !"file".equals(msgtype)) {
            log.info("钉钉 Webhook 不支持的消息类型，回复用户: msgtype={}", msgtype);
            String sessionWebhook = body.getString("sessionWebhook");
            if (StringUtils.isNotBlank(sessionWebhook)) {
                replyBySessionWebhook(sessionWebhook, "抱歉，暂不支持此消息格式的处理", null,
                        body.getString("senderNick"), body.getString("senderStaffId"), body.getString("conversationType"));
            }
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        // 幂等：钉钉可能重复推送同一条消息，用 msgId 去重
        String msgId = body.getString("msgId");
        String dedupKey = StringUtils.isNotBlank(msgId) ? DINGTALK_MSG_PREFIX + msgId
                : DINGTALK_MSG_PREFIX + body.getString("conversationId") + ":" + body.getLong("createAt");
        if (redisUtil != null && redisUtil.get(dedupKey) != null) {
            log.info("钉钉 Webhook 重复消息，跳过: msgId={}", msgId);
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        if (redisUtil != null) {
            redisUtil.set(dedupKey, "1", DINGTALK_MSG_TTL_SECONDS);
        }

        String userMessage;
        List<DingtalkAttachmentCodeDto> attachmentCodes = new ArrayList<>();
        if ("text".equals(msgtype)) {
            userMessage = body.getJSONObject("text") != null
                    ? body.getJSONObject("text").getString("content")
                    : "";
        } else if ("picture".equals(msgtype) || "file".equals(msgtype)) {
            userMessage = "";
            String code = parseAttachmentDownloadCode(body, msgtype);
            String fileName = parseAttachmentFileName(body, msgtype);
            if (StringUtils.isNotBlank(code)) {
                attachmentCodes.add(new DingtalkAttachmentCodeDto(code, "picture".equals(msgtype), fileName));
            } else {
                log.warn("钉钉 {} 消息未解析到 downloadCode, bodyKeys={}, picture={}, content={}",
                        msgtype, body != null ? body.keySet() : null,
                        body != null ? body.getJSONObject("picture") : null,
                        body != null ? body.getJSONObject("content") : null);
            }
        } else {
            // richText
            var parsed = parseRichTextContent(body.getJSONObject("content"));
            userMessage = parsed.getLeft();
            attachmentCodes = parsed.getRight();
        }
        String senderNick = body.getString("senderNick");

        // 下载附件并上传到项目存储
        List<AttachmentDto> attachments = new ArrayList<>();
        if (!attachmentCodes.isEmpty()) {
            DingtalkOpenApiClient apiClient = getApiClient(config);
            // robotCode 需与接收该消息的机器人一致。picture 消息体可能不含 robotCode，richText 通常包含
            String robotCode = body.getString("robotCode");
            if (StringUtils.isBlank(robotCode)) {
                robotCode = StringUtils.isNotBlank(config.getRobotCode()) ? config.getRobotCode() : config.getClientId();
            }
            var tenantConfig = tenantConfigApplicationService.getTenantConfig(config.getTenantId());
            var attachmentResult = dingtalkAttachmentService.downloadAndUpload(
                    apiClient, attachmentCodes, robotCode, null, tenantConfig);
            attachments = attachmentResult.getAttachments();
            // 仅当全部附件下载失败时提示；有任一成功则只显示 [附件]
            if (!attachmentResult.getUnsupportedKeys().isEmpty() && attachmentResult.getAttachments().isEmpty()) {
                String errHint = "附件下载失败，可能原因：1) 群聊不支持文件下载，请在单聊中发送；2) 在钉钉开放平台robotCode 并配置。";
                if (StringUtils.isNotBlank(userMessage)) {
                    userMessage = userMessage + "\n\n[系统提示：" + errHint + "]";
                } else {
                    // 仅附件且全部下载失败：直接回复用户，不调用智能体
                    replyBySessionWebhook(body.getString("sessionWebhook"), errHint, null, senderNick,
                            body.getString("senderStaffId"), body.getString("conversationType"));
                    response.setStatus(HttpServletResponse.SC_OK);
                    return;
                }
            } else if (!attachmentResult.getAttachments().isEmpty() && StringUtils.isBlank(userMessage)) {
                userMessage = buildAttachmentDisplay(attachments.size());
            }
        }

        if (StringUtils.isBlank(userMessage) && attachments.isEmpty()) {
            replyBySessionWebhook(body.getString("sessionWebhook"), "请输入文本内容或发送附件", null, senderNick, body.getString("senderStaffId"), body.getString("conversationType"));
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }
        if (StringUtils.isBlank(userMessage)) {
            userMessage = "[用户发送了附件]";
        }

        // 引用块显示：有文本有附件时显示「文本 [附件]xN」，仅附件时显示「[附件]xN」（避免 userMessage 已是 [附件] 时重复拼接）
        String attachmentDisplay = buildAttachmentDisplay(attachments.size());
        String quoteDisplayMessage = attachments.isEmpty() ? userMessage
                : (StringUtils.isNotBlank(userMessage) && !userMessage.equals(attachmentDisplay)
                        ? userMessage + " " + attachmentDisplay
                        : attachmentDisplay);

        String senderId = body.getString("senderId");
        if (StringUtils.isBlank(senderId)) {
            senderId = body.getString("conversationId");
        }

        String sessionWebhook = body.getString("sessionWebhook");
        Long sessionWebhookExpiredTime = body.getLong("sessionWebhookExpiredTime");
        if (sessionWebhookExpiredTime != null && sessionWebhookExpiredTime < System.currentTimeMillis()) {
            log.warn("钉钉 sessionWebhook 已过期: expiredTime={}", sessionWebhookExpiredTime);
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        log.info("处理钉钉消息: senderId={}, conversationId={}, content={}, robotCode={}",
                senderId, body.getString("conversationId"), userMessage, body.getString("robotCode"));

        String conversationType = body.getString("conversationType");
        String conversationId = body.getString("conversationId");
        String senderStaffId = body.getString("senderStaffId");
        String sessionName = StringUtils.isNotBlank(senderNick) ? senderNick : null;
        // 群聊：查群名，失败则回退 senderNick / conversationId（不抛异常）
        if ("2".equals(conversationType) && StringUtils.isNotBlank(conversationId)) {
            try {
                DingtalkOpenApiClient apiClient = getApiClient(config);
                String groupName = apiClient.queryGroupName(conversationId);
                if (StringUtils.isNotBlank(groupName)) {
                    sessionName = groupName;
                } else if (StringUtils.isBlank(sessionName)) {
                    sessionName = conversationId;
                }
            } catch (Exception ignore) {
                if (StringUtils.isBlank(sessionName)) {
                    sessionName = conversationId;
                }
            }
        }
        if (isNewCommand(userMessage)) {
            createNewConversationForDingtalk(senderId, conversationType, conversationId, sessionName, config);
            replyBySessionWebhook(sessionWebhook, "已为你创建新会话，后续消息默认走新会话", null, senderNick, senderStaffId, conversationType);
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        if (ImOutputModeEnum.ONCE == ImOutputModeEnum.fromCode(config.getOutputMode())) {
            // 一次性输出：非流式，使用 sessionWebhook 发送 Markdown（非互动卡片）
            DingtalkAgentApplicationService.AgentExecuteResultWithConv result = dingtalkAgentApplicationService.executeAgentWithConv(
                    senderId, userMessage, attachments, conversationType, conversationId,
                    config.getTenantId(), config.getUserId(), config.getAgentId(), sessionName);
            String fileUrlDomain = getPlatformBaseUrl(config.getTenantId());
            String processed = ImOutputProcessor.processOutput(result.getText(), result.getConversationId(),
                    config.getAgentId(), fileUrlDomain, config.getUserId(), config.getTenantId(),
                    imFileShareService, computerFileApplicationService);
            replyBySessionWebhook(sessionWebhook, processed, quoteDisplayMessage, senderNick, senderStaffId, conversationType);
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        // 优先使用互动卡片流式更新，失败时回退到 sessionWebhook 同步回复
        // 需要权限：qyapi_chat_manage
        String outTrackId = "card_" + UUID.randomUUID();
        // 优先使用 webhook 回调中的 robotCode（钉钉推送的机器人标识），否则用配置的 robotCode/clientId
        String robotCode = body.getString("robotCode");
        if (StringUtils.isBlank(robotCode)) {
            robotCode = StringUtils.isNotBlank(config.getRobotCode()) ? config.getRobotCode() : config.getClientId();
        }
        DingtalkOpenApiClient apiClient = getApiClient(config);
        String initialCardContent = buildReplyContent("正在思考...", quoteDisplayMessage, senderNick, senderStaffId, conversationType);
        boolean cardSent = apiClient.sendInteractiveCard(
                conversationType, conversationId, senderStaffId,
                outTrackId, DingtalkOpenApiClient.buildCardData(initialCardContent), robotCode);

        if (cardSent) {
            streamAndPatchCard(apiClient, outTrackId, senderId, userMessage, quoteDisplayMessage, attachments, senderNick, senderStaffId,
                    conversationType, conversationId, config);
        } else {
            String lastErr = apiClient.getLastError();
            boolean isCredentialError = lastErr != null && (lastErr.contains("AccessToken 为空")
                    || lastErr.contains("invalidClientIdOrSecret") || lastErr.contains("无效的clientId"));
            boolean isPermissionError = lastErr != null && (lastErr.contains("qyapi_chat_manage")
                    || lastErr.contains("AccessTokenPermissionDenied") || lastErr.contains("应用尚未开通所需的权限"));
            boolean isRobotNotFound = lastErr != null && (lastErr.contains("chatbot.notFound") || lastErr.contains("机器人不存在"));
            if (isCredentialError) {
                // 凭证错误：不执行智能体，仅提示用户
                log.warn("钉钉凭证配置错误，跳过回复: senderId={}, 原因={}", senderId, lastErr);
                replyBySessionWebhook(sessionWebhook, "凭证配置错误，请联系管理员检查钉钉应用 ClientId/ClientSecret", quoteDisplayMessage, senderNick, senderStaffId, conversationType);
            } else if (isPermissionError) {
                // 权限错误：提示开通会话管理权限
                log.warn("钉钉应用权限未开通，跳过回复: senderId={}, 原因={}", senderId, lastErr);
                replyBySessionWebhook(sessionWebhook, "钉钉应用权限未开通，请联系管理员在钉钉开放平台开通「会话管理」权限（qyapi_chat_manage）", quoteDisplayMessage, senderNick, senderStaffId, conversationType);
            } else if (isRobotNotFound) {
                // 机器人不存在：提示检查机器人是否已添加到群聊
                log.warn("钉钉机器人不存在，跳过回复: senderId={}, 原因={}", senderId, lastErr);
                replyBySessionWebhook(sessionWebhook, "机器人未找到，请确认：1) 机器人已添加到当前群聊；2) 机器人在钉钉开放平台已发布；3) 若 robotCode 与 AppKey 不同，请在配置中填写 robotCode（在【消息推送】获取）", quoteDisplayMessage, senderNick, senderStaffId, conversationType);
            } else {
                // 其他失败（如网络等），回退到 sessionWebhook 流式回复
                log.warn("钉钉发送互动卡片失败，回退到 sessionWebhook 模式: senderId={}, conversationId={}, 失败原因={}", senderId, conversationId, lastErr);
                streamBySessionWebhook(sessionWebhook, senderId, userMessage, quoteDisplayMessage, attachments, senderNick, senderStaffId,
                        conversationType, conversationId, config);
            }
        }

        response.setStatus(HttpServletResponse.SC_OK);
    }

    /**
     * 验证钉钉签名
     * sign = Base64(HmacSHA256(timestamp + "\n" + clientSecret, clientSecret))
     */
    private boolean verifySign(String timestamp, String sign, String clientSecret) {
        if (StringUtils.isBlank(timestamp) || StringUtils.isBlank(sign) || StringUtils.isBlank(clientSecret)) {
            return false;
        }
        try {
            long ts = Long.parseLong(timestamp);
            if (Math.abs(System.currentTimeMillis() - ts) > TIMESTAMP_TOLERANCE_MS) {
                log.warn("钉钉 timestamp 超出允许范围: ts={}", ts);
                return false;
            }
            String stringToSign = timestamp + "\n" + clientSecret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] signData = mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8));
            String expectedSign = Base64.getEncoder().encodeToString(signData);
            return sign.equals(expectedSign);
        } catch (Exception e) {
            log.warn("钉钉签名验证异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 根据签名验证解析当前请求对应的机器人配置。
     * 从 body 的 robotCode 查找（企业机器人），否则遍历所有 config 用签名匹配。
     */
    private DingtalkBotConfig resolveBotConfig(String timestamp, String sign, JSONObject body) {
        if (body != null) {
            String robotCode = body.getString("robotCode");
            if (StringUtils.isNotBlank(robotCode)) {
                DingtalkBotConfig config = getConfigByRobotCode(robotCode);
                if (config != null && verifySign(timestamp, sign, config.getClientSecret())) {
                    return config;
                }
            }
        }
        return null;
    }

    private DingtalkBotConfig getConfigByRobotCode(String robotCode) {
        if (StringUtils.isBlank(robotCode)) {
            throw new BizException("钉钉 Webhook 缺少必要参数: robotCode");
        }
        ImChannelConfigDto cfg = imChannelConfigApplicationService.getDingtalkConfigByRobotCode(robotCode);
        ImChannelConfigDto.DingtalkConfig ding = cfg != null ? cfg.getDingtalk() : null;
        if (ding == null || StringUtils.isBlank(ding.getRobotCode())) {
            throw new BizException("钉钉机器人未绑定");
        }
        return DingtalkBotConfig.builder()
                .clientId(ding.getClientId())
                .clientSecret(ding.getClientSecret())
                .robotCode(ding.getRobotCode())
                .tenantId(cfg.getTenantId())
                .userId(cfg.getUserId())
                .agentId(cfg.getAgentId())
                .outputMode(cfg.getOutputMode())
                .build();
    }

    private String getPlatformBaseUrl(Long tenantId) {
        TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(tenantId);
        if (tenantConfig == null || StringUtils.isBlank(tenantConfig.getSiteUrl())) {
            return null;
        }
        String domain = tenantConfig.getSiteUrl().trim();
        if (!domain.startsWith("http://") && !domain.startsWith("https://")) {
            domain = "https://" + domain;
        }
        if (domain.endsWith("/")) {
            domain = domain.substring(0, domain.length() - 1);
        }
        return domain;
    }

    private DingtalkOpenApiClient getApiClient(DingtalkBotConfig config) {
        return new DingtalkOpenApiClient(config.getClientId(), config.getClientSecret(), config.getRobotCode());
    }

    /** 根据附件数量生成显示文本，如 3 个附件显示 [附件][附件][附件] */
    private static String buildAttachmentDisplay(int count) {
        if (count <= 0) return "";
        return "[附件]".repeat(count);
    }

    /**
     * 解析钉钉 picture/file 消息的下载码。picture 消息 content 同时含 pictureDownloadCode 和 downloadCode，
     * 下载接口需用 downloadCode（mIofN... 格式），pictureDownloadCode（vcOp... 格式）会返回 500。
     */
    private String parseAttachmentDownloadCode(JSONObject body, String msgtype) {
        if (body == null) return null;
        JSONObject content = body.getJSONObject("content");
        if (content != null) {
            // 优先 downloadCode：钉钉下载接口要求此字段，picture 消息 content 中会同时提供
            String code = content.getString("downloadCode");
            if (StringUtils.isNotBlank(code)) return code;
            code = content.getString("pictureDownloadCode");
            if (StringUtils.isNotBlank(code)) return code;
            code = content.getString("fileDownloadCode");
            if (StringUtils.isNotBlank(code)) return code;
        }
        String objKey = "picture".equals(msgtype) ? "picture" : "file";
        JSONObject obj = body.getJSONObject(objKey);
        if (obj != null) {
            String code = obj.getString("downloadCode");
            if (StringUtils.isNotBlank(code)) return code;
            code = obj.getString("pictureDownloadCode");
            if (StringUtils.isNotBlank(code)) return code;
            code = obj.getString("fileDownloadCode");
            if (StringUtils.isNotBlank(code)) return code;
        }
        String code = body.getString("downloadCode");
        if (StringUtils.isNotBlank(code)) return code;
        code = body.getString("pictureDownloadCode");
        if (StringUtils.isNotBlank(code)) return code;
        code = body.getString("fileDownloadCode");
        if (StringUtils.isNotBlank(code)) return code;
        return null;
    }

    /**
     * 解析钉钉 picture/file 消息的原始文件名。可能在 content、picture、file 对象下。
     */
    private String parseAttachmentFileName(JSONObject body, String msgtype) {
        if (body == null) return null;
        JSONObject content = body.getJSONObject("content");
        if (content != null) {
            String name = content.getString("fileName");
            if (StringUtils.isNotBlank(name)) return sanitizeFileName(name);
        }
        String objKey = "picture".equals(msgtype) ? "picture" : "file";
        JSONObject obj = body.getJSONObject(objKey);
        if (obj != null) {
            String name = obj.getString("fileName");
            if (StringUtils.isNotBlank(name)) return sanitizeFileName(name);
        }
        return null;
    }

    private static String sanitizeFileName(String name) {
        if (name == null) return null;
        return name.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    private static boolean isNewCommand(String userMessage) {
        return "/new".equals(StringUtils.trimToEmpty(userMessage));
    }

    private void createNewConversationForDingtalk(String senderId, String conversationType, String conversationId,
                                                  String sessionName, DingtalkBotConfig config) {
        String sessionKey = "2".equals(conversationType) && StringUtils.isNotBlank(conversationId)
                ? conversationId : senderId;
        ImSession imSession = ImSession.builder()
                .channel(ImChannelEnum.DINGTALK.getCode())
                .targetType(ImTargetTypeEnum.BOT.getCode())
                .sessionKey(sessionKey)
                .sessionName(sessionName)
                .chatType("2".equals(conversationType) ? ImChatTypeEnum.GROUP.getCode() : ImChatTypeEnum.PRIVATE.getCode())
                .userId(config.getUserId())
                .agentId(config.getAgentId())
                .tenantId(config.getTenantId())
                .build();
        imSessionApplicationService.createNewConversationId(imSession);
    }

    /**
     * 解析钉钉 richText 富文本消息。
     * content.richText 为数组，每项可能包含：text、pictureDownloadCode、fileDownloadCode、downloadCode、fileName。
     *
     * @return Pair(userMessage 拼接文本, List of DingtalkAttachmentCodeDto)
     */
    private Pair<String, List<DingtalkAttachmentCodeDto>> parseRichTextContent(JSONObject content) {
        if (content == null) {
            return ImmutablePair.of("", new ArrayList<>());
        }
        JSONArray richText = content.getJSONArray("richText");
        if (richText == null || richText.isEmpty()) {
            return ImmutablePair.of("", new ArrayList<>());
        }
        StringBuilder textSb = new StringBuilder();
        List<DingtalkAttachmentCodeDto> attachmentCodes = new ArrayList<>();
        for (int i = 0; i < richText.size(); i++) {
            JSONObject item = richText.getJSONObject(i);
            if (item == null) continue;
            String text = item.getString("text");
            if (StringUtils.isNotBlank(text)) {
                if (textSb.length() > 0) textSb.append("\n");
                textSb.append(text);
            }
            String code = item.getString("downloadCode");
            boolean isPicture = false;
            if (StringUtils.isNotBlank(code)) {
                isPicture = false;
            } else {
                code = item.getString("pictureDownloadCode");
                if (StringUtils.isNotBlank(code)) isPicture = true;
                else {
                    code = item.getString("fileDownloadCode");
                    if (StringUtils.isNotBlank(code)) isPicture = false;
                }
            }
            String fileName = item.getString("fileName");
            if (StringUtils.isNotBlank(fileName)) fileName = sanitizeFileName(fileName);
            if (StringUtils.isNotBlank(code)) {
                attachmentCodes.add(new DingtalkAttachmentCodeDto(code, isPicture, fileName));
            }
        }
        return ImmutablePair.of(textSb.toString(), attachmentCodes);
    }

    /**
     * 流式执行智能体并更新互动卡片（打字机效果）
     */
    private void streamAndPatchCard(DingtalkOpenApiClient apiClient, String outTrackId, String senderId,
                                    String userMessage, String quoteDisplayMessage,
                                    List<AttachmentDto> attachments,
                                    String senderNick, String senderStaffId,
                                    String conversationType, String conversationId, DingtalkBotConfig config) {
        AtomicLong lastPatchTime = new AtomicLong(0);
        AtomicInteger lastPatchLength = new AtomicInteger(0);

        dingtalkAgentApplicationService.executeAgentStream(
                        senderId, userMessage, attachments, conversationType, conversationId,
                        config.getTenantId(), config.getUserId(), config.getAgentId())
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
                                String fileUrlDomain = getPlatformBaseUrl(config.getTenantId());
                                String content = ImOutputProcessor.processOutput(chunk.getText(),
                                        chunk.getConversationId(), config.getAgentId(), fileUrlDomain,
                                        config.getUserId(), config.getTenantId(), imFileShareService, computerFileApplicationService);
                                String displayContent = buildReplyContent(content, quoteDisplayMessage, senderNick, senderStaffId, conversationType);
                                boolean ok = apiClient.updateInteractiveCard(outTrackId,
                                        DingtalkOpenApiClient.buildCardData(displayContent));
                                if (ok) {
                                    lastPatchTime.set(now);
                                    lastPatchLength.set(currentLen);
                                }
                            }
                        },
                        e -> {
                            log.error("钉钉流式执行异常: senderId={}", senderId, e);
                            String errMsg = "执行异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误");
                            // 仅更新卡片，避免同时调用 sessionWebhook 导致重复回复
                                    apiClient.updateInteractiveCard(outTrackId,
                                            DingtalkOpenApiClient.buildCardData(buildReplyContent(errMsg, quoteDisplayMessage, senderNick, senderStaffId, conversationType)));
                        },
                        () -> log.debug("钉钉流式执行完成: senderId={}", senderId)
                );
    }

    /**
     * 流式失败回退时，通过 sessionWebhook 一次性发送最终结果。
     * 注意：sessionWebhook 每次 POST 会生成新消息，无法更新同一条；为避免多次发卡片，仅在全量完成后发送一次。
     */
    private void streamBySessionWebhook(String sessionWebhook, String senderId, String userMessage,
                                        String quoteDisplayMessage,
                                        List<AttachmentDto> attachments,
                                        String senderNick, String senderStaffId,
                                        String conversationType, String conversationId, DingtalkBotConfig config) {
        if (StringUtils.isBlank(sessionWebhook)) {
            log.warn("钉钉 sessionWebhook 为空，无法回复");
            return;
        }

        AtomicReference<String> finalContent = new AtomicReference<>("");
        AtomicReference<Long> finalConversationId = new AtomicReference<>();

        dingtalkAgentApplicationService.executeAgentStream(
                        senderId, userMessage, attachments, conversationType, conversationId,
                        config.getTenantId(), config.getUserId(), config.getAgentId())
                .filter(chunk -> chunk != null && chunk.getText() != null)
                .subscribe(
                        chunk -> {
                            finalContent.set(chunk.getText());
                            if (chunk.getConversationId() != null) {
                                finalConversationId.set(chunk.getConversationId());
                            }
                        },
                        e -> {
                            log.error("钉钉 sessionWebhook 流式执行异常: senderId={}", senderId, e);
                            String errMsg = "执行异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误");
                            replyBySessionWebhook(sessionWebhook, errMsg, quoteDisplayMessage, senderNick, senderStaffId, conversationType);
                        },
                        () -> {
                            String content = finalContent.get();
                            if (StringUtils.isNotBlank(content)) {
                                String fileUrlDomain = getPlatformBaseUrl(config.getTenantId());
                                content = ImOutputProcessor.processOutput(content, finalConversationId.get(),
                                        config.getAgentId(), fileUrlDomain, config.getUserId(), config.getTenantId(),
                                        imFileShareService, computerFileApplicationService);
                                replyBySessionWebhook(sessionWebhook, content, quoteDisplayMessage, senderNick, senderStaffId, conversationType);
                            }
                            log.debug("钉钉 sessionWebhook 流式执行完成: senderId={}", senderId);
                        }
                );
    }

    /**
     * 通过 sessionWebhook 发送 Markdown 消息。
     * 私聊：引用块仅显示原消息内容，不@用户；群聊：引用块含用户名，@用户。
     * 参考：<a href="https://open.dingtalk.com/document/development/enterprise-internal-robots-send-markdown-messages">企业内部机器人发送Markdown消息</a>
     */
    private boolean replyBySessionWebhook(String sessionWebhook, String content, String userMessage,
                                          String senderNick, String senderStaffId, String conversationType) {
        if (StringUtils.isBlank(sessionWebhook) || content == null) {
            return false;
        }
        String finalContent = buildReplyContent(content, userMessage, senderNick, senderStaffId, conversationType);
        try {
            String title = extractMarkdownTitle(finalContent);
            Map<String, Object> markdown = Map.of("title", title, "text", finalContent);
            Map<String, Object> payload = new HashMap<>(Map.of("msgtype", "markdown", "markdown", markdown));
            // 群聊才 @ 用户，私聊不 @
            if ("2".equals(conversationType) && StringUtils.isNotBlank(senderStaffId)) {
                payload.put("at", Map.of("atUserIds", Collections.singletonList(senderStaffId), "isAtAll", false));
            }
            String json = JSON.toJSONString(payload);
            byte[] body = json.getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn = (HttpURLConnection) new URL(sessionWebhook).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);
            conn.getOutputStream().write(body);

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                return true;
            }
            InputStream errStream = conn.getErrorStream();
            String resp = errStream != null
                    ? new String(StreamUtils.copyToByteArray(errStream), StandardCharsets.UTF_8)
                    : "";
            log.warn("钉钉 sessionWebhook 回复失败: code={}, resp={}", code, resp);
            return false;
        } catch (Exception e) {
            log.error("钉钉 sessionWebhook 回复异常: url={}", sessionWebhook, e);
            return false;
        }
    }

    /**
     * 构建回复内容，仿 AI 小钉效果。
     * 私聊：引用块仅显示原消息内容，不@用户。
     * 群聊：引用块含发送者昵称，@用户。
     *
     * @param conversationType "1" 单聊，"2" 群聊
     */
    private String buildReplyContent(String content, String quoteDisplayMessage, String senderNick, String senderStaffId, String conversationType) {
        if (StringUtils.isBlank(quoteDisplayMessage)) return content;
        String quoted = quoteDisplayMessage.length() > 100 ? quoteDisplayMessage.substring(0, 97) + "..." : quoteDisplayMessage;
        quoted = quoted.replace("\r", "");
        String quotedEscaped = escapeHtml(quoted);
        String styleOpen = "<span style=\"font-size:12px;color:#999999\">";
        String styleClose = "</span>";
        String quotedLines = quotedEscaped.replace("\n", styleClose + "\n> " + styleOpen) + styleClose;

        String ref;
        if ("1".equals(conversationType)) {
            // 私聊：引用块仅显示原消息内容，不显示用户名
            ref = "> " + styleOpen + quotedLines;
        } else {
            // 群聊：引用块含发送者昵称 + 原消息，@用户
            String displayName = StringUtils.isNotBlank(senderNick) ? senderNick : "用户";
            String line1 = "> " + styleOpen + escapeHtml(displayName) + styleClose;
            String line2 = "> " + styleOpen + quotedEscaped.replace("\n", styleClose + "\n> " + styleOpen) + styleClose;
            ref = line1 + "\n" + line2;
        }

        if ("1".equals(conversationType)) {
            return ref + "\n\n" + content;
        }
        String atUser = StringUtils.isNotBlank(senderStaffId) ? senderStaffId : senderNick;
        if (StringUtils.isNotBlank(atUser)) {
            return ref + "\n\n@" + atUser + " " + content;
        }
        return ref + "\n\n" + content;
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /**
     * 从内容提取 Markdown 标题（首屏展示，最长 128 字节）
     */
    private String extractMarkdownTitle(String content) {
        if (StringUtils.isBlank(content)) return "AI 回复";
        String firstLine = content.split("[\r\n]+", 2)[0].trim();
        firstLine = firstLine.replaceAll("^#+\\s*", ""); // 去掉 markdown 标题符
        if (firstLine.isEmpty()) return "AI 回复";
        byte[] bytes = firstLine.getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 128) return firstLine;
        for (int len = Math.min(125, firstLine.length()); len > 0; len--) {
            String s = firstLine.substring(0, len);
            if (s.getBytes(StandardCharsets.UTF_8).length <= 125) return s + "...";
        }
        return "AI 回复";
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DingtalkBotConfig {
        private String clientId;
        private String clientSecret;
        /** 机器人编码，在钉钉开放平台【消息推送】获取，与 AppKey 可能不同。不填则用 clientId。 */
        private String robotCode;
        private Long tenantId;
        private Long userId;
        private Long agentId;
        private String outputMode;
    }
}
