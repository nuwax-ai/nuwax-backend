package com.xspaceagi.custompage.ui.web.dto;

import com.xspaceagi.custompage.sdk.dto.SourceTypeEnum;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Frontend project update request body")
public class CustomPageUpdateReq {

    @NotNull(message = "projectId is required")
    @Schema(description = "Project ID", required = true)
    private Long projectId;

    @Schema(description = "Project name", required = true)
    private String projectName;

    @Schema(description = "Project description")
    private String projectDesc;

    @Schema(description = "Project icon URL")
    private String icon;

    @Schema(description = "Cover image URL")
    private String coverImg;

    @Schema(description = "Cover image source type")
    private SourceTypeEnum coverImgSourceType;

    @Schema(description = "Whether login is required (true = required, false = not required)")
    private Boolean needLogin;

}