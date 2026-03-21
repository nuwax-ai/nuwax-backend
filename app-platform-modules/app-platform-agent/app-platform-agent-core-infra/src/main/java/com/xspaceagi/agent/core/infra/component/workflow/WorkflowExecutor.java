package com.xspaceagi.agent.core.infra.component.workflow;

import cn.hutool.core.collection.ConcurrentHashSet;
import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.ExceptionHandleConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.NodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.adapter.repository.entity.WorkflowNodeConfig;
import com.xspaceagi.agent.core.infra.code.CodeExecuteService;
import com.xspaceagi.agent.core.infra.component.AsyncExecuteResponseHandler;
import com.xspaceagi.agent.core.infra.component.knowledge.KnowledgeBaseSearcher;
import com.xspaceagi.agent.core.infra.component.mcp.McpExecutor;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.infra.component.plugin.PluginExecutor;
import com.xspaceagi.agent.core.infra.component.table.TableExecutor;
import com.xspaceagi.agent.core.infra.component.workflow.dto.LoopNodeExecutingDto;
import com.xspaceagi.agent.core.infra.component.workflow.dto.NodeExecuteResult;
import com.xspaceagi.agent.core.infra.component.workflow.dto.NodeExecutingDto;
import com.xspaceagi.agent.core.infra.component.workflow.enums.NodeExecuteStatus;
import com.xspaceagi.agent.core.infra.component.workflow.handler.HandlerFactory;
import com.xspaceagi.agent.core.infra.component.workflow.handler.NodeHandler;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import com.xspaceagi.log.sdk.service.ILogRpcService;
import com.xspaceagi.log.sdk.vo.LogDocument;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.spec.utils.HttpClient;
import com.xspaceagi.system.spec.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 工作流执行引擎
 */
@Slf4j
@Component
public class WorkflowExecutor {

    private AsyncExecuteResponseHandler asyncExecuteResponseHandler;

    private ILogRpcService iLogRpcService;

    private ChatMemory chatMemory;

    private ModelInvoker modelInvoker;

    private KnowledgeBaseSearcher knowledgeBaseSearcher;

    private McpExecutor mcpExecutor;

    private PluginExecutor pluginExecutor;

    private TableExecutor tableExecutor;

    private RedisUtil redisUtil;

    private CodeExecuteService codeExecuteService;


    private HttpClient httpClient;

