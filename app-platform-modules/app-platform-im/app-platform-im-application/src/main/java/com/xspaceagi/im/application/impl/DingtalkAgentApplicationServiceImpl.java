package com.xspaceagi.im.application.impl;

import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AgentOutputDto;
import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.agent.core.adapter.dto.ChatMessageDto;
import com.xspaceagi.agent.core.adapter.dto.TryReqDto;
import com.xspaceagi.agent.core.infra.component.agent.dto.AgentExecuteResult;
import com.xspaceagi.agent.core.infra.component.model.dto.CallMessage;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentExecutingDto;
import com.xspaceagi.im.application.DingtalkAgentApplicationService;
import com.xspaceagi.im.application.ImSessionApplicationService;
import com.xspaceagi.im.application.dto.StreamChunk;
import com.xspaceagi.im.infra.dao.enitity.ImSession;
import com.xspaceagi.im.infra.enums.ImChatTypeEnum;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.infra.enums.ImTargetTypeEnum;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * 钉钉机器人智能体执行服务实现
 */
@Slf4j
@Service
public class DingtalkAgentApplicationServiceImpl implements DingtalkAgentApplicationService {

    @Resource
    private UserApplicationService userApplicationService;
    @Resource
    private ImSessionApplicationService imSessionApplicationService;
    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;
    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Override
    public String executeAgent(String senderId, String message, String conversationType, String conversationId,
                               Long tenantId, Long userId, Long agentId) {
        return executeAgent(senderId, message, null, conversationType, conversationId, tenantId, userId, agentId);
    }

    @Override
    public String executeAgent(String senderId, String message, List<AttachmentDto> attachments,
                               String conversationType, String conversationId,
                               Long tenantId, Long userId, Long agentId) {
        AgentExecuteResultWithConv result = executeAgentWithConv(senderId, message, attachments, conversationType, conversationId, tenantId, userId, agentId);
        return result.getText();
    }

