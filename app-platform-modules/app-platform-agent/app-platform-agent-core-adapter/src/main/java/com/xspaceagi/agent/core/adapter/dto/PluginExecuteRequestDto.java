package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class PluginExecuteRequestDto implements Serializable {

    @Schema(description = "请求ID")
    private String requestId;

    private Long agentId;

    @Schema(description = "插件ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long pluginId;

    @Schema(description = "插件参数")
    private Map<String, Object> params;

    private boolean test;

    private String apiKey;
}
