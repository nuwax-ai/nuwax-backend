package com.xspaceagi.agent.core.infra.component.model;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.dto.ChatMessageDto;
import com.xspaceagi.agent.core.adapter.dto.KnowledgeSearchConfigDto;
import com.xspaceagi.agent.core.adapter.dto.PluginExecuteResultDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.PageArgConfig;
import com.xspaceagi.agent.core.adapter.dto.config.bind.*;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.EndNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.infra.component.ArgExtractUtil;
import com.xspaceagi.agent.core.infra.component.BaseComponent;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.agent.AgentExecutor;
import com.xspaceagi.agent.core.infra.component.knowledge.KnowledgeBaseSearcher;
import com.xspaceagi.agent.core.infra.component.knowledge.SearchContext;
import com.xspaceagi.agent.core.infra.component.mcp.McpContext;
import com.xspaceagi.agent.core.infra.component.mcp.McpExecutor;
import com.xspaceagi.agent.core.infra.component.model.dto.*;
import com.xspaceagi.agent.core.infra.component.model.openai.OpenAiApi;
import com.xspaceagi.agent.core.infra.component.plugin.PluginContext;
import com.xspaceagi.agent.core.infra.component.plugin.PluginExecutor;
import com.xspaceagi.agent.core.infra.component.table.TableExecutor;
import com.xspaceagi.agent.core.infra.component.table.dto.TableExecutorContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowExecutor;
import com.xspaceagi.agent.core.infra.converter.ArgConverter;
import com.xspaceagi.agent.core.spec.constant.Prompts;
import com.xspaceagi.agent.core.spec.enums.*;
import com.xspaceagi.agent.core.spec.utils.TikTokensUtil;
import com.xspaceagi.agent.core.spec.utils.ToolInfoExtractor;
import com.xspaceagi.agent.core.spec.utils.UrlFile;
import com.xspaceagi.compose.sdk.vo.define.TableDefineVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeQaVo;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.dto.McpToolDto;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.AgentInterruptException;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.Media;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.metadata.DefaultToolMetadata;
import org.springframework.ai.tool.metadata.ToolMetadata;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class ModelInvoker extends BaseComponent {

    private ModelClientFactory modelClientFactory;

    private ModelPageRequest modelPageRequest;

    private WorkflowExecutor workflowExecutor;

    private AgentExecutor agentExecutor;

    private KnowledgeBaseSearcher knowledgeBaseSearcher;

    private McpExecutor mcpExecutor;

    private PluginExecutor pluginExecutor;

    private TableExecutor tableExecutor;

    @Autowired
    public void setModelPageRequest(ModelPageRequest modelPageRequest) {
        this.modelPageRequest = modelPageRequest;
    }

    @Autowired
    public void setModelClientFactory(ModelClientFactory modelClientFactory) {
        this.modelClientFactory = modelClientFactory;
    }

    @Autowired
    public void setWorkflowExecutor(WorkflowExecutor workflowExecutor) {
        this.workflowExecutor = workflowExecutor;
    }

    @Autowired
    public void setAgentExecutor(AgentExecutor agentExecutor) {
        this.agentExecutor = agentExecutor;
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

    public Flux<CallMessage> invoke(ModelContext modelContext) {
        if (modelContext.getModelConfig() == null) {
            return Flux.error(new IllegalArgumentException("The model is not configured or has been deleted."));
        }
        log.info("invoke id {}, model: {}, call config: {}", modelContext.getModelConfig().getId(), modelContext.getModelConfig().getName(), modelContext.getModelCallConfig());
        // Reset tool block
        try {
            String systemPrompt = resetToolBlock(modelContext.getModelCallConfig().getComponentConfigs(), modelContext.getModelCallConfig().getSystemPrompt());
            String userPrompt = resetToolBlock(modelContext.getModelCallConfig().getComponentConfigs(), modelContext.getModelCallConfig().getUserPrompt());
            modelContext.getModelCallConfig().setSystemPrompt(systemPrompt);
            modelContext.getModelCallConfig().setUserPrompt(userPrompt);
        } catch (Exception e) {
            // ignore
            log.error("Reset tool block error", e);
        }
        if (modelContext.getAgentContext().isInterrupted()) {
            return Flux.empty();
        }
        Sinks.Many<CallMessage> sink = Sinks.many().multicast().onBackpressureBuffer();
        AtomicReference<Disposable> atomicReference = null;
        if (modelContext.getModelCallConfig().isStreamCall()) {
            try {
                atomicReference = streamCall(modelContext, sink);
            } catch (Throwable e) {
                return Flux.error(e);
            }
        } else {
            // Not used
            submit(() -> nonStreamCall(modelContext, sink));
        }
        AtomicReference<Disposable> disposableAtomicReference = atomicReference;
        return sink.asFlux().publishOn(Schedulers.boundedElastic()).doOnCancel(() -> {
            if (disposableAtomicReference != null && disposableAtomicReference.get() != null) {
                disposableAtomicReference.get().dispose();
            }
        });
    }

    private AtomicReference<Disposable> streamCall(ModelContext modelContext, Sinks.Many<CallMessage> sink) {
        log.debug("stream call");
        AtomicReference<Disposable> atomicReference = new AtomicReference<>();
        List<Message> tempMessages;
        if (modelContext.getAgentContext().getContextMessages() != null) {
            tempMessages = modelContext.getAgentContext().getContextMessages();
        } else {
            tempMessages = new ArrayList<>();
            modelContext.getAgentContext().setContextMessages(tempMessages);
        }
        // User message
        tempMessages.add(buildUserMessage(modelContext));
        // Append tool auto-call context
        if (modelContext.getAgentContext().getAutoToolCallMessages() != null) {
            tempMessages.addAll(modelContext.getAgentContext().getAutoToolCallMessages());
        }

        StringBuilder finalMsgSb = new StringBuilder();
        List<ToolCallback> functionCallbacks = buildFunctionCallbacks(modelContext);
        ChatClient.ChatClientRequestSpec chatClientRequestSpec;
        // Model does not support streaming function calls, use custom function call execution
        if (!functionCallbacks.isEmpty() && modelContext.getModelConfig().getFunctionCall() != ModelFunctionCallEnum.StreamCallSupported) {
            String systemPrompt = Prompts.buildToolUsePrompt(functionCallbacks.stream().map(functionToolCallback -> {
                Prompts.ToolUse toolUse = new Prompts.ToolUse();
                toolUse.setName(functionToolCallback.getToolDefinition().name());
                toolUse.setDescription(functionToolCallback.getToolDefinition().description());
                toolUse.setArguments(functionToolCallback.getToolDefinition().inputSchema());
                return toolUse;
            }).collect(Collectors.toList()), buildSystemMessage(modelContext).getText());
            SystemMessage systemMessage = new SystemMessage(systemPrompt);
            tempMessages.add(0, systemMessage);
            customReactStreamCall(modelContext, functionCallbacks, tempMessages, sink, finalMsgSb, atomicReference);
            return atomicReference;
        }
        tempMessages.add(0, buildSystemMessage(modelContext));
        // Model supports streaming function calls
        chatClientRequestSpec = createChatClientRequestSpec(modelContext, functionCallbacks, new Prompt(tempMessages));
        String messageId = modelContext.getRequestId();
        AtomicBoolean thinking = new AtomicBoolean(false);
        AtomicBoolean addThinkTag = new AtomicBoolean(false);
        AtomicBoolean finished = new AtomicBoolean(false);
        Disposable disposable = chatClientRequestSpec.stream().chatResponse().onErrorResume(throwable -> {
            if (throwable instanceof TimeoutException) {
                return Mono.error(new TimeoutException("Model execution timeout"));
            }
            if (!(throwable instanceof AgentInterruptException)) {
                log.warn("customStream call error", throwable);
            }
            return Mono.error(throwable);
        }).doOnComplete(() -> {
            if (!finished.get()) {
                handleFinish(modelContext, sink, messageId, finalMsgSb.toString(), "stop");
            }
        }).subscribe(chatResponse -> {
            if (chatResponse.getResult() == null) {
                return;
            }
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            doMessage(modelContext, sink, messageId, assistantMessage, assistantMessage.getText(), finalMsgSb, thinking, addThinkTag, finished, null);
        }, throwable -> doOnError(modelContext, sink, messageId, throwable));
        atomicReference.set(disposable);
        return atomicReference;
    }

    private void customReactStreamCall(ModelContext modelContext, List<ToolCallback> functionCallbacks, List<Message> tempMessages,
                                       Sinks.Many<CallMessage> sink, StringBuilder finalMsgSb, AtomicReference<Disposable> atomicReference) {
        Prompt prompt = new Prompt(tempMessages);
        ChatClient.ChatClientRequestSpec chatClientRequestSpec = createChatClientRequestSpec(modelContext, new ArrayList<>(), prompt);
        String messageId = modelContext.getRequestId();
        StringBuilder tempMessageSb = new StringBuilder();
        AtomicBoolean thinking = new AtomicBoolean(false);
        AtomicBoolean addThinkTag = new AtomicBoolean(false);
        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicBoolean waitingForToolCallInfo = new AtomicBoolean(false);
        Disposable disposable = chatClientRequestSpec.stream().chatResponse().onErrorResume(throwable -> {
            if (!(throwable instanceof AgentInterruptException)) {
                log.warn("customStream call error", throwable);
            }
            if (throwable instanceof TimeoutException) {
                return Mono.error(new TimeoutException("Model execution timeout"));
            }
            return Mono.error(throwable);
        }).doOnComplete(() -> {
            if (waitingForToolCallInfo.get() && !finished.get()) {
                customToolCalls(modelContext, sink, functionCallbacks, tempMessages, thinking, finished, tempMessageSb, finalMsgSb, finalMsgSb, messageId, atomicReference);
            }
            if (!finished.get()) {
                handleFinish(modelContext, sink, messageId, finalMsgSb.toString(), "stop");
            }
        }).subscribe(chatResponse -> {
            if (chatResponse.getResult() == null) {
                return;
            }
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            String text = assistantMessage.getText();
            if (text != null) {
                if (waitingForToolCallInfo.get()) {
                    tempMessageSb.append(text);
                    Object finishReason = assistantMessage.getMetadata().get("finishReason");
                    if (OpenAiApi.ChatCompletionFinishReason.value(finishReason) != null) {
                        customToolCalls(modelContext, sink, functionCallbacks, tempMessages, thinking, finished, tempMessageSb, finalMsgSb, finalMsgSb, messageId, atomicReference);
                        waitingForToolCallInfo.set(false);
                    }
                    return;
                } else if (thinking.get() && text.contains("</think>")) {
                    // Model thinking ends without reasoning_content, but status not yet updated, avoid missing tool calls
                    String[] split = text.split("</think>");
                    text = "</think>";
                    if (split.length > 0) {
                        text = split[0] + "</think>";
                    }
                    if (split.length > 1) {
                        tempMessageSb.append(split[1]);
                    }
                } else if (modelContext.isHasReasoningContent() && StringUtils.isNotBlank(text) && thinking.get()) {
                    // Model thinking ends with reasoning_content, but status not yet updated, avoid missing tool calls
                    tempMessageSb.append(text);
                    text = "";
                } else if (!thinking.get()) {
                    tempMessageSb.append(text);
                    String str = tempMessageSb.toString();
                    if (str.endsWith("<") || str.endsWith("```") || str.trim().endsWith("xml")) {
                        return;
                    }
                    if (str.contains("<tool")) {
                        waitingForToolCallInfo.set(true);
                        text = str.substring(0, str.indexOf("<tool"));
                        if (text.trim().endsWith("```xml")) {
                            text = text.substring(0, text.lastIndexOf("```xml"));
                        }
                    } else {
                        text = str;
                        tempMessageSb.setLength(0);
                    }
                }
            }

            doMessage(modelContext, sink, messageId, assistantMessage, text, finalMsgSb, thinking, addThinkTag, finished, waitingForToolCallInfo);
        }, throwable -> doOnError(modelContext, sink, messageId, throwable));
        // Can be dynamically replaced during react process
        atomicReference.set(disposable);
    }

    private void customToolCalls(ModelContext modelContext, Sinks.Many<CallMessage> sink, List<ToolCallback> functionCallbacks,
                                 List<Message> tempMessages, AtomicBoolean thinking, AtomicBoolean finished,
                                 StringBuilder tempMessageSb, StringBuilder msgSb, StringBuilder finalMsgSb, String messageId,
                                 AtomicReference<Disposable> atomicReference) {
        String toolMsg = tempMessageSb.toString();
        if (toolMsg.matches("[\\s\\S]*?<tool_.*>[\\s\\S]*?</tool_.*>[\\s\\S]*?")) {
            // Parse tool and execute
            log.info("Tool call info: {}", toolMsg);
            finished.set(true);
            addModelExecuteLog(modelContext, msgSb + toolMsg);
            List<Object> list = ToolInfoExtractor.extractToolInfo(toolMsg);
            StringBuilder toolCallResult = new StringBuilder();
            for (Object obj : list) {
                if (obj instanceof String) {
                    CallMessage callMessage = new CallMessage();
                    callMessage.setText(obj.toString());
                    callMessage.setType(thinking.get() ? MessageTypeEnum.THINK : MessageTypeEnum.CHAT);
                    callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
                    callMessage.setId(messageId);
                    sink.tryEmitNext(callMessage);
                } else {
                    Prompts.ToolUse toolUse = (Prompts.ToolUse) obj;
                    ToolCallback functionToolCallback = functionCallbacks.stream().filter(functionToolCallback0 -> functionToolCallback0.getToolDefinition().name().equals(toolUse.getName())).findFirst().orElse(null);
                    if (functionToolCallback == null && toolUse.getName() != null) {
                        functionToolCallback = functionCallbacks.stream().filter(functionToolCallback0 -> functionToolCallback0.getToolDefinition().description() != null
                                && functionToolCallback0.getToolDefinition().description().startsWith(toolUse.getName())).findFirst().orElse(null);
                    }
                    if (functionToolCallback != null) {
                        String call;
                        try {
                            call = functionToolCallback.call(toolUse.getArguments());
                            if (functionToolCallback.getToolMetadata() != null && functionToolCallback.getToolMetadata().returnDirect()) {
                                CallMessage callMessage = new CallMessage();
                                callMessage.setText(call);
                                callMessage.setType(MessageTypeEnum.CHAT);
                                callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
                                callMessage.setId(messageId);
                                sink.tryEmitNext(callMessage);
                                handleFinish(modelContext, sink, messageId, call, "stop");
                                return;
                            }
                        } catch (Exception e) {
                            if (e instanceof IllegalStateException && e.getMessage().contains("Conversion from JSON to java.util.HashMap failed")) {
                                call = "Error arguments：Invalid JSON";
                            } else {
                                doOnError(modelContext, sink, messageId, e);
                                return;
                            }
                        }
                        toolCallResult.append("Below is the result of calling tool `").append(toolUse.getName()).append("`:\n").append(call).append("\n");
                    } else {
                        toolCallResult.append("The tool returned by the model does not exist, please do not fabricate tools");
                    }
                }
            }
            tempMessages.add(new AssistantMessage(msgSb + toolMsg));
            tempMessages.add(new UserMessage(toolCallResult.toString()));
            customReactStreamCall(modelContext, functionCallbacks, tempMessages, sink, finalMsgSb, atomicReference);
        } else {
            CallMessage callMessage = new CallMessage();
            callMessage.setText(toolMsg);
            callMessage.setType(MessageTypeEnum.CHAT);
            callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
            callMessage.setId(messageId);
            sink.tryEmitNext(callMessage);
        }
    }

    private void doMessage(ModelContext modelContext, Sinks.Many<CallMessage> sink, String messageId, AssistantMessage assistantMessage,
                           String text, StringBuilder msgSb, AtomicBoolean thinking, AtomicBoolean addThinkTag, AtomicBoolean finished, AtomicBoolean waitingForToolCallInfo) {
        if (modelContext.getAgentContext().isInterrupted()) {
            if (thinking.get()) {
                msgSb.append("</think>");
            }
            modelContext.getAgentContext().getAgentExecuteResult().setOutputText(msgSb.toString());
            modelContext.getModelCallResult().setResponseText(msgSb.toString());
            AgentInterruptException agentInterruptException = new AgentInterruptException("Interrupted output (this exception can be ignored)");
            sink.tryEmitError(agentInterruptException);
            throw agentInterruptException;
        }

        String textContent = null;
        Object reasoningContent = assistantMessage.getMetadata().get("reasoningContent");
        if (reasoningContent == null || StringUtils.isBlank(reasoningContent.toString())) {
            reasoningContent = assistantMessage.getMetadata().get("reasoning");
        }
        // Thinking
        boolean isReasoning = reasoningContent != null && StringUtils.isNotBlank(reasoningContent.toString());
        if (modelContext.getModelCallResult().getFirstResponseTime() == null) {
            modelContext.getModelCallResult().setFirstResponseTime(System.currentTimeMillis());
            if (StringUtils.isBlank(text) && !isReasoning && assistantMessage.getMetadata() != null) {
                // deepseek function call exception finishReason=TOOL_CALLS
                Object finishReason = assistantMessage.getMetadata().get("finishReason");
                if (finishReason != null && finishReason.toString().equals("TOOL_CALLS")
                        && CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
                    CallMessage callMessage = new CallMessage();
                    callMessage.setText(text);
                    callMessage.setType(MessageTypeEnum.CHAT);
                    callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
                    callMessage.setId(messageId);
                    callMessage.setFinished(false);
                    callMessage.setText("The model is not working properly😒 please resend your message～");
                    sink.tryEmitNext(callMessage);
                    modelContext.getAgentContext().getAgentExecuteResult().setSuccess(false);
                    modelContext.getAgentContext().getAgentExecuteResult().setError(callMessage.getText());
                }
                return;
            }
        }

        modelContext.setHasReasoningContent(isReasoning);
        // Configuration only for models that do not support the reasoning_content field
        boolean isReasoningModel = modelContext.getModelConfig().getIsReasonModel() != null && modelContext.getModelConfig().getIsReasonModel() == 1;
        // Models that normally support the reasoning_content field
        if (modelContext.isHasReasoningContent()) {
            if (isReasoning && !thinking.get()) {
                thinking.set(true);
                text = "<think>" + reasoningContent;
            } else if (thinking.get() && !isReasoning) {
                thinking.set(false);
                textContent = text == null ? "" : text;
                text = "</think>" + (text == null ? "" : text);
            } else if (!isReasoning) {
                textContent = text;
            } else if (isReasoning) {
                text = reasoningContent.toString();
            }
        } else if (isReasoningModel) {
            // Models without reasoning_content field but with think tags
            // Compatible with reasoning models that have neither reasoning_content nor think start tags
            if (!addThinkTag.get() && StringUtils.isNotBlank(text) && !text.contains("<think>")) {
                text = "<think>" + text;
                addThinkTag.set(true);
            }

            // Do not handle the case where start and end tags are together for now
            if (text != null && text.contains("<think>") && !thinking.get()) {
                // Start tag processing
                // Models without reasoning_content field but with think tags
                thinking.set(true);
                textContent = text.substring(0, text.indexOf("<think>"));
                reasoningContent = text.substring(text.indexOf("<think>") + 7);
            } else if (text != null && text.contains("</think>")) {
                // End tag processing
                thinking.set(false);
                textContent = text.substring(text.indexOf("</think>") + 8);
                reasoningContent = text.substring(0, text.indexOf("</think>"));
            } else if (thinking.get()) {
                // Models without reasoning_content field, output thinking content
                reasoningContent = text;
            } else {
                textContent = text;
            }
        } else {
            textContent = text;
        }

        // Record all message content
        msgSb.append(text == null ? "" : text);
        log.debug("textContent: {}, reasoningContent: {}", textContent, reasoningContent);

        if (reasoningContent != null && !"".equals(reasoningContent)) {
            CallMessage callMessage = new CallMessage();
            callMessage.setText(reasoningContent.toString());
            callMessage.setType(MessageTypeEnum.THINK);
            callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
            callMessage.setId(messageId);
            callMessage.setFinished(false);
            sink.tryEmitNext(callMessage);
        }
        if (textContent != null && !textContent.isEmpty()) {
            CallMessage callMessage = new CallMessage();
            callMessage.setText(textContent);
            callMessage.setType(MessageTypeEnum.CHAT);
            callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
            callMessage.setId(messageId);
            callMessage.setFinished(false);
            sink.tryEmitNext(callMessage);
        }

        if (assistantMessage.getMetadata() != null && (waitingForToolCallInfo == null || !waitingForToolCallInfo.get())) {
            OpenAiApi.ChatCompletionFinishReason finishReason = OpenAiApi.ChatCompletionFinishReason.value(assistantMessage.getMetadata().get("finishReason"));
            if (finishReason != null && finishReason != OpenAiApi.ChatCompletionFinishReason.TOOL_CALL && finishReason != OpenAiApi.ChatCompletionFinishReason.TOOL_CALLS) {
                handleFinish(modelContext, sink, messageId, msgSb.toString(), finishReason.toString());
                finished.set(true);
            }
        }
    }


    private void doOnError(ModelContext modelContext, Sinks.Many<CallMessage> sink, String messageId, Throwable throwable) {
        if (!modelContext.getAgentContext().isInterrupted()) {
            log.error("stream call error", throwable);
        }
        CallMessage callMessage = new CallMessage();
        callMessage.setText(throwable.getMessage());
        callMessage.setType(MessageTypeEnum.CHAT);
        callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
        callMessage.setId(messageId);
        callMessage.setFinished(true);
        callMessage.setFinishReason("ERROR");
        modelContext.getModelCallResult().setEndTime(System.currentTimeMillis());
        sink.tryEmitNext(callMessage);
        sink.tryEmitError(throwable);
    }

    private void handleFinish(ModelContext modelContext, Sinks.Many<CallMessage> sink, String messageId, String text, String finishReason) {
        if (modelContext.getModelCallConfig().getOutputType() == OutputTypeEnum.JSON) {
            String text0 = getJSONText(text == null ? "" : text);
            if (!JSON.isValid(text0) && modelContext.getRetryCount() < 3) {
                modelContext.setRetryCount(modelContext.getRetryCount() + 1);
                log.warn("The JSON format returned by the model is incorrect, please check the prompt. Returned content: {}", text);
                streamCall(modelContext, sink);
                return;
            }
        }
        setModelCallResult(modelContext, text);
        CallMessage callMessage = new CallMessage();
        callMessage.setText("");
        callMessage.setType(MessageTypeEnum.CHAT);
        callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
        callMessage.setId(messageId);
        callMessage.setFinished(true);
        callMessage.setFinishReason(finishReason);
        sink.tryEmitNext(callMessage);

        // Add model execution log
        addModelExecuteLog(modelContext, text);
        sink.tryEmitComplete();
    }

    private void setModelCallResult(ModelContext modelContext, String text) {
        AtomicInteger completionTokens = new AtomicInteger(0);
        AtomicInteger promptTokens = new AtomicInteger(tikPromptTokensCount(modelContext));
        AtomicInteger totalTokens = new AtomicInteger(0);
        completionTokens.set(TikTokensUtil.tikTokensCount(text));
        totalTokens.set(completionTokens.get() + promptTokens.get());
        modelContext.getModelCallResult().setCompletionTokens(completionTokens.get());
        modelContext.getModelCallResult().setPromptTokens(promptTokens.get());
        modelContext.getModelCallResult().setTotalTokens(totalTokens.get());
        modelContext.getModelCallResult().setResponseText(text);
        modelContext.getModelCallResult().setData(parseText(modelContext, text));
        modelContext.getModelCallResult().setEndTime(System.currentTimeMillis());
    }

    private void nonStreamCall(ModelContext modelContext, Sinks.Many<CallMessage> sink) {
        nonStreamCall(modelContext, sink, 0);
    }

    // Not used
    private void nonStreamCall(ModelContext modelContext, Sinks.Many<CallMessage> sink, int retryCount) {
        log.debug("non stream call");
        List<ToolCallback> functionCallbacks = buildFunctionCallbacks(modelContext);
        ChatResponse chatResponse = createChatClientRequestSpec(modelContext, functionCallbacks, null).call().chatResponse();
        String text = null;
        assert chatResponse != null;
        if (chatResponse.getResult() != null && chatResponse.getResult().getOutput() != null) {
            text = chatResponse.getResult().getOutput().getText();
        }

        if (modelContext.getModelCallConfig().getOutputType() == OutputTypeEnum.JSON) {
            text = getJSONText(text == null ? "" : text);
            if (!JSON.isValid(text) && retryCount < 3) {
                nonStreamCall(modelContext, sink, retryCount + 1);
                return;
            }
        }

        if (chatResponse.getMetadata() != null) {
            Usage usage = chatResponse.getMetadata().getUsage();
            modelContext.getModelCallResult().setTotalTokens(usage.getTotalTokens());
            modelContext.getModelCallResult().setPromptTokens(usage.getPromptTokens());
            modelContext.getModelCallResult().setCompletionTokens(usage.getCompletionTokens());
        }
        modelContext.getModelCallResult().setResponseText(text);
        modelContext.getModelCallResult().setEndTime(System.currentTimeMillis());
        Object data = parseText(modelContext, text);
        modelContext.getModelCallResult().setData(data);
        CallMessage callMessage = new CallMessage();
        callMessage.setText(text);
        callMessage.setType(MessageTypeEnum.CHAT);
        callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
        callMessage.setId(UUID.randomUUID().toString().replace("-", ""));
        callMessage.setFinished(true);
        sink.tryEmitNext(callMessage);
        sink.tryEmitComplete();

        // Add model execution result log
        addModelExecuteLog(modelContext, text);
    }

    private void addModelExecuteLog(ModelContext modelContext, String text) {
        try {
            Object modelExecuteInfos = SimpleJvmHashCache.getHash(modelContext.getRequestId(), "modelExecuteInfos");
            if (modelExecuteInfos != null && modelContext.getTraceId() != null) {
                Map<String, Object> modelExecuteInfoMap = (Map<String, Object>) modelExecuteInfos;
                Object o = modelExecuteInfoMap.get(modelContext.getTraceId());
                if (o != null) {
                    Map<String, Object> modelExecuteInfo = (Map<String, Object>) o;
                    if (CollectionUtils.isNotEmpty(modelContext.getAgentContext().getContextMessages())) {
                        StringBuilder userPromptSb = new StringBuilder();
                        modelContext.getAgentContext().getContextMessages().forEach(message -> {
                            if (message.getMessageType() != MessageType.SYSTEM) {
                                userPromptSb.append(message.getMessageType().name()).append(":\n").append(message.getText()).append("\n\n");
                            }
                        });
                        modelExecuteInfo.put("userPrompt", userPromptSb.toString());
                        modelContext.getModelCallConfig().setUserPrompt(userPromptSb.toString());
                        modelContext.getModelCallResult().setPromptTokens(tikPromptTokensCount(modelContext));
                    }
                    modelExecuteInfo.put("outputText", text);
                    modelExecuteInfo.put("promptTokens", modelContext.getModelCallResult().getPromptTokens() == null ? tikPromptTokensCount(modelContext) : modelContext.getModelCallResult().getPromptTokens());
                    modelExecuteInfo.put("completionTokens", TikTokensUtil.tikTokensCount(text));
                    modelExecuteInfo.put("endTime", System.currentTimeMillis());
                }
            }
        } catch (Exception e) {
            log.error("add model execute log error", e);
            //  ignore
        }
    }

    private ChatClient.ChatClientRequestSpec createChatClientRequestSpec(ModelContext modelContext, List<ToolCallback> functionCallbacks, Prompt prompt) {
        String traceId = UUID.randomUUID().toString().replace("-", "");
        modelContext.setTraceId(traceId);
        if (modelContext.getModelCallConfig().getMaxTokens() != null && modelContext.getModelCallConfig().getMaxTokens() > 0) {
            modelContext.getModelConfig().setMaxTokens(modelContext.getModelCallConfig().getMaxTokens());
        }
        modelContext.getModelConfig().setTemperature(modelContext.getModelCallConfig().getTemperature());
        modelContext.getModelConfig().setTopP(modelContext.getModelCallConfig().getTopP());
        ChatClient client = modelClientFactory.createChatClient(modelContext, modelContext.getModelConfig(), functionCallbacks);
        modelContext.getModelCallResult().setStartTime(System.currentTimeMillis());
        if (prompt == null) {
            prompt = new Prompt(List.of(buildSystemMessage(modelContext), buildUserMessage(modelContext)));
        }
        ChatClient.ChatClientRequestSpec chatClientRequestSpec = client.prompt(prompt);
        try {
            // Check if it is an agent execution log
            Object agentId = SimpleJvmHashCache.getHash(modelContext.getRequestId(), "agentId");
            if (agentId != null) {
                Map<String, Map<String, Object>> modelExecuteInfos = (Map<String, Map<String, Object>>) SimpleJvmHashCache.getHash(modelContext.getRequestId(), "modelExecuteInfos");
                if (modelExecuteInfos == null) {
                    modelExecuteInfos = new HashMap<>();
                    SimpleJvmHashCache.putHash(modelContext.getRequestId(), "modelExecuteInfos", modelExecuteInfos, SimpleJvmHashCache.DEFAULT_EXPIRE_AFTER_SECONDS);
                }
                Map<String, Object> modelExecuteInfo = new HashMap<>();
                modelExecuteInfos.put(traceId, modelExecuteInfo);
                modelExecuteInfo.put("name", modelContext.getModelConfig().getName());
                modelExecuteInfo.put("model", modelContext.getModelConfig().getModel());
                modelExecuteInfo.put("startTime", System.currentTimeMillis());
                modelExecuteInfo.put("userPrompt", modelContext.getModelCallConfig().getUserPrompt());
            }
        } catch (Exception e) {
            log.error("agent log error", e);
            // ignore
        }
        return chatClientRequestSpec;
    }

    private static int tikPromptTokensCount(ModelContext modelContext) {
        StringBuilder sb = new StringBuilder();
        sb.append(modelContext.getModelCallConfig().getSystemPrompt());
        sb.append(modelContext.getModelCallConfig().getUserPrompt());
        if (modelContext.getAgentContext().getVariableParams().get(GlobalVariableEnum.CHAT_CONTEXT.name()) != null) {
            Object value = modelContext.getAgentContext().getVariableParams().get(GlobalVariableEnum.CHAT_CONTEXT.name());
            if (value != null && value instanceof List<?>) {
                List<Message> messages = (List<Message>) value;
                messages.forEach(message -> sb.append(message.getText()));
                return TikTokensUtil.tikTokensCount(sb.toString());
            }
        }
        try {
            // Query intermediate appended context
            List<Message> messages = modelContext.getAgentContext().getContextMessages();
            messages.forEach(message -> sb.append(message.getText()));
        } catch (Exception e) {
            //ignore
        }
        return TikTokensUtil.tikTokensCount(sb.toString());
    }

    private static Object parseText(ModelContext modelContext, String text) {
        log.debug("parse text: {}, type {}", text, modelContext.getModelCallConfig().getOutputType());
        return parseText(modelContext.getModelCallConfig().getOutputType(), text);
    }

    private static Object parseText(OutputTypeEnum outputType, String text) {
        if (outputType == OutputTypeEnum.JSON) {
            //remove <think>xxx</think>
            text = getJSONText(text);
            if (JSON.isValid(text)) {
                try {
                    return JSON.parseObject(text);
                } catch (Exception e) {
                    return new HashMap<>();
                }
            }
            log.warn("Invalid JSON response: {}", text);
            return new HashMap<>();
        }
        return new JSONObject();
    }

    public static String getJSONText(String text) {
        if (text.trim().startsWith("<think>")) {
            text = text.replaceAll("<think>[\\s\\S]*?</think>", "").trim();
        }
        // Check for and remove triple backticks and "json" identifier
        if (text.startsWith("```") && text.endsWith("```")) {
            // Remove the first line if it contains "```json"
            String[] lines = text.split("\n", 2);
            if (lines[0].trim().equalsIgnoreCase("```json")) {
                text = lines.length > 1 ? lines[1] : "";
            } else {
                text = text.substring(3); // Remove leading ```
            }
            // Remove trailing ```
            text = text.substring(0, text.length() - 3);
            // Trim again to remove any potential whitespace
            text = text.trim();
        }
        return text;
    }

    private List<ToolCallback> buildFunctionCallbacks(ModelContext modelContext) {
        if (modelContext.getModelCallConfig().getComponentConfigs() == null) {
            return new ArrayList<>();
        }
        // Remove duplicates
        Set<String> functionNames = new HashSet<>();
        return modelContext.getModelCallConfig().getComponentConfigs().stream().map(componentConfig -> {
            if (componentConfig.getType() == null) {
                return null;
            }
            Object inputSchema = null;
            ToolMetadata toolMetadata = null;
            if (componentConfig.getType() == ComponentTypeEnum.Workflow) {
                WorkflowBindConfigDto workflowBindConfigDto = (WorkflowBindConfigDto) componentConfig.getBindConfig();
                if (workflowBindConfigDto != null && workflowBindConfigDto.getDirectOutput() != null && workflowBindConfigDto.getDirectOutput().equals(YesOrNoEnum.Y.getKey())) {
                    toolMetadata = DefaultToolMetadata.builder().returnDirect(true).build();
                }
            }
            switch (componentConfig.getType()) {
                case Plugin, Workflow, Page -> inputSchema = buildFunctionParams(componentConfig.getInputArgs());
                case Mcp -> {
                    McpDto mcpDto = (McpDto) componentConfig.getTargetConfig();
                    // Get MCP configuration
                    McpToolDto mcpToolDto = mcpDto.getDeployedConfig().getTools().stream().filter(tool -> tool.getName().equals(componentConfig.getFunctionName())).findFirst().orElse(null);
                    if (mcpToolDto != null) {
                        inputSchema = mcpToolDto.getJsonSchema();
                    }
                }
                case Knowledge -> {
                    List<String> required = new ArrayList<>();
                    Map<String, Object> properties = new HashMap<>();
                    properties.put("query", Map.of("type", "string", "description", "Knowledge base search keywords"));
                    required.add("query");
                    inputSchema = Map.of("type", "object", "properties", properties, "required", required);
                }
                case Agent -> {
                    List<String> required = new ArrayList<>();
                    Map<String, Object> properties = new HashMap<>();
                    properties.put("message", Map.of("type", "string", "description", "Message content for deep thinking, such as execution results from other tools"));
                    required.add("message");
                    inputSchema = Map.of("type", "object", "properties", properties, "required", required);
                }
                case Table -> {
                    if (componentConfig.getSubType() == ComponentSubTypeEnum.TABLE_DATA_INSERT) {
                        // Remove system variables
                        componentConfig.getInputArgs().removeIf(Arg::isSystemVariable);
                        inputSchema = buildFunctionParams(componentConfig.getInputArgs());
                    } else {
                        TableDefineVo dorisTableDefinitionVo = (TableDefineVo) componentConfig.getTargetConfig();
                        String tableStruct = ArgConverter.convertArgsToSimpleTableStructure(dorisTableDefinitionVo.getFieldList());
                        List<String> required = new ArrayList<>();
                        Map<String, Object> properties = new HashMap<>();
                        properties.put("sql", Map.of("type", "string", "description", "SQL statement that can be executed directly and meets business requirements, following MySQL syntax. Table structure: " + tableStruct));
                        required.add("sql");
                        inputSchema = Map.of("type", "object", "properties", properties, "required", required);
                        // For subsequent use of default conditions
                        componentConfig.setBindArgs(new ArrayList<>(componentConfig.getInputArgs()));
                        componentConfig.getInputArgs().clear();
                        componentConfig.getInputArgs().add(Arg.builder().name("sql").dataType(DataTypeEnum.String).require(true).build());
                    }
                }
            }
            String name = componentConfig.getType().name().toLowerCase() + componentConfig.getTargetId();
            if (componentConfig.getFunctionName() != null) {
                name = componentConfig.getFunctionName();
            }
            name = ToolInfoExtractor.toolNameExchange(componentConfig.getName(), name);
            if (functionNames.contains(name) || inputSchema == null) {
                return null;
            }
            functionNames.add(name);
            String description = StringUtils.isBlank(componentConfig.getDescription()) ? componentConfig.getName() : componentConfig.getDescription();
            return FunctionToolCallback
                    .builder(name, (input, context) -> componentExecute(modelContext, componentConfig, input))
                    .description(description)
                    .inputSchema((inputSchema instanceof String) ? inputSchema.toString() : ModelOptionsUtils.toJsonString(inputSchema))
                    .inputType(HashMap.class)
                    .toolMetadata(toolMetadata)
                    .toolCallResultConverter((res, returnType) -> (res instanceof String) ? (String) res : JSON.toJSONString(res))
                    .build();
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    private Object componentExecute(ModelContext modelContext, ComponentConfig componentConfig, Object input) {
        if (modelContext.getAgentContext().isInterrupted()) {
            throw new AgentInterruptException("Execution interrupted, since spring-ai does not currently have an interrupt execution interface, the current method will log error messages, which can be ignored");
        }
        if (input == null) {
            input = new HashMap<>();
        }
        ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
        componentExecuteResult.setExecuteId(UUID.randomUUID().toString().replace("-", ""));
        componentExecuteResult.setStartTime(System.currentTimeMillis());
        componentExecuteResult.setId(componentConfig.getTargetId());
        componentExecuteResult.setName(componentConfig.getName());
        componentExecuteResult.setIcon(componentConfig.getIcon());
        componentExecuteResult.setType(componentConfig.getType());
        componentExecuteResult.setInput(JsonSerializeUtil.deepCopy(input));
        ComponentExecutingDto componentExecutingDto = new ComponentExecutingDto();
        componentExecutingDto.setTargetId(componentConfig.getTargetId());
        componentExecutingDto.setName(componentConfig.getName());
        componentExecutingDto.setType(componentConfig.getType());
        componentExecutingDto.setStatus(ExecuteStatusEnum.EXECUTING);
        if (componentConfig.getType() == ComponentTypeEnum.Page) {
            beforeCallPage(componentConfig, input, componentExecutingDto);
            componentExecuteResult.setInput(JsonSerializeUtil.deepCopy(input));
        }
        componentExecutingDto.setResult(componentExecuteResult);
        if (modelContext.getComponentExecutingConsumer() != null) {
            ComponentExecutingDto componentExecutingDto0 = new ComponentExecutingDto();
            BeanUtils.copyProperties(componentExecutingDto, componentExecutingDto0);
            log.info("tool-call component executing: {}", componentExecutingDto);
            modelContext.getComponentExecutingConsumer().accept(componentExecutingDto0);
        }
        try {
            List<String> errorMessages = new ArrayList<>();
            ArgExtractUtil.setArgDefaultValue(modelContext.getAgentContext(), componentConfig.getInputArgs(), input, componentConfig.getParams(), errorMessages);
            if (!errorMessages.isEmpty()) {
                throw new IllegalArgumentException(String.join("\n", errorMessages));
            }
            Object res = null;
            switch (componentConfig.getType()) {
                case Plugin -> res = executePlugin(modelContext, componentConfig, input, componentExecutingDto);
                case Mcp -> res = executeMcp(modelContext, componentConfig, input, componentExecutingDto);
                case Workflow -> res = executeWorkflow(modelContext, componentConfig, input, componentExecutingDto);
                case Knowledge -> res = queryKnowledge(modelContext, componentConfig, input);
                case Table -> res = queryTable(modelContext, componentConfig, input, componentExecutingDto);
                case Agent -> res = callAgent(modelContext, componentConfig, input);
                case Page -> res = callPageResult(componentConfig, input);
            }
            componentExecuteResult.setSuccess(true);
            componentExecuteResult.setEndTime(System.currentTimeMillis());
            componentExecuteResult.setData(res);
            componentExecutingDto.setStatus(ExecuteStatusEnum.FINISHED);
            modelContext.getComponentExecuteResults().add(componentExecuteResult);
            if (modelContext.getComponentExecutingConsumer() != null) {
                log.info("tool-call component executed: {}", componentExecutingDto);
                modelContext.getComponentExecutingConsumer().accept(componentExecutingDto);
            }
            if (modelContext.getAgentContext().isInterrupted()) {
                throw new AgentInterruptException("Execution interrupted, since spring-ai does not currently have an interrupt execution interface, the current method will log error messages, which can be ignored");
            }
            return res;
        } catch (Throwable e) {
            // Internal logic requires interruption
            if (e instanceof AgentInterruptException) {
                modelContext.getAgentContext().setInterrupted(true);
                throw e;
            }
            log.warn("component invoke error", e);
            componentExecuteResult.setSuccess(false);
            componentExecuteResult.setError(e.getMessage());
            componentExecuteResult.setEndTime(System.currentTimeMillis());
            componentExecutingDto.setStatus(ExecuteStatusEnum.FAILED);
            if (modelContext.getComponentExecutingConsumer() != null) {
                modelContext.getComponentExecutingConsumer().accept(componentExecutingDto);
            }
            if (componentConfig.getExceptionOut() != null && componentConfig.getExceptionOut().equals(YesOrNoEnum.Y.getKey())) {
                throw e;
            } else if (StringUtils.isNotBlank(componentConfig.getFallbackMsg())) {
                return componentConfig.getFallbackMsg();
            }
            return "tool execution failed:" + e.getMessage();
        }
    }

    private Object callPageResult(ComponentConfig componentConfig, Object input) {
        if ("browser_navigate_page".equals(componentConfig.getFunctionName())) {
            Object requestId = ((Map<String, Object>) input).get("request_id");
            if (requestId != null) {
                Object dataType = ((Map<String, Object>) input).get("data_type");
                return modelPageRequest.getPageRequestResult(requestId.toString(), dataType == null ? "html" : dataType.toString());
            }
        }
        return "Page opened";
    }

    private static String beforeCallPage(ComponentConfig componentConfig, Object input, ComponentExecutingDto componentExecutingDto) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        Object bindConfig = componentConfig.getBindConfig();
        if (bindConfig instanceof Map<?, ?>) {
            Map<String, PageArgConfig> pageArgConfigMap = (Map<String, PageArgConfig>) bindConfig;
            if (input instanceof Map<?, ?>) {
                Object uri = ((Map<?, ?>) input).get("uri");
                if (uri != null) {
                    PageArgConfig pageArgConfig = pageArgConfigMap.get(uri);
                    if (pageArgConfig != null) {
                        componentExecutingDto.setPageArgConfig(pageArgConfig);
                        ((Map<String, Object>) input).put("uri_type", "Page");
                    } else if (uri.toString().startsWith("http")) {
                        ((Map<String, Object>) input).put("uri_type", "Link");
                    }
                }
                ((Map<String, Object>) input).put("request_id", requestId);
                ((Map<String, Object>) input).put("method", componentConfig.getFunctionName());
            }
        }
        return requestId;
    }

    private Object executeMcp(ModelContext modelContext, ComponentConfig componentConfig, Object input, ComponentExecutingDto componentExecutingDto) {
        McpBindConfigDto mcpBindConfigDto = (McpBindConfigDto) componentConfig.getBindConfig();
        if (componentConfig.getBindConfig() != null) {
            componentExecutingDto.setCardBindConfig(mcpBindConfigDto.getCardBindConfig());
        }
        McpContext mcpContext = McpContext.builder()
                .requestId(modelContext.getAgentContext().getRequestId())
                .conversationId(modelContext.getAgentContext().getConversationId())
                .user(modelContext.getAgentContext().getUser())
                .mcpDto((McpDto) componentConfig.getTargetConfig())
                .params((Map<String, Object>) input)
                .name(componentConfig.getFunctionName())
                .build();
        return mcpExecutor.execute(mcpContext).blockLast();
    }

    private Object queryTable(ModelContext modelContext, ComponentConfig componentConfig, Object input, ComponentExecutingDto componentExecutingDto) {
        if (componentConfig.getBindConfig() != null && componentConfig.getBindConfig() instanceof TableBindConfigDto) {
            componentExecutingDto.setCardBindConfig(((TableBindConfigDto) componentConfig.getBindConfig()).getCardBindConfig());
        }
        Map<String, Object> args = new HashMap<>();
        Map<String, Object> extArgs = new HashMap<>();
        args.putAll((Map<String, Object>) input);
        args.put("uid", modelContext.getAgentContext().getUid());
        if (modelContext.getAgentContext().getUser() != null) {
            args.put("nick_name", modelContext.getAgentContext().getUser().getNickName());
            args.put("user_name", modelContext.getAgentContext().getUser().getUserName());
        }
        if (modelContext.getAgentContext().getAgentConfig() != null) {
            args.put("agent_id", modelContext.getAgentContext().getAgentConfig().getId());
            args.put("agent_name", modelContext.getAgentContext().getAgentConfig().getName());
        }
        TableExecutorContext tableExecutorContext = new TableExecutorContext();
        tableExecutorContext.setTableId(componentConfig.getTargetId());
        tableExecutorContext.setAgentContext(modelContext.getAgentContext());
        if (componentConfig.getSubType() == ComponentSubTypeEnum.TABLE_DATA_INSERT) {
            TableDefineVo dorisTableDefinitionVo = (TableDefineVo) componentConfig.getTargetConfig();
            String sql = TableExecutor.generateInsertSql(new ArrayList<>(args.keySet()), ArgConverter.convertTableFieldsToArgs(dorisTableDefinitionVo.getFieldList()));
            tableExecutorContext.setSql(sql);
            tableExecutorContext.setArgs(args);
            tableExecutorContext.setExtArgs(new HashMap<>());
        } else {
            // SQL execution
            Object sql = ((Map<?, ?>) input).get("sql");
            if (sql == null || StringUtils.isBlank(sql.toString())) {
                return "require argument 'sql' is null";
            }
            // Keep default values for system variable reference scenarios, remove the rest
            List<Arg> collect = componentConfig.getBindArgs().stream().filter(arg -> arg.getBindValueType() == Arg.BindValueType.Reference && arg.isSystemVariable()).collect(Collectors.toList());
            ArgExtractUtil.setArgDefaultValue(modelContext.getAgentContext(), collect, extArgs, null, new ArrayList<>());
            tableExecutorContext.setSql(sql.toString());
            tableExecutorContext.setArgs(new HashMap<>());
            tableExecutorContext.setExtArgs(extArgs);
        }
        return tableExecutor.execute(tableExecutorContext).block();
    }

    private Object queryKnowledge(ModelContext modelContext, ComponentConfig componentConfig, Object input) {
        KnowledgeSearchConfigDto knowledgeSearchConfigDto = (KnowledgeSearchConfigDto) componentConfig.getTargetConfig();
        SearchContext searchContext = new SearchContext();
        searchContext.setAgentContext(modelContext.getAgentContext());
        searchContext.setQuery((String) ((Map<String, Object>) input).get("query"));
        searchContext.setSearchStrategy(knowledgeSearchConfigDto.getSearchStrategy());
        searchContext.setMatchingDegree(knowledgeSearchConfigDto.getMatchingDegree());
        searchContext.setMaxRecallCount(knowledgeSearchConfigDto.getMaxRecallCount());
        searchContext.setKnowledgeBaseIds(List.of(componentConfig.getTargetId()));
        searchContext.setRequestId(modelContext.getAgentContext().getRequestId());
        List<KnowledgeQaVo> list = knowledgeBaseSearcher.search(searchContext).timeout(Duration.ofSeconds(60)).block();
        if (CollectionUtils.isNotEmpty(list)) {
            return list;
        }
        if (knowledgeSearchConfigDto.getNoneRecallReplyType() != null && knowledgeSearchConfigDto.getNoneRecallReplyType() == KnowledgeBaseBindConfigDto.NoneRecallReplyTypeEnum.CUSTOM &&
                StringUtils.isNotBlank(knowledgeSearchConfigDto.getNoneRecallReply())) {
            return knowledgeSearchConfigDto.getNoneRecallReply();
        }
        return "No relevant information found";
    }

    private Object executeWorkflow(ModelContext modelContext, ComponentConfig componentConfig, Object input, ComponentExecutingDto componentExecutingDto) {
        if (componentConfig.getBindConfig() != null && componentConfig.getBindConfig() instanceof WorkflowBindConfigDto) {
            componentExecutingDto.setCardBindConfig(((WorkflowBindConfigDto) componentConfig.getBindConfig()).getCardBindConfig());
        }
        WorkflowConfigDto workflowConfigDto = (WorkflowConfigDto) componentConfig.getTargetConfig();
        if (workflowConfigDto == null) {
            return "tool execution failed";
        }
        WorkflowContext workflowContext1 = new WorkflowContext();
        workflowContext1.setOriginalWorkflowId(workflowConfigDto.getId());
        if (componentConfig.getOriginalTargetId() != null) {
            workflowContext1.setOriginalWorkflowId(componentConfig.getOriginalTargetId());
        }
        workflowContext1.setAgentContext(modelContext.getAgentContext());
        workflowContext1.setRequestId(modelContext.getRequestId());
        workflowContext1.setWorkflowConfig(workflowConfigDto);
        workflowContext1.setNodeExecutingConsumer(modelContext.getNodeExecutingConsumer());
        workflowContext1.setParams((Map<String, Object>) input);
        workflowContext1.setAsyncExecute(componentConfig.isAsyncExecute());
        workflowContext1.setAsyncReplyContent(componentConfig.getAsyncReplyContent());
        Object res = workflowExecutor.execute(workflowContext1).block();
        EndNodeConfigDto endNodeConfigDto = (EndNodeConfigDto) workflowConfigDto.getEndNode().getNodeConfig();
        if (endNodeConfigDto.getReturnType() == EndNodeConfigDto.ReturnType.TEXT && StringUtils.isNotBlank(workflowContext1.getEndNodeContent())) {
            res = workflowContext1.getEndNodeContent();
        }
        if (workflowContext1.getAgentContext().getAgentConfig() != null && workflowContext1.getWorkflowConfig().getSpaceId().equals(workflowContext1.getAgentContext().getAgentConfig().getSpaceId())) {
            // Record internal execution logs only when the workflow and agent belong to the same space
            componentExecutingDto.getResult().setInnerExecuteInfo(workflowContext1.getNodeExecuteResultMap().values());
        }
        return res;
    }

    private Object executePlugin(ModelContext modelContext, ComponentConfig componentConfig, Object input, ComponentExecutingDto componentExecutingDto) {
        if (componentConfig.getBindConfig() != null && componentConfig.getBindConfig() instanceof PluginBindConfigDto) {
            componentExecutingDto.setCardBindConfig(((PluginBindConfigDto) componentConfig.getBindConfig()).getCardBindConfig());
        }
        PluginDto pluginDto = (PluginDto) componentConfig.getTargetConfig();
        PluginContext pluginContext = PluginContext.builder()
                .requestId(modelContext.getRequestId())
                .agentContext(modelContext.getAgentContext())
                .pluginConfig((PluginConfigDto) pluginDto.getConfig())
                .pluginDto(pluginDto)
                .params((Map<String, Object>) input)
                .userId(modelContext.getAgentContext().getUserId())
                .asyncExecute(componentConfig.isAsyncExecute())
                .asyncReplyContent(componentConfig.getAsyncReplyContent())
                .build();
        PluginExecuteResultDto pluginExecuteResultDto = pluginExecutor.execute(pluginContext).timeout(Duration.ofSeconds(60)).block();
        if (pluginExecuteResultDto != null) {
            componentExecutingDto.getResult().setSuccess(pluginExecuteResultDto.isSuccess());
            componentExecutingDto.getResult().setError(pluginExecuteResultDto.getError());
            componentExecutingDto.getResult().setData(pluginExecuteResultDto.getResult());
            componentExecutingDto.getResult().setInnerExecuteInfo(pluginExecuteResultDto.getLogs());
            componentExecutingDto.getResult().setEndTime(System.currentTimeMillis());
            return pluginExecuteResultDto.getResult();
        }
        return null;
    }

    private Object callAgent(ModelContext modelContext, ComponentConfig componentConfig, Object input) {
        Long tenantId = modelContext.getAgentContext().getAgentConfig().getTenantId();
        AgentContext agentContext;
        try {
            RequestContext.setThreadTenantId(tenantId);
            agentContext = modelContext.getAgentContext().getAgentContextFunction().apply(componentConfig.getTargetId());
            if (agentContext == null) {
                return "execution failed";
            }
            Object message = ((Map<String, Object>) input).get("user_message");
            if (message == null) {
                message = modelContext.getAgentContext().getMessage();
            }
            // Add agent: prefix to filter duplicate message storage, message storage implementation is in ConversationApplicationServiceImpl
            agentContext.setConversationId("agent:" + modelContext.getAgentContext().getConversationId());
            agentContext.setRequestId(modelContext.getAgentContext().getRequestId());
            agentContext.setUserId(modelContext.getAgentContext().getUserId());
            agentContext.setUser(modelContext.getAgentContext().getUser());
            agentContext.setUid(modelContext.getAgentContext().getUid());
            agentContext.setUserName(modelContext.getAgentContext().getUserName());
            agentContext.setHeaders(modelContext.getAgentContext().getHeaders());
            agentContext.setOutputConsumer(modelContext.getAgentContext().getOutputConsumer());
            agentContext.setVariableParams(modelContext.getAgentContext().getVariableParams());
            agentContext.setAgentExecuteResult(modelContext.getAgentContext().getAgentExecuteResult());
            agentContext.setAttachments(modelContext.getAgentContext().getAttachments());
            agentContext.setDebug(modelContext.getAgentContext().isDebug());
            agentContext.setMessage(message.toString());
            agentContext.setIfInterrupted(modelContext.getAgentContext().getIfInterrupted());
            agentExecutor.execute(agentContext).blockLast();
            return agentContext.getAgentExecuteResult().getOutputText();
        } finally {
            RequestContext.remove();
        }
    }

    private static Map<String, Object> buildFunctionParams(List<Arg> inputArgs) {
        return ArgConverter.convertArgsToJsonSchema(inputArgs);
    }

    private static SystemMessage buildSystemMessage(ModelContext modelContext) {
        if (StringUtils.isBlank(modelContext.getModelCallConfig().getSystemPrompt())) {
            return new SystemMessage(Prompts.TIME_PROMPT.replace("${time}", new Date().toString()));
        }

        return new SystemMessage(Prompts.TIME_PROMPT.replace("${time}", new Date().toString()) + modelContext.getModelCallConfig().getSystemPrompt());
    }

    private static UserMessage buildUserMessage(ModelContext modelContext) {
        List<Media> mediaList = new ArrayList<>();
        if (modelContext.getModelConfig().getType() == ModelTypeEnum.Multi && CollectionUtils.isNotEmpty(modelContext.getAgentContext().getAttachments())) {
            modelContext.getAgentContext().getAttachments().forEach(attachment -> {
                try {
                    String[] split = attachment.getMimeType().split("/");
                    if (split.length != 2 || !split[0].equals("image")) {
                        return;
                    }
                    Media media = Media.builder()
                            .mimeType(new MimeType(split[0], split[1]))
                            .data(UrlFile.urlToBytes(attachment.getFileUrl()))
                            .id(attachment.getFileKey())
                            .name(attachment.getFileName())
                            .build();
                    mediaList.add(media);
                } catch (Exception e) {
                    log.warn("Invalid URL: {}", attachment.getFileUrl(), e);
                }
            });
        }
        if (modelContext.getModelConfig().getType() == ModelTypeEnum.Audio && CollectionUtils.isNotEmpty(modelContext.getAgentContext().getAttachments())) {
            modelContext.getAgentContext().getAttachments().forEach(attachment -> {
                try {
                    String[] split = attachment.getMimeType().split("/");
                    if (split.length != 2 || !split[0].equals("audio")) {
                        return;
                    }
                    Media media = Media.builder()
                            .mimeType(new MimeType(split[0], split[1]))
                            .data(UrlFile.urlToBytes(attachment.getFileUrl()))
                            .id(attachment.getFileKey())
                            .name(attachment.getFileName())
                            .build();
                    mediaList.add(media);
                } catch (Exception e) {
                    log.warn("Invalid URL: {}", attachment.getFileUrl(), e);
                }
            });
        }
        if (modelContext.getModelConfig().getType() == ModelTypeEnum.Video && CollectionUtils.isNotEmpty(modelContext.getAgentContext().getAttachments())) {
            modelContext.getAgentContext().getAttachments().forEach(attachment -> {
                try {
                    String[] split = attachment.getMimeType().split("/");
                    if (split.length != 2 || !split[0].equals("video")) {
                        return;
                    }
                    Media media = Media.builder()
                            .mimeType(new MimeType(split[0], split[1]))
                            .data(UrlFile.urlToBytes(attachment.getFileUrl()))
                            .id(attachment.getFileKey())
                            .name(attachment.getFileName())
                            .build();
                    mediaList.add(media);
                } catch (Exception e) {
                    log.warn("Invalid URL: {}", attachment.getFileUrl(), e);
                }
            });
        }
        String userPrompt = modelContext.getModelCallConfig().getUserPrompt();
        userPrompt = userPrompt == null ? "" : userPrompt;
        if (modelContext.getModelCallConfig().getOutputType() == OutputTypeEnum.JSON) {
            Map<String, Object> map = buildFunctionParams(convertToArgList(modelContext.getModelCallConfig().getOutputArgs()));
            map.put("$schema", "https://json-schema.org/draft/2020-12/schema");
            String jsonSchema = JSON.toJSONString(map);
            modelContext.getModelCallConfig().setJsonSchema(jsonSchema);
            return new UserMessage(userPrompt + Prompts.JSON_FORMAT_PROMPT.replace("${schema}", jsonSchema), mediaList);
        }

        if (modelContext.getModelCallConfig().getOutputType() == OutputTypeEnum.Markdown) {
            return new UserMessage(userPrompt, mediaList);
        }

        if (modelContext.getModelCallConfig().getOutputType() == OutputTypeEnum.Text) {
            return new UserMessage(userPrompt + Prompts.TEXT_FORMAT_PROMPT, mediaList);
        }

        return new UserMessage(userPrompt, mediaList);
    }

    private static List<Arg> convertToArgList(List<Arg> outputArgs) {
        if (outputArgs == null) {
            return new ArrayList<>();
        }
        return outputArgs;
    }

    public static String resetToolBlock(List<ComponentConfig> componentConfigs0, String prompt) {
        if (CollectionUtils.isEmpty(componentConfigs0)) {
            return prompt;
        }
        List<ComponentConfig> componentConfigs = new ArrayList<>(componentConfigs0);
        componentConfigs.removeIf(c -> c.getType() == null || c.getTargetId() == null || (c.getType() != ComponentTypeEnum.Plugin && c.getType() != ComponentTypeEnum.Workflow && c.getType() != ComponentTypeEnum.Mcp));
        if (componentConfigs.isEmpty() || StringUtils.isBlank(prompt)) {
            return prompt;
        }
        // Convert id+type to key map
        Map<String, ComponentConfig> componentConfigMap = componentConfigs.stream().collect(Collectors.toMap((c) -> {
            if (c.getType() == ComponentTypeEnum.Mcp) {
                return c.getType().name().toLowerCase() + "_" + c.getTargetId() + "_" + c.getFunctionName();
            }
            return c.getType().name().toLowerCase() + "_" + c.getTargetId() + "_" + c.getName();
        }, c -> c, (c1, c2) -> c1));
        // Regular expression to match ToolBlock tags
        String regex = "\\{#ToolBlock\\s+id=\"(\\d+)\"\\s+type=\"([^\"]+)\"[^#]*#\\}([^#]*)\\{#/ToolBlock#\\}";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(prompt);
        List<ToolBlockInfo> results = new ArrayList<>();
        while (matcher.find()) {
            String fullMatch = matcher.group(0);      // Full match content
            String id = matcher.group(1);             // id value
            String type = matcher.group(2);           // type value
            String content = matcher.group(3);        // Content within tags
            if ("Skill".equals(type) || "SubAgent".equals(type)) {
                results.add(new ToolBlockInfo(fullMatch, id, type, content, content));
                continue;
            }
            ComponentConfig componentConfig = componentConfigMap.get(type.toLowerCase() + "_" + id + "_" + content);
            if (componentConfig == null) {
                continue;
            }
            String name = type.toLowerCase() + id;
            if (componentConfig.getFunctionName() != null) {
                name = componentConfig.getFunctionName();
            }
            name = ToolInfoExtractor.toolNameExchange(componentConfig.getName(), name);
            results.add(new ToolBlockInfo(fullMatch, id, type, content, name));
        }
        for (ToolBlockInfo result : results) {
            prompt = prompt.replace(result.getFullMatch(), "`" + result.getFunctionName() + "`");
        }
        return prompt;
    }
}
