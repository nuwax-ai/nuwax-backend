package com.xspaceagi.custompage.ui.web.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Page argument schema request DTO
 */
@Data
@Schema(description = "Page argument schema request DTO")
public class PageArgConfigReq {

    @Schema(description = "Project ID", required = true)
    private Long projectId;

    @Schema(description = "Page URI path", required = true, example = "/view")
    private String pageUri;

    @Schema(description = "Page display name", example = "View page")
    private String name;

    @Schema(description = "Page description", example = "Page for viewing data")
    private String description;

    @Schema(description = "Argument definitions", required = true)
    private List<PageArgReq> args;

    @Data
    @Schema(description = "Page argument definition")
    public static class PageArgReq {
        @Schema(description = "Argument key (unique identifier)")
        private String key;

        @Schema(description = "Argument name (valid as identifier)", required = true, example = "userId")
        private String name;

        @Schema(description = "Detailed description", example = "User ID")
        private String description;

        @Schema(description = "Data type", required = true, example = "String")
        private String dataType;

        @Schema(description = "Whether required", example = "true")
        private Boolean require;

        @Schema(description = "Whether enabled (visible to model; default true)", example = "true")
        private Boolean enable;

        @Schema(description = "Default / bound value", example = "123")
        private String bindValue;

        @Schema(description = "Input type", example = "text")
        private String inputType;
    }
}
