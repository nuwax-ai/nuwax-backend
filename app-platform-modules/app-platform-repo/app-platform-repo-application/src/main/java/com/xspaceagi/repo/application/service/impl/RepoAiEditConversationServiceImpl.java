package com.xspaceagi.repo.application.service.impl;

import com.xspaceagi.agent.core.sdk.IConversationRpcService;
import com.xspaceagi.repo.application.service.RepoAiEditConversationService;
import com.xspaceagi.repo.domain.model.RepoPageDomain;
import com.xspaceagi.repo.domain.repository.RepoDocEditConversationRepository;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.spec.common.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 文档 AI 编辑会话服务实现。
 */
@Service
@RequiredArgsConstructor
public class RepoAiEditConversationServiceImpl implements RepoAiEditConversationService {

    private final RepoDocEditConversationRepository repoDocEditConversationRepository;

    private final IConversationRpcService conversationRpcService;

    @Override
    public Long getOrCreateConversationId(RepoPageDomain page) {
        Long userId = RequestContext.get().getUserId();
        Long pageId = page.getId();

        Long existing = repoDocEditConversationRepository.findConversationId(userId, pageId);
        if (existing != null) {
            return existing;
        }
        Long editAgentId = ((TenantConfigDto) RequestContext.get().getTenantConfig()).getDocEditAgentId();
        Long conversationId = conversationRpcService.createRepoDocConversation(
                userId, editAgentId, page.getSlugId(), page.getTitle());
        repoDocEditConversationRepository.upsert(userId, pageId, page.getSlugId(), conversationId);
        return conversationId;
    }
}
