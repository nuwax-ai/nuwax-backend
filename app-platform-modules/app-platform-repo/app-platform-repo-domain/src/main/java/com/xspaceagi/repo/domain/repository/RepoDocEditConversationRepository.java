package com.xspaceagi.repo.domain.repository;

/**
 * 文档 AI 编辑会话映射仓储（用户 × 文档 → AI 编辑会话）。
 */
public interface RepoDocEditConversationRepository {

    /**
     * 查询「用户 × 文档」已存在的 AI 编辑会话 ID；无有效记录返回 null。
     */
    Long findConversationId(Long userId, Long pageId);

    /**
     * 更新「用户 × 文档」对应的会话 ID（含冗余 slugId）。
     * 无记录则新增；已软删则复活复用主键，避免 uk_user_page 唯一约束冲突。
     */
    void upsert(Long userId, Long pageId, String slugId, Long conversationId);
}
