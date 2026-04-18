package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class WorkflowUpdateDto {

    @Schema(description = "工作流ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "id is required")
    private Long id;

    @Schema(description = "工作流名称")
    private String name;

    @Schema(description = "工作流描述")
    private String description;

    @Schema(description = "图标地址")
    private String icon;

    @Schema(description = "扩展字段，用于前端存储画布位置等相关配置")
    private Map<String, Object> extension;
}
