package com.xspaceagi.agent.web.ui.controller.api.dto;

import lombok.Data;

import java.util.List;

@Data
public class DevAgentConfigDTO {

    private String systemPrompt;
    private String openingChatMsg;
    private List<ToolSearchResultItemDTO> tools;
    private List<SkillResultItemDTO> skills;
    private List<McpResultDTO> mcpConfigs;
}
