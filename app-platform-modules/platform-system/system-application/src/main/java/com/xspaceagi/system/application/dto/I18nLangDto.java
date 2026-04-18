package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 语言信息 DTO
 */
@Data
@Schema(description = "语言信息")
public class I18nLangDto implements Serializable {

    @Schema(description = "语言 ID")
    private Long id;

    @Schema(description = "语言名称，例如 简体中文")
    private String name;

    @Schema(description = "语言标识，中文：zh-cn，英文：en-us 等等")
    private String lang;

    @Schema(description = "语言状态，0 停用；1 启用")
    private Integer status;

    @Schema(description = "是否为默认语言，0 否；1 是")
    private Integer isDefault;

    @Schema(description = "排序，值越小越靠前")
    private Integer sort;

    @Schema(description = "更新时间")
    private Date modified;

    @Schema(description = "创建时间")
    private Date created;
}
