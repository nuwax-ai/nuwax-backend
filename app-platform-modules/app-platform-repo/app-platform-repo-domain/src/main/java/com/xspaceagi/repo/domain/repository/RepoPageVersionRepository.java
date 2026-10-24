package com.xspaceagi.repo.domain.repository;

import com.xspaceagi.repo.domain.model.RepoPageVersionDomain;

import java.util.List;

/**
 * 文档版本仓储接口。
 * <p>
 * 租户隔离由平台 MyBatis-Plus TenantLineInnerInterceptor 统一注入 _tenant_id 条件。
 */
public interface RepoPageVersionRepository {

    /**
     * 分页查询页面版本列表（按 id 倒序，不含 ydoc_snapshot/html 大字段）
     *
     * @param pageId 页面ID
     * @param offset 偏移量
     * @param size   分页大小
     * @return 版本列表（ydocSnapshot/html 为 null）
     */
    List<RepoPageVersionDomain> listByPageId(Long pageId, int offset, int size);

    /**
     * 统计页面版本总数
     *
     * @param pageId 页面ID
     * @return 版本数量
     */
    long countByPageId(Long pageId);

    /**
     * 按主键查询版本（含 ydoc_snapshot/html 大字段，供预览/恢复使用）
     *
     * @param id 版本ID
     * @return 版本领域模型，不存在返回 null
     */
    RepoPageVersionDomain findById(Long id);

    /**
     * 新建版本记录
     *
     * @param domain 版本领域模型
     * @return 自增主键
     */
    Long insert(RepoPageVersionDomain domain);
}
