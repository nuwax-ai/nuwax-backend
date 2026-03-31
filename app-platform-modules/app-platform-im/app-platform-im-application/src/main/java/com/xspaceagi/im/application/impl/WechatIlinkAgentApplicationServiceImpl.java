package com.xspaceagi.im.application.impl;

import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AgentOutputDto;
import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.agent.core.adapter.dto.ChatMessageDto;
import com.xspaceagi.agent.core.adapter.dto.TryReqDto;
import com.xspaceagi.agent.core.infra.component.agent.dto.AgentExecuteResult;
import com.xspaceagi.agent.core.infra.component.model.dto.CallMessage;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentExecutingDto;
import com.xspaceagi.im.application.ImSessionApplicationService;
import com.xspaceagi.im.application.WechatIlinkAgentApplicationService;
import com.xspaceagi.im.application.WeworkAgentApplicationService;
import com.xspaceagi.im.infra.dao.enitity.ImSession;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.infra.enums.ImChatTypeEnum;
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
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 微信 iLink 智能体执行（会话维度：单聊 peer = from_user_id）
 */
@Slf4j
@Service
public class WechatIlinkAgentApplicationServiceImpl implements WechatIlinkAgentApplicationService {

    @Resource
    private UserApplicationService userApplicationService;
    @Resource
    private ImSessionApplicationService imSessionApplicationService;
    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;
    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Override
    public Mono<WeworkAgentApplicationService.AgentExecuteResultWithConv> executeAgentWithConv(
            String fromUserId,
            String userMessage,
            List<AttachmentDto> attachments,
            Long tenantId,
            Long userId,
            Long agentId,
            String sessionName) {
        boolean noText = StringUtils.isBlank(userMessage);
        boolean noAttachments = attachments == null || attachments.isEmpty();
        if (StringUtils.isBlank(fromUserId) || (noText && noAttachments)) {
            return Mono.just(new WeworkAgentApplicationService.AgentExecuteResultWithConv("消息内容不能为空", null, agentId));
        }
        if (agentId == null || agentId <= 0) {
            return Mono.just(new WeworkAgentApplicationService.AgentExecuteResultWithConv("微信 iLink 智能体未配置，请联系管理员", null, agentId));
        }

        return Mono.defer(() -> {
                    RequestContext<Object> requestContext = new RequestContext<>();
                    requestContext.setTenantId(tenantId);
                    requestContext.setTenantConfig(tenantConfigApplicationService.getTenantConfig(tenantId));
                    RequestContext.set(requestContext);

                    UserDto userDto = userApplicationService.queryById(userId);
                    if (userDto == null) {
                        return Mono.just(new WeworkAgentApplicationService.AgentExecuteResultWithConv("系统用户不存在", null, agentId));
                    }
                    requestContext.setUser(userDto);
                    requestContext.setUserId(userId);

                    Long convId = getConversationId(fromUserId, userId, agentId, tenantId, sessionName);
                    if (convId == null) {
                        return Mono.just(new WeworkAgentApplicationService.AgentExecuteResultWithConv("创建会话失败", null, agentId));
                    }

                    TryReqDto tryReqDto = new TryReqDto();
                    tryReqDto.setConversationId(convId);
                    tryReqDto.setMessage(userMessage);
                    tryReqDto.setAttachments(attachments != null ? attachments : new ArrayList<>());
                    tryReqDto.setFrom(ImChannelEnum.WECHAT_ILINK.getCode());

                    Flux<AgentOutputDto> flux = conversationApplicationService.chat(tryReqDto, new HashMap<>(), false);

                    StringBuilder accumulated = new StringBuilder();
                    AtomicReference<AgentExecuteResult> finalRef = new AtomicReference<>();
                    final Long convIdFinal = convId;

                    return flux
                            .doOnNext(o -> {
                                if (o.getEventType() == AgentOutputDto.EventTypeEnum.MESSAGE && o.getData() instanceof ChatMessageDto) {
                                    String text = ((ChatMessageDto) o.getData()).getText();
                                    if (StringUtils.isNotBlank(text)) {
                                        accumulated.append(text);
                                    }
                                } else if (o.getEventType() == AgentOutputDto.EventTypeEnum.PROCESSING_MESSAGE && o.getData() instanceof ComponentExecutingDto) {
                                    Object exec = ((ComponentExecutingDto) o.getData()).getExecutingMessage();
                                    if (exec instanceof CallMessage) {
                                        String text = ((CallMessage) exec).getText();
                                        if (StringUtils.isNotBlank(text)) {
                                            accumulated.append(text);
                                        }
                                    }
                                } else if (o.getEventType() == AgentOutputDto.EventTypeEnum.FINAL_RESULT && o.getData() instanceof AgentExecuteResult) {
                                    finalRef.set((AgentExecuteResult) o.getData());
                                }
                            })
                            .then(Mono.fromCallable(() -> mapToConvResult(accumulated, finalRef, convIdFinal, agentId)));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .doFinally(st -> RequestContext.remove())
                .onErrorResume(e -> {
                    log.error("微信 iLink 智能体执行异常: fromUserId={}", fromUserId, e);
                    String errorText = "执行异常: " + (e.getMessage() != null ? e.getMessage() : "未知错误");
                    return Mono.just(new WeworkAgentApplicationService.AgentExecuteResultWithConv(errorText, null, agentId));
                });
    }

    private static WeworkAgentApplicationService.AgentExecuteResultWithConv mapToConvResult(
            StringBuilder accumulated,
            AtomicReference<AgentExecuteResult> finalRef,
            Long convId,
            Long agentId) {
        AgentExecuteResult finalResult = finalRef.get();
        if (finalResult == null) {
            if (accumulated.length() > 0) {
                return new WeworkAgentApplicationService.AgentExecuteResultWithConv(accumulated.toString(), convId, agentId);
            }
            return new WeworkAgentApplicationService.AgentExecuteResultWithConv("执行超时或未返回结果", convId, agentId);
        }
        if (Boolean.FALSE.equals(finalResult.getSuccess())) {
            String errorText = StringUtils.isNotBlank(finalResult.getError()) ? finalResult.getError() : "执行失败";
            return new WeworkAgentApplicationService.AgentExecuteResultWithConv(errorText, convId, agentId);
        }
        String outputText = StringUtils.isNotBlank(finalResult.getOutputText()) ? finalResult.getOutputText() : null;
        if (outputText == null) {
            outputText = accumulated.toString();
        }
        if (StringUtils.isBlank(outputText)) {
            outputText = "已处理";
        }
        return new WeworkAgentApplicationService.AgentExecuteResultWithConv(outputText, convId, agentId);
    }

    private Long getConversationId(String fromUserId, Long userId, Long agentId, Long tenantId, String sessionName) {
        ImSession imSession = ImSession.builder()
                .channel(ImChannelEnum.WECHAT_ILINK.getCode())
                .targetType(ImTargetTypeEnum.BOT.getCode())
                .sessionKey(fromUserId)
                .sessionName(sessionName)
                .chatType(ImChatTypeEnum.PRIVATE.getCode())
                .userId(userId)
                .agentId(agentId)
                .tenantId(tenantId)
                .build();
        return imSessionApplicationService.getConversationId(imSession);
    }
}
