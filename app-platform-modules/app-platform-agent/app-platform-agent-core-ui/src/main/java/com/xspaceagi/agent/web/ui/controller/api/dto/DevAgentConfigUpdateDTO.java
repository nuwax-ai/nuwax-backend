package com.xspaceagi.agent.web.ui.controller.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class DevAgentConfigUpdateDTO {

    @Schema(description = "开发的AgentID，DEV_AGENT_ID 环境变量", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long devAgentId;

    @Schema(description = "系统提示词，留空不修改")
    private String systemPrompt;

    @Schema(description = "开始聊天提示词，留空不修改")
    private String openingChatMsg;
}
