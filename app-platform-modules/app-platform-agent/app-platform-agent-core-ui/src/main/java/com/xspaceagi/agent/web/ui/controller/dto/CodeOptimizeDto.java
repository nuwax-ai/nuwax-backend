package com.xspaceagi.agent.web.ui.controller.dto;

import com.xspaceagi.agent.core.spec.enums.CodeLanguageEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CodeOptimizeDto {

    @Schema(description = "请求ID，必须传，效果不理想时用于多论对话")
    @NotNull(message = "requestId is required")
    private String requestId;

    @Schema(description = "提示词")
    @NotNull(message = "Prompt is required")
    private String prompt;

    @Schema(description = "语言")
    private CodeLanguageEnum codeLanguage;
}