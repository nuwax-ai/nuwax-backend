package com.xspaceagi.repo.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档附件映射领域模型（文件本体在 file 模块 FileRecord）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoAttachmentDomain {

    /**
     * 主键ID
     */
    private Long id;

    /**
     * 租户ID
     */
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
     * 创建时间
     */
    private Date created;
}
