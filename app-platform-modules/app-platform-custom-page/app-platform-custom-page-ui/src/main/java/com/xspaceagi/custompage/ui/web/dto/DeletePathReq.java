package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Delete path configuration request DTO
 */
@Data
@Schema(description = "Delete path configuration request DTO")
public class DeletePathReq {

    @Schema(description = "Project ID", required = true)
    private Long projectId;

    @Schema(description = "Page URI path", required = true, example = "/view")
    private String pageUri;
}
