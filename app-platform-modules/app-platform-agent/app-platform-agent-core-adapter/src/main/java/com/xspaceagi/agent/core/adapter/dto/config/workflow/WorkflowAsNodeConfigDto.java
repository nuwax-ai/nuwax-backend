package com.xspaceagi.agent.core.adapter.dto.config.workflow;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 工作流作为节点配置
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowAsNodeConfigDto extends NodeConfigDto {

    @Schema(description = "工作流ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Workflow ID is required")
    private Long workflowId;

    @Schema(description = "工作流配置", hidden = true)
    private WorkflowConfigDto workflowConfig;
}
