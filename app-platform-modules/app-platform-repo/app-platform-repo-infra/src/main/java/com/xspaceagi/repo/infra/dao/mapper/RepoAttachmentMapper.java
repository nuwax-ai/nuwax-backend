package com.xspaceagi.repo.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.repo.infra.dao.entity.RepoAttachment;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档附件映射Mapper
 *
 * @description 针对表【repo_attachment(文档附件映射表)】的数据库操作Mapper
 * @Entity com.xspaceagi.repo.infra.dao.entity.RepoAttachment
 */
public interface RepoAttachmentMapper extends BaseMapper<RepoAttachment> {

    /**
     * 查询页面有效附件列表（按 id 倒序）。
     * 租户条件由平台 TenantLineInnerInterceptor 自动注入。
     *
     * @param pageId 页面ID
     * @return 附件列表
     */
    List<RepoAttachment> listByPageId(@Param("pageId") Long pageId);

    /**
     * 查询空间全部有效附件（含页面级与空间级，按 id 倒序）。
     *
     * @param spaceId 空间ID
     * @return 附件列表
     */
    List<RepoAttachment> listBySpaceId(@Param("spaceId") Long spaceId);

    /**
     * 软删除附件（置 yn=0）。
     *
     * @param id 附件ID
     * @return 受影响行数
     */
    int softDelete(@Param("id") Long id);

    /**
     * 按页面批量软删除附件（删除页面时级联清理）。
     *
     * @param pageIds 页面ID集合，非空
     * @return 受影响行数
     */
    int softDeleteByPageIds(@Param("pageIds") List<Long> pageIds);

    /**
     * 批量查询页面附件字节数（仅 page_id + size 两列，其余字段为 null）。
     * 树/列表的附件大小聚合用，避免为算大小而解析 content_snapshot JSON。
     * 租户条件由平台 TenantLineInnerInterceptor 自动注入。
     *
     * @param pageIds 页面ID集合
     * @return 附件行（仅 pageId/size 有值）
     */
    List<RepoAttachment> listSizeByPageIds(@Param("pageIds") List<Long> pageIds);
}
