package com.xspaceagi.agent.core.infra.component.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.base.Joiner;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AgentOutputDto;
import com.xspaceagi.agent.core.adapter.dto.ChatMessageDto;
import com.xspaceagi.agent.core.adapter.dto.KnowledgeSearchConfigDto;
import com.xspaceagi.agent.core.adapter.dto.PluginExecuteResultDto;
import com.xspaceagi.agent.core.adapter.dto.config.*;
import com.xspaceagi.agent.core.adapter.dto.config.bind.*;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.EndNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import com.xspaceagi.agent.core.infra.component.ArgExtractUtil;
import com.xspaceagi.agent.core.infra.component.BaseComponent;
import com.xspaceagi.agent.core.infra.component.agent.dto.AgentExecuteResult;
import com.xspaceagi.agent.core.infra.component.knowledge.KnowledgeBaseSearcher;
import com.xspaceagi.agent.core.infra.component.knowledge.SearchContext;
import com.xspaceagi.agent.core.infra.component.mcp.McpContext;
import com.xspaceagi.agent.core.infra.component.mcp.McpExecutor;
import com.xspaceagi.agent.core.infra.component.model.ModelContext;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.infra.component.model.dto.*;
import com.xspaceagi.agent.core.infra.component.plugin.PluginContext;
import com.xspaceagi.agent.core.infra.component.plugin.PluginExecutor;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowExecutor;
import com.xspaceagi.agent.core.spec.enums.*;
import com.xspaceagi.agent.core.spec.utils.PlaceholderParser;
import com.xspaceagi.compose.sdk.vo.define.TableDefineVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeQaVo;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.dto.McpExecuteOutput;
import com.xspaceagi.mcp.sdk.dto.McpToolDto;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.AgentInterruptException;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.I18nUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.xspaceagi.agent.core.spec.constant.Prompts.EXTRACT_PARAM_PROMPT;
import static com.xspaceagi.agent.core.spec.constant.Prompts.SUGGEST_PROMPT;
import static com.xspaceagi.agent.core.spec.enums.GlobalVariableEnum.CHAT_CONTEXT;

@Slf4j
@Component
public class AgentExecutor extends BaseComponent {

    private ConversationApplicationService conversationApplicationService;

    private ModelInvoker modelInvoker;

    private WorkflowExecutor workflowExecutor;

    private SandboxAgentClient sandboxAgentClient;

    private KnowledgeBaseSearcher knowledgeBaseSearcher;

    private McpExecutor mcpExecutor;

    private PluginExecutor pluginExecutor;

    @Autowired
    public void setConversationApplicationService(ConversationApplicationService conversationApplicationService) {
        this.conversationApplicationService = conversationApplicationService;
    }

    @Autowired
    public void setModelInvoker(ModelInvoker modelInvoker) {
        this.modelInvoker = modelInvoker;
    }

    @Autowired
    public void setWorkflowExecutor(WorkflowExecutor workflowExecutor) {
        this.workflowExecutor = workflowExecutor;
    }

    @Autowired
    public void setSandboxAgentClient(SandboxAgentClient sandboxAgentClient) {
        this.sandboxAgentClient = sandboxAgentClient;
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

    public Flux<AgentOutputDto> execute(AgentContext agentContext) {
        try {
            Assert.notNull(agentContext, "agentContext cannot be left blank.");
            Assert.notNull(agentContext.getAgentConfig(), "agentConfig cannot be left blank.");
            Assert.notNull(agentContext.getUserId(), "userId cannot be left blank.");
            Assert.notNull(agentContext.getMessage(), "message cannot be left blank.");
            Assert.notNull(agentContext.getConversationId(), "conversationId cannot be left blank.");
        } catch (Exception e) {
            log.warn("Parameter validation failed", e);
            return Flux.error(e);
        }

        agentContext.getAgentExecuteResult().setStartTime(System.currentTimeMillis());
        if (agentContext.getAgentConfig().getModelComponentConfig().getTargetConfig() == null || !(agentContext.getAgentConfig().getModelComponentConfig().getTargetConfig() instanceof ModelConfigDto)) {
            return Flux.error(new BizException("Incomplete agent model configuration. Please check if the associated model has been deleted."));
        }
        ModelBindConfigDto modelBindConfigDto = (ModelBindConfigDto) agentContext.getAgentConfig().getModelComponentConfig().getBindConfig();
        int contextRounds = modelBindConfigDto.getContextRounds() == null ? 0 : modelBindConfigDto.getContextRounds();
        if ("TaskAgent".equals(agentContext.getAgentConfig().getType())) {
            contextRounds = 10;// 用于后续补充上下文记忆
        }
        List<Message> contextMessages = new ArrayList<>();
        if (contextRounds > 0) {
            contextMessages.addAll(getRoundMessages(agentContext, contextRounds * 2));
        }
        agentContext.setContextMessages(contextMessages);

        //长期记忆
        if (agentContext.getAgentConfig().getOpenLongMemory() == AgentConfig.OpenStatus.Open) {
            try {
                //当前记忆加载机制个人信息预加载，其他信息按需查询
                boolean justKeywordMatch = true;
                agentContext.setLongMemory(conversationApplicationService.queryMemory(agentContext.getUser().getTenantId(),
                        agentContext.getUser().getId(), agentContext.getAgentConfig().getId(), agentContext.getOriginalMessage(), "", justKeywordMatch, agentContext.isFilterSensitive()));
            } catch (Exception e) {
                log.warn("Failed to query long-term memory", e);
            }
        }

        String userMessage = buildUserMessage(agentContext);
        agentContext.setMessage(userMessage);
        Sinks.Many<AgentOutputDto> sink = Sinks.many().multicast().onBackpressureBuffer();
        //优先执行变量组件
        AtomicReference<Disposable> nextDisposable = new AtomicReference<>();
        AtomicReference<Disposable> disposableAtomicReference = new AtomicReference<>();
        return sink.asFlux().doOnSubscribe(subscriber -> {
                    Disposable disposable = variableSet(agentContext, sink).doOnError(sink::tryEmitError).doOnSuccess((result) -> submit(() -> {
                        try {
                            if (agentContext.isInterrupted()) {
                                sink.tryEmitComplete();
                                return;
                            }
                            nextDisposable.set(doNext(agentContext, sink));
                        } catch (Exception e) {
                            log.error("Execution failed", e);
                            sink.tryEmitError(e);
                        }
                    })).subscribe();
                    disposableAtomicReference.set(disposable);
                })
                .publishOn(Schedulers.boundedElastic()).doOnCancel(() -> {
                    if (disposableAtomicReference.get() != null) {
                        disposableAtomicReference.get().dispose();
                    }
                    if (nextDisposable.get() != null) {
                        nextDisposable.get().dispose();
                    }
                });
    }

    private Disposable doNext(AgentContext agentContext, Sinks.Many<AgentOutputDto> sink) {
        log.debug("doNext agentContext:{}", agentContext);
        List<String> directOutputResults = new ArrayList<>();
        List<String> autoCallTools = new ArrayList<>();
        AtomicBoolean directOutputSuccess = new AtomicBoolean(true);
        String autoToolCallResult = invokeAndRemoveAutoComponents(agentContext, sink, directOutputResults, directOutputSuccess, autoCallTools);
        // 问答场景使用
        if (agentContext.isInterrupted()) {
            chatComplete(agentContext, sink, "", null, new ArrayList<>());
            return Flux.empty().subscribe();
        }

        // 添加到会话记录
        ChatMessageDto userMessageDto = ChatMessageDto.builder()
                .tenantId(agentContext.getAgentConfig().getTenantId())
                .role(ChatMessageDto.Role.USER)
                .senderType(ChatMessageDto.SenderType.USER)
                .senderId(agentContext.getUserId().toString())
                .userId(agentContext.getUserId())
                .agentId(agentContext.getAgentConfig().getId())
                .type(MessageTypeEnum.CHAT)
                .text(agentContext.getMessage())
                .id(UUID.randomUUID().toString().replace("-", ""))
                .time(new Date())
                .build();
        addRoundMessage(agentContext, userMessageDto);
        if (!directOutputResults.isEmpty()) {
            ChatMessageDto chatMessageDto = ChatMessageDto.builder()
                    .role(ChatMessageDto.Role.ASSISTANT)
                    .type(MessageTypeEnum.CHAT)
                    .text(Joiner.on("\n\n").join(directOutputResults))
                    .id(agentContext.getRequestId())
                    .time(new Date())
                    .finished(true)
                    .finishReason("DIRECT_OUTPUT")
                    .build();
            AgentOutputDto agentOutputDto = new AgentOutputDto();
            agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.MESSAGE);
            agentOutputDto.setRequestId(agentContext.getRequestId());
            agentOutputDto.setData(chatMessageDto);
            sink.tryEmitNext(agentOutputDto);
            if (directOutputSuccess.get()) {
                chatComplete(agentContext, sink, chatMessageDto.getText(), null, new ArrayList<>());
            } else {
                agentContext.getAgentExecuteResult().setEndTime(System.currentTimeMillis());
                agentContext.getAgentExecuteResult().setSuccess(false);
                agentContext.getAgentExecuteResult().setError(chatMessageDto.getText());
                agentOutputDto = buildFinalResultOutput(agentContext);
                sink.tryEmitNext(agentOutputDto);
                sink.tryEmitError(new RuntimeException(chatMessageDto.getText()));
            }
            return null;// 工作流自动执行，配置了直接输出
        }

        // 页面组件
        List<AgentComponentConfigDto> pageComponentConfigs = agentContext.getAgentConfig().getAgentComponentConfigList().stream().filter(agentComponentConfig -> {
            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Page) {
                PageBindConfigDto bindConfig = (PageBindConfigDto) agentComponentConfig.getBindConfig();
                return Objects.equals(bindConfig.getVisibleToLLM(), YesOrNoEnum.Y.getKey());
            }
            return false;
        }).collect(Collectors.toList());

        String systemPrompt = buildSystemPrompt(agentContext, pageComponentConfigs);
        agentContext.getAgentConfig().setSystemPrompt(systemPrompt);
        ModelContext modelContext = buildModelContext(agentContext, systemPrompt, agentContext.getMessage(), agentContext.getConversationId(), null, null);
        if (CollectionUtils.isNotEmpty(autoCallTools)) {
            List<Message> messages = new ArrayList<>();
            messages.add(new AssistantMessage(Joiner.on("\n\n").join(autoCallTools)));
            messages.add(new UserMessage(autoToolCallResult));
            //转用自定义函数调用流程
            modelContext.getModelConfig().setFunctionCall(ModelFunctionCallEnum.Unsupported);
            agentContext.setAutoToolCallMessages(messages);
        }

        //模型调用技能准备
        List<ComponentConfig> componentConfigList = convertToComponentConfigList(agentContext.getAgentConfig().getAgentComponentConfigList(), agentContext);
        if (!pageComponentConfigs.isEmpty()) {
            componentConfigList.addAll(buildPageBrowserComponentConfig(pageComponentConfigs, agentContext.getUser().getLangMap()));
        }
        modelContext.getModelCallConfig().setComponentConfigs(componentConfigList);

