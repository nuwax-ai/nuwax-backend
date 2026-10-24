package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "敏感词检测请求")
public class SensitiveWordCheckDto {

    @NotBlank(message = "Text is required")
    @Schema(description = "待检测文本", required = true)
    private String text;
}
