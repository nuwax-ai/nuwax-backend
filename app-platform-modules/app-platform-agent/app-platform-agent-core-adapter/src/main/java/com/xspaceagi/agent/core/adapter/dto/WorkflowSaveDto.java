package com.xspaceagi.agent.core.adapter.dto;

import com.alibaba.fastjson2.JSONObject;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class WorkflowSaveDto {

    @Schema(description = "工作流整体配置json", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Workflow JSON configuration is required")
    private JSONObject workflowConfig;

    @Schema(description = "强制提交", requiredMode = Schema.RequiredMode.REQUIRED)
    private boolean forceCommit;
}
