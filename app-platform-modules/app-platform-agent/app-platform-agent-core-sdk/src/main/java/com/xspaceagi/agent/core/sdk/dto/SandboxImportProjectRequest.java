package com.xspaceagi.agent.core.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;
import org.springframework.web.multipart.MultipartFile;

@Data
@EqualsAndHashCode(callSuper = true)
public class SandboxImportProjectRequest extends SandboxFileBaseRequest {

    /**
     * 项目 zip 包
     */
    private MultipartFile file;
}
