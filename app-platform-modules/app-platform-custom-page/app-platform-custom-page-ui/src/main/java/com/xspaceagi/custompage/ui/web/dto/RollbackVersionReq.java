package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class RollbackVersionReq {

    @Schema(description = "Project ID")
    private Long projectId;

    @Schema(description = "Version number to roll back to")
    private Integer rollbackTo;

}

