package com.xspaceagi.agent.core.infra.component.workflow.handler;

import cn.hutool.core.collection.ConcurrentHashSet;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowAsNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowExecutor;
import com.xspaceagi.agent.core.infra.component.workflow.dto.LoopNodeExecutingDto;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

public class WorkflowAsNodeHandler extends AbstractNodeHandler {

    @Override
    public Mono<Object> execute(WorkflowContext workflowContext, WorkflowNodeDto node) {
        WorkflowAsNodeConfigDto workflowAsNodeConfigDto = (WorkflowAsNodeConfigDto) node.getNodeConfig();
        if (workflowAsNodeConfigDto.getWorkflowConfig() == null) {
            return Mono.just(new HashMap<>());
        }
        WorkflowContext workflowContext1 = new WorkflowContext();
        workflowContext1.setAgentContext(workflowContext.getAgentContext());
        workflowContext1.setRequestId(workflowContext.getRequestId());
        workflowContext1.setWorkflowConfig(workflowAsNodeConfigDto.getWorkflowConfig());
        workflowContext1.setNodeExecutingConsumer(workflowContext.getNodeExecutingConsumer());
        workflowContext1.setOriginalWorkflowId(workflowContext.getOriginalWorkflowId());
        workflowContext1.setUseResultCache(workflowContext.isUseResultCache());
        workflowContext1.setWorkflowPreIds(workflowContext.getWorkflowPreIds() == null ? node.getId().toString() : workflowContext.getWorkflowPreIds() + ":" + node.getId().toString());
        workflowContext1.setWorkflowContextServiceHolder(workflowContext.getWorkflowContextServiceHolder());
        if (node.getLoopNodeId() != null && node.getLoopNodeId() > 0) {
            LoopNodeExecutingDto loopNodeExecutingDto = workflowContext.getExecutingLoopNodeMap().get(node.getLoopNodeId().toString());
            if (loopNodeExecutingDto != null) {
                workflowContext1.setWorkflowLoopNodeIndex(workflowContext.getWorkflowLoopNodeIndex() == null ? loopNodeExecutingDto.getId().toString() + "-" + loopNodeExecutingDto.getIndex()
                        : workflowContext.getWorkflowLoopNodeIndex() + ":" + loopNodeExecutingDto.getId().toString() + "-" + loopNodeExecutingDto.getIndex());
            }
        } else {
            workflowContext1.setWorkflowLoopNodeIndex(workflowContext.getWorkflowLoopNodeIndex());
        }
        Map<String, Object> params = extraBindValueMap(workflowContext, node, workflowAsNodeConfigDto.getInputArgs());
        workflowContext1.setParams(params);
        AtomicReference<Set<Disposable>> disposableAtomicReference = new AtomicReference<>(new ConcurrentHashSet<>());
        return Mono.create(emitter -> WorkflowExecutor.execute0(workflowContext1, emitter, disposableAtomicReference))
                .doOnCancel(() -> disposableAtomicReference.get().forEach(disposable -> {
                    if (!disposable.isDisposed()) {
                        disposable.dispose();
                    }
                }));
    }
}
