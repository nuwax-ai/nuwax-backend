package com.xspaceagi.agent.core.application.api;

import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import com.xspaceagi.agent.core.domain.service.ConversationDomainService;
import com.xspaceagi.agent.core.infra.component.agent.SandboxAgentClient;
import com.xspaceagi.agent.core.sdk.IConversationRpcService;
import com.xspaceagi.agent.core.sdk.dto.ConversationRpcDto;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ConversationRpcServiceImpl implements IConversationRpcService {

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private ConversationDomainService conversationDomainService;

    @Resource
    private SandboxAgentClient sandboxAgentClient;

    @Override
    public List<ConversationRpcDto> queryLatestSandboxConversationList(List<Long> exceptSandboxIds) {
        List<Conversation> conversations = conversationDomainService.queryLatestSandboxConversationList(exceptSandboxIds);
        return conversations.stream().map(conversation -> {
            ConversationRpcDto conversationRpcDto = new ConversationRpcDto();
            conversationRpcDto.setId(conversation.getId());
            conversationRpcDto.setSandboxServerId(Long.parseLong(conversation.getSandboxServerId()));
            conversationRpcDto.setAgentId(conversation.getAgentId());
            conversationRpcDto.setModified(conversation.getModified());
            return conversationRpcDto;
        }).collect(Collectors.toList());
    }

    @Override
    public List<ConversationRpcDto> queryLatestConversationListBySandboxId(Long sandboxId) {
        List<ConversationDto> conversationDtos = conversationApplicationService.queryConversationListBySandboxServerId(sandboxId);
        return conversationDtos.stream().map(conversationDto -> {
            ConversationRpcDto conversationRpcDto = new ConversationRpcDto();
            conversationRpcDto.setId(conversationDto.getId());
            conversationRpcDto.setSandboxServerId(Long.parseLong(conversationDto.getSandboxServerId()));
            conversationRpcDto.setAgentId(conversationDto.getAgentId());
            conversationRpcDto.setModified(conversationDto.getModified());
            return conversationRpcDto;
        }).collect(Collectors.toList());
    }

    @Override
    public void stopAgent(Long agentId, Long id) {
        sandboxAgentClient.agentStop(id.toString());
    }

    @Override
    public Long createRepoDocConversation(Long userId, Long agentId, String slugId, String docName) {
        return conversationApplicationService.createRepoDocConversation(userId, agentId, slugId, docName);
    }
}
