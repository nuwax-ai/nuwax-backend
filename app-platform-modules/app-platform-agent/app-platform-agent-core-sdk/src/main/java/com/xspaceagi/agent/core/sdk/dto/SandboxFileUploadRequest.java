package com.xspaceagi.agent.core.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.web.multipart.MultipartFile;

@Data
@EqualsAndHashCode(callSuper = true)
public class SandboxFileUploadRequest extends SandboxFileBaseRequest {

    /**
     * 文件相对路径
     */
    private String filePath;

    /**
     * 上传文件
     */
    private MultipartFile file;
}
