package com.xspaceagi.agent.core.infra.component.workflow.handler;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.dto.LoopNodeExecutingDto;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
public class WrappedNodeHandler implements NodeHandler {

    public NodeHandler nodeHandler;

    public WrappedNodeHandler(NodeHandler nodeHandler) {
        this.nodeHandler = nodeHandler;
    }

    @Override
    public Mono<Object> execute(WorkflowContext workflowContext, WorkflowNodeDto node) {
        try {
            if (workflowContext.isUseResultCache()) {
                Object value = workflowContext.getWorkflowContextServiceHolder().getRedisUtil().get(generateKey(workflowContext, node));
                if (value != null && JSON.isValid(value.toString())) {
                    NodeResult nodeResult = JSON.parseObject(value.toString(), NodeResult.class);
                    if (nodeResult != null) {
                        node.setUnreachableNextNodeIds(nodeResult.getUnreachableNextNodeIds());
                        if (nodeResult.getInput() != null && nodeResult.getInput() instanceof List<?>) {
                            workflowContext.getNodeExecuteInputMap().put(node.getId().toString(), (List<Map<String, Object>>) nodeResult.getInput());
                        }
                        return Mono.just(nodeResult.getResult());
                    }
                }
            }
        } catch (Exception e) {
            log.error("redis get error", e);
            return Mono.error(e);
        }

        return nodeHandler.execute(workflowContext, node)
                .doOnSuccess(result -> {
                    try {
                        if (!workflowContext.getAgentContext().isInterrupted()) {
                            NodeResult nodeResult = new NodeResult();
                            nodeResult.setResult(result);
                            nodeResult.setInput(workflowContext.getNodeExecuteInputMap().get(node.getId().toString()));
                            nodeResult.setUnreachableNextNodeIds(node.getUnreachableNextNodeIds());
                            workflowContext.getWorkflowContextServiceHolder().getRedisUtil().set(generateKey(workflowContext, node), JSONObject.toJSONString(nodeResult), 86400);
                        }
                    } catch (Exception e) {
                        log.error("redis set error", e);
                    }
                });
    }

    // 生成key
    private String generateKey(WorkflowContext workflowContext, WorkflowNodeDto node) {
        StringBuilder keyBuilder = new StringBuilder().append("wf_node_res:").append(workflowContext.getRequestId()).append(":").append(node.getId());
        if (workflowContext.getWorkflowPreIds() != null) {
            keyBuilder.append(":").append(workflowContext.getWorkflowPreIds());
        }
        if (workflowContext.getWorkflowLoopNodeIndex() != null) {
            keyBuilder.append(":").append(workflowContext.getWorkflowLoopNodeIndex());
        }
        if (node.getLoopNodeId() != null && node.getLoopNodeId() > 0) {
            LoopNodeExecutingDto loopNodeExecutingDto = workflowContext.getExecutingLoopNodeMap().get(node.getLoopNodeId().toString());
            if (loopNodeExecutingDto != null) {
                keyBuilder.append(":loop_index:").append(loopNodeExecutingDto.getIndex());
            }
        }
        return keyBuilder.toString();
    }

    @Data
    public static class NodeResult {
        private Object input;
        private Object result;
        private Set<Long> unreachableNextNodeIds;
    }
}
