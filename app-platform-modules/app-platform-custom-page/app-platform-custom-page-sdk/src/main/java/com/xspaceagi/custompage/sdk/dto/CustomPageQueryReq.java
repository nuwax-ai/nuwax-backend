package com.xspaceagi.custompage.sdk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Custom page list / page query filter
 */
@Data
@Schema(description = "Frontend project query request body")
public class CustomPageQueryReq {

    @Schema(description = "Space ID")
    private Long spaceId;

    @Schema(description = "Publish/build running filter")
    private Boolean buildRunning;

    @Schema(description = "User ID filter")
    private Long userId;

    @Schema(description = "Publish type filter")
    private PublishTypeEnum publishType;
}
