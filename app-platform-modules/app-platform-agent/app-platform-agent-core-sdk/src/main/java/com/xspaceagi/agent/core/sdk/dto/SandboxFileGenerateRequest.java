package com.xspaceagi.agent.core.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class SandboxFileGenerateRequest extends SandboxFileBaseRequest {

    /**
     * 文件名（含后缀，可为相对路径）
     */
    private String fileName;

    /**
     * 文本内容
     */
    private String content;
}
