package com.xspaceagi.agent.core.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class SandboxFilesUpdateRequest extends SandboxFileBaseRequest {

    /**
     * 文件变更列表
     */
    private List<SandboxFileInfoDto> files;
}
