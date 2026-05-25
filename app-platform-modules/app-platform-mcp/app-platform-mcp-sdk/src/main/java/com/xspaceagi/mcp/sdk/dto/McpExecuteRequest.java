package com.xspaceagi.mcp.sdk.dto;

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
public class McpExecuteRequest implements Serializable {

    private String requestId;

    @Schema(description = "会话ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String sessionId;

    @Schema(description = "用户信息UserDto", requiredMode = Schema.RequiredMode.REQUIRED)
    private Object user;

    @Schema(description = "MCP详细信息", requiredMode = Schema.RequiredMode.REQUIRED)
    private McpDto mcpDto;

    @Schema(description = "执行类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private ExecuteTypeEnum executeType;

    @Schema(description = "MCP工具/资源/提示词名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    @Schema(description = "参数")
    private Map<String, Object> params;

    @Schema(description = "是否保持会话，保持会话时需要显示调用结束")
    private boolean keepAlive;

    private TraceContext traceContext;

    public enum ExecuteTypeEnum {
        TOOL,
        RESOURCE,
        PROMPT
    }
}