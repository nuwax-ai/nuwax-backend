package com.xspaceagi.system.sdk.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserAccessKeyDto {

    private Long id;

    private Long tenantId;

    private Long userId;

    @Schema(description = "名称")
    private String name;

    private AKTargetType targetType;

    private String targetId;

    @Schema(description = "API Key")
    private String accessKey;

    private UserAccessKeyConfig config;

    @Schema(description = "状态 0 停用; 1 启用")
    private Integer status;

    @Schema(description = "过期时间")
    private Date expire;

    private Creator creator;

    private Date created;

    @Data
    @Builder
    @AllArgsConstructor
    @NoArgsConstructor
    public static class UserAccessKeyConfig {

        @Schema(description = "是否开发模式,1 是；0 否")
        private Integer isDevMode;
        private Boolean enabled;
        private Long modelId;
        private String modelBaseUrl;
        private String modelApiKey;
        private String conversationId;
        private String modelName;
        private String protocol;
        private String scope;
        private String userName;// 用户名临时记录
        private String requestId;

        @Schema(description = "API 授权配置")
        private List<ApiConfig> apiConfigs;
    }

    @Data
    public static class ApiConfig {
        @Schema(description = "接口唯一Key")
        private String key;
        @Schema(description = "接口调用频率限制，每分钟调用次数")
        private Integer rpm;
    }

    @Data
    public static class Creator {
        private Long userId;
        private String userName;
    }

    public enum AKTargetType {
        Mcp, Agent, Sandbox, TempChat, Tenant, AgentModel, OpenApi
    }
}
