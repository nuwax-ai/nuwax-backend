package com.xspaceagi.custompage.ui.web.dto;

import org.springframework.web.multipart.MultipartFile;

import com.xspaceagi.custompage.sdk.dto.SourceTypeEnum;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "Frontend project create request body")
public class CustomPageCreateReq {

    @Schema(description = "Project ID; if set, updates the existing project")
    private Long projectId;

    @NotBlank(message = "projectName is required")
    @Schema(description = "Project name")
    private String projectName;

    @Schema(description = "Project description")
    private String projectDesc;

    @Schema(description = "Zip archive file")
    private MultipartFile file;

    @Schema(description = "Space ID")
    private Long spaceId;

    @Schema(description = "Project icon URL")
    private String icon;

    @Schema(description = "Cover image URL")
    private String coverImg;

    @Schema(description = "Cover image source type")
    private SourceTypeEnum coverImgSourceType;

}