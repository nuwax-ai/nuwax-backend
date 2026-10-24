package com.xspaceagi.agent.core.sdk;

import com.xspaceagi.agent.core.sdk.dto.ConversationRpcDto;

import java.util.List;

public interface IConversationRpcService {

    List<ConversationRpcDto> queryLatestSandboxConversationList(List<Long> exceptSandboxIds);

    List<ConversationRpcDto> queryLatestConversationListBySandboxId(Long sandboxId);

    void stopAgent(Long agentId, Long id);

    Long createRepoDocConversation(Long userId, Long agentId, String slugId, String docName);
}
