package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class I18nConfigDto {

    @Schema(description = "类型，系统 System；业务数据 BizData，暂时不用关注该字段", hidden = true)
    private String type;

    @Schema(description = "端")
    private String side;

    @Schema(description = "业务模块", hidden = true)
    private String module;

    @Schema(description = "业务模块具体记录ID", hidden = true)
    private String dataId;

    @Schema(description = "具体语言，中文：zh-cn，英文：en-us，等")
    private String lang;

    @Schema(description = "键")
    private String key;

    @Schema(description = "值")
    private String value;

    @Schema(description = "备注")
    private String remark;
}
