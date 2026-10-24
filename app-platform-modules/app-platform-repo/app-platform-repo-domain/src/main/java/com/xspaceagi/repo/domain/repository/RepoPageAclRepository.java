package com.xspaceagi.repo.domain.repository;

import com.xspaceagi.repo.domain.model.RepoPageAclDomain;

import java.util.List;

/**
 * 文档级按人权限仓储接口（需求7）。
 */
public interface RepoPageAclRepository {

    /**
     * 查询页面的全部有效权限条目
     */
    List<RepoPageAclDomain> listByPageId(Long pageId);

    /**
     * 查询页面指定用户的权限条目，不存在返回 null
     */
    RepoPageAclDomain findByPageIdAndUserId(Long pageId, Long userId);

    /**
     * 全量覆盖页面权限条目：软删旧条目后批量写入新条目
     *
     * @param entries 新条目（可空=清空）
     */
    void replaceAll(Long pageId, List<RepoPageAclDomain> entries);
}
