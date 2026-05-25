package com.xspaceagi.modelproxy.sdk.service.dto;

import com.xspaceagi.system.sdk.common.TraceContext;
import lombok.Data;

@Data
public class BackendModelDto {
    private Long tenantId;
    private Long userId;
    private Long modelId;
    private String userName;
    private String requestId;
    private String conversationId;
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private String protocol;
    private String scope;
    private boolean enabled;
    private TraceContext traceContext;
}
