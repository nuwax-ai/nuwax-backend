package com.xspaceagi.agent.core.infra.component.mcp;

import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.system.sdk.common.TraceContext;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class McpContext implements Serializable {

    private String requestId;

    @Schema(description = "会话ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String conversationId;

    @Schema(description = "用户信息UserDto", requiredMode = Schema.RequiredMode.REQUIRED)
    private Object user;

    @Schema(description = "MCP详细信息", requiredMode = Schema.RequiredMode.REQUIRED)
    private McpDto mcpDto;

    @Schema(description = "MCP工具/资源/提示词名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    private TraceContext traceContext;

    @Schema(description = "参数")
    private Map<String, Object> params;
}