package com.xspaceagi.repo.domain.repository;

import com.xspaceagi.repo.domain.model.RepoAttachmentDomain;

import java.util.List;
import java.util.Map;

/**
 * 文档附件仓储接口。
 * <p>
 * 租户隔离由平台 MyBatis-Plus TenantLineInnerInterceptor 统一注入 _tenant_id 条件。
 */
public interface RepoAttachmentRepository {

    /**
     * 新建附件映射
     *
     * @param domain 附件领域模型
     * @return 自增主键
     */
    Long insert(RepoAttachmentDomain domain);

    /**
     * 按主键查询有效附件
     *
     * @param id 附件ID
     * @return 附件领域模型，不存在返回 null
     */
    RepoAttachmentDomain findById(Long id);

    /**
     * 查询页面附件列表（按 id 倒序）
     *
     * @param pageId 页面ID
     * @return 附件列表
     */
    List<RepoAttachmentDomain> listByPageId(Long pageId);

    /**
     * 查询空间全部附件（含页面级与空间级，按 id 倒序）
     *
     * @param spaceId 空间ID
     * @return 附件列表
     */
    List<RepoAttachmentDomain> listBySpaceId(Long spaceId);

    /**
     * 软删除附件
     *
     * @param id 附件ID
     * @return 是否删除成功
     */
    boolean softDelete(Long id);

    /**
     * 按页面批量软删除附件（删除页面时级联清理）
     *
     * @param pageIds 页面ID集合
     * @return 受影响行数
     */
    int softDeleteByPageIds(List<Long> pageIds);

    /**
     * 批量聚合页面附件字节数（pageId → size 合计）。
     * 树/列表的「大小」列用，避免解析 content_snapshot JSON。
     *
     * @param pageIds 页面ID集合
     * @return 页面ID → 附件字节数合计
     */
    Map<Long, Long> sumSizeByPageIds(List<Long> pageIds);
}
