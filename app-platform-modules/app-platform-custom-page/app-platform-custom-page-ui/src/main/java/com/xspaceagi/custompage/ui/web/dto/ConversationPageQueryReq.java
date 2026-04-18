package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Conversation page query filter
 */
@Data
@Schema(description = "Conversation page query request parameters")
public class ConversationPageQueryReq {

    @Schema(description = "Project ID", requiredMode = Schema.RequiredMode.REQUIRED, example = "1")
    private Long projectId;

}