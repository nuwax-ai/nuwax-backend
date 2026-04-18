package com.xspaceagi.custompage.ui.web.dto;

import com.xspaceagi.custompage.sdk.dto.CopyTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Copy project request
 */
@Data
public class CustomPageCopyReq {

    @Schema(description = "Source project ID", required = true)
    private Long projectId;

    @Schema(description = "Target space ID", required = true)
    private Long targetSpaceId;

    @Schema(description = "Target space / copy type", required = true)
    private CopyTypeEnum copyType;

}