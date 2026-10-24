/*
 * Copyright 2023-present the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.xspaceagi.agent.core.infra.component.model;

import io.micrometer.observation.ObservationRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.execution.DefaultToolExecutionExceptionProcessor;
import org.springframework.ai.tool.execution.ToolExecutionException;
import org.springframework.ai.tool.execution.ToolExecutionExceptionProcessor;
import org.springframework.ai.tool.observation.DefaultToolCallingObservationConvention;
import org.springframework.ai.tool.observation.ToolCallingObservationContext;
import org.springframework.ai.tool.observation.ToolCallingObservationConvention;
import org.springframework.ai.tool.observation.ToolCallingObservationDocumentation;
import org.springframework.ai.tool.resolution.DelegatingToolCallbackResolver;
import org.springframework.ai.tool.resolution.ToolCallbackResolver;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Default implementation of {@link ToolCallingManager}.
 *
 * @author Thomas Vitale
 * @since 1.0.0
 */
public final class DefaultToolCallingManager implements ToolCallingManager {

    private static final Logger logger = LoggerFactory.getLogger(DefaultToolCallingManager.class);

    // @formatter:off

	private static final ObservationRegistry DEFAULT_OBSERVATION_REGISTRY
			= ObservationRegistry.NOOP;

	private static final ToolCallingObservationConvention DEFAULT_OBSERVATION_CONVENTION
			= new DefaultToolCallingObservationConvention();

	private static final ToolCallbackResolver DEFAULT_TOOL_CALLBACK_RESOLVER
			= new DelegatingToolCallbackResolver(List.of());

	private static final ToolExecutionExceptionProcessor DEFAULT_TOOL_EXECUTION_EXCEPTION_PROCESSOR
			= DefaultToolExecutionExceptionProcessor.builder().build();

	private static final String POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING
			= "LLM may have adapted the tool name '{}', especially if the name was truncated due to length limits. If this is the case, you can customize the prefixing and processing logic using McpToolNamePrefixGenerator";

	/**
	 * 熔断阈值（两段式）：相同工具 + 相同参数的调用<b>连续</b>达到该次数时软拦截
	 * （不执行，把停止指令作为普通 tool_result 喂回模型）；超过该次数——即模型收到指令后
	 * 仍发起相同调用——则硬熔断（returnDirect 强制终止循环）。
	 * 注意是连续而非累计：中间隔一次不同参数的调用即重新计数；
	 * 出现非工具调用消息（新一轮用户输入、纯文本回复等）同样清零重新计数，
	 * 避免追加的历史上下文或之前轮次的工具调用被误算进当前链路。
	 */
	private static final int MAX_IDENTICAL_TOOL_CALLS = 3;

	/**
	 * 熔断阈值（两段式）：单条对话链路内 tool_use 总数达到该值时软拦截，超过则硬熔断。
	 * 防止模型不断更换参数无意义地刷工具调用。
	 */
	private static final int MAX_TOOL_CALLS_PER_CHAIN = 100;

	// @formatter:on

    private final ObservationRegistry observationRegistry;

    private final ToolCallbackResolver toolCallbackResolver;

    private final ToolExecutionExceptionProcessor toolExecutionExceptionProcessor;

    private ToolCallingObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

    public DefaultToolCallingManager(ObservationRegistry observationRegistry, ToolCallbackResolver toolCallbackResolver,
                                     ToolExecutionExceptionProcessor toolExecutionExceptionProcessor) {
        Assert.notNull(observationRegistry, "observationRegistry cannot be null");
        Assert.notNull(toolCallbackResolver, "toolCallbackResolver cannot be null");
        Assert.notNull(toolExecutionExceptionProcessor, "toolCallExceptionConverter cannot be null");

        this.observationRegistry = observationRegistry;
        this.toolCallbackResolver = toolCallbackResolver;
        this.toolExecutionExceptionProcessor = toolExecutionExceptionProcessor;
    }

