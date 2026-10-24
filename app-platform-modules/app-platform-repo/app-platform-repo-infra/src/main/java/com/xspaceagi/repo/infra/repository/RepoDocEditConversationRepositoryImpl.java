package com.xspaceagi.repo.infra.repository;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xspaceagi.repo.domain.repository.RepoDocEditConversationRepository;
import com.xspaceagi.repo.infra.dao.entity.RepoDocEditConversation;
import com.xspaceagi.repo.infra.dao.mapper.RepoDocEditConversationMapper;
import com.xspaceagi.repo.spec.constants.RepoConstants;
import com.xspaceagi.system.spec.common.RequestContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.Date;

/**
 * 文档 AI 编辑会话映射仓储实现（用户 × 文档 → AI 编辑会话）。
 */
@Repository
@RequiredArgsConstructor
public class RepoDocEditConversationRepositoryImpl implements RepoDocEditConversationRepository {

    private final RepoDocEditConversationMapper repoDocEditConversationMapper;

    @Override
    public Long findConversationId(Long userId, Long pageId) {
        RepoDocEditConversation existing = repoDocEditConversationMapper.selectOne(
                new LambdaQueryWrapper<RepoDocEditConversation>()
                        .eq(RepoDocEditConversation::getUserId, userId)
                        .eq(RepoDocEditConversation::getPageId, pageId)
                        .eq(RepoDocEditConversation::getYn, RepoConstants.YN_VALID)
                        .last("LIMIT 1"));
        return existing == null ? null : existing.getEditConversationId();
    }

    @Override
    public void upsert(Long userId, Long pageId, String slugId, Long conversationId) {
        Date now = new Date();
        // 不按 yn 过滤：软删记录复活复用主键，避免 uk_user_page 唯一约束冲突
        RepoDocEditConversation existing = repoDocEditConversationMapper.selectOne(
                new LambdaQueryWrapper<RepoDocEditConversation>()
                        .eq(RepoDocEditConversation::getUserId, userId)
                        .eq(RepoDocEditConversation::getPageId, pageId)
                        .last("LIMIT 1"));
        if (existing != null) {
            repoDocEditConversationMapper.update(null, new LambdaUpdateWrapper<RepoDocEditConversation>()
                    .eq(RepoDocEditConversation::getId, existing.getId())
                    .set(RepoDocEditConversation::getEditConversationId, conversationId)
                    .set(RepoDocEditConversation::getSlugId, slugId)
                    .set(RepoDocEditConversation::getYn, RepoConstants.YN_VALID)
                    .set(RepoDocEditConversation::getModified, now));
            return;
        }
        RepoDocEditConversation entity = RepoDocEditConversation.builder()
                .tenantId(RequestContext.get().getTenantId())
                .userId(userId)
                .pageId(pageId)
                .slugId(slugId)
                .editConversationId(conversationId)
                .created(now)
                .modified(now)
                .yn(RepoConstants.YN_VALID)
                .build();
        repoDocEditConversationMapper.insert(entity);
    }
}
