package com.xspaceagi.custompage.ui.web.dto;

import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Dev server log query response body")
public class GetDevLogRes {

    @Schema(description = "Project ID")
    private Long projectId;

    @Schema(description = "Log line entries")
    private List<Map<String, Object>> logs;

    @Schema(description = "Total line count")
    private Integer totalLines;

    @Schema(description = "Current start line index")
    private Integer startIndex;

    @Schema(description = "Whether result was served from cache")
    private Boolean cacheHit;

    @Schema(description = "Whether log file exceeded size limit")
    private Boolean fileTooLarge;

    @Schema(description = "Log file name")
    private String logFileName;

}