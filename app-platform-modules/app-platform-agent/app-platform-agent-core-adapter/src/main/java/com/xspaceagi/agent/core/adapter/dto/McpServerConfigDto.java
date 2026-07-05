package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "MCP Server配置")
public class McpServerConfigDto implements Serializable {

    @Schema(description = "MCP Server类型，stdio、http、streamable-http、sse、ws")
    private String type;

    @Schema(description = "远程HTTP端点URL")
    private String url;

    @Schema(description = "启动命令，如 npx、python（type为stdio时使用）")
    private String command;

    @Schema(description = "命令参数列表（type为stdio时使用）")
    private List<String> args;

    @Schema(description = "环境变量（type为stdio时使用）")
    private Map<String, String> env;
}
