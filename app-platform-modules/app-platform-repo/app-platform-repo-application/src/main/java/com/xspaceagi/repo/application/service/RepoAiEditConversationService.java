package com.xspaceagi.repo.application.service;

import com.xspaceagi.repo.domain.model.RepoPageDomain;

/**
 * 文档 AI 编辑会话服务：维护「用户 × 文档 → AI 编辑会话」映射，
 * 为前端「AI 编辑」侧栏提供可复用的会话 ID。
 */
public interface RepoAiEditConversationService {

    /**
     * 获取当前用户对指定文档的 AI 编辑会话 ID。
     * 已有映射直接返回；无则调 agent 侧 createRepoDocConversation 创建会话并落库。
     */
    Long getOrCreateConversationId(RepoPageDomain page);
}
