package com.xspaceagi.repo.domain.repository;

import com.xspaceagi.repo.domain.model.RepoShareLinkVisitDomain;

import java.util.List;

/**
 * 分享链接访问日志仓储（分享链接「统计」用，append-only）。
 */
public interface RepoShareLinkVisitRepository {

    /**
     * 记录一次链接访问（append-only，每次访问插一行）。
     */
    void insert(RepoShareLinkVisitDomain domain);

    /**
     * 查询指定链接的全部访问日志（按访问时间倒序）。
     */
    List<RepoShareLinkVisitDomain> listByLinkId(Long linkId);

    /**
     * 物理删除指定链接的全部访问日志（删除分享链接时级联清理）。
     *
     * @return 受影响行数
     */
    int removeByLinkId(Long linkId);
}
