package com.xspaceagi.agent.web.ui.controller.api.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ToolAddDTO {
    @Schema(description = "开发的AgentID，DEV_AGENT_ID 环境变量", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long devAgentId;
    private Published.TargetType targetType;
    private Long targetId;
}