    @Autowired
    public void setHttpClient(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Autowired
    public void setAsyncExecuteResponseHandler(AsyncExecuteResponseHandler asyncExecuteResponseHandler) {
        this.asyncExecuteResponseHandler = asyncExecuteResponseHandler;
    }

    @Autowired
    public void setLogRpcService(ILogRpcService iLogRpcService) {
        this.iLogRpcService = iLogRpcService;
    }


    @Autowired
    public void setChatMemory(ConversationApplicationService chatMemory) {
        this.chatMemory = (ChatMemory) chatMemory;
    }

    @Autowired
    public void setModelInvoker(ModelInvoker modelInvoker) {
        this.modelInvoker = modelInvoker;
    }

    @Autowired
    public void setRedisUtil(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
    }

    @Autowired
    public void setCodeExecuteService(CodeExecuteService codeExecuteService) {
        this.codeExecuteService = codeExecuteService;
    }

    @Autowired
    public void setKnowledgeBaseSearcher(KnowledgeBaseSearcher knowledgeBaseSearcher) {
        this.knowledgeBaseSearcher = knowledgeBaseSearcher;
    }

    @Autowired
    public void setMcpExecutor(McpExecutor mcpExecutor) {
        this.mcpExecutor = mcpExecutor;
    }

    @Autowired
    public void setPluginExecutor(PluginExecutor pluginExecutor) {
        this.pluginExecutor = pluginExecutor;
    }

    @Autowired
    public void setTableExecutor(TableExecutor tableExecutor) {
        this.tableExecutor = tableExecutor;
    }

    /**
     * 执行工作流
     *
     * @param workflowContext 工作流执行上下文
     * @return Mono
     */
    public Mono<Object> execute(WorkflowContext workflowContext) {
        if (workflowContext.getWorkflowContextServiceHolder() == null) {
            WorkflowContextServiceHolder workflowContextServiceHolder = getWorkflowContextServiceHolder();
            workflowContext.setWorkflowContextServiceHolder(workflowContextServiceHolder);
        }
        workflowContext.setAgentContext(workflowContext.getCopiedAgentContext());
        UserDto user = workflowContext.getAgentContext().getUser();
        LogDocument logDocument = LogDocument.builder()
                .tenantId(user.getTenantId())
                .id(UUID.randomUUID().toString().replace("-", ""))
                .requestId(workflowContext.getRequestId())
                .spaceId(workflowContext.getWorkflowConfig().getSpaceId())
                .userId(workflowContext.getAgentContext().getUserId())
                .userName(user.getNickName() == null ? user.getUserName() : user.getNickName())
                .targetType("Workflow")
                .targetName(workflowContext.getWorkflowConfig().getName())
                .targetId(workflowContext.getWorkflowConfig().getId().toString())
                .conversationId(workflowContext.getAgentContext().getConversationId())
                .input(JSON.toJSONString(workflowContext.getParams()))
                .requestStartTime(System.currentTimeMillis())
                .from(workflowContext.getFrom())
                .build();
        AtomicReference<Set<Disposable>> disposableAtomicReference = new AtomicReference<>(new ConcurrentHashSet<>());
        return Mono.create(emitter -> {
                    if (workflowContext.isAsyncExecute()) {
                        emitter.success(Map.of("output", "工具已经在异步执行中，请按后面的语句回复用户：" + (StringUtils.isBlank(workflowContext.getAsyncReplyContent()) ? "已经开始为您处理，请耐心等待运行结果" : workflowContext.getAsyncReplyContent())));
                        Mono<Object> mono = Mono.create(sink -> execute0(workflowContext, sink, disposableAtomicReference));
                        Disposable d = mono.doOnError(e -> {
                                    asyncExecuteResponseHandler.handleError(workflowContext.getAgentContext(), ComponentTypeEnum.Workflow, e);
                                    logError(workflowContext, e, logDocument);
                                })
                                .subscribe(result -> {
                                    asyncExecuteResponseHandler.handleWorkflowSuccess(workflowContext, result);
                                    logSuccess(workflowContext, result, logDocument);
                                });
                        disposableAtomicReference.get().add(d);
                    } else {
                        execute0(workflowContext, emitter, disposableAtomicReference);
                    }
                })
                .doOnSuccess(result -> logSuccess(workflowContext, result, logDocument))
                .doOnError(throwable -> logError(workflowContext, throwable, logDocument))
                .doOnCancel(() -> disposableAtomicReference.get().forEach(disposable -> {
                    if (!disposable.isDisposed()) {
                        disposable.dispose();
                    }
                }));
    }

    private @NotNull WorkflowContextServiceHolder getWorkflowContextServiceHolder() {
        WorkflowContextServiceHolder workflowContextServiceHolder = new WorkflowContextServiceHolder();
        workflowContextServiceHolder.setChatMemory(chatMemory);
        workflowContextServiceHolder.setWorkflowExecutor(this);
        workflowContextServiceHolder.setModelInvoker(modelInvoker);
        workflowContextServiceHolder.setKnowledgeBaseSearcher(knowledgeBaseSearcher);
        workflowContextServiceHolder.setMcpExecutor(mcpExecutor);
        workflowContextServiceHolder.setPluginExecutor(pluginExecutor);
        workflowContextServiceHolder.setTableExecutor(tableExecutor);
        workflowContextServiceHolder.setRedisUtil(redisUtil);
        workflowContextServiceHolder.setCodeExecuteService(codeExecuteService);
        workflowContextServiceHolder.setHttpClient(httpClient);
        return workflowContextServiceHolder;
    }

    private void logSuccess(WorkflowContext workflowContext, Object result, LogDocument logDocument) {
        try {
            logDocument.setProcessData(JSON.toJSONString(workflowContext.getNodeExecuteResultMap()));
            logDocument.setOutput(JSON.toJSONString(result));
            logDocument.setCreateTime(System.currentTimeMillis());
            logDocument.setResultCode("0000");
            logDocument.setResultMsg("成功");
            logDocument.setRequestEndTime(System.currentTimeMillis());
            iLogRpcService.bulkIndex(List.of(logDocument));
        } catch (Exception e) {
            // 忽略
            log.error("工作流日志记录异常", e);
        }
    }

    private void logError(WorkflowContext workflowContext, Throwable throwable, LogDocument logDocument) {
        try {
            logDocument.setProcessData(JSON.toJSONString(workflowContext.getNodeExecuteResultMap()));
            logDocument.setCreateTime(System.currentTimeMillis());
            logDocument.setResultCode("0001");
            logDocument.setResultMsg(throwable.getMessage());
            logDocument.setRequestEndTime(System.currentTimeMillis());
            iLogRpcService.bulkIndex(List.of(logDocument));
        } catch (Exception e) {
            // 忽略
            log.error("工作流日志记录异常", e);
        }
    }

    public static void execute0(WorkflowContext workflowContext, MonoSink<Object> emitter, AtomicReference<Set<Disposable>> disposableAtomicReference) {
        log.info("开始执行工作流，{}", workflowContext.getWorkflowConfig());
        //nodes转map
        Map<Long, WorkflowNodeDto> nodeMap = workflowContext.getWorkflowConfig().getNodes().stream().collect(Collectors.toMap(WorkflowNodeDto::getId, n -> n));
        workflowContext.getWorkflowConfig().getNodes().forEach(node -> {
            NodeConfigDto nodeConfig = node.getNodeConfig();
            if (nodeConfig.getExceptionHandleConfig() != null && ExceptionHandleConfigDto.ExceptionHandleTypeEnum.EXECUTE_EXCEPTION_FLOW == nodeConfig.getExceptionHandleConfig().getExceptionHandleType()) {
                List<Long> exceptionHandleNodeIds = nodeConfig.getExceptionHandleConfig().getExceptionHandleNodeIds();
                if (!CollectionUtils.isEmpty(exceptionHandleNodeIds) && node.getNextNodeIds() != null) {
                    List<Long> newExceptionHandleNodeIds = new ArrayList<>(exceptionHandleNodeIds);
                    node.getNextNodeIds().forEach(nextNodeId -> {
                        if (newExceptionHandleNodeIds.contains(nextNodeId)) {
                            newExceptionHandleNodeIds.remove(nextNodeId);
                        }
                    });
                    node.setOriginalNextNodeIds(new ArrayList<>(node.getNextNodeIds()));
                    node.getNextNodeIds().addAll(newExceptionHandleNodeIds);
                }
            }
            node.setInnerNodes(new ArrayList<>());
            node.setNextNodes(new ArrayList<>());
            node.setPreNodes(new ArrayList<>());
        });
        organizeNodeForExecute(nodeMap, workflowContext.getWorkflowConfig().getStartNode());
        workflowContext.setStartTime(System.currentTimeMillis());
        WorkflowNodeDto node = workflowContext.getWorkflowConfig().getStartNode();
        executeNode(workflowContext, node, null, emitter, disposableAtomicReference);
    }

    private static void organizeNodeForExecute(Map<Long, WorkflowNodeDto> nodeMap, WorkflowNodeDto nodeDto) {
        if (!CollectionUtils.isEmpty(nodeDto.getNextNodeIds())) {
            nodeDto.getNextNodeIds().forEach(nextNodeId -> {
                WorkflowNodeDto nextNode = nodeMap.get(nextNodeId);
                if (nextNode == null) {
                    return;
                }
                if (nodeDto.getLoopNodeId() != null && nodeDto.getLoopNodeId().equals(nextNode.getId())) {
                    return;
                }
                //给下级节点附上上级节点
                if (!nextNode.getPreNodes().contains(nodeDto)) {
                    nextNode.getPreNodes().add(nodeDto);
                }
                //给上级节点附上下级节点
                if (!nodeDto.getNextNodes().contains(nextNode)) {
                    nodeDto.getNextNodes().add(nextNode);
                }
                if (nextNode.getType() == WorkflowNodeConfig.NodeType.Loop) {
                    WorkflowNodeDto innerStartNode = nodeMap.get(nextNode.getInnerStartNodeId());
                    organizeNodeForExecute(nodeMap, innerStartNode);
                }
                organizeNodeForExecute(nodeMap, nextNode);
            });
        }
        if (nodeDto.getLoopNodeId() != null && nodeDto.getLoopNodeId() > 0) {
            WorkflowNodeDto loopNode = nodeMap.get(nodeDto.getLoopNodeId());
            if (loopNode != null && !loopNode.getInnerNodes().contains(nodeDto)) {
                loopNode.getInnerNodes().add(nodeDto);
            }
        }
    }

    /**
     * 执行工作流节点
     *
     * @param workflowContext 工作流上下文
     * @param node            节点
     * @param emitter         MonoSink
     */
    public static void executeNode(WorkflowContext workflowContext, WorkflowNodeDto node, LoopNodeExecutingDto loopNodeExecutingDto, MonoSink<Object> emitter, AtomicReference<Set<Disposable>> disposableAtomicReferences) {
        log.info("开始执行节点，ID {}, 名称 {}", node.getId(), node.getName());
        if (workflowContext.getAgentContext().isInterrupted()) {
            log.info("Agent中断执行，停止执行节点，ID {}, 名称 {}", node.getId(), node.getName());
            emitter.success(Map.of());
            return;
        }
        final Map<String, NodeExecuteResult> nodeExecuteResultMap;
        final Map<String, NodeExecuteStatus> nodeExecuteStatusMap;
        if (loopNodeExecutingDto != null) {
            //本次循环内部已经在某个分支上执行完成退出了
            if (loopNodeExecutingDto.isDone()) {
                return;
            }
            nodeExecuteResultMap = loopNodeExecutingDto.getNodeExecuteResultMap();
            nodeExecuteStatusMap = loopNodeExecutingDto.getNodeExecuteStatusMap();
        } else {
            nodeExecuteResultMap = workflowContext.getNodeExecuteResultMap();
            nodeExecuteStatusMap = workflowContext.getNodeExecuteStatusMap();
        }

        Long startTime = System.currentTimeMillis();
        nodeExecuteStatusMap.put(node.getId().toString(), NodeExecuteStatus.EXECUTING);
        sendNodeExecutingStatus(workflowContext, node, nodeExecuteStatusMap, nodeExecuteResultMap);
        NodeHandler handler = HandlerFactory.createNodeHandler(node);
        Disposable disposable = handler.execute(workflowContext, node).doOnError(throwable -> {
                    if (workflowContext.getAgentContext().isInterrupted()) {
                        log.info("Agent中断执行，忽略执行异常，ID {}, 名称 {}", node.getId(), node.getName());
                        emitter.success(Map.of());
                        return;
                    }
                    List<Map<String, Object>> input = workflowContext.getNodeExecuteInputMap().getOrDefault(node.getId().toString(), new ArrayList<>());
                    workflowContext.getNodeExecuteInputMap().remove(node.getId().toString());
                    NodeExecuteResult errorNodeExecuteInfoDto = NodeExecuteResult.builder()
                            .success(false)
                            .input(input.size() > 0 ? input.get(input.size() - 1) : Map.of())
                            .error(throwable.getMessage())
                            .data(Map.of("ERROR_MESSAGE", throwable.getMessage() == null ? "" : throwable.getMessage()))
                            .startTime(startTime)
                            .endTime(System.currentTimeMillis())
                            .nodeId(node.getId())
                            .nodeName(node.getName())
                            .build();
                    nodeExecuteResultMap.put(node.getId().toString(), errorNodeExecuteInfoDto);
                    nodeExecuteStatusMap.put(node.getId().toString(), NodeExecuteStatus.FAILED);
                    sendNodeExecutingStatus(workflowContext, node, nodeExecuteStatusMap, nodeExecuteResultMap);
                    emitter.error(throwable);
                })
                .subscribe((result) -> {
                    if (workflowContext.getAgentContext().isInterrupted()) {
                        log.info("Agent中断执行，忽略执行结果，ID {}, 名称 {}", node.getId(), node.getName());
                        emitter.success(Map.of());
                        return;
                    }
                    Long nowMill = System.currentTimeMillis();
                    log.info("节点执行完毕\nID {}, 名称 {}\n耗时 {} ms", node.getId(), node.getName(), nowMill - startTime);
                    //log.debug("节点执行完毕\nID {}, 名称 {}\n结果 {} \n耗时 {} ms", node.getId(), node.getName(), result, nowMill - startTime);
                    List<Map<String, Object>> input = workflowContext.getNodeExecuteInputMap().getOrDefault(node.getId().toString(), new ArrayList<>());
                    workflowContext.getNodeExecuteInputMap().remove(node.getId().toString());
                    // 保存工作流执行日志以及结果
                    NodeExecuteResult nodeExecuteInfoDto = NodeExecuteResult.builder()
                            .startTime(startTime)
                            .success(true)
                            .endTime(nowMill)
                            .input(input.size() > 0 ? input.get(input.size() - 1) : Map.of())
                            .data(result)
                            .nodeId(node.getId())
                            .nodeName(node.getName())
                            .virtualExecute(node.isVirtualExecute())
                            .build();
                    if (result instanceof Map<?, ?>) {
                        if (((Map<?, ?>) result).size() == 1 && ((Map<?, ?>) result).containsKey("ERROR_MESSAGE")) {
                            nodeExecuteInfoDto.setError((String) ((Map<?, ?>) result).get("ERROR_MESSAGE"));
                            nodeExecuteInfoDto.setSuccess(false);
                        }
                    }
                    nodeExecuteResultMap.put(node.getId().toString(), nodeExecuteInfoDto);
                    nodeExecuteStatusMap.put(node.getId().toString(), nodeExecuteInfoDto.getSuccess() ? NodeExecuteStatus.FINISHED : NodeExecuteStatus.FAILED);
                    sendNodeExecutingStatus(workflowContext, node, nodeExecuteStatusMap, nodeExecuteResultMap);

                    if (node.getType() == WorkflowNodeConfig.NodeType.End) {
                        workflowContext.setEndTime(System.currentTimeMillis());
                        log.info("工作流执行完毕\nID {} 名称 {}\n结果 {}\n耗时 {} ms ", workflowContext.getWorkflowConfig().getId(), workflowContext.getWorkflowConfig().getName(), result, workflowContext.getEndTime() - workflowContext.getStartTime());
                        emitter.success(result);
                        return;
                    }
                    if (loopNodeExecutingDto != null && loopNodeExecutingDto.isDone()) {
                        return;
                    }
                    //循环结束节点
                    if (node.isInnerEndNode()) {
                        log.info("循环执行结束节点\nID {} 名称 {}\n结果 {}", node.getId(), node.getName(), result);
                        loopNodeExecutingDto.setDone(true);
                        emitter.success(result);
                        return;
                    }
                    //循环节点中断
                    if (node.getType() == WorkflowNodeConfig.NodeType.LoopBreak && !node.isVirtualExecute()) {
                        log.info("循环中断节点\nID {} 名称 {}\n结果 {}", node.getId(), node.getName(), result);
                        loopNodeExecutingDto.setBreakLoop(true);
                        emitter.success(result);
                        return;
                    }
                    //循环继续节点
                    if (node.getType() == WorkflowNodeConfig.NodeType.LoopContinue && !node.isVirtualExecute()) {
                        log.info("循环中断节点\nID {} 名称 {}\n结果 {}", node.getId(), node.getName(), result);
                        loopNodeExecutingDto.setContinueLoop(true);
                        emitter.success(result);
                        return;
                    }

                    if (node.getNextNodes() != null && node.getNextNodes().size() > 0) {
                        node.getNextNodes().forEach(nextNode -> {
                            nextNode.setUnreachableNextNodeIds(new HashSet<>());
                            NodeExecuteStatus status = nodeExecuteStatusMap.get(nextNode.getId().toString());
                            if (status != null && (status == NodeExecuteStatus.EXECUTING || status == NodeExecuteStatus.FINISHED)) {
                                return;
                            }
                            //检测节点上级节点是否已经都执行完毕
                            AtomicBoolean isPreNodesComplete = new AtomicBoolean(true);
                            //判断待执行节点是否在所有上级节点的不可到达的下级节点中，这决定了该节点是否需要真的执行
                            AtomicBoolean isAllInPreNodeUnreachableNextNodeIds = new AtomicBoolean(true);
                            if (nextNode.getPreNodes() != null) {
                                nextNode.getPreNodes().forEach(preNode -> {
                                    //需要更复杂的判断，解决逻辑分支意图相关的分支问题
                                    if (!nodeExecuteResultMap.containsKey(preNode.getId().toString())) {
                                        isPreNodesComplete.set(false);
                                    }
                                    if (!preNode.getUnreachableNextNodeIds().contains(nextNode.getId())) {
                                        isAllInPreNodeUnreachableNextNodeIds.set(false);
                                    }
                                });
                            }
                            if (isPreNodesComplete.get()) {
                                nextNode.setVirtualExecute(false);
                                if (isAllInPreNodeUnreachableNextNodeIds.get()) {
                                    //该节点无法真正执行，进行虚拟执行
                                    nextNode.setVirtualExecute(true);
                                    //下级节点也无法从这条线执行到
                                    nextNode.getUnreachableNextNodeIds().addAll(nextNode.getNextNodes().stream().map(WorkflowNodeDto::getId).collect(Collectors.toList()));
                                }
                                //确保从不同分支过来的节点不重复执行
                                synchronized (nextNode) {
                                    status = nodeExecuteStatusMap.get(nextNode.getId().toString());
                                    if (status == null || (status != NodeExecuteStatus.FINISHED && status != NodeExecuteStatus.EXECUTING)) {
                                        try {
                                            executeNode(workflowContext, nextNode, loopNodeExecutingDto, emitter, disposableAtomicReferences);
                                        } catch (Exception e) {
                                            log.error("节点执行异常", e);
                                            nodeExecuteStatusMap.put(nextNode.getId().toString(), NodeExecuteStatus.FAILED);
                                            emitter.error(new RuntimeException("节点<" + nextNode.getName() + ">执行异常"));
                                        }
                                    }
                                }
                            }
                        });
                    }
                });
        disposableAtomicReferences.get().add(disposable);
    }

    private static void sendNodeExecutingStatus(WorkflowContext workflowContext, WorkflowNodeDto node, Map<String, NodeExecuteStatus> nodeExecuteStatusMap, Map<String, NodeExecuteResult> nodeExecuteResultMap) {
        if (workflowContext.getNodeExecutingConsumer() != null && !node.isVirtualExecute()) {
            NodeExecutingDto nodeExecutingDto = new NodeExecutingDto();
            nodeExecutingDto.setStatus(nodeExecuteStatusMap.get(node.getId().toString()));
            nodeExecutingDto.setResult(nodeExecuteResultMap.get(node.getId().toString()));
            nodeExecutingDto.setNodeId(node.getId());
            nodeExecutingDto.setName(node.getName());
            workflowContext.getNodeExecutingConsumer().accept(nodeExecutingDto);
        }
    }

    /**
     * 单节点调试执行
     *
     * @param workflowContext 工作流上下文
     * @param node            节点
     * @return 节点执行结果
     */
    public Mono<Object> testExecuteNode(WorkflowContext workflowContext, WorkflowNodeDto node) {
        if (workflowContext.getWorkflowContextServiceHolder() == null) {
            WorkflowContextServiceHolder workflowContextServiceHolder = getWorkflowContextServiceHolder();
            workflowContext.setWorkflowContextServiceHolder(workflowContextServiceHolder);
        }
        return HandlerFactory.createNodeHandler(node).execute(workflowContext, node);
    }

}