    @Override
    public AgentExecuteResultWithConv executeAgentWithConv(String senderId, String message, List<AttachmentDto> attachments,
                                                           String conversationType, String conversationId,
                                                           Long tenantId, Long userId, Long agentId, String sessionName) {
        if (StringUtils.isBlank(senderId) || StringUtils.isBlank(message)) {
            return AgentExecuteResultWithConv.builder().text("消息内容不能为空").conversationId(null).agentId(agentId).build();
        }

        if (agentId == null || agentId <= 0) {
            log.warn("钉钉智能体未配置 agent-id");
            return AgentExecuteResultWithConv.builder().text("钉钉智能体未配置，请联系管理员").conversationId(null).agentId(agentId).build();
        }

        RequestContext<Object> requestContext = new RequestContext<>();
        requestContext.setTenantId(tenantId);
        requestContext.setTenantConfig(tenantConfigApplicationService.getTenantConfig(tenantId));
        RequestContext.set(requestContext);

        try {
            UserDto userDto = userApplicationService.queryById(userId);
            if (userDto == null) {
                return AgentExecuteResultWithConv.builder().text("系统用户不存在").conversationId(null).agentId(agentId).build();
            }
            requestContext.setUser(userDto);
            requestContext.setUserId(userId);

            Long convId = getConversationId(senderId, conversationType, conversationId, userId, agentId, tenantId, sessionName);
            if (convId == null) {
                return AgentExecuteResultWithConv.builder().text("创建会话失败").conversationId(null).agentId(agentId).build();
            }

            TryReqDto tryReqDto = new TryReqDto();
            tryReqDto.setConversationId(convId);
            tryReqDto.setMessage(message);
            tryReqDto.setAttachments(attachments != null ? attachments : new ArrayList<>());
            tryReqDto.setFrom(ImChannelEnum.DINGTALK.getCode());

            Flux<AgentOutputDto> flux = conversationApplicationService.chat(tryReqDto, new HashMap<>(), false);

            AgentExecuteResult finalResult = flux
                    .filter(o -> o.getEventType() == AgentOutputDto.EventTypeEnum.FINAL_RESULT)
                    .map(o -> (AgentExecuteResult) o.getData())
                    .next()
                    .block();

            if (finalResult == null) {
                return AgentExecuteResultWithConv.builder().text("执行超时或未返回结果").conversationId(convId).agentId(agentId).build();
            }
            if (Boolean.FALSE.equals(finalResult.getSuccess())) {
                String err = StringUtils.isNotBlank(finalResult.getError()) ? finalResult.getError() : "模型执行失败";
                return AgentExecuteResultWithConv.builder().text(err).conversationId(convId).agentId(agentId).build();
            }
            String out = StringUtils.isNotBlank(finalResult.getOutputText()) ? finalResult.getOutputText() : "模型终止执行";
            return AgentExecuteResultWithConv.builder().text(out).conversationId(convId).agentId(agentId).build();
        } catch (Exception e) {
            log.error("钉钉智能体执行异常: senderId={}", senderId, e);
            String err = "模型执行异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误");
            return AgentExecuteResultWithConv.builder().text(err).conversationId(null).agentId(agentId).build();
        } finally {
            RequestContext.remove();
        }
    }

    @Override
    public Flux<StreamChunk> executeAgentStream(String senderId, String message, String conversationType, String conversationId,
                                                      Long tenantId, Long userId, Long agentId) {
        return executeAgentStream(senderId, message, null, conversationType, conversationId, tenantId, userId, agentId);
    }

    @Override
    public Flux<StreamChunk> executeAgentStream(String senderId, String message, List<AttachmentDto> attachments,
                                                      String conversationType, String conversationId,
                                                      Long tenantId, Long userId, Long agentId) {
        if (StringUtils.isBlank(senderId) || StringUtils.isBlank(message)) {
            return Flux.just(new StreamChunk("消息内容不能为空", true));
        }
        if (agentId == null || agentId <= 0) {
            return Flux.just(new StreamChunk("钉钉智能体未配置，请联系管理员", true));
        }

        return Flux.<StreamChunk>defer(() -> {
            RequestContext<Object> requestContext = new RequestContext<>();
            requestContext.setTenantId(tenantId);
            requestContext.setTenantConfig(tenantConfigApplicationService.getTenantConfig(tenantId));
            RequestContext.set(requestContext);

            UserDto userDto = userApplicationService.queryById(userId);
            if (userDto == null) {
                RequestContext.remove();
                return Flux.just(new StreamChunk("系统用户不存在", true));
            }
            requestContext.setUser(userDto);
            requestContext.setUserId(userId);

            Long convId = getConversationId(senderId, conversationType, conversationId, userId, agentId, tenantId, null);
            if (convId == null) {
                RequestContext.remove();
                return Flux.just(new StreamChunk("创建会话失败", true));
            }

            TryReqDto tryReqDto = new TryReqDto();
            tryReqDto.setConversationId(convId);
            tryReqDto.setMessage(message);
            tryReqDto.setAttachments(attachments != null ? attachments : new ArrayList<>());
            tryReqDto.setFrom(ImChannelEnum.DINGTALK.getCode());

            Flux<AgentOutputDto> chatFlux = conversationApplicationService.chat(tryReqDto, new HashMap<>(), false);
            StringBuilder accumulated = new StringBuilder();

            return chatFlux
                    .flatMap(event -> {
                        StreamChunk chunk = null;
                        if (event.getEventType() == AgentOutputDto.EventTypeEnum.MESSAGE && event.getData() instanceof ChatMessageDto) {
                            String text = ((ChatMessageDto) event.getData()).getText();
                            if (StringUtils.isNotBlank(text)) {
                                accumulated.append(text);
                                chunk = new StreamChunk(accumulated.toString(), false, convId);
                            }
                        } else if (event.getEventType() == AgentOutputDto.EventTypeEnum.PROCESSING_MESSAGE && event.getData() instanceof ComponentExecutingDto) {
                            Object exec = ((ComponentExecutingDto) event.getData()).getExecutingMessage();
                            if (exec instanceof CallMessage) {
                                String text = ((CallMessage) exec).getText();
                                if (StringUtils.isNotBlank(text)) {
                                    accumulated.append(text);
                                    chunk = new StreamChunk(accumulated.toString(), false, convId);
                                }
                            }
                        } else if (event.getEventType() == AgentOutputDto.EventTypeEnum.FINAL_RESULT && event.getData() instanceof AgentExecuteResult) {
                            AgentExecuteResult result = (AgentExecuteResult) event.getData();
                            String finalText = result.getOutputText();
                            if (Boolean.FALSE.equals(result.getSuccess()) && StringUtils.isNotBlank(result.getError())) {
                                finalText = result.getError();
                            }
                            if (finalText == null) {
                                finalText = accumulated.toString();
                            }
                            chunk = new StreamChunk(finalText != null ? finalText : "模型终止执行", true, convId);
                        }
                        return Mono.justOrEmpty(chunk);
                    })
                    .onErrorResume(e -> Flux.just(new StreamChunk("模型执行异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误"), true, null)))
                    .doFinally(s -> RequestContext.remove());
        });
    }

    /**
     * 获取或创建会话。单聊用发送者 id 构建会话 key，群聊用群 id 构建会话 key。
     *
     * @param conversationType "1" 单聊，"2" 群聊
     * @param conversationId   群聊时的 openConversationId，单聊时可为空
     */
    private Long getConversationId(String senderId, String conversationType, String conversationId,
                                   Long userId, Long agentId, Long tenantId, String sessionName) {
        String sessionKey;
        if ("2".equals(conversationType) && StringUtils.isNotBlank(conversationId)) {
            sessionKey = conversationId;  // 群聊：用群 id
        } else {
            sessionKey = senderId;       // 单聊：用发送用户 id
        }

        ImSession imSession = ImSession.builder()
                .channel(ImChannelEnum.DINGTALK.getCode())
                .targetType(ImTargetTypeEnum.BOT.getCode())
                .sessionKey(sessionKey)
                .sessionName(sessionName)
                .chatType("2".equals(conversationType) ? ImChatTypeEnum.GROUP.getCode() : ImChatTypeEnum.PRIVATE.getCode())
                .userId(userId)
                .agentId(agentId)
                .tenantId(tenantId)
                .build();
        return imSessionApplicationService.getConversationId(imSession);
    }
}
