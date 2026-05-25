package com.xspaceagi.agent.core.sdk.dto;

import com.xspaceagi.system.sdk.common.TraceContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class PluginExecuteRequestDto implements Serializable {
    private String requestId;
    private String config;
    // 用于传递通用智能体的绑定参数配置
    private String bindConfig;
    private Long spaceId;
    private Long userId;
    private Object user;
    private Map<String, Object> params;
    private boolean test;

    @Schema(description = "调用轨迹")
    private TraceContext traceContext;
}
