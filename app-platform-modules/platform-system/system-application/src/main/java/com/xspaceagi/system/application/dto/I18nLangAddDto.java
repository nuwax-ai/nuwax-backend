package com.xspaceagi.system.application.dto;

import com.xspaceagi.system.application.validation.ValidI18nLanguageTag;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serializable;

/**
 * 语言创建请求 DTO
 */
@Data
@Schema(description = "语言创建请求")
public class I18nLangAddDto implements Serializable {

    @NotBlank(message = "Language name is required")
    @Schema(description = "语言名称，例如 简体中文", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @NotBlank(message = "Language tag is required")
    @ValidI18nLanguageTag
    @Schema(description = "语言标识（BCP 47），如 zh-CN、en-US", requiredMode = Schema.RequiredMode.REQUIRED)
    private String lang;

    @Schema(description = "语言状态，0 停用；1 启用", defaultValue = "1")
    private Integer status = 1;

    @Schema(description = "是否为默认语言，0 否；1 是", defaultValue = "0")
    private Integer isDefault = 0;

    @Schema(description = "排序，值越小越靠前", defaultValue = "0")
    private Integer sort = 0;
}
