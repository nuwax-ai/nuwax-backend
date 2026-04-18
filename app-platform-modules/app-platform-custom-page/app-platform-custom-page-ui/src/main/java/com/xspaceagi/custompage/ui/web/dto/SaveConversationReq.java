package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Save conversation request")
public class SaveConversationReq {

    @Schema(description = "Project ID", required = true)
    @NotNull(message = "Project ID is required")
    private Long projectId;

    @Schema(description = "Conversation topic")
    @NotBlank(message = "Conversation content is required")
    private String topic;

    @Schema(description = "Conversation content", required = true)
    @NotBlank(message = "Conversation content is required")
    private String content;

    @Schema(description = "Conversation summary")
    private String summary;
}
