package com.xspaceagi.custompage.sdk.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Data source DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataSourceDto implements Serializable {

    @Schema(description = "Data source type: plugin or workflow")
    private String type;

    @Schema(description = "Data source ID")
    private Long id;

    @Schema(description = "Data source key (may match ID on save)")
    private String key;

    @Schema(description = "Data source display name")
    private String name;

    @Schema(description = "Data source icon URL")
    private String icon;
}
