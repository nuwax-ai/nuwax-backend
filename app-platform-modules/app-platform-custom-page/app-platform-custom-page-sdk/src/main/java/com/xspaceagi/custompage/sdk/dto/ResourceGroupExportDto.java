package com.xspaceagi.custompage.sdk.dto;

import java.io.Serializable;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Resource group export DTO for project import/export.
 * Group name is always projectId, so only description and workflow bindings need exporting.
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Resource group export DTO")
public class ResourceGroupExportDto implements Serializable {

    @Schema(description = "Resource group description")
    private String description;

    @Schema(description = "Workflow data source keys bound to this group")
    private List<String> workflowKeys;
}
