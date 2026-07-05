package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class PermissionRequestResponseDto {

    @Schema(description = "Tool Id")
    private String toolId;
    @Schema(description = "Conversation Id")
    private Long conversationId;
    @Schema(description = "Option")
    private Option option;

    @Data
    public static class Option {
        @Schema(description = "Option Id")
        private String optionId;
        private String outcome;
    }
}
