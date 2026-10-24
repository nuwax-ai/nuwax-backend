package com.xspaceagi.repo.infra.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

/**
 * 文档版本快照实体
 * @TableName repo_page_version
 */
@TableName(value = "repo_page_version")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoPageVersion {

    /**
     * 主键ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 租户ID
     */
    @TableField(value = "_tenant_id")
    private Long tenantId;

    /**
     * 页面ID
     */
    private Long pageId;

    /**
     * 该版本的 Yjs 二进制快照
     */
    private byte[] ydocSnapshot;

    /**
     * 该版本渲染HTML快照
     */
    private String html;

    /**
     * 产生该版本的编辑人ID
     */
    private Long editorId;

    /**
     * 产生原因：autosave/manual/restore
     */
    private String reason;

    /**
     * 创建时间
     */
    private Date created;
}
