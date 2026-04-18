package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import javax.validation.constraints.NotNull;
import java.io.Serializable;
import java.util.Map;

@Data
public class AnalysisHttpPluginOutputDto implements Serializable {

    @Schema(description = "插件ID")
    @NotNull(message = "Plugin ID is required")
    private Long pluginId;

    private Map<String, Object> params;
}
