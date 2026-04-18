package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Project file content query response")
public class ProjectContentRes {

    @Schema(description = "Project file tree / content payload")
    private Object files;

    @Schema(description = "Frontend framework identifier")
    private String frontendFramework;

    @Schema(description = "Dev framework identifier")
    private String devFramework;
}