        //执行模型调用
        StringBuilder msgSb = new StringBuilder();
        List<ComponentExecutingDto> componentExecutedList = new ArrayList<>();
        //设置组件执行回调
        modelContext.setComponentExecutingConsumer((componentExecutingDto) -> {
            AgentOutputDto agentOutputDto = new AgentOutputDto();
            agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.PROCESSING);
            agentOutputDto.setRequestId(modelContext.getRequestId());
            agentOutputDto.setData(componentExecutingDto);
            sink.tryEmitNext(agentOutputDto);
            if (componentExecutingDto.getStatus() == ExecuteStatusEnum.FINISHED || componentExecutingDto.getStatus() == ExecuteStatusEnum.FAILED) {
                //组件执行完成后，将结果放入上下文
                agentContext.getAgentExecuteResult().getComponentExecuteResults().add(componentExecutingDto.getResult());
                //追加到模型调用结果
                try {
                    msgSb.append(buildProcessingText(componentExecutingDto));
                    componentExecutedList.add(componentExecutingDto);
                } catch (Exception e) {
                    // 忽略
                    log.error("Exception processing component execution result", e);
                }
            }
        });

        // sandbox补充使用
        agentContext.setComponentExecutingConsumer(modelContext.getComponentExecutingConsumer());

        Consumer<AgentOutputDto> outputConsumer = agentContext.getOutputConsumer();
        agentContext.setOutputConsumer((agentOutputDto) -> {
            if (agentOutputDto.getEventType() == AgentOutputDto.EventTypeEnum.MESSAGE && agentOutputDto.getData() instanceof ChatMessageDto) {
                msgSb.append(((ChatMessageDto) agentOutputDto.getData()).getText());
            }
            if (outputConsumer != null) {
                outputConsumer.accept(agentOutputDto);
            }
        });
        Flux<CallMessage> callMessageFlux;
        if ("TaskAgent".equals(agentContext.getAgentConfig().getType())) {
            // 替换工具技能名称占位符
            agentContext.setMessage(ModelInvoker.resetToolBlock(componentConfigList, agentContext.getMessage()));
            agentContext.getAgentConfig().setSystemPrompt(ModelInvoker.resetToolBlock(componentConfigList, agentContext.getAgentConfig().getSystemPrompt()));
            callMessageFlux = sandboxAgentClient.chat(agentContext);
        } else {
            callMessageFlux = modelInvoker.invoke(modelContext);
        }

        StringBuilder reasoningText = new StringBuilder();
        return callMessageFlux.doOnError(e -> chatError(agentContext, msgSb.toString(), reasoningText.toString(), sink, componentExecutedList, e))
                .doOnComplete(() -> chatComplete(agentContext, sink, msgSb.toString(), reasoningText.toString(), componentExecutedList))
                .doOnCancel(() -> {
                    chatComplete(agentContext, sink, msgSb.toString(), reasoningText.toString(), componentExecutedList);
                })
                .subscribe((res) -> {
                    if (agentContext.isInterrupted()) {
                        return;
                    }
                    if (res.getType() == MessageTypeEnum.THINK) {
                        reasoningText.append(res.getText());
                    } else {
                        msgSb.append(res.getText());
                    }
                    Sinks.EmitResult emitResult = sink.tryEmitNext(buildOutputMessage(agentContext, res));
                    if (emitResult.isFailure()) {
                        log.error("Agent output failed, cid {}, error {}", agentContext.getConversationId(), emitResult);
                    }
                }, throwable -> {
                    chatError(agentContext, msgSb.toString(), reasoningText.toString(), sink, componentExecutedList, throwable);
                    if (throwable instanceof AgentInterruptException) {
                        return;
                    }
                    log.error("Model invocation failed", throwable);
                });
    }

    public static String buildProcessingText(ComponentExecutingDto componentExecutingDto) {
        return String.format(
                "\n<div><markdown-custom-process executeId=\"%s\" type=\"%s\" status=\"%s\" name=\"%s\"></markdown-custom-process></div>\n\n",
                escapeHtmlAttr(componentExecutingDto.getResult().getExecuteId()),
                escapeHtmlAttr(componentExecutingDto.getType().name()),
                escapeHtmlAttr(componentExecutingDto.getStatus().name()),
                escapeHtmlAttr(componentExecutingDto.getName())
        );
    }

    private static String escapeHtmlAttr(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private void chatError(AgentContext agentContext, String message, String reasoningText, Sinks.Many<AgentOutputDto> sink, List<ComponentExecutingDto> componentExecutedList, Throwable e) {
        if (agentContext.getFinished().get()) {
            return;
        }
        agentContext.getFinished().set(true);
        if (StringUtils.isNotBlank(message)) {
            ChatMessageDto assistantMessage = ChatMessageDto.builder()
                    .tenantId(agentContext.getAgentConfig().getTenantId())
                    .role(ChatMessageDto.Role.ASSISTANT)
                    .senderType(ChatMessageDto.SenderType.AGENT)
                    .senderId(agentContext.getAgentConfig().getId().toString())
                    .userId(agentContext.getUserId())
                    .agentId(agentContext.getAgentConfig().getId())
                    .type(MessageTypeEnum.CHAT)
                    .text(message)
                    .think(reasoningText)
                    .id(UUID.randomUUID().toString().replace("-", ""))
                    .time(new Date())
                    .componentExecutedList(componentExecutedList.stream().map(componentExecutingDto -> (Object) componentExecutingDto).toList())
                    .build();
            addRoundMessage(agentContext, assistantMessage);
        } else {
            CallMessage callMessage = new CallMessage();
            callMessage.setText("```\n" + e.getMessage() + "\n```");
            callMessage.setType(MessageTypeEnum.CHAT);
            callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
            callMessage.setId(agentContext.getRequestId());
            callMessage.setFinished(true);
            callMessage.setFinishReason("ERROR");
            sink.tryEmitNext(buildOutputMessage(agentContext, callMessage));
        }
        if (agentContext.isInterrupted()) {
            agentContext.getAgentExecuteResult().setEndTime(System.currentTimeMillis());
            agentContext.getAgentExecuteResult().setSuccess(true);
            agentContext.getAgentExecuteResult().setError("Execution interrupted");
            AgentOutputDto agentOutputDto = buildFinalResultOutput(agentContext);
            sink.tryEmitNext(agentOutputDto);
            sink.tryEmitComplete();
            return;
        }
        log.error("Agent execution failed", e);
        agentContext.getAgentExecuteResult().setEndTime(System.currentTimeMillis());
        agentContext.getAgentExecuteResult().setSuccess(false);
        agentContext.getAgentExecuteResult().setError(e.getMessage());
        agentContext.getAgentExecuteResult().setOutputText(message);
        AgentOutputDto agentOutputDto = buildFinalResultOutput(agentContext);
        agentOutputDto.setError(e.getMessage());
        sink.tryEmitNext(agentOutputDto);
        sink.tryEmitError(e);
    }

    private void chatComplete(AgentContext agentContext, Sinks.Many<AgentOutputDto> sink, String outputText, String think, List<ComponentExecutingDto> componentExecutedList) {
        // 添加到上下文
        if (StringUtils.isNotBlank(outputText)) {
            ChatMessageDto assistantMessage = ChatMessageDto.builder()
                    .tenantId(agentContext.getAgentConfig().getTenantId())
                    .id(agentContext.getRequestId())
                    .senderType(ChatMessageDto.SenderType.AGENT)
                    .senderId(agentContext.getAgentConfig().getId().toString())
                    .userId(agentContext.getUserId())
                    .agentId(agentContext.getAgentConfig().getId())
                    .role(ChatMessageDto.Role.ASSISTANT)
                    .type(MessageTypeEnum.CHAT)
                    .text(outputText)
                    .think(think)
                    .componentExecutedList(componentExecutedList.stream().map(componentExecutingDto -> (Object) componentExecutingDto).toList())
                    .time(new Date())
                    .finished(true)
                    .build();
            addRoundMessage(agentContext, assistantMessage);
        }
        if (agentContext.getAgentExecuteResult().getSuccess() == null) {
            agentContext.getAgentExecuteResult().setSuccess(true);
        }
        agentContext.getAgentExecuteResult().setEndTime(System.currentTimeMillis());
        agentContext.getAgentExecuteResult().setOutputText(outputText);
        AgentOutputDto agentOutputDto = buildFinalResultOutput(agentContext);
        sink.tryEmitNext(agentOutputDto);
        sink.tryEmitComplete();
        //长期记忆总结
        if (agentContext.getAgentConfig().getOpenLongMemory() == AgentConfig.OpenStatus.Open) {
            try {
                if (!agentContext.getConversationId().startsWith("agent:")) {
                    log.info("Adding to summary queue, conversationId:{}", agentContext.getConversationId());
                    Long conversationId = Long.parseLong(agentContext.getConversationId());
                    conversationApplicationService.pushToSummaryQueue(conversationId);
                }
            } catch (Exception e) {
                log.error("Failed to add to summary queue, conversationId:{}", agentContext.getConversationId(), e);
                //do nothing
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void addRoundMessage(AgentContext agentContext, ChatMessageDto userMessageDto) {
        if (agentContext.isUseSate()) {
            userMessageDto.setComponentExecutedList(null);
            Map<String, Object> state = agentContext.getVariableParams();
            String key = buildAgentStateKey(agentContext);
            Map<String, Object> agentState = (Map<String, Object>) state.computeIfAbsent(key, k -> new HashMap<>());
            List<ChatMessageDto> chatMessages = (List<ChatMessageDto>) agentState.computeIfAbsent("roundMessageList", k -> new ArrayList<>());
            chatMessages.add(userMessageDto);
            ModelBindConfigDto modelBindConfigDto = (ModelBindConfigDto) agentContext.getAgentConfig().getModelComponentConfig().getBindConfig();
            int contextRounds = modelBindConfigDto.getContextRounds() == null ? 0 : modelBindConfigDto.getContextRounds();
            if ("TaskAgent".equals(agentContext.getAgentConfig().getType())) {
                contextRounds = 10;// 用于后续补充上下文记忆
            }
            if (contextRounds > 0 && chatMessages.size() > contextRounds) {
                chatMessages.subList(0, chatMessages.size() - contextRounds).clear();
            }
        } else {
            conversationApplicationService.addRoundMessage(agentContext.getConversationId(), userMessageDto);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Message> getRoundMessages(AgentContext agentContext, int ct) {
        if (agentContext.isUseSate()) {
            Map<String, Object> state = agentContext.getVariableParams();
            String key = buildAgentStateKey(agentContext);
            Map<String, Object> agentState = (Map<String, Object>) state.computeIfAbsent(key, k -> new HashMap<>());
            List<?> messages = (List<?>) agentState.get("roundMessageList");
            if (messages == null) {
                return new ArrayList<>();
            }
            List<ChatMessageDto> chatMessages = messages.stream().map(message -> {
                if (message instanceof ChatMessageDto chatMessageDto) {
                    return chatMessageDto;
                }
                return JSONObject.parseObject(JSON.toJSONString(message), ChatMessageDto.class);
            }).toList();
            return ChatMessageDto.toMessages(chatMessages);
        } else {
            return TenantFunctions.callWithIgnoreCheck(() -> conversationApplicationService.getRoundMessages(agentContext.getConversationId(), ct));
        }
    }

    private String buildAgentStateKey(AgentContext agentContext) {
        return "_agent" + agentContext.getAgentConfig().getId();
    }

    private Map<String, Object> extractAutoInvokeComponentRequireParamValues(AgentContext agentContext) {
        List<Arg> requireArgs = new ArrayList<>();
        List<Arg> allArgs = new ArrayList<>();
        agentContext.getAgentConfig().getAgentComponentConfigList().forEach(agentComponentConfigDto -> {
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Plugin) {
                PluginBindConfigDto pluginBindConfigDto = (PluginBindConfigDto) agentComponentConfigDto.getBindConfig();
                if (pluginBindConfigDto.getInvokeType() == PluginBindConfigDto.PluginInvokeTypeEnum.AUTO && CollectionUtils.isNotEmpty(pluginBindConfigDto.getInputArgBindConfigs())) {
                    checkAndSetRequireArgs(pluginBindConfigDto.getInputArgBindConfigs(), requireArgs, allArgs, agentComponentConfigDto.getId());

                }
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Workflow) {
                WorkflowBindConfigDto workflowBindConfigDto = (WorkflowBindConfigDto) agentComponentConfigDto.getBindConfig();
                if (workflowBindConfigDto.getInvokeType() == WorkflowBindConfigDto.WorkflowInvokeTypeEnum.AUTO && CollectionUtils.isNotEmpty(workflowBindConfigDto.getArgBindConfigs())) {
                    checkAndSetRequireArgs(workflowBindConfigDto.getArgBindConfigs(), requireArgs, allArgs, agentComponentConfigDto.getId());
                }
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Mcp) {
                McpBindConfigDto mcpBindConfigDto = (McpBindConfigDto) agentComponentConfigDto.getBindConfig();
                if (mcpBindConfigDto.getInvokeType() == McpBindConfigDto.McpInvokeTypeEnum.AUTO && CollectionUtils.isNotEmpty(mcpBindConfigDto.getInputArgBindConfigs())) {
                    checkAndSetRequireArgs(mcpBindConfigDto.getInputArgBindConfigs(), requireArgs, allArgs, agentComponentConfigDto.getId());
                }
            }
        });

        // 没有必填参数时直接过
        if (requireArgs.isEmpty()) {
            return new HashMap<>();
        }
        return extractParam(agentContext, allArgs);
    }

    private static void checkAndSetRequireArgs(List<Arg> argBindConfigs, List<Arg> requireArgs, List<Arg> allArgs, Long id) {
        if (argBindConfigs == null) {
            return;
        }
        argBindConfigs.forEach(argBindConfigDto -> {
            Arg arg = new Arg();
            BeanUtils.copyProperties(argBindConfigDto, arg);
            arg.setName(arg.getName() + "_" + id);
            allArgs.add(arg);
            if (argBindConfigDto.isRequire() && argBindConfigDto.getEnable()) {
                requireArgs.add(arg);
            }
        });
    }

    public static String buildUserMessage(AgentContext agentContext) {
        StringBuilder stringBuilder = new StringBuilder();
        //长期记忆
        if (StringUtils.isNotBlank(agentContext.getLongMemory())) {
            stringBuilder.append("\n<user-memory>\n").append(JSON.toJSONString(agentContext.getLongMemory())).append("\n</user-memory>\n");
        }
        boolean withUserPrompt = StringUtils.isNotBlank(agentContext.getAgentConfig().getUserPrompt());
        if (withUserPrompt) {
            String userPrompt = agentContext.getAgentConfig().getUserPrompt();
            if (StringUtils.isNotBlank(userPrompt)) {
                stringBuilder.append("<user-reminder>\n").append(PlaceholderParser.resoleAndReplacePlaceholder(agentContext.getVariableParams(), userPrompt)).append("\n</user-reminder>");
            }
        }

        if (CollectionUtils.isNotEmpty(agentContext.getAttachments())) {
            stringBuilder.append("\n<attachment>").append(JSON.toJSONString(agentContext.getAttachments())).append("</attachment>");
        }
        if (StringUtils.isBlank(agentContext.getMessage()) && CollectionUtils.isNotEmpty(agentContext.getAttachments())) {
            agentContext.setMessage("\n" + I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Agent.Message.AnalyzeAttachments"));
        }
        if (withUserPrompt) {
            stringBuilder.append("\n<user-message>").append(agentContext.getMessage()).append("</user-message>");
        } else {
            stringBuilder.append(agentContext.getMessage());
        }
        agentContext.setMessage(stringBuilder.toString());
        return agentContext.getMessage();
    }

    private static String buildSystemPrompt(AgentContext agentContext, List<AgentComponentConfigDto> pageComponentConfigs) {
        StringBuilder systemPromptBuilder = new StringBuilder();
        String systemPrompt = agentContext.getAgentConfig().getSystemPrompt();
        if (StringUtils.isNotBlank(systemPrompt)) {
            //变量替换
            systemPrompt = PlaceholderParser.resoleAndReplacePlaceholder(agentContext.getVariableParams(), systemPrompt);
        } else {
            systemPrompt = "";
        }
        systemPromptBuilder.append(systemPrompt);
        if (!pageComponentConfigs.isEmpty()) {
            StringBuilder pagePromptBuilder = new StringBuilder();
            for (AgentComponentConfigDto agentComponentConfig : pageComponentConfigs) {
                PageBindConfigDto bindConfig = (PageBindConfigDto) agentComponentConfig.getBindConfig();
                AtomicInteger index = new AtomicInteger(1);
                bindConfig.getPageArgConfigs().forEach(pageArgConfig -> {
                    pagePromptBuilder.append(index.getAndIncrement()).append(". ").append(pageArgConfig.getName()).append("-").append(pageArgConfig.getDescription()).append("\n");
                    pagePromptBuilder.append("URI:").append(pageArgConfig.getPageUrl(agentContext.getAgentConfig().getId())).append("\n");
                    pagePromptBuilder.append("ParameterJsonSchema:").append("\n```");
                    List<String> required = new ArrayList<>();
                    Map<String, Object> properties = new HashMap<>();
                    Map<String, Object> inputSchema = Map.of("type", "object", "properties", properties, "required", required);
                    pageArgConfig.getArgs().forEach(arg -> {
                        String description = arg.getDescription();
                        if (arg.getEnable() != null && !arg.getEnable() && StringUtils.isNotBlank(arg.getBindValue())) {
                            description += ", fixed default value: " + arg.getBindValue();
                        }
                        properties.put(arg.getName(), Map.of("type", "string", "description", description));
                        if (arg.isRequire()) {
                            required.add(arg.getName());
                        }
                    });
                    pagePromptBuilder.append(JSON.toJSONString(inputSchema)).append("\n```\n\n");
                });
            }
            if (systemPrompt != null && systemPrompt.contains("{{customPagePrompt}}")) {
                return systemPromptBuilder.toString().replace("{{customPagePrompt}}", pagePromptBuilder.toString());
            } else {
                systemPromptBuilder.append("\n").append("## CUSTOM PAGE\n").append(pagePromptBuilder);
            }
        }
        return systemPromptBuilder.toString();
    }

    /**
     * 执行和移除（无需模型再次规划）每次需要执行的组件
     *
     * @param agentContext
     * @return
     */
    private String invokeAndRemoveAutoComponents(AgentContext agentContext, Sinks.Many<AgentOutputDto> sink, List<String> directOutputResults, AtomicBoolean directOutputSuccess, List<String> autoCallTools) {
        //提取自动执行工具的必要参数
        Map<String, Object> componentRequireParams = extractAutoInvokeComponentRequireParamValues(agentContext);
        StringBuilder resultStringBuilder = new StringBuilder();
        //自动调用知识库
        Iterator<AgentComponentConfigDto> ite = agentContext.getAgentConfig().getAgentComponentConfigList().iterator();
        List<Long> knowledgeBaseIds = new ArrayList<>();
        KnowledgeBaseBindConfigDto knowledgeBaseBindConfig = null;
        AgentComponentConfigDto agentComponentConfig = null;
        // 遍历需要自动调用的知识库
        while (ite.hasNext()) {
            AgentComponentConfigDto agentComponentConfigDto = ite.next();
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Knowledge) {
                KnowledgeBaseBindConfigDto knowledgeBaseBindConfigDto = (KnowledgeBaseBindConfigDto) agentComponentConfigDto.getBindConfig();
                if (knowledgeBaseBindConfigDto != null && knowledgeBaseBindConfigDto.getInvokeType() == KnowledgeBaseBindConfigDto.InvokeTypeEnum.AUTO) {
                    if (knowledgeBaseBindConfig == null) {
                        knowledgeBaseBindConfig = knowledgeBaseBindConfigDto;
                    }
                    if (agentComponentConfig == null) {
                        agentComponentConfig = agentComponentConfigDto;
                    }
                    knowledgeBaseIds.add(agentComponentConfigDto.getTargetId());
                    ite.remove();
                }
                //手动调用时，在入口处已经根据前端传递的参数改成AUTO或ON_DEMAND
                if (knowledgeBaseBindConfigDto != null && knowledgeBaseBindConfigDto.getInvokeType() == KnowledgeBaseBindConfigDto.InvokeTypeEnum.MANUAL) {
                    ite.remove();
                }
            }
        }
        // 知识库批量调用
        if (!knowledgeBaseIds.isEmpty()) {
            //过程输出
            ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
            componentExecuteResult.setName(agentComponentConfig.getName());
            componentExecuteResult.setIcon(agentComponentConfig.getIcon());
            componentExecuteResult.setId(knowledgeBaseIds.get(0));
            AgentOutputDto agentOutputDto;
            agentComponentConfig.setType(AgentComponentConfig.Type.Knowledge);
            agentComponentConfig.setTargetId(knowledgeBaseIds.get(0));
            if (knowledgeBaseIds.size() > 1) {
                String knowledgeSearchName = I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Agent.Component.KnowledgeSearch");
                agentComponentConfig.setName(knowledgeSearchName);
                componentExecuteResult.setName(knowledgeSearchName);
                componentExecuteResult.setId(-1L);
            }
            agentComponentConfig.setBindConfig(knowledgeBaseBindConfig);
            componentExecuteResult.setStartTime(System.currentTimeMillis());
            componentExecuteResult.setExecuteId(UUID.randomUUID().toString().replace("-", ""));
            componentExecuteResult.setStartTime(System.currentTimeMillis());
            componentExecuteResult.setType(ComponentTypeEnum.Knowledge);
            try {
                agentContext.getAgentExecuteResult().getComponentExecuteResults().add(componentExecuteResult);
                agentOutputDto = buildProcessOutput(agentComponentConfig, ExecuteStatusEnum.EXECUTING, ComponentTypeEnum.Knowledge, componentExecuteResult);
                agentOutputDto.setRequestId(agentContext.getRequestId());
                sink.tryEmitNext(agentOutputDto);
                buildToolUse(autoCallTools, Map.of("query", agentContext.getMessage()), agentComponentConfig.getName());
                String res = queryKnowledgeBase(agentContext, knowledgeBaseIds, knowledgeBaseBindConfig, componentExecuteResult);
                resultStringBuilder.append(res);
                agentOutputDto = buildProcessOutput(agentComponentConfig, ExecuteStatusEnum.FINISHED, ComponentTypeEnum.Knowledge, componentExecuteResult);
                agentOutputDto.setRequestId(agentContext.getRequestId());
                componentExecuteResult.setEndTime(System.currentTimeMillis());
                sink.tryEmitNext(agentOutputDto);
            } catch (Exception e) {
                componentExecuteResult.setSuccess(false);
                componentExecuteResult.setError(e.getMessage());
                componentExecuteResult.setEndTime(System.currentTimeMillis());
                agentOutputDto = buildProcessOutput(agentComponentConfig, ExecuteStatusEnum.FAILED, ComponentTypeEnum.Knowledge, componentExecuteResult);
                agentOutputDto.setRequestId(agentContext.getRequestId());
                agentOutputDto.setError(e.getMessage());
                sink.tryEmitNext(agentOutputDto);
                resultStringBuilder.append("Knowledge base search failed: ").append(e.getMessage());
            }
        }

        //自动调用插件、MCP、工作流
        ite = agentContext.getAgentConfig().getAgentComponentConfigList().iterator();
        while (ite.hasNext()) {
            AgentComponentConfigDto agentComponentConfigDto = ite.next();
            //过程输出
            ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
            componentExecuteResult.setStartTime(System.currentTimeMillis());
            componentExecuteResult.setId(agentComponentConfigDto.getTargetId());
            componentExecuteResult.setExecuteId(UUID.randomUUID().toString().replace("-", ""));
            componentExecuteResult.setName(agentComponentConfigDto.getName());
            componentExecuteResult.setIcon(agentComponentConfigDto.getIcon());
            componentExecuteResult.setStartTime(System.currentTimeMillis());
            AgentOutputDto agentOutputDto = null;
            ComponentTypeEnum componentType = null;
            try {
                if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Plugin) {
                    PluginBindConfigDto pluginBindConfigDto = (PluginBindConfigDto) agentComponentConfigDto.getBindConfig();
                    if (pluginBindConfigDto != null && pluginBindConfigDto.getInvokeType() == PluginBindConfigDto.PluginInvokeTypeEnum.AUTO) {
                        agentContext.getAgentExecuteResult().getComponentExecuteResults().add(componentExecuteResult);
                        Map<String, Object> input = extractInput(pluginBindConfigDto.getInputArgBindConfigs(), componentRequireParams, agentComponentConfigDto.getId());
                        componentExecuteResult.setInput(JsonSerializeUtil.deepCopy(input));
                        componentType = ComponentTypeEnum.Plugin;
                        componentExecuteResult.setType(componentType);
                        agentOutputDto = buildProcessOutput(agentComponentConfigDto, ExecuteStatusEnum.EXECUTING, ComponentTypeEnum.Plugin, componentExecuteResult);
                        agentOutputDto.setRequestId(agentContext.getRequestId());
                        sink.tryEmitNext(agentOutputDto);
                        buildToolUse(autoCallTools, input, agentComponentConfigDto.getName());
                        String res = invokePlugin(agentContext, agentComponentConfigDto, input, componentExecuteResult);
                        resultStringBuilder.append(res);
                        //ite.remove();
                    }
                    if (pluginBindConfigDto != null && pluginBindConfigDto.getInvokeType() == PluginBindConfigDto.PluginInvokeTypeEnum.MANUAL) {
                        ite.remove();
                    }
                }
                if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Mcp) {
                    McpBindConfigDto mcpBindConfigDto = (McpBindConfigDto) agentComponentConfigDto.getBindConfig();
                    if (mcpBindConfigDto != null && mcpBindConfigDto.getInvokeType() == McpBindConfigDto.McpInvokeTypeEnum.AUTO) {
                        agentContext.getAgentExecuteResult().getComponentExecuteResults().add(componentExecuteResult);
                        Map<String, Object> input = extractInput(mcpBindConfigDto.getInputArgBindConfigs(), componentRequireParams, agentComponentConfigDto.getId());
                        componentExecuteResult.setInput(JsonSerializeUtil.deepCopy(input));
                        componentType = ComponentTypeEnum.Mcp;
                        componentExecuteResult.setType(componentType);
                        agentOutputDto = buildProcessOutput(agentComponentConfigDto, ExecuteStatusEnum.EXECUTING, ComponentTypeEnum.Mcp, componentExecuteResult);
                        agentOutputDto.setRequestId(agentContext.getRequestId());
                        sink.tryEmitNext(agentOutputDto);
                        buildToolUse(autoCallTools, input, agentComponentConfigDto.getName());
                        String res = invokeMcp(agentContext, agentComponentConfigDto, input, componentExecuteResult);
                        resultStringBuilder.append(res);
                        //ite.remove();
                    }
                    if (mcpBindConfigDto != null && mcpBindConfigDto.getInvokeType() == McpBindConfigDto.McpInvokeTypeEnum.MANUAL) {
                        ite.remove();
                    }
                }
                if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Workflow) {
                    WorkflowBindConfigDto workflowBindConfigDto = (WorkflowBindConfigDto) agentComponentConfigDto.getBindConfig();
                    if (workflowBindConfigDto != null && workflowBindConfigDto.getInvokeType() == WorkflowBindConfigDto.WorkflowInvokeTypeEnum.AUTO) {
                        agentContext.getAgentExecuteResult().getComponentExecuteResults().add(componentExecuteResult);
                        Map<String, Object> input = extractInput(workflowBindConfigDto.getArgBindConfigs(), componentRequireParams, agentComponentConfigDto.getId());
                        componentExecuteResult.setInput(JsonSerializeUtil.deepCopy(input));
                        componentType = ComponentTypeEnum.Workflow;
                        componentExecuteResult.setType(componentType);
                        agentOutputDto = buildProcessOutput(agentComponentConfigDto, ExecuteStatusEnum.EXECUTING, ComponentTypeEnum.Workflow, componentExecuteResult);
                        agentOutputDto.setRequestId(agentContext.getRequestId());
                        sink.tryEmitNext(agentOutputDto);
                        buildToolUse(autoCallTools, input, agentComponentConfigDto.getName());
                        String res = invokeWorkflow(agentContext, agentComponentConfigDto, input, componentExecuteResult);
                        if (workflowBindConfigDto.getDirectOutput() != null && workflowBindConfigDto.getDirectOutput().equals(YesOrNoEnum.Y.getKey())) {
                            if (!componentExecuteResult.getSuccess()) {
                                directOutputSuccess.set(false);
                                directOutputResults.clear();
                                directOutputResults.add(componentExecuteResult.getError());
                            } else {
                                directOutputResults.add(componentExecuteResult.getData() instanceof String ? (String) componentExecuteResult.getData() : JSON.toJSONString(componentExecuteResult.getData()));
                            }
                            res = "";// 直接输出了，不再交给大模型
                        }
                        resultStringBuilder.append(res);
                        //ite.remove();
                    }
                    if (workflowBindConfigDto != null && workflowBindConfigDto.getInvokeType() == WorkflowBindConfigDto.WorkflowInvokeTypeEnum.MANUAL) {
                        ite.remove();
                    }
                }
                if (agentOutputDto != null) {
                    agentOutputDto = buildProcessOutput(agentComponentConfigDto, ExecuteStatusEnum.FINISHED, componentType, componentExecuteResult);
                    agentOutputDto.setRequestId(agentContext.getRequestId());
                    componentExecuteResult.setEndTime(System.currentTimeMillis());
                    sink.tryEmitNext(agentOutputDto);
                }
            } catch (Exception e) {
                componentExecuteResult.setSuccess(false);
                componentExecuteResult.setError(e.getMessage());
                componentExecuteResult.setEndTime(System.currentTimeMillis());
                agentOutputDto = buildProcessOutput(agentComponentConfigDto, ExecuteStatusEnum.FAILED, componentType, componentExecuteResult);
                agentOutputDto.setRequestId(agentContext.getRequestId());
                agentOutputDto.setError(e.getMessage());
                sink.tryEmitNext(agentOutputDto);
                if (agentComponentConfigDto.getExceptionOut() != null && agentComponentConfigDto.getExceptionOut().equals(YesOrNoEnum.Y.getKey())) {
                    throw e;
                } else if (StringUtils.isNotBlank(agentComponentConfigDto.getFallbackMsg())) {
                    resultStringBuilder.append(agentComponentConfigDto.getFallbackMsg());
                } else {
                    resultStringBuilder.append("Tool `").append(agentComponentConfigDto.getName()).append("` invocation failed: ").append(e.getMessage());
                }
            }
        }

        return resultStringBuilder.toString();
    }

    private static void buildToolUse(List<String> autoCallTools, Map<String, Object> input, String name) {
        StringBuilder toolUse = new StringBuilder();
        toolUse.append("<tool_use>")
                .append("<name>").append(name).append("</name>")
                .append("<arguments>").append(JSON.toJSONString(input)).append("</arguments>")
                .append("</tool_use>");
        autoCallTools.add(toolUse.toString());
    }

    private static void checkAndCompleteInput(AgentContext agentContext, List<Arg> argBindConfigs, Map<String, Object> input, List<String> errorList) {
        if (CollectionUtils.isEmpty(argBindConfigs)) {
            return;
        }
        ArgExtractUtil.setArgDefaultValue(agentContext, argBindConfigs, input, null, errorList);
    }

    private String invokeMcp(AgentContext agentContext, AgentComponentConfigDto agentComponentConfigDto, Map<String, Object> input, ComponentExecuteResult componentExecuteResult) {
        McpBindConfigDto mcpBindConfigDto = (McpBindConfigDto) agentComponentConfigDto.getBindConfig();
        McpDto mcpDto = (McpDto) agentComponentConfigDto.getTargetConfig();
        McpContext mcpContext = McpContext.builder()
                .requestId(agentContext.getRequestId())
                .conversationId(agentContext.getConversationId())
                .user(agentContext.getUser())
                .mcpDto((McpDto) agentComponentConfigDto.getTargetConfig())
                .params(input)
                .name(mcpBindConfigDto.getToolName())
                .traceContext(agentContext.getTraceContext().next(TraceContext.TraceTargetType.Mcp, mcpDto.getId().toString(), mcpDto.getName(), mcpDto.getDescription(), mcpDto.getIcon()))
                .build();
        try {
            McpExecuteOutput mcpExecuteOutput = mcpExecutor.execute(mcpContext).blockLast();
            if (mcpExecuteOutput == null || !mcpExecuteOutput.isSuccess()) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        mcpExecuteOutput == null ? "" : mcpExecuteOutput.getMessage());
            }
            componentExecuteResult.setSuccess(true);
            componentExecuteResult.setData(mcpExecuteOutput.getResult());
            return buildToolCallResult(mcpContext.getName(), JSON.toJSONString(mcpExecuteOutput.getResult()), null);
        } catch (Exception e) {
            log.error("MCP execution failed: {}", e.getMessage(), e);
            componentExecuteResult.setSuccess(false);
            componentExecuteResult.setError(e.getMessage());
            return buildToolCallResult(mcpContext.getName(), null, e.getMessage());
        }
    }

    private String invokeWorkflow(AgentContext agentContext, AgentComponentConfigDto agentComponentConfigDto,
                                  Map<String, Object> input, ComponentExecuteResult componentExecuteResult) {
        try {
            WorkflowConfigDto workflowConfigDto = (WorkflowConfigDto) agentComponentConfigDto.getTargetConfig();
            if (workflowConfigDto == null) {
                throw new RuntimeException("tool execution failed");
            }
            List<String> errorList = new ArrayList<>();
            WorkflowBindConfigDto workflowBindConfigDto = (WorkflowBindConfigDto) agentComponentConfigDto.getBindConfig();
            checkAndCompleteInput(agentContext, workflowBindConfigDto.getArgBindConfigs(), input, errorList);
            Assert.isTrue(errorList.isEmpty(), String.join(",", errorList));
            WorkflowContext workflowContext1 = new WorkflowContext();
            workflowContext1.setOriginalWorkflowId(workflowConfigDto.getId());
            workflowContext1.setAgentContext(agentContext);
            workflowContext1.setRequestId(agentContext.getRequestId());
            workflowContext1.setWorkflowConfig(workflowConfigDto);
            workflowContext1.setUseResultCache(workflowBindConfigDto.isUseResultCache());
            workflowContext1.setAsyncExecute(workflowBindConfigDto.getAsync() != null && workflowBindConfigDto.getAsync() == 1);
            workflowContext1.setAsyncReplyContent(workflowBindConfigDto.getAsyncReplyContent());
            workflowContext1.setNodeExecutingConsumer(nodeExecutingDto -> {
            });
            workflowContext1.setParams(input);
            workflowContext1.setTraceContext(agentContext.getTraceContext().next(TraceContext.TraceTargetType.Workflow, workflowConfigDto.getId().toString(), workflowConfigDto.getName(), workflowConfigDto.getDescription(), workflowConfigDto.getIcon()));
            Object res = workflowExecutor.execute(workflowContext1).block();
            EndNodeConfigDto endNodeConfigDto = (EndNodeConfigDto) workflowConfigDto.getEndNode().getNodeConfig();
            if (endNodeConfigDto.getReturnType() == EndNodeConfigDto.ReturnType.TEXT && StringUtils.isNotBlank(workflowContext1.getEndNodeContent())) {
                res = workflowContext1.getEndNodeContent();
            }
            componentExecuteResult.setSuccess(true);
            if (workflowContext1.getWorkflowConfig().getSpaceId().equals(agentContext.getAgentConfig().getSpaceId())) {
                //工作流与智能体同属一个空间时才记录内部执行日志
                componentExecuteResult.setInnerExecuteInfo(workflowContext1.getNodeExecuteResultMap().values());
            }
            componentExecuteResult.setData(res);
            return buildToolCallResult(agentComponentConfigDto.getName(), res == null ? "" : JSON.toJSONString(res), null);
        } catch (Exception e) {
            log.error("Workflow invocation failed", e);
            componentExecuteResult.setSuccess(false);
            componentExecuteResult.setError(e.getMessage());
            return buildToolCallResult(agentComponentConfigDto.getName(), null, e.getMessage());
        }
    }

    private String invokePlugin(AgentContext agentContext, AgentComponentConfigDto agentComponentConfigDto,
                                Map<String, Object> input, ComponentExecuteResult componentExecuteResult) {
        try {
            List<String> errorList = new ArrayList<>();
            PluginBindConfigDto pluginBindConfigDto = (PluginBindConfigDto) agentComponentConfigDto.getBindConfig();
            checkAndCompleteInput(agentContext, pluginBindConfigDto.getInputArgBindConfigs(), input, errorList);
            Assert.isTrue(errorList.isEmpty(), String.join(",", errorList));
            PluginDto pluginDto = (PluginDto) agentComponentConfigDto.getTargetConfig();
            PluginContext pluginContext = PluginContext.builder()
                    .requestId(agentContext.getRequestId())
                    .agentContext(agentContext)
                    .pluginConfig((PluginConfigDto) pluginDto.getConfig())
                    .pluginDto(pluginDto)
                    .params(input)
                    .userId(agentContext.getUserId())
                    .asyncExecute(pluginBindConfigDto.getAsync() != null && pluginBindConfigDto.getAsync() == 1)
                    .asyncReplyContent(pluginBindConfigDto.getAsyncReplyContent())
                    .traceContext(agentContext.getTraceContext().next(TraceContext.TraceTargetType.Plugin, pluginDto.getId().toString(), pluginDto.getName(), pluginDto.getDescription(), pluginDto.getIcon()))
                    .build();
            PluginExecuteResultDto pluginExecuteResultDto = pluginExecutor.execute(pluginContext).block();
            Object res = null;
            if (pluginExecuteResultDto != null) {
                componentExecuteResult.setSuccess(pluginExecuteResultDto.isSuccess());
                componentExecuteResult.setError(pluginExecuteResultDto.getError());
                componentExecuteResult.setData(pluginExecuteResultDto.getResult());
                if (!pluginExecuteResultDto.isSuccess()) {
                    throw new RuntimeException(pluginExecuteResultDto.getError());
                }
                res = pluginExecuteResultDto.getResult();
                componentExecuteResult.setInnerExecuteInfo(pluginExecuteResultDto.getLogs());
            }
            componentExecuteResult.setSuccess(true);
            componentExecuteResult.setData(res);
            return buildToolCallResult(agentComponentConfigDto.getName(), res == null ? "" : JSON.toJSONString(res), null);
        } catch (Exception e) {
            log.error("Plugin invocation failed", e);
            componentExecuteResult.setSuccess(false);
            componentExecuteResult.setError(e.getMessage());
            return buildToolCallResult(agentComponentConfigDto.getName(), null, e.getMessage());
        }
    }

    private static Map<String, Object> extractInput(List<Arg> inputArgBindConfigs, Map<String, Object> componentRequireParams, Long id) {
        Map<String, Object> input = new HashMap<>();
        if (CollectionUtils.isNotEmpty(inputArgBindConfigs)) {
            for (Arg arg : inputArgBindConfigs) {
                if (!arg.getEnable()) {
                    continue;
                }
                Object value = componentRequireParams.get(arg.getName() + "_" + id);
                if (value != null) {
                    input.put(arg.getName(), value);
                }
            }
        }
        return input;
    }

    private String queryKnowledgeBase(AgentContext agentContext, List<Long> knowledgeBaseIds, KnowledgeBaseBindConfigDto knowledgeBaseBindConfigDto, ComponentExecuteResult componentExecuteResult) {
        SearchContext searchContext = new SearchContext();
        //移除agentContext.getMessage()中<ATTACHMENT>标签内容
        searchContext.setAgentContext(agentContext);
        searchContext.setQuery(agentContext.getMessage().replaceAll("<attachment>[\\s\\S]*?</attachment>", ""));
        searchContext.setSearchStrategy(knowledgeBaseBindConfigDto.getSearchStrategy());
        searchContext.setMatchingDegree(knowledgeBaseBindConfigDto.getMatchingDegree());
        searchContext.setMaxRecallCount(knowledgeBaseBindConfigDto.getMaxRecallCount());
        searchContext.setKnowledgeBaseIds(knowledgeBaseIds);
        searchContext.setRequestId(agentContext.getRequestId());
        componentExecuteResult.setInput(Map.of("query", searchContext.getQuery()));
        List<KnowledgeQaVo> qaVoList = knowledgeBaseSearcher.search(searchContext).block();
        componentExecuteResult.setSuccess(true);
        componentExecuteResult.setData(CollectionUtils.isEmpty(qaVoList) ? "No relevant information found" : qaVoList);
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("The following are the search results in the knowledge base:\n");
        if (!CollectionUtils.isEmpty(qaVoList)) {
            stringBuilder.append(JSON.toJSONString(qaVoList));
        } else {
            if (knowledgeBaseBindConfigDto.getNoneRecallReplyType() == KnowledgeBaseBindConfigDto.NoneRecallReplyTypeEnum.CUSTOM &&
                    StringUtils.isNotBlank(knowledgeBaseBindConfigDto.getNoneRecallReply())) {
                stringBuilder.append(knowledgeBaseBindConfigDto.getNoneRecallReply());
            } else {
                stringBuilder.append("No relevant information found");
            }
        }
        return stringBuilder.append("\n\n").toString();
    }

    private String buildSuggestUserPrompt(AgentContext agentContext) {
        ModelBindConfigDto modelBindConfigDto = (ModelBindConfigDto) agentContext.getAgentConfig().getModelComponentConfig().getBindConfig();
        StringBuilder stringBuilder = new StringBuilder();
        if (modelBindConfigDto.getContextRounds() == null || modelBindConfigDto.getContextRounds() == 0) {
            stringBuilder.append("USER:").append(agentContext.getMessage()).append("\n");
            stringBuilder.append("ASSISTANT:").append(agentContext.getAgentExecuteResult().getOutputText()).append("\n");
        } else {
            try {
                Long conversationId = Long.parseLong(agentContext.getConversationId());
                List<ChatMessageDto> messageDtos = conversationApplicationService.queryConversationMessageList(agentContext.getUserId(), conversationId, null, 2);
                if (!CollectionUtils.isEmpty(messageDtos)) {
                    messageDtos.forEach(messageDto -> {
                        if (ChatMessageDto.Role.USER.name().equals(messageDto.getRole().name())) {
                            stringBuilder.append("USER:").append(messageDto.getText()).append("\n");
                        }
                        if (ChatMessageDto.Role.ASSISTANT.name().equals(messageDto.getRole().name())) {
                            stringBuilder.append("ASSISTANT:").append(messageDto.getText()).append("\n\n");
                        }
                    });
                }
            } catch (NumberFormatException e) {
                //do nothing
            }
        }

        return stringBuilder.toString();
    }

    private static String buildSuggestSystemPrompt(AgentContext agentContext) {
        if (StringUtils.isNotBlank(agentContext.getAgentConfig().getSuggestPrompt())) {
            return agentContext.getAgentConfig().getSuggestPrompt();
        }
        return SUGGEST_PROMPT;
    }

    public static AgentOutputDto buildFinalResultOutput(AgentContext agentContext) {
        AgentExecuteResult agentExecuteResult1 = buildAgentExecuteResult(agentContext);
        AgentOutputDto agentOutputDto = new AgentOutputDto();
        agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.FINAL_RESULT);
        agentOutputDto.setRequestId(agentContext.getRequestId());
        agentOutputDto.setData(agentExecuteResult1);
        agentOutputDto.setCompleted(true);
        if (!agentContext.isDebug() && agentExecuteResult1 != null) {
            AgentExecuteResult agentExecuteResult = new AgentExecuteResult();
            BeanUtils.copyProperties(agentExecuteResult1, agentExecuteResult, "componentExecuteResults");
            agentOutputDto.setData(agentExecuteResult);
        }
        return agentOutputDto;
    }

    private static AgentOutputDto buildOutputMessage(AgentContext agentContext, CallMessage res) {
        AgentOutputDto agentOutputDto = new AgentOutputDto();
        agentOutputDto.setRequestId(agentContext.getRequestId());
        agentOutputDto.setData(res);
        agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.MESSAGE);
        return agentOutputDto;
    }

    private static List<ComponentConfig> convertToComponentConfigList(List<AgentComponentConfigDto> agentComponentConfigDtos, AgentContext agentContext) {
        List<ComponentConfig> componentConfigs = new ArrayList<>();
        for (AgentComponentConfigDto agentComponentConfigDto : agentComponentConfigDtos) {
            ComponentConfig componentConfig = new ComponentConfig();
            componentConfig.setId(agentComponentConfigDto.getId());
            componentConfig.setName(agentComponentConfigDto.getName());
            componentConfig.setIcon(agentComponentConfigDto.getIcon());
            componentConfig.setTargetId(agentComponentConfigDto.getTargetId());
            componentConfig.setDescription(agentComponentConfigDto.getDescription());
            componentConfig.setExceptionOut(agentComponentConfigDto.getExceptionOut());
            componentConfig.setFallbackMsg(agentComponentConfigDto.getFallbackMsg());
            List<Arg> inputArgBindConfigs = null;
            switch (agentComponentConfigDto.getType()) {
                case Knowledge:
                    KnowledgeBaseBindConfigDto knowledgeBaseBindConfigDto = (KnowledgeBaseBindConfigDto) agentComponentConfigDto.getBindConfig();
                    componentConfig.setType(ComponentTypeEnum.Knowledge);
                    KnowledgeSearchConfigDto knowledgeSearchConfigDto = new KnowledgeSearchConfigDto();
                    knowledgeSearchConfigDto.setSearchStrategy(knowledgeBaseBindConfigDto.getSearchStrategy());
                    knowledgeSearchConfigDto.setMatchingDegree(knowledgeBaseBindConfigDto.getMatchingDegree());
                    knowledgeSearchConfigDto.setMaxRecallCount(knowledgeBaseBindConfigDto.getMaxRecallCount());
                    knowledgeSearchConfigDto.setNoneRecallReplyType(knowledgeBaseBindConfigDto.getNoneRecallReplyType());
                    knowledgeSearchConfigDto.setNoneRecallReply(knowledgeBaseBindConfigDto.getNoneRecallReply());
                    componentConfig.setTargetConfig(knowledgeSearchConfigDto);
                    break;
                case Plugin:
                    componentConfig.setType(ComponentTypeEnum.Plugin);
                    PluginDto pluginDto = (PluginDto) agentComponentConfigDto.getTargetConfig();
                    componentConfig.setTargetConfig(pluginDto);
                    componentConfig.setName(pluginDto.getName());
                    componentConfig.setFunctionName(pluginDto.getFunctionName());
                    componentConfig.setDescription(pluginDto.getDescription());
                    PluginBindConfigDto pluginBindConfigDto = (PluginBindConfigDto) agentComponentConfigDto.getBindConfig();
                    componentConfig.setAsyncExecute(pluginBindConfigDto.getAsync() != null && pluginBindConfigDto.getAsync() == 1);
                    componentConfig.setAsyncReplyContent(pluginBindConfigDto.getAsyncReplyContent());
                    inputArgBindConfigs = pluginBindConfigDto.getInputArgBindConfigs();
                    componentConfig.setBindConfig(pluginBindConfigDto);
                    break;
                case Workflow:
                    componentConfig.setType(ComponentTypeEnum.Workflow);
                    WorkflowBindConfigDto workflowBindConfigDto = (WorkflowBindConfigDto) agentComponentConfigDto.getBindConfig();
                    WorkflowConfigDto workflowConfigDto = (WorkflowConfigDto) agentComponentConfigDto.getTargetConfig();
                    componentConfig.setTargetConfig(workflowConfigDto);
                    componentConfig.setName(workflowConfigDto.getName());
                    componentConfig.setFunctionName(workflowConfigDto.getFunctionName());
                    componentConfig.setDescription(workflowConfigDto.getDescription());
                    componentConfig.setAsyncExecute(workflowBindConfigDto.getAsync() != null && workflowBindConfigDto.getAsync() == 1);
                    componentConfig.setAsyncReplyContent(workflowBindConfigDto.getAsyncReplyContent());
                    inputArgBindConfigs = workflowBindConfigDto.getArgBindConfigs();
                    componentConfig.setBindConfig(workflowBindConfigDto);
                    break;
                case Mcp:
                    componentConfig.setType(ComponentTypeEnum.Mcp);
                    McpBindConfigDto mcpBindConfigDto = (McpBindConfigDto) agentComponentConfigDto.getBindConfig();
                    componentConfig.setTargetConfig(agentComponentConfigDto.getTargetConfig());
                    componentConfig.setName(agentComponentConfigDto.getName());
                    componentConfig.setFunctionName(mcpBindConfigDto.getToolName());
                    String toolDescription = getMcpToolDescription((McpDto) agentComponentConfigDto.getTargetConfig(), mcpBindConfigDto.getToolName());
                    componentConfig.setDescription(toolDescription);
                    componentConfig.setAsyncExecute(mcpBindConfigDto.getAsync() != null && mcpBindConfigDto.getAsync() == 1);
                    componentConfig.setAsyncReplyContent(mcpBindConfigDto.getAsyncReplyContent());
                    inputArgBindConfigs = mcpBindConfigDto.getInputArgBindConfigs();
                    componentConfig.setBindConfig(mcpBindConfigDto);
                    break;
                case Agent:
                    componentConfig.setType(ComponentTypeEnum.Agent);
                    break;
                case Table:
                    //insert
                    ComponentConfig componentConfigInsert = buildTableComponentConfig(agentComponentConfigDto, ComponentSubTypeEnum.TABLE_DATA_INSERT);
                    componentConfigInsert.setName(agentComponentConfigDto.getName() + I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Agent.Component.TableInsertSuffix"));
                    componentConfigs.add(componentConfigInsert);
                    ComponentConfig componentConfigSql = buildTableComponentConfig(agentComponentConfigDto, ComponentSubTypeEnum.TABLE_SQL_EXECUTE);
                    componentConfigSql.setName(agentComponentConfigDto.getName() + I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Agent.Component.TableSqlSuffix"));
                    componentConfigs.add(componentConfigSql);
                    continue;
                default:
                    break;
            }
            if (!CollectionUtils.isEmpty(inputArgBindConfigs)) {
                componentConfig.setInputArgs(inputArgBindConfigs.stream().map((arg) -> (Arg) arg).toList());
            }
            componentConfigs.add(componentConfig);
        }
        return componentConfigs;
    }

    private static ComponentConfig buildTableComponentConfig(AgentComponentConfigDto agentComponentConfigDto, ComponentSubTypeEnum componentSubType) {
        ComponentConfig componentConfig = new ComponentConfig();
        componentConfig.setId(agentComponentConfigDto.getId());
        TableDefineVo dorisTableDefinitionVo = (TableDefineVo) agentComponentConfigDto.getTargetConfig();
        componentConfig.setTargetConfig(dorisTableDefinitionVo);
        TableBindConfigDto tableBindConfigDto = (TableBindConfigDto) agentComponentConfigDto.getBindConfig();
        componentConfig.setBindConfig(tableBindConfigDto);
        componentConfig.setName(agentComponentConfigDto.getName());
        componentConfig.setFunctionName(componentSubType.name() + "_" + agentComponentConfigDto.getId());
        componentConfig.setDescription(agentComponentConfigDto.getDescription());
        componentConfig.setTargetId(agentComponentConfigDto.getTargetId());
        componentConfig.setType(ComponentTypeEnum.Table);
        componentConfig.setSubType(componentSubType);
        componentConfig.setInputArgs(new ArrayList<>(tableBindConfigDto.getInputArgBindConfigs()));
        return componentConfig;
    }

    private static List<ComponentConfig> buildPageBrowserComponentConfig(List<AgentComponentConfigDto> pageComponentConfigs, Map<String, String> langMap) {
        List<ComponentConfig> componentConfigs = new ArrayList<>();
        Map<String, PageArgConfig> pageArgConfigMap = null;
        if (CollectionUtils.isNotEmpty(pageComponentConfigs)) {
            List<PageArgConfig> pageArgConfigs = new ArrayList<>();
            pageComponentConfigs.forEach(agentComponentConfigDto -> {
                PageBindConfigDto pageBindConfigDto = (PageBindConfigDto) agentComponentConfigDto.getBindConfig();
                if (pageBindConfigDto.getPageArgConfigs() != null) {
                    pageArgConfigs.addAll(pageBindConfigDto.getPageArgConfigs());
                }
            });
            pageArgConfigMap = pageArgConfigs.stream().collect(Collectors.toMap(pageArgConfig -> pageArgConfig.getPageUrl(pageComponentConfigs.get(0).getAgentId()), pageArgConfig -> pageArgConfig, (pageArgConfig1, pageArgConfig2) -> pageArgConfig1));
        }
        // 创建打开页面组件
        ComponentConfig componentConfig = new ComponentConfig();
        componentConfig.setName(I18nUtil.systemMessage(langMap, "Backend.Agent.Component.OpenPage"));
        componentConfig.setFunctionName("browser_open_page");
        componentConfig.setDescription("Identify user intent and open the page the user wants to view. Just open it, no need to return data");
        componentConfig.setTargetId(-1L);
        componentConfig.setType(ComponentTypeEnum.Page);
        componentConfig.setInputArgs(List.of(
                        Arg.builder().name("uri").description("Page path, can only request internal addresses starting with a slash (/)").dataType(DataTypeEnum.String).require(true).build(),
                        Arg.builder().name("arguments").dataType(DataTypeEnum.Object).subArgs(List.of(
                                Arg.builder().name("{dynamic_parameters}").description("Page request parameters, specific parameter fields returned according to actual situation").build()
                        )).build()
                )
        );
        componentConfig.setBindConfig(pageArgConfigMap);
        componentConfigs.add(componentConfig);

        componentConfig = new ComponentConfig();
        componentConfig.setName(I18nUtil.systemMessage(langMap, "Backend.Agent.Component.BrowsePageData"));
        componentConfig.setFunctionName("browser_navigate_page");
        componentConfig.setDescription("Identify user intent and call when page data needs to be retrieved for analysis");
        componentConfig.setTargetId(-1L);
        componentConfig.setType(ComponentTypeEnum.Page);
        componentConfig.setInputArgs(List.of(
                        Arg.builder().name("uri").description("Page path, can only request internal addresses starting with a slash (/)").dataType(DataTypeEnum.String).require(true).build(),
                        Arg.builder().name("data_type").description("Page data type, optional range: markdown - data converted to markdown for model understanding; html - original DOM information for identifying and filling forms").dataType(DataTypeEnum.String).require(true).build(),
                        Arg.builder().name("arguments").dataType(DataTypeEnum.Object).subArgs(List.of(
                                Arg.builder().name("{dynamic_parameters}").description("Page request parameters, specific parameter fields returned according to actual situation").build()
                        )).build()
                )
        );
        componentConfig.setBindConfig(pageArgConfigMap);
        componentConfigs.add(componentConfig);
        return componentConfigs;
    }

    private Mono<Object> variableSet(AgentContext agentContext, Sinks.Many<AgentOutputDto> fluxSink) {
        AtomicReference<Disposable> disposable = new AtomicReference<>();
        return Mono.create(sink -> {
            List<Arg> userArgs = new ArrayList<>();
            //初始化变量
            List<AgentComponentConfigDto> agentComponentConfigList = agentContext.getAgentConfig().getAgentComponentConfigList();
            //找出变量组件
            AgentComponentConfigDto variableComponent = agentComponentConfigList.stream().filter(agentComponentConfigDto -> agentComponentConfigDto.getType() == AgentComponentConfig.Type.Variable).findFirst().orElse(null);
            if (variableComponent != null) {
                VariableConfigDto variableConfigDto = (VariableConfigDto) variableComponent.getBindConfig();
                if (variableConfigDto != null && !CollectionUtils.isEmpty(variableConfigDto.getVariables())) {
                    //过滤出自定义变量，同时把前端已经传递的变量也过滤掉
                    List<Arg> nonSysArgs = variableConfigDto.getVariables().stream().filter(var -> !var.isSystemVariable()
                                    && var.getInputType() != null && var.getInputType() == Arg.InputTypeEnum.AutoRecognition)
                            .toList();
                    userArgs.addAll(nonSysArgs);
                    variableConfigDto.getVariables().forEach((var) -> {
                        if (var.isSystemVariable() && var.getEnable()) {
                            if (CHAT_CONTEXT.name().equals(var.getName())) {
                                ModelBindConfigDto modelBindConfigDto = (ModelBindConfigDto) agentContext.getAgentConfig().getModelComponentConfig().getBindConfig();
                                if (modelBindConfigDto != null) {
                                    List<Message> messages = new ArrayList<>(agentContext.getContextMessages());
                                    messages.add(0, new SystemMessage(agentContext.getAgentConfig().getSystemPrompt() == null ? "" : agentContext.getAgentConfig().getSystemPrompt()));
                                    messages.add(new UserMessage(agentContext.getMessage()));
                                    agentContext.getVariableParams().put(var.getName(), messages);
                                }
                            }
                        }
                        // 设置变量默认值
                        if (!var.isSystemVariable() && !agentContext.getVariableParams().containsKey(var.getName()) && StringUtils.isNotBlank(var.getBindValue())) {
                            agentContext.getVariableParams().put(var.getName(), var.getBindValue());
                        }
                    });
                }
            }
            if (!userArgs.isEmpty()) {
                String variableSettingName = I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Agent.Component.VariableSetting");
                ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
                componentExecuteResult.setStartTime(System.currentTimeMillis());
                componentExecuteResult.setName(variableSettingName);
                componentExecuteResult.setType(ComponentTypeEnum.Variable);
                componentExecuteResult.setSuccess(true);
                componentExecuteResult.setStartTime(System.currentTimeMillis());
                variableComponent.setName(variableSettingName);
                AgentOutputDto agentOutputDto = buildProcessOutput(variableComponent, ExecuteStatusEnum.EXECUTING, ComponentTypeEnum.Variable, componentExecuteResult);
                agentOutputDto.setRequestId(agentContext.getRequestId());
                fluxSink.tryEmitNext(agentOutputDto);
                agentContext.getAgentExecuteResult().getComponentExecuteResults().add(componentExecuteResult);

                userArgs.forEach(arg -> arg.setDataType(DataTypeEnum.String));
                String userPrompt = buildVarUserPrompt(agentContext);
                AgentContext agentContext1 = new AgentContext();
                BeanUtils.copyProperties(agentContext, agentContext1);
                agentContext1.setContextMessages(new ArrayList<>());
                ModelContext modelContext = buildModelContext(agentContext1, EXTRACT_PARAM_PROMPT, userPrompt, null, OutputTypeEnum.JSON, userArgs);
                ModelConfigDto modelConfig = modelContext.getModelConfig();
                if (modelConfig.getIsReasonModel() != null && modelConfig.getIsReasonModel() == 1) {
                    ModelConfigDto modelConfigDto = new ModelConfigDto();
                    BeanUtils.copyProperties(modelConfig, modelConfigDto);
                    modelConfigDto.setIsReasonModel(0);
                    modelContext.setModelConfig(modelConfigDto);
                }
                Disposable d = modelInvoker.invoke(modelContext).doOnError(throwable -> {
                    componentExecuteResult.setEndTime(System.currentTimeMillis());
                    componentExecuteResult.setSuccess(false);
                    componentExecuteResult.setError(throwable.getMessage());
                    AgentOutputDto agentOutput = buildProcessOutput(variableComponent, ExecuteStatusEnum.FINISHED, ComponentTypeEnum.Variable, componentExecuteResult);
                    agentOutput.setRequestId(agentContext.getRequestId());
                    agentOutput.setError(throwable.getMessage());
                    fluxSink.tryEmitNext(agentOutput);

                    //输出错误信息到会话中
                    AgentOutputDto errorOutput = new AgentOutputDto();
                    CallMessage callMessage = new CallMessage();
                    callMessage.setText(throwable.getMessage());
                    callMessage.setType(MessageTypeEnum.CHAT);
                    callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
                    callMessage.setId(agentContext.getRequestId());
                    callMessage.setFinished(true);
                    errorOutput.setError(throwable.getMessage());
                    errorOutput.setEventType(AgentOutputDto.EventTypeEnum.MESSAGE);
                    errorOutput.setRequestId(agentContext.getRequestId());
                    errorOutput.setData(callMessage);
                    fluxSink.tryEmitNext(errorOutput);

                    //结束输出
                    agentContext.getAgentExecuteResult().setEndTime(System.currentTimeMillis());
                    agentContext.getAgentExecuteResult().setSuccess(false);
                    agentContext.getAgentExecuteResult().setError(throwable.getMessage());
                    AgentOutputDto agentOutputDto0 = buildFinalResultOutput(agentContext);
                    fluxSink.tryEmitNext(agentOutputDto0);

                    sink.error(throwable);
                }).doOnComplete(() -> {
                    Object res = modelContext.getModelCallResult().getData();
                    componentExecuteResult.setEndTime(System.currentTimeMillis());
                    componentExecuteResult.setData(res);
                    AgentOutputDto agentOutput = buildProcessOutput(variableComponent, ExecuteStatusEnum.FINISHED, ComponentTypeEnum.Variable, componentExecuteResult);
                    agentOutput.setRequestId(agentContext.getRequestId());
                    fluxSink.tryEmitNext(agentOutput);
                    log.info("User variable setting result:{}", res);
                    try {
                        if (res instanceof Map) {
                            Map<String, Object> resMap = (Map<String, Object>) res;
                            resMap.forEach((k, v) -> agentContext.getVariableParams().put(k, v));
                            // 通用智能体提前保存识别的变量
                            if ("TaskAgent".equals(agentContext.getAgentConfig().getType())) {
                                try {
                                    conversationApplicationService.updateConversationVariables(Long.parseLong(agentContext.getConversationId()), agentContext.getVariableParams());
                                } catch (Exception e) {
                                    // ignore
                                    log.error("Update conversation variables failed", e);
                                }
                            }
                        }
                    } catch (Exception e) {
                        log.error("User variable setting failed", e);
                    }
                    sink.success(null);
                }).subscribe();
                disposable.set(d);
            } else {
                sink.success(null);
            }
        }).doOnCancel(() -> {
            if (disposable.get() != null && !disposable.get().isDisposed()) {
                disposable.get().dispose();
            }
        });
    }

    private Map<String, Object> extractParam(AgentContext agentContext, List<Arg> args) {
        //args组装一起
        String userPrompt = buildVarUserPrompt(agentContext);
        AgentContext agentContext1 = new AgentContext();
        BeanUtils.copyProperties(agentContext, agentContext1);
        agentContext1.setContextMessages(new ArrayList<>());
        ModelContext modelContext = buildModelContext(agentContext1, EXTRACT_PARAM_PROMPT, userPrompt, null, OutputTypeEnum.JSON, args);
        ModelConfigDto modelConfig = modelContext.getModelConfig();
        if (modelConfig.getIsReasonModel() != null && modelConfig.getIsReasonModel() == 1) {
            ModelConfigDto modelConfigDto = new ModelConfigDto();
            BeanUtils.copyProperties(modelConfig, modelConfigDto);
            modelConfigDto.setIsReasonModel(0);// 变量提取关闭思考模式
            modelContext.setModelConfig(modelConfigDto);
        }
        modelInvoker.invoke(modelContext).blockLast();
        Object res = modelContext.getModelCallResult().getData();
        if (!(res instanceof Map)) {
            return new HashMap<>();
        }
        return (Map<String, Object>) res;
    }

    private static String buildVarUserPrompt(AgentContext agentContext) {
        StringBuilder stringBuilder = new StringBuilder();
        if (StringUtils.isNotBlank(agentContext.getAgentConfig().getSystemPrompt())) {
            stringBuilder.append("## Target Conversation's System Prompt:\n").append(agentContext.getAgentConfig().getSystemPrompt()).append("\n\n");
        }
        return stringBuilder.append(agentContext.getContextMd()).toString();
    }

    private static ModelContext buildModelContext(AgentContext agentContext, String systemPrompt, String userPrompt,
                                                  String conversationId, OutputTypeEnum outputType, List<Arg> outputArgs) {
        ModelContext modelContext = new ModelContext();
        modelContext.setRequestId(agentContext.getRequestId());
        modelContext.setAgentContext(agentContext);
        modelContext.setConversationId(conversationId);
        modelContext.setModelConfig((ModelConfigDto) agentContext.getAgentConfig().getModelComponentConfig().getTargetConfig());
        ModelBindConfigDto modelBindConfigDto = (ModelBindConfigDto) agentContext.getAgentConfig().getModelComponentConfig().getBindConfig();
        ModelCallConfigDto modelCallConfigDto = new ModelCallConfigDto();
        modelCallConfigDto.setSystemPrompt(systemPrompt);
        modelCallConfigDto.setUserPrompt(userPrompt);
        modelCallConfigDto.setChatRound(0);
        modelCallConfigDto.setStreamCall(true);
        modelCallConfigDto.setOutputType(outputType);
        modelCallConfigDto.setOutputArgs(outputArgs);
        modelCallConfigDto.setMaxTokens(modelBindConfigDto.getMaxTokens());
        modelCallConfigDto.setTemperature(modelBindConfigDto.getTemperature());
        modelCallConfigDto.setTopP(modelBindConfigDto.getTopP());
        modelContext.setModelCallConfig(modelCallConfigDto);
        modelContext.setTraceContext(agentContext.getTraceContext().next(TraceContext.TraceTargetType.Model, modelContext.getModelConfig().getId().toString(),
                modelContext.getModelConfig().getModel(), modelContext.getModelConfig().getName(), null, agentContext.isDefaultModelChanged() ? agentContext.getUserId() : null));
        return modelContext;
    }

    private static AgentOutputDto buildProcessOutput(AgentComponentConfigDto componentConfig, ExecuteStatusEnum executeStatus, ComponentTypeEnum type, ComponentExecuteResult componentExecuteResult) {
        CardBindConfigDto cardBindDto = null;
        if (componentConfig.getType() == AgentComponentConfig.Type.Workflow) {
            WorkflowBindConfigDto workflowBindConfigDto = (WorkflowBindConfigDto) componentConfig.getBindConfig();
            cardBindDto = workflowBindConfigDto.getCardBindConfig();
        }
        if (componentConfig.getType() == AgentComponentConfig.Type.Plugin) {
            PluginBindConfigDto pluginBindConfigDto = (PluginBindConfigDto) componentConfig.getBindConfig();
            cardBindDto = pluginBindConfigDto.getCardBindConfig();
        }
        ComponentExecutingDto componentExecutingDto = new ComponentExecutingDto();
        componentExecutingDto.setTargetId(componentConfig.getTargetId());
        componentExecutingDto.setName(componentConfig.getName());
        componentExecutingDto.setType(type);
        componentExecutingDto.setStatus(executeStatus);
        componentExecutingDto.setResult(componentExecuteResult);
        componentExecutingDto.setCardBindConfig(cardBindDto);
        AgentOutputDto agentOutputDto = new AgentOutputDto();
        agentOutputDto.setData(componentExecutingDto);
        agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.PROCESSING);
        return agentOutputDto;
    }

    //从缓存中提取完整的执行日志
    public static AgentExecuteResult buildAgentExecuteResult(AgentContext agentContext) {
        Map<String, Object> hashAll = SimpleJvmHashCache.getHashAll(agentContext.getRequestId());
        if (hashAll == null) {
            return null;
        }
        AgentExecuteResult agentExecuteResult0 = agentContext.getAgentExecuteResult();
        if (agentExecuteResult0 == null) {
            return null;
        }
        AgentExecuteResult agentExecuteResult = new AgentExecuteResult();
        agentExecuteResult.setError(agentExecuteResult0.getError());
        agentExecuteResult.setSuccess(agentExecuteResult0.getSuccess());
        agentExecuteResult.setStartTime(agentExecuteResult0.getStartTime());
        agentExecuteResult.setEndTime(agentExecuteResult0.getEndTime() == null || agentExecuteResult0.getEndTime() == 0 ? System.currentTimeMillis() : agentExecuteResult0.getEndTime());
        agentExecuteResult.setCompletionTokens(agentExecuteResult0.getCompletionTokens());
        agentExecuteResult.setPromptTokens(agentExecuteResult0.getPromptTokens());
        agentExecuteResult.setTotalTokens(agentExecuteResult0.getTotalTokens());
        agentExecuteResult.setOutputText(agentExecuteResult0.getOutputText());
        List<ComponentExecuteResult> componentExecuteResults = new ArrayList<>(agentExecuteResult0.getComponentExecuteResults());
        agentExecuteResult.setComponentExecuteResults(componentExecuteResults);
        Map<String, Map<String, Object>> modelExecuteInfos = (Map<String, Map<String, Object>>) hashAll.get("modelExecuteInfos");
        if (modelExecuteInfos != null) {
            AtomicInteger completionTokens = new AtomicInteger();
            AtomicInteger promptTokens = new AtomicInteger();
            AtomicInteger totalTokens = new AtomicInteger();
            Collection<Map<String, Object>> values = modelExecuteInfos.values();
            values.forEach(modelExecuteInfo -> {
                ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
                componentExecuteResult.setName(String.valueOf(modelExecuteInfo.get("name")));
                componentExecuteResult.setSuccess(modelExecuteInfo.get("outputText") != null);
                componentExecuteResult.setData(String.valueOf(modelExecuteInfo.get("outputText")));
                componentExecuteResult.setStartTime(Long.valueOf(String.valueOf(modelExecuteInfo.get("startTime"))));
                if (modelExecuteInfo.get("endTime") != null) {
                    componentExecuteResult.setEndTime(Long.valueOf(String.valueOf(modelExecuteInfo.get("endTime"))));
                }
                componentExecuteResult.setInput(modelExecuteInfo.get("userPrompt"));
                componentExecuteResult.setType(ComponentTypeEnum.Model);
                componentExecuteResult.setInnerExecuteInfo(modelExecuteInfo.get("originalOutput"));
                try {
                    //promptTokens
                    int promptTokens1 = promptTokens.addAndGet(Integer.parseInt(String.valueOf(modelExecuteInfo.get("promptTokens"))));
                    int completionTokens1 = completionTokens.addAndGet(Integer.parseInt(String.valueOf(modelExecuteInfo.get("completionTokens"))));
                    totalTokens.set(promptTokens1 + completionTokens1);
                } catch (Exception e) {
                    log.warn("Parse modelExecuteInfo error:{}", e.getMessage());
                }

                componentExecuteResults.add(componentExecuteResult);
            });
            agentExecuteResult.setTotalTokens(totalTokens.get());
            agentExecuteResult.setCompletionTokens(completionTokens.get());
            agentExecuteResult.setPromptTokens(promptTokens.get());
            //按照componentExecuteResults endTime排序，getEndTime为null时默认为0
            componentExecuteResults.sort((o1, o2) -> {
                Long endTime1 = o1.getEndTime();
                Long endTime2 = o2.getEndTime();
                if (endTime1 == null) {
                    endTime1 = 0L;
                }
                if (endTime2 == null) {
                    endTime2 = 0L;
                }
                return endTime1.compareTo(endTime2);
            });
            if (!componentExecuteResults.isEmpty()) {
                // 如果最后一个是模型调用，并且模型调用结果为空，则使用agentExecuteResult0.getOutputText()
                ComponentExecuteResult componentExecuteResult = componentExecuteResults.get(componentExecuteResults.size() - 1);
                if (componentExecuteResult.getType() == ComponentTypeEnum.Model && (componentExecuteResult.getData() == null || StringUtils.isBlank(componentExecuteResult.getData().toString()))
                        && agentExecuteResult0.getSuccess() != null && !componentExecuteResult.getSuccess()) {
                    componentExecuteResult.setData(agentExecuteResult0.getOutputText());
                }
            }
        }
        return agentExecuteResult;
    }

    private static String buildToolCallResult(String toolName, String result, String errorMessage) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("The following is the tool `").append(toolName).append("` invocation result:\n");
        if (result != null) {
            stringBuilder.append(result);
        }
        if (errorMessage != null) {
            stringBuilder.append("<error_message>").append(errorMessage).append("</error_message>");
        }
        return stringBuilder.append("\n\n").toString();
    }

    private static String getMcpToolDescription(McpDto targetConfig, String toolName) {
        if (targetConfig.getMcpConfig() == null || targetConfig.getDeployedConfig().getTools() == null) {
            return targetConfig.getDescription();
        }
        for (McpToolDto tool : targetConfig.getDeployedConfig().getTools()) {
            if (tool.getName().equals(toolName)) {
                return tool.getDescription();
            }
        }
        return targetConfig.getDescription();
    }

    public Mono<List<String>> suggestQuestions(AgentContext agentContext) {
        try {
            Assert.notNull(agentContext, "agentContext cannot be left blank.");
            Assert.notNull(agentContext.getAgentConfig(), "agentConfig cannot be left blank.");
            Assert.notNull(agentContext.getConversationId(), "conversationId cannot be left blank.");
            Assert.notNull(agentContext.getUserId(), "userId cannot be left blank.");

        } catch (Exception e) {
            log.warn("Parameter validation failed", e);
            return Mono.error(e);
        }
        AtomicReference<Disposable> nextDisposable = new AtomicReference<>();
        Mono<List<String>> suggestQuestions = Mono.create(sink -> {
            //建议问题
            if (agentContext.getAgentConfig().getOpenSuggest() == AgentConfig.OpenStatus.Open) {
                List<Arg> outputArgs = new ArrayList<>();
                Arg arg = new Arg();
                arg.setRequire(true);
                arg.setName("suggestList");
                arg.setDescription("Suggest list");
                arg.setDataType(DataTypeEnum.Array_String);
                outputArgs.add(arg);
                ModelContext suggestModelContext = buildModelContext(agentContext, buildSuggestSystemPrompt(agentContext), buildSuggestUserPrompt(agentContext),
                        null, OutputTypeEnum.JSON, outputArgs);
                Disposable disposable = modelInvoker.invoke(suggestModelContext).doOnError((e) -> {
                    log.error("Suggested question invocation failed", e);
                    sink.success(new ArrayList<>());
                }).doOnComplete(() -> {
                    log.info("Suggested question result:{}", suggestModelContext.getModelCallResult().getData());
                    Object data = suggestModelContext.getModelCallResult().getData();
                    List<String> list = new ArrayList<>();
                    if (data instanceof Map) {
                        Map<String, Object> resMap = (Map<String, Object>) data;
                        if (resMap.containsKey("suggestList")) {
                            List<String> suggestList = (List<String>) resMap.get("suggestList");
                            if (!CollectionUtils.isEmpty(suggestList)) {
                                list.addAll(suggestList);
                            }
                        }
                    }
                    sink.success(list);
                }).subscribe();
                nextDisposable.set(disposable);
            } else {
                sink.success(new ArrayList<>());
            }
        });

        return suggestQuestions.doOnCancel(() -> {
            if (nextDisposable.get() != null) {
                nextDisposable.get().dispose();
            }
        });
    }

}
