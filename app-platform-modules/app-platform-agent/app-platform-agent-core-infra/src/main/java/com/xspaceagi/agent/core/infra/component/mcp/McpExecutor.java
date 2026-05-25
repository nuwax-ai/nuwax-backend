package com.xspaceagi.agent.core.infra.component.mcp;

import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.infra.component.ArgExtractUtil;
import com.xspaceagi.agent.core.infra.converter.ArgConverter;
import com.xspaceagi.agent.core.infra.rpc.McpRpcService;
import com.xspaceagi.mcp.sdk.dto.McpExecuteOutput;
import com.xspaceagi.mcp.sdk.dto.McpExecuteRequest;
import com.xspaceagi.mcp.sdk.dto.McpToolDto;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class McpExecutor {

    private McpRpcService mcpRpcService;

    @Autowired
    public void setMcpRpcService(McpRpcService mcpRpcService) {
        this.mcpRpcService = mcpRpcService;
    }

    public Flux<McpExecuteOutput> execute(McpContext mcpContext) {
        McpToolDto mcpToolDto = mcpContext.getMcpDto().getDeployedConfig().getTools().stream()
                .filter(tool -> tool.getName().equals(mcpContext.getName()))
                .findFirst().orElse(null);
        if (mcpToolDto == null) {
            return Flux.just(McpExecuteOutput.builder()
                    .success(false)
                    .message("MCP tool not found")
                    .build());
        }
        List<Arg> args = ArgConverter.convertMcpArgsToArgs(mcpToolDto.getInputArgs());
        Map<String, Object> params = new HashMap<>();
        try {
            args.forEach(arg -> params.put(arg.getName(), ArgExtractUtil.getTypeValue(arg.getDataType(), ArgExtractUtil.extraParams(arg, mcpContext.getParams()))));
        } catch (Exception e) {
            return Flux.just(McpExecuteOutput.builder()
                    .success(false)
                    .message(e.getMessage())
                    .build());
        }

        McpExecuteRequest mcpExecuteRequestDto = McpExecuteRequest.builder()
                .requestId(mcpContext.getRequestId())
                .sessionId(mcpContext.getConversationId())
                .user(mcpContext.getUser())
                .mcpDto(mcpContext.getMcpDto())
                .executeType(McpExecuteRequest.ExecuteTypeEnum.TOOL)
                .name(mcpContext.getName())
                .params(params)
                .keepAlive(true)
                .traceContext(mcpContext.getTraceContext())
                .build();
        return mcpRpcService.execute(mcpExecuteRequestDto).onErrorResume(Mono::error);
    }
}
