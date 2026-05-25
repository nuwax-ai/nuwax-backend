package com.xspaceagi.agent.core.sdk.dto;

import com.xspaceagi.system.sdk.common.TraceContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Map;

@Data
public class WorkflowExecuteRequestDto implements Serializable {
    private Object user;
    private String requestId;
    @Schema(description = "会话ID")
    private String conversationId;
    private Long workflowId;
    private Long spaceId;
    private Map<String, Object> params;
    private String config;
    // 用于传递通用智能体的绑定参数配置
    private String bindConfig;

    @Schema(description = "调用轨迹")
    private TraceContext traceContext;

    public TraceContext getTraceContext() {
        return traceContext == null ? traceContext = TraceContext.builder()
                .traceId(requestId)
                .traceTargets(new ArrayList<>())
                .conversationId(conversationId)
                .build() : traceContext;
    }
}
