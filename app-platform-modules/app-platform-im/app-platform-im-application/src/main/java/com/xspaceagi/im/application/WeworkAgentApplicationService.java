package com.xspaceagi.im.application;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.application.dto.StreamChunk;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 企业微信智能机器人智能体执行服务
 */
public interface WeworkAgentApplicationService {

    /**
     * 执行智能体并返回最终输出文本
     *
     * @param chatType   会话类型：single 单聊，group 群聊
     * @param chatId     群聊时的 chatid，单聊时可为空
     * @param targetType 目标类型：bot 机器人，app 应用
     */
    String executeAgent(String senderId, String message, String chatType, String chatId, String targetType,
                        Long tenantId, Long userId, Long agentId);

    /**
     * 执行智能体并返回最终输出文本（支持附件）
     */
    String executeAgent(String senderId, String message, List<AttachmentDto> attachments,
                        String chatType, String chatId, String targetType,
                        Long tenantId, Long userId, Long agentId);

    /**
     * 执行智能体并返回结果和会话ID（用于内容后处理）
     */
    AgentExecuteResultWithConv executeAgentWithConv(String senderId, String message, List<AttachmentDto> attachments,
                                                     String chatType, String chatId, String targetType,
                                                     Long tenantId, Long userId, Long agentId, String sessionName);

    default AgentExecuteResultWithConv executeAgentWithConv(String senderId, String message, List<AttachmentDto> attachments,
                                                           String chatType, String chatId, String targetType,
                                                           Long tenantId, Long userId, Long agentId) {
        return executeAgentWithConv(senderId, message, attachments, chatType, chatId, targetType, tenantId, userId, agentId, null);
    }

    /**
     * 流式执行智能体，返回增量输出的 Flux
     */
    Flux<StreamChunk> executeAgentStream(String senderId, String message, String chatType, String chatId, String targetType,
                                         Long tenantId, Long userId, Long agentId, String sessionName);

    default Flux<StreamChunk> executeAgentStream(String senderId, String message, String chatType, String chatId, String targetType,
                                                 Long tenantId, Long userId, Long agentId) {
        return executeAgentStream(senderId, message, chatType, chatId, targetType, tenantId, userId, agentId, null);
    }

    /**
     * 流式执行智能体（支持附件）
     */
    Flux<StreamChunk> executeAgentStream(String senderId, String message, List<AttachmentDto> attachments,
                                         String chatType, String chatId, String targetType,
                                         Long tenantId, Long userId, Long agentId, String sessionName);

    default Flux<StreamChunk> executeAgentStream(String senderId, String message, List<AttachmentDto> attachments,
                                                 String chatType, String chatId, String targetType,
                                                 Long tenantId, Long userId, Long agentId) {
        return executeAgentStream(senderId, message, attachments, chatType, chatId, targetType, tenantId, userId, agentId, null);
    }

    /**
     * 智能体执行结果（包含会话ID）
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    class AgentExecuteResultWithConv {
        private String text;
        private Long conversationId;
        private Long agentId;
    }
}
