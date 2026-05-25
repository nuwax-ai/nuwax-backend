package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;

@Data
@Schema(description = "批量翻译请求")
public class I18nBatchTranslateDto {

    @NotEmpty(message = "The key list cannot be empty.")
    @Schema(description = "待翻译的 fieldKey 列表", requiredMode = Schema.RequiredMode.REQUIRED)
    private List<String> keys;

    @NotBlank(message = "The source language cannot be empty.")
    @Schema(description = "源语言，如 zh-CN", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sourceLang;

    @NotBlank(message = "The target language cannot be empty.")
    @Schema(description = "目标语言，如 en-US", requiredMode = Schema.RequiredMode.REQUIRED)
    private String targetLang;
}
