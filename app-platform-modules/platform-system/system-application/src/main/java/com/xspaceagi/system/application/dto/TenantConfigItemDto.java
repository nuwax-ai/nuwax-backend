package com.xspaceagi.system.application.dto;

import com.xspaceagi.system.infra.dao.entity.TenantConfig;
import com.xspaceagi.system.spec.annotation.I18n;
import com.xspaceagi.system.spec.annotation.I18nField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@I18n(module = "TenantConfig")
@Data
public class TenantConfigItemDto implements Serializable {

    private Long tenantId;

    @I18nField(keyPrefix = true)
    @Schema(description = "配置项名称")
    private String name;

    @Schema(description = "配置项值")
    private Object value;

    @Schema(description = "配置项描述")
    private String description;

    @Schema(description = "配置项分类")
    private TenantConfig.ConfigCategory category;

    @Schema(description = "配置项输入类型")
    private TenantConfig.InputType inputType;

    @Schema(description = "配置项数据类型")
    private TenantConfig.DataType dataType;

    @Schema(description = "配置项提示")
    private String notice;

    @Schema(description = "配置项占位符")
    private String placeholder;

    @Schema(description = "配置项最小高度")
    private Integer minHeight;

    @Schema(description = "是否必填")
    private boolean required;

    @Schema(description = "排序", hidden = true)
    private Integer sort;
}
