package com.xspaceagi.agent.core.sdk.dto;

import com.xspaceagi.system.sdk.common.TraceContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
public class AgentExecuteRequestDto {

    @Schema(description = "用户信息UserDto", requiredMode = Schema.RequiredMode.REQUIRED)
    private Object user;
    private Long agentId;
    @Schema(description = "会话ID")
    private String sessionId;
    private String message;
    private Map<String, Object> variables;

    @Schema(description = "调用轨迹")
    private TraceContext traceContext;
}
