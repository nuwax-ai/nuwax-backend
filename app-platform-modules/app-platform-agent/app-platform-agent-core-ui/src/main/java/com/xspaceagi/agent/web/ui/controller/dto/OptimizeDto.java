package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class OptimizeDto {

    @Schema(description = "请求ID，必须传，效果不理想时用于多论对话")
    @NotNull(message = "requestId is required")
    private String requestId;

    @Schema(description = "提示词")
    @NotNull(message = "Prompt is required")
    private String prompt;

    @Schema(description = "类型")
    private TypeEnum type;

    @Schema(description = "智能体ID或工作流节点ID，可选")
    private Long id;

    public enum TypeEnum {
        WORKFLOW_LLM_NODE,
        AGENT,
    }
}