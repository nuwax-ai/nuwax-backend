package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Frontend project build response body")
public class CustomBuildRes {

    @Schema(description = "Project ID")
    private Long projectId;

    @Schema(description = "Project ID as string")
    private String projectIdStr;

    @Schema(description = "Dev server URL")
    private String devServerUrl;

    @Schema(description = "Production server URL")
    private String prodServerUrl;

    // @Schema(description = "Agent ID")
    // private Long agentId;
}