    @Override
    public List<ToolDefinition> resolveToolDefinitions(ToolCallingChatOptions chatOptions) {
        Assert.notNull(chatOptions, "chatOptions cannot be null");

        List<ToolCallback> toolCallbacks = new ArrayList<>(chatOptions.getToolCallbacks());
        for (String toolName : chatOptions.getToolNames()) {
            // Skip the tool if it is already present in the request toolCallbacks.
            // That might happen if a tool is defined in the options
            // both as a ToolCallback and as a tool name.
            if (chatOptions.getToolCallbacks()
                    .stream()
                    .anyMatch(tool -> tool.getToolDefinition().name().equals(toolName))) {
                continue;
            }
            ToolCallback toolCallback = this.toolCallbackResolver.resolve(toolName);
            if (toolCallback == null) {
                logger.warn(POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING, toolName);
                throw new IllegalStateException("No ToolCallback found for tool name: " + toolName);
            }
            toolCallbacks.add(toolCallback);
        }

        return toolCallbacks.stream().map(ToolCallback::getToolDefinition).toList();
    }

    @Override
    public ToolExecutionResult executeToolCalls(Prompt prompt, ChatResponse chatResponse) {
        Assert.notNull(prompt, "prompt cannot be null");
        Assert.notNull(chatResponse, "chatResponse cannot be null");

        Optional<Generation> toolCallGeneration = chatResponse.getResults()
                .stream()
                .filter(g -> !CollectionUtils.isEmpty(g.getOutput().getToolCalls()))
                .findFirst();

        if (toolCallGeneration.isEmpty()) {
            throw new IllegalStateException("No tool call requested by the chat model");
        }

        AssistantMessage assistantMessage = toolCallGeneration.get().getOutput();

        ToolContext toolContext = buildToolContext(prompt, assistantMessage);

        InternalToolExecutionResult internalToolExecutionResult = executeToolCall(prompt, assistantMessage,
                toolContext);

        List<Message> conversationHistory = buildConversationHistoryAfterToolExecution(prompt.getInstructions(),
                assistantMessage, internalToolExecutionResult.toolResponseMessage());

        return ToolExecutionResult.builder()
                .conversationHistory(conversationHistory)
                .returnDirect(internalToolExecutionResult.returnDirect())
                .build();
    }

    private static ToolContext buildToolContext(Prompt prompt, AssistantMessage assistantMessage) {
        Map<String, Object> toolContextMap = Map.of();

        if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions
                && !CollectionUtils.isEmpty(toolCallingChatOptions.getToolContext())) {
            toolContextMap = new HashMap<>(toolCallingChatOptions.getToolContext());

            toolContextMap.put(ToolContext.TOOL_CALL_HISTORY,
                    buildConversationHistoryBeforeToolExecution(prompt, assistantMessage));
        }

