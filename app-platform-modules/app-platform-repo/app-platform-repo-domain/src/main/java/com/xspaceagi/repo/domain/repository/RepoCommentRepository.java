package com.xspaceagi.repo.domain.repository;

import com.xspaceagi.repo.domain.model.RepoCommentDomain;

import java.util.List;

/**
 * 文档评论仓储接口。
 * <p>
 * 租户隔离由平台 MyBatis-Plus TenantLineInnerInterceptor 统一注入 _tenant_id 条件。
 */
public interface RepoCommentRepository {

    /**
     * 新建评论
     *
     * @param domain 评论领域模型
     * @return 自增主键
     */
    Long insert(RepoCommentDomain domain);

    /**
     * 按主键查询有效评论
     *
     * @param id 评论ID
     * @return 评论领域模型，不存在返回 null
     */
    RepoCommentDomain findById(Long id);

    /**
     * 查询页面全部评论（含回复，按 id 升序）
     *
     * @param pageId 页面ID
     * @return 评论列表
     */
    List<RepoCommentDomain> listByPageId(Long pageId);

    /**
     * 更新解决状态
     *
     * @param id       评论ID
     * @param resolved 0未解决 1已解决
     * @return 是否更新成功
     */
    boolean updateResolved(Long id, int resolved);

    /**
     * 批量软删除评论
     *
     * @param ids 评论ID集合
     * @return 受影响行数
     */
    int softDeleteByIds(List<Long> ids);
}
