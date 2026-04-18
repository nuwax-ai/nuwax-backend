package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Keep-alive response body")
public class KeepAliveRes {

    @Schema(description = "Project ID")
    private Long projectId;

    @Schema(description = "Project ID as string")
    private String projectIdStr;

    @Schema(description = "Dev server URL")
    private String devServerUrl;

}
