package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Frontend project build request body")
public class CustomBuildReq {

    @NotBlank(message = "projectId is required")
    @Schema(description = "Project ID")
    private Long projectId;

    @NotBlank(message = "publishType is required")
    @Schema(description = "Publish type")
    private String publishType;

}
