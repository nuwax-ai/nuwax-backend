package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;

@Data
public class PluginUpdateDto<T> implements Serializable {

    @Schema(description =  "插件ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Plugin ID is required")
    private Long id;

    @Schema(description =  "插件名称")
    private String name;

    @Schema(description =  "插件描述")
    private String description;

    @Schema(description =  "插件图标")
    private String icon;

    @Schema(description =  "插件配置")
    private T config;
}