        return new ToolContext(toolContextMap);
    }

    private static List<Message> buildConversationHistoryBeforeToolExecution(Prompt prompt,
                                                                             AssistantMessage assistantMessage) {
        List<Message> messageHistory = new ArrayList<>(prompt.copy().getInstructions());
        messageHistory.add(AssistantMessage.builder()
                .content(assistantMessage.getText())
                .properties(assistantMessage.getMetadata())
                .toolCalls(assistantMessage.getToolCalls())
                .build());
        return messageHistory;
    }

    /**
     * Execute the tool call and return the response message.
     */
    private InternalToolExecutionResult executeToolCall(Prompt prompt, AssistantMessage assistantMessage,
                                                        ToolContext toolContext) {
        List<ToolCallback> toolCallbacks = List.of();
        if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions) {
            toolCallbacks = toolCallingChatOptions.getToolCallbacks();
        }

        List<ToolResponseMessage.ToolResponse> toolResponses = new ArrayList<>();

        Boolean returnDirect = null;
        AtomicBoolean noneReturnDirect = new AtomicBoolean(false);

        // 熔断前置：把历史按序展开为 tool_use 指纹序列，遇到"非工具调用"消息即清零重新计数。
        // prompt.getInstructions() 携带到目前为止的完整 assistant(tool_use) + user(tool_result) 链
        // （模型内部工具循环每轮递归都会把 conversationHistory 完整带回），因此 manager 无需持有任何状态。
        // 清零规则：一旦出现非工具调用消息（新一轮用户输入、纯文本助手回复、系统消息等），
        // 说明上一条工具链已经结束，指纹序列与总量计数全部清零——只统计当前这条工具链，
        // 追加的历史上下文或之前轮次里的工具调用不会被计入。
        // ToolResponseMessage（工具结果）属于工具链的一部分，不清零。
        // 软拦截轮次本身也以 assistant(tool_use) + user(tool_result) 进入历史，下一轮连续计数自然递增，
        // 模型"不听话"继续重复调用时自动升级为硬熔断。
        List<String> historyFingerprints = new ArrayList<>();
        int historyToolCallTotal = 0;
        for (Message message : prompt.getInstructions()) {
            if (message instanceof AssistantMessage am && am.getToolCalls() != null && !am.getToolCalls().isEmpty()) {
                for (AssistantMessage.ToolCall tc : am.getToolCalls()) {
                    historyFingerprints.add(fingerprintKey(tc.name(), tc.arguments()));
                    historyToolCallTotal++;
                }
            } else if (!(message instanceof ToolResponseMessage)) {
                // 非工具调用消息：当前工具链结束，清零重新计数
                historyFingerprints.clear();
                historyToolCallTotal = 0;
            }
        }

        // 本批每个调用的"相同工具 + 相同参数"连续次数：该指纹在历史末尾的连续出现次数
        // + 本批内前面调用中不被打断的连续出现次数；中间隔一次不同参数的调用即重新计数
        // （历史侧的清零已在前置遍历中按非工具调用消息完成），
        // 避免交叉了其他工具调用的合法重复调用被误拦。
        List<AssistantMessage.ToolCall> toolCalls = assistantMessage.getToolCalls();
        List<Integer> consecutiveTimes = new ArrayList<>(toolCalls.size());
        List<String> seenFingerprints = new ArrayList<>(historyFingerprints);
        for (AssistantMessage.ToolCall toolCall : toolCalls) {
            String fp = fingerprintKey(toolCall.name(), toolCall.arguments());
            int streak = 1;
            for (int i = seenFingerprints.size() - 1; i >= 0 && seenFingerprints.get(i).equals(fp); i--) {
                streak++;
            }
            consecutiveTimes.add(streak);
            seenFingerprints.add(fp);
        }

        // 两段式熔断——第二段（硬熔断）：模型收到过停止指令后仍重复调用，或总量超限后仍继续。
        // 整批终止，强制 returnDirect=true 硬断模型内部递归（不再依赖模型"自觉"停止）。
        String hardBreakReason = null;
        for (int i = 0; i < toolCalls.size(); i++) {
            if (consecutiveTimes.get(i) > MAX_IDENTICAL_TOOL_CALLS) {
                hardBreakReason = String.format("工具 `%s` 以完全相同的参数已连续调用 %d 次，且在收到停止指令后仍继续重复调用",
                        toolCalls.get(i).name(), consecutiveTimes.get(i) - 1);
                break;
            }
        }
        if (hardBreakReason == null && historyToolCallTotal + 1 > MAX_TOOL_CALLS_PER_CHAIN) {
            hardBreakReason = String.format("本次对话累计工具调用已达 %d 次上限且仍继续调用", historyToolCallTotal);
        }
        if (hardBreakReason != null) {
            logger.warn("Tool call circuit breaker HARD tripped: {}", hardBreakReason);
            String stopMessage = buildCircuitBreakMessage(hardBreakReason);
            for (AssistantMessage.ToolCall toolCall : toolCalls) {
                toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolCall.name(), stopMessage));
            }
            return new InternalToolExecutionResult(ToolResponseMessage.builder().responses(toolResponses).build(), true);
        }

        // 两段式熔断——第一段（软拦截）标记：本批调用恰好触达总量上限（尚未超过），
        // 命中后本批所有调用不执行，把停止指令喂回模型，给其一次自行组织回答的机会。
        boolean softBlockByCap = historyToolCallTotal + 1 == MAX_TOOL_CALLS_PER_CHAIN;

        for (int i = 0; i < toolCalls.size(); i++) {
            AssistantMessage.ToolCall toolCall = toolCalls.get(i);

            logger.debug("Executing tool call: {}", toolCall.name());

            String toolName = toolCall.name();
            String toolInputArguments = toolCall.arguments();

            // 两段式熔断——第一段（软拦截）：首次命中相同调用阈值或触达总量上限时不执行，
            // 把停止指令作为普通 tool_result 喂回（returnDirect 保持 false），给模型一次组织最终回答的机会。
            if (softBlockByCap) {
                logger.warn("Tool call soft-blocked by total cap (would be call #{}): {}({})",
                        historyToolCallTotal + 1, toolName, toolInputArguments);
                toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName,
                        buildCapSoftBlockMessage(historyToolCallTotal + 1)));
                continue;
            }
            int identicalTimes = consecutiveTimes.get(i);
            if (identicalTimes == MAX_IDENTICAL_TOOL_CALLS) {
                logger.warn("Tool call soft-blocked (identical call #{} in a row): {}({})", identicalTimes, toolName, toolInputArguments);
                toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName,
                        buildIdenticalSoftBlockMessage(toolName, identicalTimes - 1)));
                continue;
            }

            // Handle the possible null parameter situation in streaming mode.
            final String finalToolInputArguments;
            if (!StringUtils.hasText(toolInputArguments)) {
                logger.warn("Tool call arguments are null or empty for tool: {}. Using empty JSON object as default.",
                        toolName);
                finalToolInputArguments = "{}";
            } else {
                finalToolInputArguments = toolInputArguments;
            }

            ToolCallback toolCallback = toolCallbacks.stream()
                    .filter(tool -> toolName.equals(tool.getToolDefinition().name()))
                    .findFirst()
                    .orElseGet(() -> this.toolCallbackResolver.resolve(toolName));

            if (toolCallback == null) {
                logger.warn(POSSIBLE_LLM_TOOL_NAME_CHANGE_WARNING, toolName);
                toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName,
                        "Tool `" + toolName + "` does not exist in the tool list. Please check carefully, tool names that appeared in the context may no longer exist."));
                continue;
            }

            if (returnDirect == null) {
                returnDirect = toolCallback.getToolMetadata().returnDirect();
            } else {
                returnDirect = returnDirect && toolCallback.getToolMetadata().returnDirect();
            }

            ToolCallingObservationContext observationContext = ToolCallingObservationContext.builder()
                    .toolDefinition(toolCallback.getToolDefinition())
                    .toolMetadata(toolCallback.getToolMetadata())
                    .toolCallArguments(finalToolInputArguments)
                    .build();

            String toolCallResult = ToolCallingObservationDocumentation.TOOL_CALL
                    .observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
                            this.observationRegistry)
                    .observe(() -> {
                        String toolResult;
                        try {
                            Map<String, Object> map = new HashMap<>();
                            if (toolContext.getContext() != null) {
                                map.putAll(toolContext.getContext());
                                map.put("toolCall", toolCall);
                            }
                            ToolContext context = new ToolContext(map);
                            toolResult = toolCallback.call(finalToolInputArguments, context);
                            if (toolResult.startsWith("Error parsing tool input:")) {
                                noneReturnDirect.set(true);
                            }
                        } catch (ToolExecutionException ex) {
                            toolResult = this.toolExecutionExceptionProcessor.process(ex);
                        }
                        observationContext.setToolCallResult(toolResult);
                        return toolResult;
                    });

            // 空结果占位兜底：部分模型网关要求所有消息 content 非空，空 tool 结果会在
            // 模型内部循环下一轮请求时报 "messages.N: all messages must have non-empty content"
            toolResponses.add(new ToolResponseMessage.ToolResponse(toolCall.id(), toolName,
                    StringUtils.hasText(toolCallResult) ? toolCallResult : "(empty)"));
        }

        return new InternalToolExecutionResult(ToolResponseMessage.builder().responses(toolResponses).build(),
                Boolean.TRUE.equals(returnDirect) && !noneReturnDirect.get());
    }

    private List<Message> buildConversationHistoryAfterToolExecution(List<Message> previousMessages,
                                                                     AssistantMessage assistantMessage, ToolResponseMessage toolResponseMessage) {
        List<Message> messages = new ArrayList<>(previousMessages);
        messages.add(assistantMessage);
        messages.add(toolResponseMessage);
        return messages;
    }

    public void setObservationConvention(ToolCallingObservationConvention observationConvention) {
        this.observationConvention = observationConvention;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * 工具调用指纹：相同工具名 + 相同参数视为同一调用。
     */
    private static String fingerprintKey(String name, String arguments) {
        return name + "|" + (arguments == null ? "" : arguments.trim());
    }

    /**
     * 构造硬熔断结果文案。returnDirect 时该文案会直接作为最终回复透出，因此需自解释、对用户可读。
     */
    private static String buildCircuitBreakMessage(String reason) {
        return "[系统熔断] " + reason + "，已强制停止工具执行。请勿再次调用工具，直接基于上下文已有信息回答；若信息不足，请如实告知并请用户补充。"
                + " [CIRCUIT_BREAK] " + reason + ". Tool execution stopped. Do NOT call the tool again — answer from existing context, or ask the user for more information.";
    }

    /**
     * 构造软拦截文案（相同调用达阈值）：作为普通 tool_result 喂回模型，指令其停止调用并组织最终回答。
     */
    private static String buildIdenticalSoftBlockMessage(String toolName, int executedTimes) {
        return String.format("[系统拦截] 工具 `%s` 以完全相同的参数已连续成功执行 %d 次，结果不会改变，本次调用未执行。"
                        + "请勿再次调用任何工具，请直接基于上下文已有信息组织对用户的最终回答；若信息不足，请如实说明并请求用户补充。"
                        + " [BLOCKED] `%s` has already been executed %d consecutive times with identical arguments; the result will not change, so this call was skipped. "
                        + "Do NOT call any tool again — produce your final answer to the user from the existing context now.",
                toolName, executedTimes, toolName, executedTimes);
    }

    /**
     * 构造软拦截文案（总量触顶）：同 {@link #buildIdenticalSoftBlockMessage}，喂回模型一次组织回答的机会。
     */
    private static String buildCapSoftBlockMessage(int totalCalls) {
        return String.format("[系统拦截] 本次对话工具调用累计已达 %d 次上限，本次调用未执行。"
                        + "请勿再次调用任何工具，请直接基于上下文已有信息组织对用户的最终回答；若信息不足，请如实说明并请求用户补充。"
                        + " [BLOCKED] Tool call budget (%d) for this conversation is exhausted; this call was skipped. "
                        + "Do NOT call any tool again — produce your final answer to the user from the existing context now.",
                totalCalls, totalCalls);
    }

    private record InternalToolExecutionResult(ToolResponseMessage toolResponseMessage, boolean returnDirect) {
    }

    public final static class Builder {

        private ObservationRegistry observationRegistry = DEFAULT_OBSERVATION_REGISTRY;

        private ToolCallbackResolver toolCallbackResolver = DEFAULT_TOOL_CALLBACK_RESOLVER;

        private ToolExecutionExceptionProcessor toolExecutionExceptionProcessor = DEFAULT_TOOL_EXECUTION_EXCEPTION_PROCESSOR;

        private Builder() {
        }

        public Builder observationRegistry(ObservationRegistry observationRegistry) {
            this.observationRegistry = observationRegistry;
            return this;
        }

        public Builder toolCallbackResolver(ToolCallbackResolver toolCallbackResolver) {
            this.toolCallbackResolver = toolCallbackResolver;
            return this;
        }

        public Builder toolExecutionExceptionProcessor(
                ToolExecutionExceptionProcessor toolExecutionExceptionProcessor) {
            this.toolExecutionExceptionProcessor = toolExecutionExceptionProcessor;
            return this;
        }

        public DefaultToolCallingManager build() {
            return new DefaultToolCallingManager(this.observationRegistry, this.toolCallbackResolver,
                    this.toolExecutionExceptionProcessor);
        }

    }

}
