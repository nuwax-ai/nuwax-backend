package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Frontend project delete request body")
public class CustomPageDeleteReq {

    @NotNull(message = "projectId is required")
    @Schema(description = "Project ID", required = true)
    private Long projectId;

}
