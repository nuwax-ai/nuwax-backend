package com.xspaceagi.im.web.controller;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.application.IComputerFileApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.application.ImChannelConfigApplicationService;
import com.xspaceagi.im.application.WeworkAgentApplicationService;
import com.xspaceagi.im.application.dto.ImChannelConfigDto;
import com.xspaceagi.im.infra.enums.ImChatTypeEnum;
import com.xspaceagi.im.infra.enums.ImTargetTypeEnum;
import com.xspaceagi.im.web.service.WeworkAttachmentService;
import com.xspaceagi.im.web.service.ImFileShareService;
import com.xspaceagi.im.web.util.ImOutputProcessor;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
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
import me.chanjar.weixin.cp.config.impl.WxCpDefaultConfigImpl;
import me.chanjar.weixin.cp.util.crypto.WxCpCryptUtil;
import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.StreamUtils;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 企业微信智能机器人 IM 集成控制器。
 * <p>
 * 配置说明：
 * 1. 登录企业微信管理后台：https://work.weixin.qq.com/wework_admin/frame#/aiHelper/list?from=manage_tools
 * 2. 创建/配置智能机器人，设置「接收消息」回调 URL：
 *    智能机器人：https://你的域名/api/im/wework/webhook?token=你的Token值
 *    自建应用：https://你的域名/api/im/wework/app-callback?token=Token值
 *    重要：在URL中添加token参数可以避免遍历所有配置，大幅提升性能
 * 3. 配置 Token、EncodingAESKey（与下方 WEWORK_BOT_CONFIG 一致）
 * 4. 用户私聊或群里 @机器人 发送的消息会回调到此接口
 * <p>
 * 工作原理：
 * 企业微信智能机器人webhook不允许被动回复，只能通过response_url主动推送。
 * 统一采用异步方式：Webhook收到消息 → 立即返回200 OK → 异步执行智能体 → 推送最终结果
 * <p>
 */
@RestController
@RequestMapping("/api/im/wework")
@Slf4j
@Tag(name = "企业微信 IM 集成")
public class ImWeworkController {

    @Resource
    private RedisUtil redisUtil;
    @Resource
    private ThreadPoolTaskExecutor asyncTaskExecutor;
    @Resource
    private WeworkAttachmentService weworkAttachmentService;
    @Resource
    private WeworkAgentApplicationService weworkAgentApplicationService;
    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;
    @Resource
    private ImChannelConfigApplicationService imChannelConfigApplicationService;
    @Resource
    private ImFileShareService imFileShareService;
    @Resource
    private IComputerFileApplicationService computerFileApplicationService;

    /**
     * 企业微信消息幂等 Redis Key 前缀
     * 用于在 Redis 中存储已处理的消息标识，防止重复处理
     */
    private static final String WEWORK_MSG_PREFIX = "wework:msg:";

    /**
     * 企业微信消息幂等 Redis Key 过期时间（秒）
     * 用于控制幂等 Key 的有效期，5 分钟后自动删除
     * 超过此时间的消息不再认为是重复消息
     */
    private static final int WEWORK_MSG_TTL_SECONDS = 300;

