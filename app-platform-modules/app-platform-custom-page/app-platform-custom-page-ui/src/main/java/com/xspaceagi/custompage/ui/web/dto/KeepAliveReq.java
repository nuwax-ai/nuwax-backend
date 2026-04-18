package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Dev server keep-alive request body")
public class KeepAliveReq {

    @NotBlank(message = "projectId is required")
    @Schema(description = "Project ID")
    private Long projectId;

}
