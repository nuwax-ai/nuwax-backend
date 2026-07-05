package com.xspaceagi.custompage.sdk.dto;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Project configuration export DTO
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "Project configuration export DTO")
public class ProjectConfigExportDto implements Serializable {

    @Schema(description = "Project name")
    private String name;

    @Schema(description = "Project description")
    private String description;

    @Schema(description = "Project icon URL")
    private String icon;

    @Schema(description = "Cover image URL")
    private String coverImg;

    @Schema(description = "Cover image source type")
    private SourceTypeEnum coverImgSourceType;

    @Schema(description = "Whether login is required")
    private Boolean needLogin;

    @Schema(description = "Reverse-proxy configuration")
    private List<ProxyConfig> proxyConfigs;

    @Schema(description = "Page argument schema list")
    private List<PageArgConfig> pageArgConfigs;

    @Schema(description = "Bound plugin data sources")
    private List<Map<String, Object>> dataSourcePlugins;

    @Schema(description = "Bound workflow data sources")
    private List<Map<String, Object>> dataSourceWorkflows;

    @Schema(description = "Project resource group and workflow bindings")
    private ResourceGroupExportDto resourceGroup;
}