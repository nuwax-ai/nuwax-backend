package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Bind data source request
 */
@Data
public class BindDataSourceReq {

    @Schema(description = "Project ID", required = true)
    private Long projectId;

    @Schema(description = "Data source type: plugin or workflow", required = true)
    private String type;

    @Schema(description = "Data source ID", required = true)
    private Long dataSourceId;
}
