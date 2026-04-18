package com.xspaceagi.custompage.ui.web.dto;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * Batch reverse-proxy configuration request DTO
 */
@Data
@Schema(description = "Batch reverse-proxy configuration request DTO")
public class ProxyConfigBatchReq {

    @Schema(description = "Project ID", required = true)
    private Long projectId;

    @Schema(description = "Reverse-proxy configuration list", required = true)
    private List<ProxyConfigItem> proxyConfigs;

    @Data
    @Schema(description = "Reverse-proxy configuration item")
    public static class ProxyConfigItem {
        @Schema(description = "Environment", required = true, example = "dev")
        private String env;

        @Schema(description = "Path prefix", required = true, example = "/api")
        private String path;

        @Schema(description = "Backend upstream list", required = true)
        private List<BackendReq> backends;

        @Schema(description = "Health check path", example = "/health")
        private String healthCheckPath;

        @Schema(description = "Whether authentication is required", example = "true")
        private Boolean requireAuth;
    }

    @Data
    @Schema(description = "Backend upstream configuration")
    public static class BackendReq {
        @Schema(description = "Backend URL", required = true)
        private String backend;

        @Schema(description = "Weight", example = "1")
        private Integer weight;
    }
}
