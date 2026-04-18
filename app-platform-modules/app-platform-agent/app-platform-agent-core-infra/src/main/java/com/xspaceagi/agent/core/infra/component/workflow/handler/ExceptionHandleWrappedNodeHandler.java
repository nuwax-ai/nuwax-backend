package com.xspaceagi.agent.core.infra.component.workflow.handler;

import cn.hutool.core.collection.ConcurrentHashSet;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.ExceptionHandleConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.adapter.repository.entity.WorkflowNodeConfig;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.system.spec.exception.AgentInterruptException;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class ExceptionHandleWrappedNodeHandler implements NodeHandler {
    public NodeHandler nodeHandler;

    public ExceptionHandleWrappedNodeHandler(NodeHandler nodeHandler) {
        this.nodeHandler = nodeHandler;
    }

    @Override
    public Mono<Object> execute(WorkflowContext workflowContext, WorkflowNodeDto node) {
        AtomicReference<Set<Disposable>> disposableAtomicReferences = new AtomicReference<>(new ConcurrentHashSet<>());
        return Mono.create(monoSink -> execute0(workflowContext, node, 0, monoSink, disposableAtomicReferences))
                .doOnCancel(() -> disposableAtomicReferences.get().forEach(disposable -> {
                    if (!disposable.isDisposed()) {
                        disposable.dispose();
                    }
                }));
    }

    private void execute0(WorkflowContext workflowContext, WorkflowNodeDto node, int tryCount, MonoSink<Object> monoSink, AtomicReference<Set<Disposable>> disposableAtomicReferences) {
        ExceptionHandleConfigDto exceptionHandleConfig = node.getNodeConfig().getExceptionHandleConfig();
        int timeout = exceptionHandleConfig.getTimeout() == null ? 180 : exceptionHandleConfig.getTimeout();
        if (node.getType() == WorkflowNodeConfig.NodeType.Loop) {
            // Loop node does not timeout
            timeout = Integer.MAX_VALUE;
        }

        Disposable disposable = nodeHandler.execute(workflowContext, node).timeout(Duration.ofSeconds(timeout))
                .onErrorResume(throwable -> {
                    if (throwable instanceof TimeoutException) {
                        return Mono.error(new TimeoutException("Execution timeout"));
                    }
                    return Mono.error(throwable);
                })
                .doOnError(throwable -> {
                    if (throwable instanceof AgentInterruptException) {
                        monoSink.error(throwable);
                        return;
                    }
                    if (exceptionHandleConfig.getRetryCount() != null && exceptionHandleConfig.getRetryCount() > 0 && exceptionHandleConfig.getRetryCount() > tryCount) {
                        execute0(workflowContext, node, tryCount + 1, monoSink, disposableAtomicReferences);
                        return;
                    }
                    if (exceptionHandleConfig.getExceptionHandleType() == ExceptionHandleConfigDto.ExceptionHandleTypeEnum.SPECIFIC_CONTENT) {
                        Map<String, Object> result = null;
                        if (exceptionHandleConfig.getSpecificContent() != null) {
                            if (exceptionHandleConfig.getSpecificContent() instanceof String && JSON.isValidObject(exceptionHandleConfig.getSpecificContent().toString())) {
                                result = JSONObject.parseObject(exceptionHandleConfig.getSpecificContent().toString());
                            } else if (exceptionHandleConfig.getSpecificContent() instanceof Map<?, ?>) {
                                result = (Map<String, Object>) exceptionHandleConfig.getSpecificContent();
                            }
                        }
                        if (result == null) {
                            result = new JSONObject();
                        }
                        result.put("ERROR_MESSAGE", throwable.getMessage());
                        monoSink.success(result);
                    } else if (exceptionHandleConfig.getExceptionHandleType() == ExceptionHandleConfigDto.ExceptionHandleTypeEnum.EXECUTE_EXCEPTION_FLOW) {
                        // Execute exception flow
                        List<Long> exceptionHandleNodeIds = exceptionHandleConfig.getExceptionHandleNodeIds();
                        Set<Long> unreachableNextNodeIds = new HashSet<>();
                        if (CollectionUtils.isNotEmpty(node.getNextNodeIds())) {
                            node.getNextNodeIds().forEach(nextNodeId -> {
                                // If child node is not in exception handling node list, add it to the unreachable child node ID set
                                if (exceptionHandleNodeIds != null && !exceptionHandleNodeIds.contains(nextNodeId)) {
                                    unreachableNextNodeIds.add(nextNodeId);
                                }
                            });
                        }
                        node.setUnreachableNextNodeIds(unreachableNextNodeIds);
                        monoSink.success(Map.of("ERROR_MESSAGE", throwable.getMessage()));
                    } else {
                        monoSink.error(throwable);
                    }
                }).doOnSuccess(result -> {
                    if (!node.isVirtualExecute() && exceptionHandleConfig.getExceptionHandleType() == ExceptionHandleConfigDto.ExceptionHandleTypeEnum.EXECUTE_EXCEPTION_FLOW) {
                        // Remove execution of exception flow
                        List<Long> originalNextNodeIds = node.getOriginalNextNodeIds();
                        Set<Long> unreachableNextNodeIds = new HashSet<>();
                        node.getNextNodeIds().forEach(nextNodeId -> {
                            // If child node is not in exception handling node list, add it to the unreachable child node ID set
                            if (originalNextNodeIds != null && !originalNextNodeIds.contains(nextNodeId)) {
                                unreachableNextNodeIds.add(nextNodeId);
                            }
                        });
                        node.setUnreachableNextNodeIds(unreachableNextNodeIds);
                    }
                    monoSink.success(result);
                }).subscribe();
        disposableAtomicReferences.get().add(disposable);
    }
}
