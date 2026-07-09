package com.xspaceagi.agent.web.ui.controller.dto.api;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class ConvCreateDto implements Serializable {

    @Schema(description = "智能体ID")
    private Long agentId;

    @Schema(description = "会话主题")
    private String topic;

    @Schema(description = "会话变量内容")
    private Map<String, Object> variables;

    @Schema(description = "开发模式，为true时，智能体无需发布，变更实时生效")
    private Boolean devMode;
}
