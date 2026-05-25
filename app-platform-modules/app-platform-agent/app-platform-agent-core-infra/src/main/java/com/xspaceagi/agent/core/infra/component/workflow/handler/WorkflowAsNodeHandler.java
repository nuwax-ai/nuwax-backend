package com.xspaceagi.agent.core.infra.component.workflow.handler;

import cn.hutool.core.collection.ConcurrentHashSet;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowAsNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowExecutor;
import com.xspaceagi.agent.core.infra.component.workflow.dto.LoopNodeExecutingDto;
import com.xspaceagi.log.sdk.vo.LogDocument;
import com.xspaceagi.system.sdk.common.TraceContext;
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
        workflowContext1.setTraceContext(workflowContext.getTraceContext().next(TraceContext.TraceTargetType.Workflow, workflowAsNodeConfigDto.getWorkflowConfig().getId().toString(),
                workflowAsNodeConfigDto.getWorkflowConfig().getName(), workflowAsNodeConfigDto.getWorkflowConfig().getDescription(), workflowAsNodeConfigDto.getWorkflowConfig().getIcon()));
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
        LogDocument logDocument = WorkflowExecutor.buildLogDocument(workflowContext1);
        return Mono.create(emitter -> WorkflowExecutor.execute0(workflowContext1, emitter, disposableAtomicReference))
                .doOnError(throwable -> WorkflowExecutor.logError(workflowContext1, throwable, logDocument))
                .doOnSuccess(result -> WorkflowExecutor.logSuccess(workflowContext1, result, logDocument))
                .doOnCancel(() -> disposableAtomicReference.get().forEach(disposable -> {
                    if (!disposable.isDisposed()) {
                        disposable.dispose();
                    }
                }));
    }
}
