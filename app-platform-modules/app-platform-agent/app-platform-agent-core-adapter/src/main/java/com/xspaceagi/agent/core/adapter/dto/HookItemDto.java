package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "单个Hook脚本配置")
public class HookItemDto implements Serializable {

    @Schema(description = "Hook类型，" +
            "command - 运行 shell 脚本、" +
            "http - 调用 HTTP 端点、" +
            "mcp_tool - 调用 MCP 工具、" +
            "prompt - 向 Claude 发送提示、" +
            "agent - 生成 subagent")
    private String type;

    @Schema(description = "要执行的命令")
    private String command;

    @Schema(description = "命令参数列表")
    private List<String> args;
}
