package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Bind dev Agent request body")
public class BindDevAgentReq {

    @NotNull(message = "projectId is required")
    @Schema(description = "Project ID")
    private Long projectId;

    @NotNull(message = "devAgentId is required")
    @Schema(description = "Dev Agent ID")
    private Long devAgentId;

}
