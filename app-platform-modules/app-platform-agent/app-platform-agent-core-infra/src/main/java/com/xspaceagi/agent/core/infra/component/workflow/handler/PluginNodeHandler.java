package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.PluginNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.plugin.PluginContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.system.sdk.common.TraceContext;
import reactor.core.publisher.Mono;

import java.util.Map;

public class PluginNodeHandler extends AbstractNodeHandler {

    @Override
    public Mono<Object> execute(WorkflowContext workflowContext, WorkflowNodeDto node) {
        PluginNodeConfigDto pluginNodeConfigDto = (PluginNodeConfigDto) node.getNodeConfig();
        PluginContext pluginContext = new PluginContext();
        pluginContext.setAgentContext(workflowContext.getAgentContext());
        pluginContext.setRequestId(workflowContext.getRequestId());
        pluginContext.setPluginConfig((PluginConfigDto) pluginNodeConfigDto.getPluginConfig().getConfig());
        pluginContext.setPluginDto(pluginNodeConfigDto.getPluginConfig());
        Map<String, Object> params = extraBindValueMap(workflowContext, node, pluginNodeConfigDto.getInputArgs());
        pluginContext.setParams(params);
        pluginContext.setTraceContext(workflowContext.getTraceContext().next(TraceContext.TraceTargetType.Plugin, pluginNodeConfigDto.getPluginConfig().getId().toString(),
                pluginNodeConfigDto.getPluginConfig().getName(), pluginNodeConfigDto.getPluginConfig().getDescription(), pluginNodeConfigDto.getPluginConfig().getIcon()));
        return workflowContext.getWorkflowContextServiceHolder().getPluginExecutor().execute(pluginContext).flatMap(pluginExecuteResultDto -> {
            if (pluginExecuteResultDto.isSuccess() && pluginExecuteResultDto.getResult() != null) {
                return Mono.just(pluginExecuteResultDto.getResult());
            } else {
                return Mono.error(new Exception(pluginExecuteResultDto.getError()));
            }
        });
    }
}
