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
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
                log.debug("找到已存在的IM会话: platform={}, sessionKey={}, agentId={}, conversationId={}",
                        imSession.getChannel(), imSession.getSessionKey(), imSession.getAgentId(), existing.getConversationId());
                return existing.getConversationId();
            } else {
                log.info("IM会话对应的会话已不存在，删除会话: platform={}, sessionKey={}, agentId={}, conversationId={}",
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
        log.info("创建新会话: platform={}, sessionKey={}, agentId={}, conversationId={}",
                imSession.getChannel(), imSession.getSessionKey(), imSession.getAgentId(), newConversation.getId());

        ImChannelEnum imChannelEnum = ImChannelEnum.fromCode(imSession.getChannel());
        ImTargetTypeEnum imTargetTypeEnum = ImTargetTypeEnum.fromCode(imSession.getTargetType());
        String topic = "IM机器人";
        switch (imChannelEnum) {
            case FEISHU:
                topic = "飞书机器人";
                break;
            case DINGTALK:
                topic = "钉钉机器人";
                break;
            case WEWORK:
                if (ImTargetTypeEnum.APP.equals(imTargetTypeEnum)) {
                    topic = "企业微信应用";
                } else if (ImTargetTypeEnum.BOT.equals(imTargetTypeEnum)) {
                    topic = "企业微信机器人";
                }
                break;
            case WECHAT_ILINK:
                topic = "微信机器人";
                break;
            default:
                break;
        }

        String sessionDisplay = imSession.getSessionName();
        if (sessionDisplay == null || sessionDisplay.trim().isEmpty()) {
            sessionDisplay = imSession.getSessionKey();
        }
        topic = topic + " - " + ImChatTypeEnum.fromCode(imSession.getChatType()).getName() + " - " + sessionDisplay;

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
