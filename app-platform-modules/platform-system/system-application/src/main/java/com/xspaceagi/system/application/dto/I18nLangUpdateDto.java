package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

/**
 * 语言更新请求 DTO
 */
@Data
@Schema(description = "语言更新请求")
public class I18nLangUpdateDto implements Serializable {

    @NotNull(message = "Language ID is required")
    @Schema(description = "语言 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "语言名称，例如 简体中文")
    private String name;

    @Schema(description = "语言状态，0 停用；1 启用")
    private Integer status;

    @Schema(description = "是否为默认语言，0 否；1 是")
    private Integer isDefault;

    @Schema(description = "排序，值越小越靠前")
    private Integer sort;
}
