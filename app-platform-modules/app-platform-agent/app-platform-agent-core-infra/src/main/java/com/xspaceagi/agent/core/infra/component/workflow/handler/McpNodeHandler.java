package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.McpNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.mcp.McpContext;
import com.xspaceagi.agent.core.infra.component.mcp.McpExecutor;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.dto.McpLogContent;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

public class McpNodeHandler extends AbstractNodeHandler {

    @Override
    public Mono<Object> execute(WorkflowContext workflowContext, WorkflowNodeDto node) {
        McpNodeConfigDto mcpNodeConfigDto = (McpNodeConfigDto) node.getNodeConfig();
        if (mcpNodeConfigDto.getMcp() == null){
            return Mono.error(BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowMcpReferenceRemoved));
        }
        Map<String, Object> params = extraBindValueMap(workflowContext, node, mcpNodeConfigDto.getInputArgs());
        McpContext mcpContext = McpContext.builder()
                .requestId(workflowContext.getAgentContext().getRequestId())
                .conversationId(workflowContext.getAgentContext().getConversationId())
                .user(workflowContext.getAgentContext().getUser())
                .mcpDto((McpDto) mcpNodeConfigDto.getMcp())
                .params(params)
                .name(mcpNodeConfigDto.getToolName())
                .build();
        AtomicReference<Disposable> disposableAtomicReference = new AtomicReference<>();
        return Mono.create(emitter -> {
            McpExecutor mcpExecutor = workflowContext.getWorkflowContextServiceHolder().getMcpExecutor();
            Disposable disposable = mcpExecutor.execute(mcpContext).doOnNext(mcpExecuteOutput -> {
                if (CollectionUtils.isNotEmpty(mcpExecuteOutput.getResult()) && !(mcpExecuteOutput.getResult().get(0) instanceof McpLogContent)) {
                    emitter.success(JSON.parseObject(JSON.toJSONString(mcpExecuteOutput)));//对象转map
                } else {
                    //LOG
                }
            }).doOnError(emitter::error).subscribe();
            disposableAtomicReference.set(disposable);
        }).doOnCancel(() -> {
            if (disposableAtomicReference.get() != null) {
                disposableAtomicReference.get().dispose();
            }
        });

    }
}
