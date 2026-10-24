package com.xspaceagi.repo.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档版本领域模型。
 * <p>
 * 列表查询不携带 ydocSnapshot/html 大字段，仅 findById 取全量（恢复版本用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoPageVersionDomain {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 租户ID
     */
    private Long tenantId;

    /**
     * 页面ID
     */
    private Long pageId;

    /**
     * 该版本的 Yjs 二进制快照（列表查询为 null）
     */
    private byte[] ydocSnapshot;

    /**
     * 该版本渲染HTML快照（列表查询为 null）
     */
    private String html;

    /**
     * 产生该版本的编辑人ID
     */
    private Long editorId;

    /**
     * 版本来源：autosave/manual/restore
     */
    private String reason;

    /**
     * 创建时间
     */
    private Date created;
}
