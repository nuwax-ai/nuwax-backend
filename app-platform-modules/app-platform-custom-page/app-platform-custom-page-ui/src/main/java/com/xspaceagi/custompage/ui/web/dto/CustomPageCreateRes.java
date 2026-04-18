package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Frontend project create response body")
public class CustomPageCreateRes {

    @Schema(description = "Project ID")
    private Long projectId;

    @Schema(description = "Dev server URL")
    private String devServerUrl;

    @Schema(description = "Space ID")
    private Long spaceId;
}