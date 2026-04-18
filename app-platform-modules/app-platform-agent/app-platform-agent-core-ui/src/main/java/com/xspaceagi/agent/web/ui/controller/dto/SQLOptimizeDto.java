package com.xspaceagi.agent.web.ui.controller.dto;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class SQLOptimizeDto {

    @Schema(description = "请求ID，必须传，效果不理想时用于多论对话")
    @NotNull(message = "requestId is required")
    private String requestId;

    @Schema(description = "提示词", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Prompt is required")
    private String prompt;

    @Schema(description = "数据表ID，必须传", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Table ID is required")
    private Long tableId;

    @Schema(description = "节点已配置好的入参，可选")
    private List<Arg> inputArgs;
}