package com.xspaceagi.agent.web.ui.controller.api.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class McpResultDTO {
    private String name;
    private String description;
    private String serverConfig;
    private String usedTool;
}