    /**
     * 自建应用消息接收回调
     * 配置 url：/api/im/wework/app-callback?token=Token值

     * 请求体：XML（外层 Encrypt 字段）
     * 回复方式：必须在同一个 HTTP 响应里返回「加密后的 XML 被动消息」
     * 回复流程：解密 XML → 调智能体 → 再加密 XML 回复
     * 
     * 根据企业微信文档，对「接收消息与事件」的回调：服务端 必须在 5 秒内 返回加密后的 XML 被动回复；
     * 超过 5 秒，哪怕后来返回了正常的 XML，企业微信也会当成失败，不展示这条被动消息，只按失败重试一次。
     * 
     * 所以采用：直接返回200，后续使用主动发消息接口进行异步回复。
     */
    @RequestMapping(value = "/app-callback", method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(summary = "企业微信自建应用消息接收回调", hidden = true)
    public void appCallback(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String msgSignature = request.getParameter("msg_signature");
        String timestamp = request.getParameter("timestamp");
        String nonce = request.getParameter("nonce");
        String echostr = request.getParameter("echostr");
        String tokenFromUrl = request.getParameter("token"); // 从URL参数中获取token

        log.info("企业微信自建应用回调: method={}, msgSignature={}, timestamp={}, nonce={}, tokenFromUrl={}",
                request.getMethod(), msgSignature != null ? "***" : null, timestamp, nonce,
                tokenFromUrl != null ? "***" : null);

        // GET: URL 验证
        if (RequestMethod.GET.name().equalsIgnoreCase(request.getMethod())) {
            if (StringUtils.isBlank(echostr)) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            // 优先使用URL参数中的token直接查询配置，避免遍历所有配置
            WeworkBotConfig appConfig = resolveAppConfigFromToken(tokenFromUrl, ImTargetTypeEnum.APP);
            if (appConfig == null) {
                // 如果URL中没有token参数，回退到签名验证方式（向后兼容）
                log.info("URL中未提供token参数，使用签名验证方式查找配置");
                appConfig = resolveBotConfigBySignature(echostr, timestamp, nonce, msgSignature, ImTargetTypeEnum.APP);
            }

            if (appConfig == null) {
                log.warn("企业微信自建应用 URL 验证签名失败，无法匹配到任何应用配置");
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            // 验证签名
            if (!verifyWeworkSignature(appConfig.getToken(), timestamp, nonce, echostr, msgSignature)) {
                log.warn("企业微信自建应用 URL 验证签名失败: agentId={}", appConfig.getAgentId());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            WxCpCryptUtil cryptUtil = getCryptUtil(appConfig);
            try {
                String plainEchostr = cryptUtil.decrypt(echostr);
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write(plainEchostr);
                log.info("企业微信自建应用 URL 验证成功: agentId={}", appConfig.getAgentId());
            } catch (Exception e) {
                log.warn("企业微信自建应用 URL 验证解密失败: {}", e.getMessage());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            }
            return;
        }

        // POST: 接收消息
        byte[] bodyBytes = StreamUtils.copyToByteArray(request.getInputStream());
        if (bodyBytes.length == 0) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        String encryptedBody = normalizeRequestBody(bodyBytes);
        String encryptPayload = extractEncryptPayload(encryptedBody);
        if (encryptPayload == null) {
            log.warn("企业微信自建应用 POST body 无法解析出 Encrypt 内容");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        // 优先使用URL参数中的token直接查询配置，避免遍历所有配置
        WeworkBotConfig appConfig = resolveAppConfigFromToken(tokenFromUrl, ImTargetTypeEnum.APP);
        if (appConfig == null) {
            // 如果URL中没有token参数，回退到签名验证方式（向后兼容）
            log.info("URL中未提供token参数，使用签名验证方式查找配置");
            appConfig = resolveBotConfigBySignature(encryptPayload, timestamp, nonce, msgSignature, ImTargetTypeEnum.APP);
        }

        if (appConfig == null) {
            log.warn("企业微信自建应用消息签名验证失败，无法匹配到任何应用配置");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        // 验证签名
        if (!verifyWeworkSignature(appConfig.getToken(), timestamp, nonce, encryptPayload, msgSignature)) {
            log.warn("企业微信自建应用消息签名验证失败: agentId={}", appConfig.getAgentId());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        WxCpCryptUtil cryptUtil = getCryptUtil(appConfig);

        // URL 验证
        if (RequestMethod.GET.name().equalsIgnoreCase(request.getMethod())) {
            if (StringUtils.isBlank(echostr)) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }
            try {
                String plainEchostr = cryptUtil.decrypt(echostr);
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write(plainEchostr);
            } catch (Exception e) {
                log.warn("企业微信自建应用 URL 验证解密失败: {}", e.getMessage());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            }
            return;
        }

        // POST 消息解密
        String decryptedXml;
        try {
            decryptedXml = cryptUtil.decrypt(encryptPayload);
        } catch (Exception e) {
            log.warn("企业微信自建应用消息解密失败: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        // 简单 XML 解析：提取 AgentID / FromUserName / ToUserName / MsgType / Content / MsgId / MediaId
        String agentId = extractXmlTag(decryptedXml, "AgentID");
        String toUserName = extractXmlTag(decryptedXml, "ToUserName");
        String fromUserName = extractXmlTag(decryptedXml, "FromUserName");
        String msgType = extractXmlTag(decryptedXml, "MsgType");
        String content = extractXmlTag(decryptedXml, "Content");
        String msgId = extractXmlTag(decryptedXml, "MsgId");
        String mediaId = extractXmlTag(decryptedXml, "MediaId");

        log.info("企业微信自建应用收到消息: agentId={}, fromUserName={}, toUserName={}, msgType={}, msgId={}, content={}",
                agentId, fromUserName, toUserName, msgType, msgId, content);

        // 幂等去重（复用机器人通道同一前缀），只影响是否触发异步智能体，不影响当前请求的被动回复
        boolean firstSeen = true;
        if (StringUtils.isNotBlank(msgId)) {
            String dedupKey = WEWORK_MSG_PREFIX + msgId;
            if (redisUtil != null && redisUtil.get(dedupKey) != null) {
                log.info("企业微信自建应用重复消息: msgid={}", msgId);
                firstSeen = false;
            } else if (redisUtil != null) {
                redisUtil.set(dedupKey, "1", WEWORK_MSG_TTL_SECONDS);
            }
        }

        // 自建应用场景：统一视为单聊（智能体里的会话模型）
        String chatType = "single";
        String chatId = null;

        // 首次收到该消息时，异步执行智能体并通过企业微信自建应用主动推送结果
        if (firstSeen) {
            final String finalFromUserName = fromUserName;
            final String finalMsgType = msgType != null ? msgType.toLowerCase() : "";
            final String finalContent = content;
            final String finalMediaId = mediaId;
            final WeworkBotConfig finalConfig = appConfig;

            asyncTaskExecutor.execute(() -> {
                try {
                    // 提取用户消息与附件（完整逻辑放在异步，避免阻塞被动回复）
                    String userMessage = "";
                    List<AttachmentDto> attachments = new ArrayList<>();

                    if ("text".equals(finalMsgType)) {
                        userMessage = finalContent != null ? finalContent : "";
                    } else if ("image".equals(finalMsgType) || "file".equals(finalMsgType)
                            || "voice".equals(finalMsgType) || "video".equals(finalMsgType)) {
                        if ("image".equals(finalMsgType)) {
                            userMessage = "[用户发送了图片]";
                        } else if ("file".equals(finalMsgType)) {
                            userMessage = "[用户发送了文件]";
                        } else if ("voice".equals(finalMsgType)) {
                            userMessage = "[用户发送了语音]";
                        } else if ("video".equals(finalMsgType)) {
                            userMessage = "[用户发送了视频]";
                        }

                        if (StringUtils.isNotBlank(finalMediaId)) {
                            try {
                                TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(finalConfig.getTenantId());
                                var attachmentResult = weworkAttachmentService.downloadAndUpload(
                                        finalConfig.getCorpId(), finalConfig.getCorpSecret(), finalMediaId, finalMsgType,
                                        finalConfig.getTenantId(), tenantConfig);
                                attachments = attachmentResult.getAttachments();
                                if (!attachmentResult.getUnsupportedKeys().isEmpty()) {
                                    userMessage = userMessage + "\n\n[系统提示：部分附件类型不支持下载，请发送具体文件。]";
                                }
                            } catch (Exception e) {
                                log.error("企业微信自建应用附件处理异常(异步): mediaId={}, msgType={}", finalMediaId, finalMsgType, e);
                                userMessage = userMessage + "\n\n[系统提示：附件处理失败，请稍后重试。]";
                            }
                        }
                    }

                    if (StringUtils.isBlank(userMessage)) {
                        userMessage = "[用户发送了非文本内容]";
                    }

                    // 调用智能体
                    String processedText = handleWeworkAgentMessage(
                            finalFromUserName, userMessage, attachments, chatType, chatId, ImTargetTypeEnum.APP.getCode(), finalConfig);

                    // 通过自建应用主动推送消息给用户
                    sendAppTextMessage(finalConfig, finalFromUserName, processedText);
                    log.info("企业微信自建应用异步智能体执行完成，已通过应用消息推送结果");
                } catch (Exception e) {
                    log.error("企业微信自建应用异步智能体执行异常: fromUserName={}", finalFromUserName, e);
                    try {
                        sendAppTextMessage(finalConfig, finalFromUserName,
                                "执行异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
                    } catch (Exception ignored) {
                        // 忽略再次发送失败
                    }
                }
            });
        }

        // 不做被动 XML 回复，直接返回 200 空包体，实际结果通过主动发消息接口异步推送
        response.setStatus(HttpServletResponse.SC_OK);
    }

    /**
     * 智能机器人 /webhook
     * 配置 url：/api/im/wework/webhook?token=Token值
     * 请求体：JSON，内层还有一层 encrypt
     * 回复方式：不能被动 XML 回复，只能用 response_url 主动 POST markdown
     * 回复流程：立即 200 OK → 异步调用智能体 → response_url 推送结果
     */
    @RequestMapping(value = "/webhook", method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(summary = "企业微信智能机器人消息接收 Webhook", hidden = true)
    public void webhook(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String msgSignature = request.getParameter("msg_signature");
        String timestamp = request.getParameter("timestamp");
        String nonce = request.getParameter("nonce");
        String echostr = request.getParameter("echostr");
        String tokenFromUrl = request.getParameter("token"); // 从URL参数中获取token

        log.info("企业微信 Webhook 请求: method={}, msgSignature={}, timestamp={}, nonce={}, tokenFromUrl={}",
                request.getMethod(), msgSignature != null ? "***" : null, timestamp, nonce,
                tokenFromUrl != null ? "***" : null);

        byte[] bodyBytes = StreamUtils.copyToByteArray(request.getInputStream());
        String encryptedBody = normalizeRequestBody(bodyBytes);

        // GET: URL 验证
        if (RequestMethod.GET.name().equalsIgnoreCase(request.getMethod())) {
            if (StringUtils.isBlank(echostr)) {
                response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                return;
            }

            // 优先使用URL参数中的token直接查询配置，避免遍历所有配置
            WeworkBotConfig config = resolveBotConfigFromToken(tokenFromUrl);
            if (config == null) {
                // 如果URL中没有token参数，回退到签名验证方式（向后兼容）
                log.info("URL中未提供token参数，使用签名验证方式查找配置");
                String encryptPayload = echostr; // echostr 就是加密内容
                config = resolveBotConfigBySignature(encryptPayload, timestamp, nonce, msgSignature, ImTargetTypeEnum.BOT);
            }

            if (config == null) {
                log.warn("企业微信 URL 验证签名失败，无法匹配到任何机器人配置");
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            // 验证签名
            if (!verifyWeworkSignature(config.getToken(), timestamp, nonce, echostr, msgSignature)) {
                log.warn("企业微信 URL 验证签名失败: aibotId={}", config.getAibotId());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                return;
            }

            WxCpCryptUtil cryptUtil = getCryptUtil(config);
            try {
                String plainEchostr = cryptUtil.decrypt(echostr);
                response.setStatus(HttpServletResponse.SC_OK);
                response.setContentType("text/plain;charset=UTF-8");
                response.getWriter().write(plainEchostr);
                log.info("企业微信智能机器人 URL 验证成功: aibotId={}", config.getAibotId());
            } catch (Exception e) {
                log.warn("企业微信 URL 验证解密失败: {}", e.getMessage());
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            }
            return;
        }

        // POST: 接收消息
        if (bodyBytes.length == 0) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        String encryptPayload = extractEncryptPayload(encryptedBody);
        if (encryptPayload == null) {
            log.warn("企业微信 POST body 无法解析出 Encrypt 内容");
            response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            return;
        }

        // 优先使用URL参数中的token直接查询配置，避免遍历所有配置
        WeworkBotConfig botConfig = resolveBotConfigFromToken(tokenFromUrl);
        if (botConfig == null) {
            // 如果URL中没有token参数，回退到签名验证方式（向后兼容）
            log.info("URL中未提供token参数，使用签名验证方式查找配置");
            botConfig = resolveBotConfigBySignature(encryptPayload, timestamp, nonce, msgSignature, ImTargetTypeEnum.BOT);
        }

        if (botConfig == null) {
            log.warn("企业微信消息签名验证失败，无法匹配到配置");
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        // 验证签名
        if (!verifyWeworkSignature(botConfig.getToken(), timestamp, nonce, encryptPayload, msgSignature)) {
            log.warn("企业微信消息签名验证失败: aibotId={}", botConfig.getAibotId());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        WxCpCryptUtil cryptUtil = getCryptUtil(botConfig);

        String decryptedJson;
        try {
            decryptedJson = cryptUtil.decrypt(encryptPayload);
        } catch (Exception e) {
            log.warn("企业微信消息解密失败: {}", e.getMessage());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            return;
        }

        JSONObject body = JSON.parseObject(decryptedJson);
        if (body == null) {
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        String msgtype = body.getString("msgtype");
        String msgid = body.getString("msgid");

        // 幂等去重
        if (StringUtils.isNotBlank(msgid)) {
            String dedupKey = WEWORK_MSG_PREFIX + msgid;
            if (redisUtil != null && redisUtil.get(dedupKey) != null) {
                log.info("企业微信重复消息跳过: msgid={}", msgid);
                response.setStatus(HttpServletResponse.SC_OK);
                return;
            }
            if (redisUtil != null) {
                redisUtil.set(dedupKey, "1", WEWORK_MSG_TTL_SECONDS);
            }
        }

        // 从解密后的消息中获取 aibotid 用于日志记录（配置已通过签名验证确定）
        String aibotIdFromBody = body.getString("aibotid");
        log.info("企业微信消息: msgtype={}, msgid={}, aibotid={}", msgtype, msgid, aibotIdFromBody);

        String userMessage = extractUserMessage(body, msgtype);

        // 处理图片和文件附件（企业微信智能机器人提供临时 URL，需要下载解密后上传到项目存储）
        List<AttachmentDto> attachments = new ArrayList<>();
        String attachmentUrl = null;

        log.info("企业微信消息类型检查: msgtype={}, body={}", msgtype, body.toJSONString());
        log.info("完整的 image 对象字段: {}", body.getJSONObject("image") != null ?
                body.getJSONObject("image").entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .reduce((a, b) -> a + ", " + b)
                        .orElse("null") : "null");

        if ("mixed".equals(msgtype)) {
            // 图文混排消息：遍历 msg_item 数组提取附件
            JSONObject mixed = body.getJSONObject("mixed");
            if (mixed != null && mixed.containsKey("msg_item")) {
                com.alibaba.fastjson2.JSONArray msgItems = mixed.getJSONArray("msg_item");
                if (msgItems != null && !msgItems.isEmpty()) {
                    for (int i = 0; i < msgItems.size(); i++) {
                        JSONObject item = msgItems.getJSONObject(i);
                        if (item == null) continue;

                        String itemMsgType = item.getString("msgtype");
                        if ("image".equals(itemMsgType)) {
                            JSONObject imageObj = item.getJSONObject("image");
                            if (imageObj != null) {
                                String url = imageObj.getString("url");
                                if (StringUtils.isNotBlank(url)) {
                                    log.info("企业微信mixed消息中的图片: url={}", url);
                                    attachmentUrl = url;
                                    break; // 只处理第一个图片
                                }
                            }
                        } else if ("file".equals(itemMsgType)) {
                            JSONObject fileObj = item.getJSONObject("file");
                            if (fileObj != null) {
                                String url = fileObj.getString("url");
                                if (StringUtils.isNotBlank(url)) {
                                    log.info("企业微信mixed消息中的文件: url={}", url);
                                    attachmentUrl = url;
                                    break; // 只处理第一个文件
                                }
                            }
                        }
                    }
                }
            }
        } else if ("image".equals(msgtype)) {
            JSONObject imageObj = body.getJSONObject("image");
            log.info("企业微信图片对象: imageObj={}", imageObj != null ? imageObj.toJSONString() : "null");
            if (imageObj != null) {
                // 企业微信智能机器人图片使用 url 字段（临时 COS 签名 URL，5分钟有效）
                String url = imageObj.getString("url");
                if (StringUtils.isNotBlank(url)) {
                    log.info("企业微信图片使用url: {}", url);
                    attachmentUrl = url;
                } else {
                    log.warn("企业微信图片对象无url字段");
                }
            }
        } else if ("file".equals(msgtype)) {
            JSONObject fileObj = body.getJSONObject("file");
            log.info("企业微信文件对象: fileObj={}", fileObj != null ? fileObj.toJSONString() : "null");
            if (fileObj != null) {
                // 企业微信智能机器人文件使用 url 字段（临时 COS 签名 URL，5分钟有效）
                String url = fileObj.getString("url");
                if (StringUtils.isNotBlank(url)) {
                    log.info("企业微信文件使用url: {}", url);
                    attachmentUrl = url;
                } else {
                    log.warn("企业微信文件对象无url字段");
                }
            }
        }

        // 只有在没有使用 media_id 方式处理成功时，才尝试使用 url 方式
        if (StringUtils.isNotBlank(attachmentUrl) && attachments.isEmpty()) {
            log.info("企业微信收到附件消息: msgtype={}, url={}", msgtype, attachmentUrl);
            try {
                TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(botConfig.getTenantId());
                var attachmentResult = weworkAttachmentService.downloadAndUploadFromUrl(
                        attachmentUrl, msgtype, botConfig.getEncodingAesKey(), botConfig.getTenantId(), tenantConfig);
                attachments = attachmentResult.getAttachments();
                log.info("企业微信附件处理完成: url={}, 附件数量={}", attachmentUrl, attachments.size());
            } catch (Exception e) {
                log.error("企业微信附件处理异常: url={}", attachmentUrl, e);
            }
        } else if (!attachments.isEmpty()) {
            log.info("企业微信附件已通过 media_id 处理完成，跳过 url 处理: msgtype={}, 附件数量={}", msgtype, attachments.size());
        } else {
            log.info("企业微信消息无附件: msgtype={}", msgtype);
        }

        if (StringUtils.isBlank(userMessage)) {
            userMessage = "[用户发送了非文本内容]";
        }

        String senderId = body.getJSONObject("from") != null
                ? body.getJSONObject("from").getString("userid")
                : "unknown";
        String chatType = body.getString("chattype");
        String chatId = body.getString("chatid");
        String responseUrl = body.getString("response_url");

        if (StringUtils.isBlank(responseUrl)) {
            log.warn("企业微信消息无 response_url: msgid={}", msgid);
            response.setStatus(HttpServletResponse.SC_OK);
            return;
        }

        log.info("处理企业微信消息: senderId={}, chatType={}, chatId={}, content={}",
                senderId, chatType, chatId, userMessage);

        // 企业微信智能机器人webhook不允许被动回复，统一使用异步+主动推送方式
        // 注意：企业微信的response_url可能只能使用一次，所以不能先推送"正在思考..."再推送结果
        // 流程：webhook立即返回200 OK → 异步执行智能体 → 推送最终结果
        response.setStatus(HttpServletResponse.SC_OK);

        WeworkBotConfig finalConfig = botConfig;
        String finalResponseUrl = responseUrl;
        String finalSenderId = senderId;
        String finalUserMessage = userMessage;
        String finalChatType = chatType;
        String finalChatId = chatId;
        List<AttachmentDto> finalAttachments = new ArrayList<>(attachments);

        /**
         * Webhook 超时限制：企业微信 webhook 有5-10秒超时
         * 智能体执行时间长
         * 必须立即返回：返回 200 OK 释放 HTTP 连接，避免企业微信重试
         */
        asyncTaskExecutor.execute(() -> {
            try {
                // 执行智能体并处理输出内容：替换文件标签、规范化 markdown 等
                String processedText = handleWeworkAgentMessage(
                        finalSenderId, finalUserMessage, finalAttachments, finalChatType, finalChatId, ImTargetTypeEnum.BOT.getCode(), finalConfig);

                // 推送最终结果
                replyByResponseUrl(finalResponseUrl, processedText);
                log.info("企业微信异步智能体执行完成，已主动推送最终结果");
            } catch (Exception e) {
                log.error("企业微信智能体执行异常: senderId={}", finalSenderId, e);
                replyByResponseUrl(finalResponseUrl, "执行异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误"));
            }
        });
    }


    private String extractUserMessage(JSONObject body, String msgtype) {
        if (body == null) return "";
        if ("text".equals(msgtype)) {
            JSONObject text = body.getJSONObject("text");
            return text != null ? text.getString("content") : "";
        }
        if ("voice".equals(msgtype)) {
            JSONObject voice = body.getJSONObject("voice");
            return voice != null ? voice.getString("content") : "";
        }
        if ("mixed".equals(msgtype)) {
            JSONObject mixed = body.getJSONObject("mixed");
            if (mixed == null) return "";
            JSONArray items = mixed.getJSONArray("msg_item");
            if (items == null || items.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < items.size(); i++) {
                JSONObject item = items.getJSONObject(i);
                if (item == null) continue;
                if ("text".equals(item.getString("msgtype"))) {
                    JSONObject t = item.getJSONObject("text");
                    if (t != null && StringUtils.isNotBlank(t.getString("content"))) {
                        if (sb.length() > 0) sb.append("\n");
                        sb.append(t.getString("content"));
                    }
                }
            }
            return sb.toString();
        }
        if ("image".equals(msgtype) || "file".equals(msgtype)) {
            return "[用户发送了" + ("image".equals(msgtype) ? "图片" : "文件") + "]";
        }
        return "";
    }

    /**
     * 通过 response_url 主动回复 markdown 消息
     */
    private void replyByResponseUrl(String responseUrl, String content) {
        if (StringUtils.isBlank(responseUrl) || content == null) return;
        try {
            JSONObject payload = new JSONObject();
            payload.put("msgtype", "markdown");
            JSONObject markdown = new JSONObject();
            markdown.put("content", content);
            payload.put("markdown", markdown);

            URL url = new URL(responseUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(30000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toJSONString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                log.info("企业微信主动回复成功: responseUrl={}", responseUrl);
            } else {
                log.warn("企业微信主动回复失败: code={}, response={}", code, conn.getResponseMessage());
            }
        } catch (Exception e) {
            log.error("企业微信主动回复异常: responseUrl={}", responseUrl, e);
        }
    }

    /**
     * 企业微信回调签名验证：msg_signature = sha1(sort(token, timestamp, nonce, encrypt) 拼接后)。
     */
    private static boolean verifyWeworkSignature(String token, String timestamp, String nonce, String encrypt, String msgSignature) {
        if (StringUtils.isBlank(token) || StringUtils.isBlank(msgSignature)) return false;
        try {
            String[] arr = new String[]{token, timestamp == null ? "" : timestamp, nonce == null ? "" : nonce, encrypt == null ? "" : encrypt};
            Arrays.sort(arr);
            StringBuilder sb = new StringBuilder();
            for (String s : arr) sb.append(s);
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b & 0xff));
            return hex.toString().equals(msgSignature == null ? "" : msgSignature.toLowerCase());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 从 POST body 中提取密文，不依赖 XML 解析，避免「前言中不允许有内容」。
     * 支持 XML 格式（&lt;Encrypt&gt;&lt;![CDATA[...]]&gt;&lt;/Encrypt&gt;）和 JSON 格式（encrypt 字段）。
     */
    private static String extractEncryptPayload(String body) {
        if (body == null || body.isEmpty()) return null;
        String s = body.trim();
        if (s.startsWith("{")) {
            JSONObject json = JSON.parseObject(s);
            return json != null ? json.getString("encrypt") : null;
        }
        int cdataStart = s.indexOf("<Encrypt><![CDATA[");
        if (cdataStart >= 0) {
            int start = cdataStart + "<Encrypt><![CDATA[".length();
            int end = s.indexOf("]]></Encrypt>", start);
            if (end > start) return s.substring(start, end);
        }
        int plainStart = s.indexOf("<Encrypt>");
        if (plainStart >= 0) {
            int start = plainStart + "<Encrypt>".length();
            int end = s.indexOf("</Encrypt>", start);
            if (end > start) return s.substring(start, end);
        }
        return null;
    }

    /**
     * 去除 BOM 和首尾空白，避免 XML 解析报「前言中不允许有内容」。
     */
    private static String normalizeRequestBody(byte[] bodyBytes) {
        if (bodyBytes == null || bodyBytes.length == 0) {
            return "";
        }
        int start = 0;
        if (bodyBytes.length >= 3
                && bodyBytes[0] == (byte) 0xEF
                && bodyBytes[1] == (byte) 0xBB
                && bodyBytes[2] == (byte) 0xBF) {
            start = 3;
        }
        return new String(bodyBytes, start, bodyBytes.length - start, StandardCharsets.UTF_8).trim();
    }

    /**
     * 通过签名验证解析当前请求对应的企业微信配置（智能机器人或自建应用）
     * 优化策略：分页查询配置并验证签名，匹配到即停止
     *
     * 注意：建议在回调URL中添加token参数以避免分页查询：
     * - 智能机器人：/api/im/wework/webhook?token=xxx
     * - 自建应用：/api/im/wework/app-callback?token=xxx
     */
    private WeworkBotConfig resolveBotConfigBySignature(String encryptPayload, String timestamp,
                                                          String nonce, String msgSignature, ImTargetTypeEnum targetType) {
        // 分页查询配置并验证签名，避免一次加载所有配置
        final int PAGE_SIZE = 100; // 每页查询100条配置
        int offset = 0;
        int totalChecked = 0;
        boolean isBotConfig = targetType == ImTargetTypeEnum.BOT;

        while (true) {
            // 分页查询配置
            List<ImChannelConfigDto> configs;
            if (isBotConfig) {
                configs = imChannelConfigApplicationService.listWeworkBotConfigsByPage(offset, PAGE_SIZE);
            } else {
                configs = imChannelConfigApplicationService.listWeworkAppConfigsByPage(offset, PAGE_SIZE);
            }

            // 如果没有更多配置了
            if (configs.isEmpty()) {
                break;
            }

            // 遍历当前页的配置，用每个配置的 token 验证签名
            for (ImChannelConfigDto cfg : configs) {
                if (cfg == null) {
                    continue;
                }

                totalChecked++;
                String token;
                try {
                    if (isBotConfig) {
                        if (cfg.getWeworkBot() == null) continue;
                        token = cfg.getWeworkBot().getToken();
                    } else {
                        if (cfg.getWeworkApp() == null) continue;
                        token = cfg.getWeworkApp().getToken();
                    }
                    if (StringUtils.isBlank(token)) {
                        continue;
                    }

                    // 验证签名
                    if (!verifyWeworkSignature(token, timestamp, nonce, encryptPayload, msgSignature)) {
                        continue;
                    }

                    // 签名验证成功！
                    String configIdentifier = isBotConfig ? cfg.getWeworkBot().getAibotId() : cfg.getWeworkApp().getAgentId();
                    log.info("企业微信{}消息匹配到配置: token={}, identifier={}, tenantId={}, 检查了{}个配置",
                            isBotConfig ? "智能机器人" : "自建应用",
                            token, configIdentifier, cfg.getTenantId(), totalChecked);
                    return buildWeworkBotConfig(cfg, targetType);
                } catch (Exception e) {
                    log.warn("处理企业微信{}配置异常: error={}",
                            isBotConfig ? "智能机器人" : "自建应用",
                            e.getMessage());
                }
            }

            // 如果当前页查询的数量少于 PAGE_SIZE，说明已经是最后一页了
            if (configs.size() < PAGE_SIZE) {
                break;
            }

            // 继续查询下一页
            offset += PAGE_SIZE;
        }

        log.warn("企业微信{}消息无法匹配到任何配置，共检查了 {} 个配置",
                isBotConfig ? "智能机器人" : "自建应用", totalChecked);
        return null;
    }

    /**
     * 从URL参数中的token直接查询配置，避免遍历所有配置
     *
     * @param tokenFromUrl URL参数中的token
     * @return 匹配的配置，如果token为空或未找到返回null
     */
    private WeworkBotConfig resolveBotConfigFromToken(String tokenFromUrl) {
        if (StringUtils.isBlank(tokenFromUrl)) {
            return null;
        }

        try {
            ImChannelConfigDto configDto = imChannelConfigApplicationService.getWeworkBotConfigByToken(tokenFromUrl);
            if (configDto == null || configDto.getWeworkBot() == null) {
                log.warn("根据URL参数中的token未找到智能机器人配置: token={}", tokenFromUrl);
                return null;
            }

            log.info("企业微信智能机器人通过URL参数token直接匹配到配置: token={}, aibotId={}, tenantId={}",
                    tokenFromUrl, configDto.getWeworkBot().getAibotId(), configDto.getTenantId());
            return buildWeworkBotConfig(configDto, ImTargetTypeEnum.BOT);
        } catch (Exception e) {
            log.warn("根据URL参数中的token查询智能机器人配置异常: token={}, error={}", tokenFromUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 从URL参数中的token直接查询自建应用配置，避免遍历所有配置
     *
     * @param tokenFromUrl URL参数中的token
     * @return 匹配的配置，如果token为空或未找到返回null
     */
    private WeworkBotConfig resolveAppConfigFromToken(String tokenFromUrl, ImTargetTypeEnum targetType) {
        if (StringUtils.isBlank(tokenFromUrl)) {
            return null;
        }

        try {
            ImChannelConfigDto configDto = null;
            if (targetType == ImTargetTypeEnum.APP) {
                configDto = imChannelConfigApplicationService.getWeworkAppConfigByToken(tokenFromUrl);
            } else {
                configDto = imChannelConfigApplicationService.getWeworkBotConfigByToken(tokenFromUrl);
            }
            if (configDto == null || configDto.getWeworkApp() == null) {
                log.warn("根据URL参数中的token未找到配置: targetType={}, token={}", targetType.getCode(), tokenFromUrl);
                return null;
            }
            log.info("企业微信通过URL参数token匹配到配置: targetType={}, token={}", targetType.getCode(), tokenFromUrl);

            return buildWeworkBotConfig(configDto, targetType);
        } catch (Exception e) {
            log.warn("根据URL参数中的token查询自建应用配置异常: token={}, error={}", tokenFromUrl, e.getMessage());
            return null;
        }
    }

    /**
     * 根据配置类型构建 WeworkBotConfig 对象
     */
    private WeworkBotConfig buildWeworkBotConfig(ImChannelConfigDto matchedConfig, ImTargetTypeEnum configType) {
        WeworkBotConfig config;
        if (configType == ImTargetTypeEnum.BOT) {
            ImChannelConfigDto.WeworkBotConfig bot = matchedConfig.getWeworkBot();
            config = WeworkBotConfig.builder()
                    .aibotId(bot.getAibotId())
                    .corpId(bot.getCorpId())
                    .corpSecret(bot.getCorpSecret())
                    .token(bot.getToken())
                    .encodingAesKey(bot.getEncodingAesKey())
                    .tenantId(matchedConfig.getTenantId())
                    .userId(matchedConfig.getUserId())
                    .nuwaxAgentId(matchedConfig.getAgentId())
                    .build();
        } else {
            ImChannelConfigDto.WeworkAppConfig app = matchedConfig.getWeworkApp();
            config = WeworkBotConfig.builder()
                    .agentId(app.getAgentId())
                    .corpId(app.getCorpId())
                    .corpSecret(app.getCorpSecret())
                    .token(app.getToken())
                    .encodingAesKey(app.getEncodingAesKey())
                    .tenantId(matchedConfig.getTenantId())
                    .userId(matchedConfig.getUserId())
                    .nuwaxAgentId(matchedConfig.getAgentId())
                    .build();
        }
        return config;
    }

    private WxCpCryptUtil getCryptUtil(WeworkBotConfig config) {
        WxCpDefaultConfigImpl wxConfig = new WxCpDefaultConfigImpl();
        wxConfig.setCorpId(config.getCorpId());
        wxConfig.setToken(config.getToken());
        wxConfig.setAesKey(config.getEncodingAesKey());
        return new WxCpCryptUtil(wxConfig);
    }

    /**
     * 从简单 XML 文本中提取指定标签内容（支持 <![CDATA[]]>）
     */
    private static String extractXmlTag(String xml, String tagName) {
        if (xml == null || tagName == null) {
            return null;
        }
        String startCdata = "<" + tagName + "><![CDATA[";
        String endCdata = "]]></" + tagName + ">";
        int start = xml.indexOf(startCdata);
        if (start >= 0) {
            start += startCdata.length();
            int end = xml.indexOf(endCdata, start);
            if (end > start) {
                return xml.substring(start, end);
            }
        }
        String startPlain = "<" + tagName + ">";
        String endPlain = "</" + tagName + ">";
        start = xml.indexOf(startPlain);
        if (start >= 0) {
            start += startPlain.length();
            int end = xml.indexOf(endPlain, start);
            if (end > start) {
                return xml.substring(start, end);
            }
        }
        return null;
    }

    /**
     * 处理智能体输出内容，使用统一的处理工具
     */
    private String processAgentOutput(String text, Long conversationId, Long nuwaxAgentId, WeworkBotConfig config) {
        Long tenantId = config != null ? config.getTenantId() : null;
        TenantConfigDto tenantConfig = tenantId != null ? tenantConfigApplicationService.getTenantConfig(tenantId) : null;
        String domain = tenantConfig != null ? tenantConfig.getSiteUrl() : null;
        if (domain != null) {
            domain = domain.trim();
            if (!domain.startsWith("http://") && !domain.startsWith("https://")) {
                domain = "https://" + domain;
            }
            if (domain.endsWith("/")) {
                domain = domain.substring(0, domain.length() - 1);
            }
        }
        Long userId = config != null ? config.getUserId() : null;
        return ImOutputProcessor.processOutput(text, conversationId, nuwaxAgentId, domain, userId, tenantId,
                imFileShareService, computerFileApplicationService);
    }

    /**
     * 公共处理：根据 senderId / userMessage / 附件 / 会话信息 调用企业微信智能体，并做统一输出处理。
     * 被 /webhook 和 /app-callback 复用。
     */
    private String handleWeworkAgentMessage(String senderId,
                                            String userMessage,
                                            List<AttachmentDto> attachments,
                                            String chatType,
                                            String chatId,
                                            String targetType,
                                            WeworkBotConfig config) {
        try {
            String sessionName = resolveSessionName(senderId, chatType, chatId, config);
            WeworkAgentApplicationService.AgentExecuteResultWithConv result =
                    weworkAgentApplicationService.executeAgentWithConv(
                            senderId,
                            userMessage,
                            attachments != null ? attachments : new ArrayList<>(),
                            chatType,
                            chatId,
                            targetType,
                            config.getTenantId(),
                            config.getUserId(),
                            config.getNuwaxAgentId(),
                            sessionName);

            log.info("企业微信智能体执行完成: senderId={}, chatType={}, chatId={}, conversationId={}, agentId={}",
                    senderId, chatType, chatId, result.getConversationId(), result.getAgentId());

            String processedText = processAgentOutput(result.getText(), result.getConversationId(), result.getAgentId(), config);
            if (StringUtils.isBlank(processedText)) {
                processedText = "已处理";
            }
            return processedText;
        } catch (Exception e) {
            log.error("企业微信智能体执行异常: senderId={}", senderId, e);
            return "执行异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误");
        }
    }

    /**
     * 企业微信会话展示名获取（不抛异常，取不到就回退到 senderId/chatId）。
     * - 单聊：优先 user/get 取用户名（name）
     * - 群聊：优先 appchat/get 取群名（name）
     */
    private String resolveSessionName(String senderId, String chatType, String chatId, WeworkBotConfig config) {
        try {
            if (config == null) {
                return StringUtils.isNotBlank(senderId) ? senderId : chatId;
            }
            String accessToken = getCorpAccessToken(config.getCorpId(), config.getCorpSecret());
            if (StringUtils.isBlank(accessToken)) {
                return StringUtils.isNotBlank(senderId) ? senderId : chatId;
            }
            // 群聊：chatId 为 appchat id
            if (ImChatTypeEnum.fromCode(chatType) == ImChatTypeEnum.GROUP && StringUtils.isNotBlank(chatId)) {
                String groupName = fetchAppChatName(accessToken, chatId);
                if (StringUtils.isNotBlank(groupName)) {
                    return groupName;
                }
                return chatId;
            }
            // 单聊：senderId 为 userid
            if (StringUtils.isNotBlank(senderId)) {
                String userName = fetchUserName(accessToken, senderId);
                if (StringUtils.isNotBlank(userName)) {
                    return userName;
                }
                return senderId;
            }
        } catch (Exception e) {
            // ignore
        }
        return StringUtils.isNotBlank(senderId) ? senderId : chatId;
    }

    private String fetchUserName(String accessToken, String userId) {
        try {
            String url = "https://qyapi.weixin.qq.com/cgi-bin/user/get?access_token=" + accessToken + "&userid=" + userId;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            String respBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JSONObject obj = JSON.parseObject(respBody);
            if (obj == null || obj.getIntValue("errcode") != 0) return null;
            return obj.getString("name");
        } catch (Exception e) {
            return null;
        }
    }

    private String fetchAppChatName(String accessToken, String chatId) {
        try {
            String url = "https://qyapi.weixin.qq.com/cgi-bin/appchat/get?access_token=" + accessToken + "&chatid=" + chatId;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(8000);
            int code = conn.getResponseCode();
            if (code != 200) return null;
            String respBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JSONObject obj = JSON.parseObject(respBody);
            if (obj == null) return null;
            int errcode = obj.getIntValue("errcode");
            if (errcode != 0) {
                // 86008: 无权限访问该 chat（通常是非本应用创建/托管的 appchat），按设计回退 chatId，不抛异常
                log.debug("企业微信 appchat/get 无法获取群名，回退 chatId: errcode={}, errmsg={}, chatId={}",
                        errcode, obj.getString("errmsg"), chatId);
                return null;
            }
            JSONObject chatInfo = obj.getJSONObject("chat_info");
            if (chatInfo == null) return null;
            return chatInfo.getString("name");
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 通过企业微信自建应用主动发送文本消息给指定用户
     */
    private void sendAppTextMessage(WeworkBotConfig config, String toUser, String content) {
        if (config == null || StringUtils.isBlank(toUser) || StringUtils.isBlank(content)) {
            return;
        }
        try {
            String accessToken = getCorpAccessToken(config.getCorpId(), config.getCorpSecret());
            if (StringUtils.isBlank(accessToken)) {
                log.warn("企业微信自建应用发送消息失败：获取access_token失败");
                return;
            }

            String url = "https://qyapi.weixin.qq.com/cgi-bin/message/send?access_token=" + accessToken;
            JSONObject payload = new JSONObject();
            payload.put("touser", toUser);
            payload.put("msgtype", "text");
            payload.put("agentid", config.getAgentId());

            JSONObject text = new JSONObject();
            text.put("content", content);
            payload.put("text", text);

            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json;charset=UTF-8");
            conn.setDoOutput(true);
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toJSONString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String respBody = null;
            try {
                respBody = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            } catch (Exception ignore) {
                // ignore
            }
            if (code >= 200 && code < 300 && respBody != null) {
                JSONObject respJson = JSON.parseObject(respBody);
                int errcode = respJson != null ? respJson.getIntValue("errcode") : -1;
                String errmsg = respJson != null ? respJson.getString("errmsg") : null;
                if (errcode == 0) {
                    log.info("企业微信自建应用主动发送消息成功: toUser={}, resp={}", toUser, respBody);
                } else {
                    log.warn("企业微信自建应用主动发送消息返回业务错误: toUser={}, errcode={}, errmsg={}, resp={}",
                            toUser, errcode, errmsg, respBody);
                }
            } else {
                log.warn("企业微信自建应用主动发送消息失败: httpCode={}, httpMsg={}, resp={}",
                        code, conn.getResponseMessage(), respBody);
            }
        } catch (Exception e) {
            log.error("企业微信自建应用主动发送消息异常: toUser={}", toUser, e);
        }
    }

    /**
     * 获取企业微信 access_token（仅用于自建应用主动发消息）
     */
    private String getCorpAccessToken(String corpId, String corpSecret) {
        if (StringUtils.isBlank(corpId) || StringUtils.isBlank(corpSecret)) {
            return null;
        }
        try {
            String url = "https://qyapi.weixin.qq.com/cgi-bin/gettoken?corpid=" + corpId
                    + "&corpsecret=" + corpSecret;
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(8000);

            int code = conn.getResponseCode();
            if (code != 200) {
                log.warn("获取企业微信access_token失败: httpCode={}", code);
                return null;
            }
            String resp = new String(conn.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            JSONObject json = JSON.parseObject(resp);
            if (json == null || json.getIntValue("errcode") != 0) {
                log.warn("获取企业微信access_token失败: resp={}", resp);
                return null;
            }
            return json.getString("access_token");
        } catch (Exception e) {
            log.error("获取企业微信access_token异常", e);
            return null;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class WeworkBotConfig {
        private String agentId; // 微信企业应用id
        private String aibotId; //企业微信机器人id
        private String corpId;
        private String corpSecret;
        private String token;
        private String encodingAesKey;
        private Long tenantId;
        private Long userId;
        private Long nuwaxAgentId;
    }
}
