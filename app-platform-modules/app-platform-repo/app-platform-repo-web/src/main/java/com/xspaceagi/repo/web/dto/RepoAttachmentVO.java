package com.xspaceagi.repo.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

/**
 * 文档附件视图对象
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RepoAttachmentVO {

    private Long id;

    private Long spaceId;

    /**
     * 关联页面ID，null=空间级附件
     */
    private Long pageId;

    private Long userId;

    /**
     * file 模块 FileRecord.fileKey
     */
    private String fileKey;

    private String fileName;

    private String mime;

    private Long size;

    private Date created;
}
