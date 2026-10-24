package com.xspaceagi.repo.domain.repository;

import com.xspaceagi.repo.domain.model.RepoPageFavoriteDomain;

import java.util.List;

/**
 * 文档收藏仓储（门户「我的收藏」）。
 */
public interface RepoPageFavoriteRepository {

    /**
     * 收藏：同用户同页面唯一（uk_user_page），已软删则复活并刷新时间，否则新增。
     */
    void add(Long userId, Long pageId);

    /**
     * 取消收藏（软删置 yn=0）。
     */
    boolean remove(Long userId, Long pageId);

    /**
     * 是否已收藏。
     */
    boolean isFavorite(Long userId, Long pageId);

    /**
     * 按收藏时间倒序分页取收藏记录。
     */
    List<RepoPageFavoriteDomain> listByUserId(Long userId, int offset, int size);
}
