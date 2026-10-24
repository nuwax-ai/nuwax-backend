package com.xspaceagi.agent.core.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class SandboxFilesUploadRequest extends SandboxFileBaseRequest {

    /**
     * 文件相对路径列表
     */
    private List<String> filePaths;

    /**
     * 上传文件列表
     */
    private List<MultipartFile> files;
}
