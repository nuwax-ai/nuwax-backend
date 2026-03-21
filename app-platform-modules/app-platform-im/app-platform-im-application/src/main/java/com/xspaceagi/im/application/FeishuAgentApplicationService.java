package com.xspaceagi.im.application;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.application.dto.StreamChunk;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 飞书机器人智能体执行服务
 */
public interface FeishuAgentApplicationService {

    /**
     * 执行智能体并返回最终输出文本（支持附件）
     */
    String executeAgent(String sessionId, String chatType, String message, List<AttachmentDto> attachments, Long tenantId, Long userId, Long agentId);

    /**
     * 执行智能体并返回结果和会话ID（用于内容后处理）
     */
    AgentExecuteResultWithConv executeAgentWithConv(String sessionId, String chatType, String message, List<AttachmentDto> attachments, Long tenantId, Long userId, Long agentId, String sessionName);

    default AgentExecuteResultWithConv executeAgentWithConv(String sessionId, String chatType, String message, List<AttachmentDto> attachments, Long tenantId, Long userId, Long agentId) {
        return executeAgentWithConv(sessionId, chatType, message, attachments, tenantId, userId, agentId, null);
    }

    /**
     * 流式执行智能体，返回增量输出的 Flux（支持附件）
     */
    Flux<StreamChunk> executeAgentStream(String sessionId, String chatType, String message, List<AttachmentDto> attachments, Long tenantId, Long userId, Long agentId, String sessionName);

    default Flux<StreamChunk> executeAgentStream(String sessionId, String chatType, String message, List<AttachmentDto> attachments, Long tenantId, Long userId, Long agentId) {
        return executeAgentStream(sessionId, chatType, message, attachments, tenantId, userId, agentId, null);
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
