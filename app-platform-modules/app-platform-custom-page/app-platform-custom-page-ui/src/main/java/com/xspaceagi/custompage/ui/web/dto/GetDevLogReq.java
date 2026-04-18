package com.xspaceagi.custompage.ui.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "Dev server log query request body")
public class GetDevLogReq {

    @NotNull(message = "projectId is required")
    @Schema(description = "Project ID")
    private Long projectId;

    @Schema(description = "Start line index")
    private Integer startIndex;

    @Schema(description = "Log type: main (primary log) or temp (temporary log)")
    private String logType;

}
