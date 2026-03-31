package com.xspaceagi.im.application;

import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * 微信 iLink 渠道智能体执行
 */
public interface WechatIlinkAgentApplicationService {

    /**
     * 非阻塞：在 {@link reactor.core.scheduler.Schedulers#boundedElastic()} 上执行会话与智能体链路，避免调用方占用线程池。
     */
    Mono<WeworkAgentApplicationService.AgentExecuteResultWithConv> executeAgentWithConv(
            String fromUserId,
            String userMessage,
            List<AttachmentDto> attachments,
            Long tenantId,
            Long userId,
            Long agentId,
            String sessionName);
}
