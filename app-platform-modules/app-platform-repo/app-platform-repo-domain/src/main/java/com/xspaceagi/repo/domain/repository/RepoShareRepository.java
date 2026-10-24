package com.xspaceagi.repo.domain.repository;

import com.xspaceagi.repo.domain.model.RepoShareDomain;

import java.util.List;

/**
 * 文档空间分享仓储接口（需求4）。
 * <p>
 * 租户隔离由平台 TenantLineInnerInterceptor 统一注入。
 */
public interface RepoShareRepository {

    /**
     * 查询页面的全部有效分享记录
     */
    List<RepoShareDomain> listByPageId(Long pageId);

    /**
     * 查询页面在指定目标空间集合内的有效分享记录（访问裁决/搜索过滤用）
     */
    List<RepoShareDomain> listByPageIdAndTargetSpaceIds(Long pageId, List<Long> targetSpaceIds);

    /**
     * 批量查询多个页面在指定目标空间集合内的有效分享记录（搜索后置过滤用）
     */
    List<RepoShareDomain> listByPageIdsAndTargetSpaceIds(List<Long> pageIds, List<Long> targetSpaceIds);

    /**
     * 查询某用户发起的全部有效分享记录（门户「我分享的」用），按分享时间倒序
     */
    List<RepoShareDomain> listByCreatedBy(Long userId);

    /**
     * 查询目标空间在指定集合内的全部有效分享记录（门户「与我共享」用），按分享时间倒序
     */
    List<RepoShareDomain> listByTargetSpaceIds(List<Long> targetSpaceIds);

    /**
     * 批量查询多个页面的全部有效分享记录（空间主页「对外分享」用），按分享时间倒序
     */
    List<RepoShareDomain> listByPageIds(List<Long> pageIds);

    /**
     * 按页面+目标空间查分享记录（含软删行，供 upsert 复活判断；唯一键含 yn，
     * 同 page+space 至多一行有效 + 一行已删）
     *
     * @return 分享记录，不存在返回 null
     */
    RepoShareDomain findByPageIdAndTargetSpaceIdIncludeDeleted(Long pageId, Long targetSpaceId);

    /**
     * 新增分享记录
     *
     * @return 自增主键
     */
    Long insert(RepoShareDomain domain);

    /**
     * 复活软删记录：yn 置回有效并覆盖权限位/分享人（避免重复分享→取消→再分享撞唯一键）
     */
    boolean revive(Long id, Integer permBits, Long createdBy);

    /**
     * 更新权限位（同页面+空间重复分享时覆盖）
     */
    boolean updatePermBits(Long id, Integer permBits);

    /**
     * 软删除单条分享
     */
    boolean softDelete(Long id);

    /**
     * 软删除页面的全部空间分享（全部取消）
     *
     * @return 受影响行数
     */
    int softDeleteByPageId(Long pageId);

    /**
     * 软删除页面的指定空间分享
     */
    boolean softDeleteByPageIdAndSpaceId(Long pageId, Long targetSpaceId);
}
