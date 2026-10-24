package com.xspaceagi.repo.domain.repository;

import com.xspaceagi.repo.domain.model.RepoShareLinkDomain;

import java.util.List;

/**
 * 文档分享链接仓储接口（需求4）。
 */
public interface RepoShareLinkRepository {

    /**
     * 查询页面的全部有效分享链接
     */
    List<RepoShareLinkDomain> listByPageId(Long pageId);

    /**
     * 按 token 查询有效链接（匿名入口：需绕过租户过滤，调用方负责包装）
     */
    RepoShareLinkDomain findByToken(String token);

    /**
     * 查询某用户创建的全部有效分享链接（门户「我分享的」用），按创建时间倒序
     */
    List<RepoShareLinkDomain> listByCreatedBy(Long userId);

    /**
     * 新增分享链接
     *
     * @return 自增主键
     */
    Long insert(RepoShareLinkDomain domain);

    /**
     * 更新链接的「是否允许评论」（需求5）
     *
     * @return 是否更新成功
     */
    boolean updateAllowComment(Long id, Integer allowComment);

    /**
     * 软删除分享链接
     */
    boolean softDelete(Long id);

    /**
     * 软删除页面的全部分享链接
     *
     * @return 受影响行数
     */
    int softDeleteByPageId(Long pageId);
}
