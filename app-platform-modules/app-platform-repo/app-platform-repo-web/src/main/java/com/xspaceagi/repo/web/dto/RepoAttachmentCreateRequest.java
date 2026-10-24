package com.xspaceagi.repo.web.dto;

import lombok.Data;

/**
 * 挂接附件请求（文件已先行上传至 file 模块）
 */
@Data
public class RepoAttachmentCreateRequest {

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
}
