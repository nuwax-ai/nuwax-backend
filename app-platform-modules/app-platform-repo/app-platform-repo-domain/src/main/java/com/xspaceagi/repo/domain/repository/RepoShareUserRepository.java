package com.xspaceagi.repo.domain.repository;

import com.xspaceagi.repo.domain.model.RepoShareUserDomain;

import java.util.List;

/**
 * 文档个人分享仓储接口（需求3）。
 * <p>
 * 租户隔离由平台 TenantLineInnerInterceptor 统一注入。
 */
public interface RepoShareUserRepository {

    /**
     * 查询页面的全部有效个人分享记录
     */
    List<RepoShareUserDomain> listByPageId(Long pageId);

    /**
     * 查询页面的「手动分享」记录（link_id 为 NULL，即非链接访问自动加入）。
     * 「分享给个人」页签与删除入口只应作用于手动分享，与链接分享相互独立。
     */
    List<RepoShareUserDomain> listManualByPageId(Long pageId);

    /**
     * 查询页面分享给指定用户的有效记录（访问裁决用）
     */
    List<RepoShareUserDomain> listByPageIdAndTargetUserId(Long pageId, Long targetUserId);

    /**
     * 查询分享给指定用户的全部有效记录（门户「与我共享」用），按分享时间倒序
     */
    List<RepoShareUserDomain> listByTargetUserId(Long targetUserId);

    /**
     * 按页面+目标用户查分享记录（含软删行，供 upsert 复活判断；唯一键含 yn，
     * 同 page+user 至多一行有效 + 一行已删）
     *
     * @return 分享记录，不存在返回 null
     */
    RepoShareUserDomain findByPageIdAndTargetUserIdIncludeDeleted(Long pageId, Long targetUserId);

    /**
     * 新增分享记录
     *
     * @return 自增主键
     */
    Long insert(RepoShareUserDomain domain);

    /**
     * 复活软删记录：yn 置回有效并覆盖权限位/分享人/链接来源（避免重复分享→取消→再分享撞唯一键）。
     *
     * @param linkId 来源分享链接ID；NULL 表示清除链接来源（个人分享）
     */
    boolean revive(Long id, Integer permBits, Long createdBy, Long linkId);

    /**
     * 更新权限位（同页面+用户重复分享时覆盖），并同步链接来源。
     *
     * @param linkId 来源分享链接ID；NULL 表示清除链接来源（个人分享）
     */
    boolean updatePermBits(Long id, Integer permBits, Long linkId);

    /**
     * 软删除单条分享
     */
    boolean softDelete(Long id);

    /**
     * 软删除页面的全部个人分享（页面删除/全部取消时级联）
     *
     * @return 受影响行数
     */
    int softDeleteByPageId(Long pageId);

    /**
     * 软删除由指定分享链接产生的全部记录（删除分享链接时级联清理「与我共享」）
     *
     * @return 受影响行数
     */
    int softDeleteByLinkId(Long linkId);
}
