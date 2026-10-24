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
 * 文档附件映射实体（文件对象本体存于 file 模块 FileRecord）
 * @TableName repo_attachment
 */
@TableName(value = "repo_attachment")
@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RepoAttachment {

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
     * 所属空间ID
     */
    private Long spaceId;

    /**
     * 关联页面ID，null=空间级附件
     */
    private Long pageId;

    /**
     * 上传人ID
     */
    private Long userId;

    /**
     * file 模块 FileRecord.fileKey
     */
    private String fileKey;

    /**
     * 文件名
     */
    private String fileName;

    /**
     * MIME类型
     */
    private String mime;

    /**
     * 文件大小（字节）
     */
    private Long size;

    /**
     * 软删除标记：1有效 0删除
     */
    private Integer yn;

    /**
     * 创建时间
     */
    private Date created;
}
