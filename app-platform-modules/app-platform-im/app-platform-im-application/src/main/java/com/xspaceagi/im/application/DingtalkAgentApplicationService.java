package com.xspaceagi.im.application;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.application.dto.StreamChunk;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 钉钉机器人智能体执行服务
 */
public interface DingtalkAgentApplicationService {

    /**
     * 执行智能体并返回最终输出文本
     *
     * @param conversationType "1" 单聊，"2" 群聊
     * @param conversationId   会话 ID，群聊时为 openConversationId
     */
    String executeAgent(String senderId, String message, String conversationType, String conversationId,
                        Long tenantId, Long userId, Long agentId);

    /**
     * 执行智能体并返回最终输出文本（支持附件）
     */
    String executeAgent(String senderId, String message, List<AttachmentDto> attachments,
                        String conversationType, String conversationId,
                        Long tenantId, Long userId, Long agentId);

    /**
     * 执行智能体并返回结果和会话ID（用于内容后处理）
     */
    AgentExecuteResultWithConv executeAgentWithConv(String senderId, String message, List<AttachmentDto> attachments,
                                                    String conversationType, String conversationId,
                                                    Long tenantId, Long userId, Long agentId, String sessionName, String imUserName);

    default AgentExecuteResultWithConv executeAgentWithConv(String senderId, String message, List<AttachmentDto> attachments,
                                                            String conversationType, String conversationId,
                                                            Long tenantId, Long userId, Long agentId, String sessionName) {
        return executeAgentWithConv(senderId, message, attachments, conversationType, conversationId, tenantId, userId, agentId, sessionName, null);
    }

    default AgentExecuteResultWithConv executeAgentWithConv(String senderId, String message, List<AttachmentDto> attachments,
                                                            String conversationType, String conversationId,
                                                            Long tenantId, Long userId, Long agentId) {
        return executeAgentWithConv(senderId, message, attachments, conversationType, conversationId, tenantId, userId, agentId, null, null);
    }

    Flux<StreamChunk> executeAgentStream(String senderId, String message, List<AttachmentDto> attachments,
                                         String conversationType, String conversationId,
                                         Long tenantId, Long userId, Long agentId, String sessionName, String imUserName);

    default Flux<StreamChunk> executeAgentStream(String senderId, String message, List<AttachmentDto> attachments,
                                                 String conversationType, String conversationId,
                                                 Long tenantId, Long userId, Long agentId) {
        return executeAgentStream(senderId, message, attachments, conversationType, conversationId, tenantId, userId, agentId, null, null);
    }

    default Flux<StreamChunk> executeAgentStream(String senderId, String message, String conversationType, String conversationId,
                                                 Long tenantId, Long userId, Long agentId) {
        return executeAgentStream(senderId, message, null, conversationType, conversationId, tenantId, userId, agentId, null, null);
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
