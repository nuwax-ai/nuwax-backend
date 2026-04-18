package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "多语言配置分页查询条件")
public class I18nConfigQueryDto {

    @Schema(description = "端，如 Backend")
    private String side;

    @Schema(description = "语言，如 zh-cn")
    private String lang;

    @Schema(description = "业务模块，精确匹配")
    private String module;

    @Schema(description = "配置键 fieldKey，模糊匹配")
    private String key;

    @Schema(description = "配置值 fieldValue，模糊匹配")
    private String value;

    @Schema(description = "页码，从 1 开始", example = "1")
    private Long pageNo;

    @Schema(description = "每页条数", example = "20")
    private Long pageSize;
}
