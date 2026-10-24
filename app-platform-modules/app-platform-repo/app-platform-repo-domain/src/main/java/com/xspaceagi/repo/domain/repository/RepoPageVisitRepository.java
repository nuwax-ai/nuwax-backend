package com.xspaceagi.repo.domain.repository;

import com.xspaceagi.repo.domain.model.RepoPageVisitDomain;

import java.util.List;

/**
 * 文档访问记录仓储（需求1：门户最近访问）。
 */
public interface RepoPageVisitRepository {

    /**
     * 记录一次访问：同用户同页面仅更新最近访问时间（uk_user_page 唯一约束）。
     *
     * @param linkId 来源分享链接ID（通过链接访问时非空）；正常访问传 NULL 覆盖旧链接来源
     */
    void upsert(Long userId, Long pageId, Long linkId);

    /**
     * 按用户倒序取访问记录（用于门户最近访问）。
     */
    List<RepoPageVisitDomain> listByUserId(Long userId, int limit);

    /**
     * 移除某用户对某页面的访问记录（门户行菜单）。
     */
    boolean remove(Long userId, Long pageId);

    /**
     * 移除由指定分享链接产生的全部访问记录（删除分享链接时级联清理「最近访问」）。
     *
     * @return 受影响行数
     */
    int removeByLinkId(Long linkId);
}
