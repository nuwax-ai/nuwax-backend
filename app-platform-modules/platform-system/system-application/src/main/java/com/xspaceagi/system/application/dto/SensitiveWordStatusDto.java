package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "敏感词状态更新请求")
public class SensitiveWordStatusDto {

    @NotNull(message = "ID is required")
    @Schema(description = "主键ID", required = true)
    private Long id;

    @NotNull(message = "Status is required")
    @Schema(description = "状态：1-启用、0-禁用", required = true)
    private Integer status;
}
