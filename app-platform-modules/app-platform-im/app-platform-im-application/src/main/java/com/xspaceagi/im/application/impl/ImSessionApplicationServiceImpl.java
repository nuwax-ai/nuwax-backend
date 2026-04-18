package com.xspaceagi.im.application.impl;

import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.ConversationUpdateDto;
import com.xspaceagi.im.application.ImSessionApplicationService;
import com.xspaceagi.im.domain.service.ImSessionDomainService;
import com.xspaceagi.im.infra.dao.enitity.ImSession;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.infra.enums.ImChatTypeEnum;
import com.xspaceagi.im.infra.enums.ImTargetTypeEnum;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.utils.I18nUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static com.xspaceagi.im.infra.enums.ImChannelEnum.*;

/**
 * IM会话应用服务
 */
@Slf4j
@Service
public class ImSessionApplicationServiceImpl implements ImSessionApplicationService {

    @Resource
    private ImSessionDomainService imSessionDomainService;
    @Resource
    private ConversationApplicationService conversationApplicationService;

    /**
     * 获取或创建会话ID
     */
    @Override
    public Long getConversationId(ImSession imSession) {
        // 先查询是否已存在
        ImSession existing = imSessionDomainService.findSession(imSession);
        if (existing != null) {
            // 验证会话是否仍然有效
            ConversationDto conversation = conversationApplicationService.getConversation(null, existing.getConversationId());
            if (conversation != null) {
                log.debug("Found existing IM session: platform={}, sessionKey={}, agentId={}, conversationId={}",
                        imSession.getChannel(), imSession.getSessionKey(), imSession.getAgentId(), existing.getConversationId());
                return existing.getConversationId();
            } else {
                log.info("Underlying conversation missing, deleting IM session: platform={}, sessionKey={}, agentId={}, conversationId={}",
                        imSession.getChannel(), imSession.getSessionKey(), imSession.getAgentId(), existing.getConversationId());
                imSessionDomainService.deleteSession(imSession);
            }
        }

        return createAndSaveNewConversation(imSession);
    }

    @Override
    public Long createNewConversationId(ImSession imSession) {
        // /new 场景：始终重建会话映射，后续消息默认走新会话
        imSessionDomainService.deleteSession(imSession);
        return createAndSaveNewConversation(imSession);
    }

    private Long createAndSaveNewConversation(ImSession imSession) {
        // 创建新会话
        ConversationDto newConversation = conversationApplicationService.createConversation(imSession.getUserId(), imSession.getAgentId(), false, false);
        log.info("Created new session: platform={}, sessionKey={}, agentId={}, conversationId={}",
                imSession.getChannel(), imSession.getSessionKey(), imSession.getAgentId(), newConversation.getId());

        ImChannelEnum imChannelEnum = ImChannelEnum.fromCode(imSession.getChannel());
        ImTargetTypeEnum imTargetTypeEnum = ImTargetTypeEnum.fromCode(imSession.getTargetType());

        String lang = RequestContext.get().getLang();
        boolean isCN = "zh-CN".equalsIgnoreCase(lang);

        String topic = "";
        switch (imChannelEnum) {
            case FEISHU:
                topic = isCN ? FEISHU.getName() : FEISHU.getCode();
                break;
            case DINGTALK:
                topic = isCN ? DINGTALK.getName() : DINGTALK.getCode();
                break;
            case WEWORK:
                if (ImTargetTypeEnum.APP.equals(imTargetTypeEnum)) {
                    topic = isCN ? (WEWORK.getName() + ImTargetTypeEnum.APP.getName()) : (WEWORK.getCode() + ImTargetTypeEnum.APP.getCode());
                } else if (ImTargetTypeEnum.BOT.equals(imTargetTypeEnum)) {
                    topic = isCN ? (WEWORK.getName() + ImTargetTypeEnum.BOT.getName()) : (WEWORK.getCode() + ImTargetTypeEnum.BOT.getCode());
                }
                break;
            case WECHAT_ILINK:
                topic = isCN ? WECHAT_ILINK.getName() : WECHAT_ILINK.getCode();
                break;
            default:
                break;
        }

        String sessionDisplay = imSession.getSessionName();
        if (sessionDisplay == null || sessionDisplay.trim().isEmpty()) {
            sessionDisplay = imSession.getSessionKey();
        }
        topic = topic + " - "
                + (isCN ? ImChatTypeEnum.fromCode(imSession.getChatType()).getName() : ImChatTypeEnum.fromCode(imSession.getChatType()).getCode())
                + " - " + sessionDisplay;

        ConversationUpdateDto conversationUpdateDto = new ConversationUpdateDto();
        conversationUpdateDto.setId(newConversation.getId());
        conversationUpdateDto.setTopic(topic);
        conversationApplicationService.updateConversationTopic(imSession.getUserId(), conversationUpdateDto);

        // 保存IM会话
        imSession.setConversationId(newConversation.getId());
        imSessionDomainService.saveSession(imSession);

        return newConversation.getId();
    }

}
