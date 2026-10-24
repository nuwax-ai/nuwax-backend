package com.xspaceagi.repo.application.service;

import com.xspaceagi.repo.application.service.dto.RepoPortalPageVO;

import java.util.List;

/**
 * 门户应用服务（需求1：飞书式「最近」门户）。
 * <p>
 * 三个标签页：最近访问（访问记录 ∪ 我编辑过，范围全部空间，逐页权限过滤）、
 * 我分享的（我发起的空间分享 + 我创建的链接）、与我共享（分享进我空间的外部页面）。
 */
public interface RepoPortalApplicationService {

    /**
     * 最近访问：访问记录 ∪ 最近编辑，按最新时间倒序，逐页裁决可查看。
     *
     * @param from 偏移量
     * @param size 条数上限（&lt;=0 取默认，上限 200）
     */
    List<RepoPortalPageVO> listRecentlyAccessed(int from, int size);

    /**
     * 我分享的：我发起的空间分享 ∪ 我创建的分享链接对应页面，按分享时间倒序。
     */
    List<RepoPortalPageVO> listSharedByMe();

    /**
     * 与我共享：被分享进我的空间、且不属于我的空间的页面，按分享时间倒序。
     */
    List<RepoPortalPageVO> listSharedWithMe();

    /**
     * 我的资料：我所有成员空间内的页面（不含被分享进空间的外部页面），按编辑时间倒序分页。
     */
    List<RepoPortalPageVO> listMyResources(int from, int size);

    /**
     * 我的收藏：按收藏时间倒序分页。
     */
    List<RepoPortalPageVO> listFavorites(int from, int size);

    /**
     * 收藏页面（无查看权限抛 403）。
     */
    void favorite(Long pageId);

    /**
     * 取消收藏。
     */
    void unfavorite(Long pageId);

    /**
     * 是否已收藏。
     */
    boolean isFavorite(Long pageId);

    /**
     * 记录一次访问（登录态打开页面时调用；失败不影响主流程）。
     *
     * @param linkId 来源分享链接ID（通过链接访问时非空）；正常访问传 NULL 覆盖旧链接来源
     */
    void recordVisit(Long pageId, Long linkId);

    /**
     * 移除当前用户对页面的访问记录（门户行菜单）。
     */
    void removeVisit(Long pageId);
}
