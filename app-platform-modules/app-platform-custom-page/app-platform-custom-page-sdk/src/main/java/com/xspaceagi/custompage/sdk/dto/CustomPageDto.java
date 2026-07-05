package com.xspaceagi.custompage.sdk.dto;

import java.util.Date;
import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Custom page (user web app) DTO
 */
@Data
@Schema(description = "Custom page project DTO")
public class CustomPageDto {

    @Schema(description = "Project ID")
    private Long projectId;

    @Schema(description = "Project ID as string")
    private String projectIdStr;

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

    @Schema(description = "Project base path")
    private String basePath;

    @Schema(description = "Publish/build running state (true = published / running)")
    private Boolean buildRunning;

    @Schema(description = "Last publish time")
    private Date buildTime;

    @Schema(description = "Publish version")
    private Integer buildVersion;

    @Schema(description = "Publish type")
    private PublishTypeEnum publishType;

    @Schema(description = "Code version")
    private Integer codeVersion;

    @Schema(description = "Version metadata")
    private Object versionInfo;

    @Schema(description = "Last selected chat model ID")
    private Long lastChatModelId;

    @Schema(description = "Last selected multimodal model ID")
    private Long lastMultiModelId;

    @Schema(description = "Whether login is required (true = required)")
    private Boolean needLogin;

    @Schema(description = "Dev / debug Agent ID")
    private Long devAgentId;

    @Schema(description = "Project type")
    private ProjectType projectType;

    @Schema(description = "Reverse-proxy configuration")
    private List<ProxyConfig> proxyConfigs;

    @Schema(description = "Page argument schema list")
    private List<PageArgConfig> pageArgConfigs;

    @Schema(description = "Bound data sources")
    private List<DataSourceDto> dataSources;

    @Schema(description = "Extension payload")
    private Object ext;

    @Schema(description = "Tenant ID")
    private Long tenantId;

    @Schema(description = "Space ID")
    private Long spaceId;

    @Schema(description = "Sandbox ID")
    private Long sandboxId;

    @Schema(description = "Created time")
    private Date created;

    @Schema(description = "Creator user ID")
    private Long creatorId;

    @Schema(description = "Creator username")
    private String creatorName;

    @Schema(description = "Creator nickname")
    private String creatorNickName;

    @Schema(description = "Creator avatar URL")
    private String creatorAvatar;

    @Schema(description = "Public page URL")
    private String pageUrl;
}