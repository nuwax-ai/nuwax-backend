/*
 * Copyright 2023-2025 the original author or authors.
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

package com.xspaceagi.agent.core.infra.component.model.openai;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.infra.component.model.openai.OpenAiApi.ChatCompletion;
import com.xspaceagi.agent.core.infra.component.model.openai.OpenAiApi.ChatCompletion.Choice;
import com.xspaceagi.agent.core.infra.component.model.openai.OpenAiApi.ChatCompletionMessage;
import com.xspaceagi.agent.core.infra.component.model.openai.OpenAiApi.ChatCompletionMessage.AudioOutput;
import com.xspaceagi.agent.core.infra.component.model.openai.OpenAiApi.ChatCompletionMessage.ChatCompletionFunction;
import com.xspaceagi.agent.core.infra.component.model.openai.OpenAiApi.ChatCompletionMessage.MediaContent;
import com.xspaceagi.agent.core.infra.component.model.openai.OpenAiApi.ChatCompletionMessage.ToolCall;
import com.xspaceagi.agent.core.infra.component.model.openai.OpenAiApi.ChatCompletionRequest;
import com.xspaceagi.system.spec.utils.TimeWheel;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.observation.contextpropagation.ObservationThreadLocalAccessor;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.*;
import org.springframework.ai.chat.model.*;
import org.springframework.ai.chat.observation.ChatModelObservationContext;
import org.springframework.ai.chat.observation.ChatModelObservationConvention;
import org.springframework.ai.chat.observation.ChatModelObservationDocumentation;
import org.springframework.ai.chat.observation.DefaultChatModelObservationConvention;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.Media;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.model.function.FunctionCallbackResolver;
import org.springframework.ai.model.function.FunctionCallingOptions;
import org.springframework.ai.model.tool.LegacyToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.common.OpenAiApiConstants;
import org.springframework.ai.openai.metadata.support.OpenAiResponseHeaderExtractor;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.lang.Nullable;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.util.*;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import static org.springframework.ai.openai.api.OpenAiApi.DEFAULT_CHAT_MODEL;

/**
 * {@link ChatModel} and {@link StreamingChatModel} implementation for {@literal OpenAI}
 * backed by {@link OpenAiApi}.
 *
 * @author Mark Pollack
 * @author Christian Tzolov
 * @author Ueibin Kim
 * @author John Blum
 * @author Josh Long
 * @author Jemin Huh
 * @author Grogdunn
 * @author Hyunjoon Choi
 * @author Mariusz Bernacki
 * @author luocongqiu
 * @author Thomas Vitale
 * @author Ilayaperumal Gopinathan
 * @author Alexandros Pappas
 * @see ChatModel
 * @see StreamingChatModel
 * @see OpenAiApi
 */
public class OpenAiChatModel extends AbstractToolCallSupport implements ChatModel {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiChatModel.class);

    private static final ChatModelObservationConvention DEFAULT_OBSERVATION_CONVENTION = new DefaultChatModelObservationConvention();

    private static final ToolCallingManager DEFAULT_TOOL_CALLING_MANAGER = ToolCallingManager.builder().build();

    private static final String TOOL_CALL_DEPTH_KEY = "_tool_call_depth";

    private static final int MAX_TOOL_CALL_DEPTH = 50; // 最大工具调用递归深度，防止无限循环

    /**
     * The default options used for the chat completion requests.
     */
    private final OpenAiChatOptions defaultOptions;

    /**
     * The retry template used to retry the OpenAI API calls.
     */
    private final RetryTemplate retryTemplate;

    /**
     * Low-level access to the OpenAI API.
     */
    private final OpenAiApi openAiApi;

    /**
     * Observation registry used for instrumentation.
     */
    private final ObservationRegistry observationRegistry;

    private final ToolCallingManager toolCallingManager;

    /**
     * Conventions to use for generating observations.
     */
    private ChatModelObservationConvention observationConvention = DEFAULT_OBSERVATION_CONVENTION;

    /**
     * Creates an instance of the OpenAiChatModel.
     *
     * @param openAiApi The OpenAiApi instance to be used for interacting with the OpenAI
     *                  Chat API.
     * @throws IllegalArgumentException if openAiApi is null
     * @deprecated Use OpenAiChatModel.Builder.
     */

    /**
     * Initializes an instance of the OpenAiChatModel.
     *
     * @param openAiApi The OpenAiApi instance to be used for interacting with the OpenAI
     *                  Chat API.
     * @param options   The OpenAiChatOptions to configure the chat model.
     * @deprecated Use OpenAiChatModel.Builder.
     */
    @Deprecated
    public OpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions options) {
        this(openAiApi, options, null, RetryUtils.DEFAULT_RETRY_TEMPLATE);
    }

    /**
     * Initializes a new instance of the OpenAiChatModel.
     *
     * @param openAiApi                The OpenAiApi instance to be used for interacting with the OpenAI
     *                                 Chat API.
     * @param options                  The OpenAiChatOptions to configure the chat model.
     * @param functionCallbackResolver The function callback resolver.
     * @param retryTemplate            The retry template.
     * @deprecated Use OpenAiChatModel.Builder.
     */
    @Deprecated
    public OpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions options,
                           @Nullable FunctionCallbackResolver functionCallbackResolver, RetryTemplate retryTemplate) {
        this(openAiApi, options, functionCallbackResolver, List.of(), retryTemplate);
    }

    /**
     * Initializes a new instance of the OpenAiChatModel.
     *
     * @param openAiApi                The OpenAiApi instance to be used for interacting with the OpenAI
     *                                 Chat API.
     * @param options                  The OpenAiChatOptions to configure the chat model.
     * @param functionCallbackResolver The function callback resolver.
     * @param toolFunctionCallbacks    The tool function callbacks.
     * @param retryTemplate            The retry template.
     * @deprecated Use OpenAiChatModel.Builder.
     */
    @Deprecated
    public OpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions options,
                           @Nullable FunctionCallbackResolver functionCallbackResolver,
                           @Nullable List<FunctionCallback> toolFunctionCallbacks, RetryTemplate retryTemplate) {
        this(openAiApi, options, functionCallbackResolver, toolFunctionCallbacks, retryTemplate,
                ObservationRegistry.NOOP);
    }

    /**
     * Initializes a new instance of the OpenAiChatModel.
     *
     * @param openAiApi                The OpenAiApi instance to be used for interacting with the OpenAI
     *                                 Chat API.
     * @param options                  The OpenAiChatOptions to configure the chat model.
     * @param functionCallbackResolver The function callback resolver.
     * @param toolFunctionCallbacks    The tool function callbacks.
     * @param retryTemplate            The retry template.
     * @param observationRegistry      The ObservationRegistry used for instrumentation.
     * @deprecated Use OpenAiChatModel.Builder or OpenAiChatModel(OpenAiApi,
     * OpenAiChatOptions, ToolCallingManager, RetryTemplate, ObservationRegistry).
     */
    @Deprecated
    public OpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions options,
                           @Nullable FunctionCallbackResolver functionCallbackResolver,
                           @Nullable List<FunctionCallback> toolFunctionCallbacks, RetryTemplate retryTemplate,
                           ObservationRegistry observationRegistry) {
        this(openAiApi, options,
                LegacyToolCallingManager.builder()
                        .functionCallbackResolver(functionCallbackResolver)
                        .functionCallbacks(toolFunctionCallbacks)
                        .build(),
                retryTemplate, observationRegistry);
        logger.warn("This constructor is deprecated and will be removed in the next milestone. "
                + "Please use the OpenAiChatModel.Builder or the new constructor accepting ToolCallingManager instead.");
    }

    public OpenAiChatModel(OpenAiApi openAiApi, OpenAiChatOptions defaultOptions, ToolCallingManager toolCallingManager,
                           RetryTemplate retryTemplate, ObservationRegistry observationRegistry) {
        // We do not pass the 'defaultOptions' to the AbstractToolSupport,
        // because it modifies them. We are using ToolCallingManager instead,
        // so we just pass empty options here.
        super(null, OpenAiChatOptions.builder().build(), List.of());
        Assert.notNull(openAiApi, "openAiApi cannot be null");
        Assert.notNull(defaultOptions, "defaultOptions cannot be null");
        Assert.notNull(toolCallingManager, "toolCallingManager cannot be null");
        Assert.notNull(retryTemplate, "retryTemplate cannot be null");
        Assert.notNull(observationRegistry, "observationRegistry cannot be null");
        this.openAiApi = openAiApi;
        this.defaultOptions = defaultOptions;
        this.toolCallingManager = toolCallingManager;
        this.retryTemplate = retryTemplate;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        // Before moving any further, build the final request Prompt,
        // merging runtime and default options.
        Prompt requestPrompt = buildRequestPrompt(prompt);
        return this.internalCall(requestPrompt, null);
    }

    public ChatResponse internalCall(Prompt prompt, ChatResponse previousChatResponse) {

        ChatCompletionRequest request = createRequest(prompt, false);

        ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
                .prompt(prompt)
                .provider(OpenAiApiConstants.PROVIDER_NAME)
                .requestOptions(prompt.getOptions())
                .build();

        ChatResponse response = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION
                .observation(this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
                        this.observationRegistry)
                .observe(() -> {

                    ResponseEntity<ChatCompletion> completionEntity = this.retryTemplate
                            .execute(ctx -> this.openAiApi.chatCompletionEntity(request, getAdditionalHttpHeaders(prompt)));

                    var chatCompletion = completionEntity.getBody();

                    if (chatCompletion == null) {
                        logger.warn("No chat completion returned for prompt: {}", prompt);
                        return new ChatResponse(List.of());
                    }

                    List<Choice> choices = chatCompletion.choices();
                    if (choices == null) {
                        logger.warn("No choices returned for prompt: {}", prompt);
                        return new ChatResponse(List.of());
                    }

                    List<Generation> generations = choices.stream().map(choice -> {
                        // @formatter:off
                        Map<String, Object> metadata = Map.of(
                                "id", chatCompletion.id() != null ? chatCompletion.id() : "",
                                "role", choice.message().role() != null ? choice.message().role().name() : "",
                                "index", choice.index(),
                                "finishReason", choice.finishReason() != null ? choice.finishReason().name() : "",
                                "refusal", StringUtils.hasText(choice.message().refusal()) ? choice.message().refusal() : "");
                        // @formatter:on
                        return buildGeneration(choice, metadata, request);
                    }).toList();

                    RateLimit rateLimit = OpenAiResponseHeaderExtractor.extractAiResponseHeaders(completionEntity);

                    // Current usage
                    OpenAiApi.Usage usage = completionEntity.getBody().usage();
                    Usage currentChatResponseUsage = usage != null ? getDefaultUsage(usage) : new EmptyUsage();
                    Usage accumulatedUsage = UsageUtils.getCumulativeUsage(currentChatResponseUsage, previousChatResponse);
                    ChatResponse chatResponse = new ChatResponse(generations,
                            from(completionEntity.getBody(), rateLimit, accumulatedUsage));

                    observationContext.setResponse(chatResponse);

                    return chatResponse;

                });

        if (ToolCallingChatOptions.isInternalToolExecutionEnabled(prompt.getOptions()) && response != null
                && response.hasToolCalls()) {
            var toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, response);
            if (toolExecutionResult.returnDirect()) {
                // Return tool execution result directly to the client.
                return ChatResponse.builder()
                        .from(response)
                        .generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
                        .build();
            } else {
                // Send the tool execution result back to the model.
                return this.internalCall(new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()),
                        response);
            }
        }

        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        // Before moving any further, build the final request Prompt,
        // merging runtime and default options.
        Prompt requestPrompt = buildRequestPrompt(prompt);
        return internalStream(requestPrompt, null, new AtomicInteger(0));
    }

    public Flux<ChatResponse> internalStream(Prompt prompt, ChatResponse previousChatResponse, AtomicInteger toolCallCount) {
        return Flux.deferContextual(contextView -> {
            ChatCompletionRequest request = createRequest(prompt, true);
            if (request.outputModalities() != null) {
                if (request.outputModalities().stream().anyMatch(m -> m.equals("audio"))) {
                    logger.warn("Audio output is not supported for streaming requests. Removing audio output.");
                    throw new IllegalArgumentException("Audio output is not supported for streaming requests.");
                }
            }
            if (request.audioParameters() != null) {
                logger.warn("Audio parameters are not supported for streaming requests. Removing audio parameters.");
                throw new IllegalArgumentException("Audio parameters are not supported for streaming requests.");
            }
            AtomicBoolean isStreamingCall = new AtomicBoolean(false);
            Flux<OpenAiApi.ChatCompletionChunk> completionChunks = this.openAiApi.chatCompletionStream(request,
                    getAdditionalHttpHeaders(prompt), isStreamingCall);

            // For chunked responses, only the first chunk contains the choice role.
            // The rest of the chunks with same ID share the same role.
            ConcurrentHashMap<String, String> roleMap = new ConcurrentHashMap<>();

            final ChatModelObservationContext observationContext = ChatModelObservationContext.builder()
                    .prompt(prompt)
                    .provider(OpenAiApiConstants.PROVIDER_NAME)
                    .requestOptions(prompt.getOptions())
                    .build();

            Observation observation = ChatModelObservationDocumentation.CHAT_MODEL_OPERATION.observation(
                    this.observationConvention, DEFAULT_OBSERVATION_CONVENTION, () -> observationContext,
                    this.observationRegistry);

            observation.parentObservation(contextView.getOrDefault(ObservationThreadLocalAccessor.KEY, null)).start();

            // Convert the ChatCompletionChunk into a ChatCompletion to be able to reuse
            // the function call handling logic.
            Flux<ChatResponse> chatResponse = completionChunks.map(this::chunkToChatCompletion)
                    .switchMap(chatCompletion -> Mono.just(chatCompletion).map(chatCompletion2 -> {
                        try {
                            @SuppressWarnings("null")
                            String id = chatCompletion2.id();

                            List<Generation> generations = chatCompletion2.choices().stream().map(choice -> { // @formatter:off
                                if (choice.message().role() != null) {
                                    roleMap.putIfAbsent(id, choice.message().role().name());
                                }
                                Map<String, Object> metadata = Map.of(
                                        "id", chatCompletion2.id(),
                                        "role", roleMap.getOrDefault(id, ""),
                                        "index", choice.index(),
                                        "reasoningContent", choice.message().reasoningContent() != null ? choice.message().reasoningContent() : "",
                                        "reasoning", choice.message().reasoning() != null ? choice.message().reasoning() : "",
                                        "finishReason", choice.finishReason() != null ? choice.finishReason().name() : "",
                                        "refusal", StringUtils.hasText(choice.message().refusal()) ? choice.message().refusal() : "");

                                return buildGeneration(choice, metadata, request);
                            }).toList();
                            // @formatter:on
                            OpenAiApi.Usage usage = chatCompletion2.usage();
                            Usage currentChatResponseUsage = usage != null ? getDefaultUsage(usage) : new EmptyUsage();
                            Usage accumulatedUsage = UsageUtils.getCumulativeUsage(currentChatResponseUsage,
                                    previousChatResponse);
                            return new ChatResponse(generations, from(chatCompletion2, null, accumulatedUsage));
                        } catch (Exception e) {
                            logger.error("Error processing chat completion", e);
                            return new ChatResponse(List.of());
                        }
                        // When in stream mode and enabled to include the usage, the OpenAI
                        // Chat completion response would have the usage set only in its
                        // final response. Hence, the following overlapping buffer is
                        // created to store both the current and the subsequent response
                        // to accumulate the usage from the subsequent response.
                    }))
                    .buffer(2, 1)
                    .map(bufferList -> {
                        ChatResponse firstResponse = bufferList.get(0);
                        if (request.streamOptions() != null && request.streamOptions().includeUsage()) {
                            if (bufferList.size() == 2) {
                                ChatResponse secondResponse = bufferList.get(1);
                                if (secondResponse != null && secondResponse.getMetadata() != null) {
                                    // This is the usage from the final Chat response for a
                                    // given Chat request.
                                    Usage usage = secondResponse.getMetadata().getUsage();
                                    if (!UsageUtils.isEmpty(usage)) {
                                        // Store the usage from the final response to the
                                        // penultimate response for accumulation.
                                        return new ChatResponse(firstResponse.getResults(),
                                                from(firstResponse.getMetadata(), usage));
                                    }
                                }
                            }
                        }
                        return firstResponse;
                    });
            AtomicLong lastActiveTime = new AtomicLong(System.currentTimeMillis());
            AtomicBoolean finished = new AtomicBoolean(false);
            List<ChatResponse> toolCallResponses = new ArrayList<>();
            // @formatter:off
            Flux<ChatResponse> flux = chatResponse.flatMap(response -> {
                        lastActiveTime.set(System.currentTimeMillis());
                        String finishReason = null;
                        if (response.getResult() != null && response.getResult().getOutput() != null && response.getResult().getOutput().getMetadata() != null) {
                            Object finishReason0 = response.getResult().getOutput().getMetadata().get("finishReason");
                            finishReason = finishReason0 != null ? finishReason0.toString() : null;
                        }
                        if (ToolCallingChatOptions.isInternalToolExecutionEnabled(prompt.getOptions()) && response.hasToolCalls() && isStreamingCall.get()) {
                            //流式函数调用
                            finished.set(true);
                            return toolCall(response, prompt, response, toolCallCount);
                        } else if (ToolCallingChatOptions.isInternalToolExecutionEnabled(prompt.getOptions()) && response.hasToolCalls()) {
                            //非流式函数调用，针对GLM等模型
                            toolCallResponses.add(response);
                            return Flux.just(new ChatResponse(List.of()));
                        } else if (finishReason != null && (finishReason.equalsIgnoreCase("tool_calls") || finishReason.equalsIgnoreCase("tool_call")) && !toolCallResponses.isEmpty()) {
                            //合并chatResponse0.getResult().getOutput().getToolCalls()
                            List<AssistantMessage.ToolCall> toolCalls = new ArrayList<>();
                            toolCallResponses.forEach(chatResponse0 -> chatResponse0.getResults().forEach(result -> {
                                if (result.getOutput() != null && result.getOutput().getToolCalls() != null) {
                                    toolCalls.addAll(result.getOutput().getToolCalls());
                                }
                            }));

                            var assistantMessage = new AssistantMessage("", response.getResult().getOutput().getMetadata(), toolCalls);
                            Generation generation = new Generation(assistantMessage);
                            ChatResponse chatResponse0 = new ChatResponse(List.of(generation));
                            finished.set(true);
                            return toolCall(chatResponse0, prompt, response, toolCallCount);
                        } else {
                            return Flux.just(response);
                        }
                    })
                    .doOnError(observation::error)
                    .doFinally(s -> {
                        observation.stop();
                        finished.set(true);
                    })
                    .contextWrite(ctx -> ctx.put(ObservationThreadLocalAccessor.KEY, observation));

            // 超时回调
            Sinks.Many<ChatResponse> sink = Sinks.many().multicast().onBackpressureBuffer();
            // 1. 原 flux 的数据全部转存到 sink
            // 转发 TODO 优化
            Disposable d = flux.subscribe(sink::tryEmitNext, sink::tryEmitError, sink::tryEmitComplete);
            checkReadTimeoutTimer(sink, d, finished, lastActiveTime, toolCallCount, prompt);
            Flux<ChatResponse> chatResponseFlux = sink.asFlux().doOnCancel(d::dispose);
            // @formatter:on
            return new com.xspaceagi.agent.core.infra.component.model.openai.MessageAggregator().aggregate(chatResponseFlux, observationContext::setResponse);

        });
    }

    private Publisher<? extends ChatResponse> toolCall(ChatResponse chatResponse0, Prompt prompt, ChatResponse response, AtomicInteger toolCallCount) {
        ToolExecutionResult toolExecutionResult;
        try {
            toolExecutionResult = this.toolCallingManager.executeToolCalls(prompt, chatResponse0);
            // 避免模型出问题时，无限递归
            if (toolCallCount.getAndIncrement() > MAX_TOOL_CALL_DEPTH) {
                toolExecutionResult = ToolExecutionResult.builder()
                        .returnDirect(true)
                        .conversationHistory(toolExecutionResult.conversationHistory())
                        .build();
            }
        } catch (Throwable e) {
            return Flux.error(e);
        }
        if (toolExecutionResult.returnDirect()) {
            // Return tool execution result directly to the client.
            return Flux.just(ChatResponse.builder().from(chatResponse0)
                    .generations(ToolExecutionResult.buildGenerations(toolExecutionResult))
                    .build());
        } else {
            // Send the tool execution result back to the model.
            return this.internalStream(new Prompt(toolExecutionResult.conversationHistory(), prompt.getOptions()),
                    response, toolCallCount);
        }
    }

    private void checkReadTimeoutTimer(Sinks.Many<ChatResponse> sink, Disposable d, AtomicBoolean finished, AtomicLong lastActiveTime, AtomicInteger toolCallCount, Prompt prompt) {
        if (finished.get()) {
            if (toolCallCount.get() >= MAX_TOOL_CALL_DEPTH - 1) {
                logger.warn("checkReadTimeoutTimer finished, {}", JSON.toJSONString(prompt.getInstructions()));
            } else {
                logger.debug("checkReadTimeoutTimer finished");
            }
            return;
        }
        //3分钟以上没有数据就强制结束（目前全部使用的是流式输出，一般情况下不会等待太久）
        if (System.currentTimeMillis() - lastActiveTime.get() > 60 * 1000 * 3) {
            finished.set(true);
            logger.warn("checkReadTimeoutTimer timeout");
            sink.tryEmitError(new TimeoutException("Stream token read timeout"));
            d.dispose();
            return;
        }
        logger.debug("checkReadTimeoutTimer add");
        if (toolCallCount.get() >= MAX_TOOL_CALL_DEPTH - 1) {
            logger.warn("checkReadTimeoutTimer add, {}", JSON.toJSONString(prompt.getInstructions()));
        }
        TimeWheel.getInstance().schedule((res) -> checkReadTimeoutTimer(sink, d, finished, lastActiveTime, toolCallCount, prompt), 20);
    }

    private MultiValueMap<String, String> getAdditionalHttpHeaders(Prompt prompt) {

        Map<String, String> headers = new HashMap<>(this.defaultOptions.getHttpHeaders());
        if (prompt.getOptions() != null && prompt.getOptions() instanceof OpenAiChatOptions chatOptions) {
            headers.putAll(chatOptions.getHttpHeaders());
        }
        return CollectionUtils.toMultiValueMap(
                headers.entrySet().stream().collect(Collectors.toMap(Map.Entry::getKey, e -> List.of(e.getValue()))));
    }

    private Generation buildGeneration(Choice choice, Map<String, Object> metadata, ChatCompletionRequest request) {
        List<AssistantMessage.ToolCall> toolCalls = choice.message().toolCalls() == null ? List.of()
                : choice.message()
                .toolCalls()
                .stream()
                .map(toolCall -> new AssistantMessage.ToolCall(toolCall.id(), "function",
                        toolCall.function().name(), toolCall.function().arguments()))
                .toList();

        String finishReason = (choice.finishReason() != null ? choice.finishReason().name() : "");
        var generationMetadataBuilder = ChatGenerationMetadata.builder().finishReason(finishReason);

        List<Media> media = new ArrayList<>();
        String textContent = choice.message().content();
        var audioOutput = choice.message().audioOutput();
        if (audioOutput != null) {
            String mimeType = String.format("audio/%s", request.audioParameters().format().name().toLowerCase());
            byte[] audioData = Base64.getDecoder().decode(audioOutput.data());
            Resource resource = new ByteArrayResource(audioData);
            Media.builder().mimeType(MimeTypeUtils.parseMimeType(mimeType)).data(resource).id(audioOutput.id()).build();
            media.add(Media.builder()
                    .mimeType(MimeTypeUtils.parseMimeType(mimeType))
                    .data(resource)
                    .id(audioOutput.id())
                    .build());
            if (!StringUtils.hasText(textContent)) {
                textContent = audioOutput.transcript();
            }
            generationMetadataBuilder.metadata("audioId", audioOutput.id());
            generationMetadataBuilder.metadata("audioExpiresAt", audioOutput.expiresAt());
        }

        var assistantMessage = new AssistantMessage(textContent, metadata, toolCalls, media);
        return new Generation(assistantMessage, generationMetadataBuilder.build());
    }

    private ChatResponseMetadata from(OpenAiApi.ChatCompletion result, RateLimit rateLimit, Usage usage) {
        Assert.notNull(result, "OpenAI ChatCompletionResult must not be null");
        var builder = ChatResponseMetadata.builder()
                .id(result.id() != null ? result.id() : "")
                .usage(usage)
                .model(result.model() != null ? result.model() : "")
                .keyValue("created", result.created() != null ? result.created() : 0L)
                .keyValue("system-fingerprint", result.systemFingerprint() != null ? result.systemFingerprint() : "");
        if (rateLimit != null) {
            builder.rateLimit(rateLimit);
        }
        return builder.build();
    }

    private ChatResponseMetadata from(ChatResponseMetadata chatResponseMetadata, Usage usage) {
        Assert.notNull(chatResponseMetadata, "OpenAI ChatResponseMetadata must not be null");
        var builder = ChatResponseMetadata.builder()
                .id(chatResponseMetadata.getId() != null ? chatResponseMetadata.getId() : "")
                .usage(usage)
                .model(chatResponseMetadata.getModel() != null ? chatResponseMetadata.getModel() : "");
        if (chatResponseMetadata.getRateLimit() != null) {
            builder.rateLimit(chatResponseMetadata.getRateLimit());
        }
        return builder.build();
    }

    /**
     * Convert the ChatCompletionChunk into a ChatCompletion. The Usage is set to null.
     *
     * @param chunk the ChatCompletionChunk to convert
     * @return the ChatCompletion
     */
    private OpenAiApi.ChatCompletion chunkToChatCompletion(OpenAiApi.ChatCompletionChunk chunk) {
        List<Choice> choices = chunk.choices()
                .stream()
                .map(chunkChoice -> new Choice(chunkChoice.finishReason(), chunkChoice.index(), chunkChoice.delta(),
                        chunkChoice.logprobs()))
                .toList();

        return new OpenAiApi.ChatCompletion(chunk.id(), choices, chunk.created(), chunk.model(), chunk.serviceTier(),
                chunk.systemFingerprint(), "chat.completion", chunk.usage());
    }

    private DefaultUsage getDefaultUsage(OpenAiApi.Usage usage) {
        return new DefaultUsage(usage.promptTokens(), usage.completionTokens(), usage.totalTokens(), usage);
    }

    Prompt buildRequestPrompt(Prompt prompt) {
        // Process runtime options
        OpenAiChatOptions runtimeOptions = null;
        if (prompt.getOptions() != null) {
            if (prompt.getOptions() instanceof ToolCallingChatOptions toolCallingChatOptions) {
                runtimeOptions = ModelOptionsUtils.copyToTarget(toolCallingChatOptions, ToolCallingChatOptions.class,
                        OpenAiChatOptions.class);
            } else if (prompt.getOptions() instanceof FunctionCallingOptions functionCallingOptions) {
                runtimeOptions = ModelOptionsUtils.copyToTarget(functionCallingOptions, FunctionCallingOptions.class,
                        OpenAiChatOptions.class);
            } else {
                runtimeOptions = ModelOptionsUtils.copyToTarget(prompt.getOptions(), ChatOptions.class,
                        OpenAiChatOptions.class);
            }
        }

        // Define request options by merging runtime options and default options
        OpenAiChatOptions requestOptions = ModelOptionsUtils.merge(runtimeOptions, this.defaultOptions,
                OpenAiChatOptions.class);

        // Merge @JsonIgnore-annotated options explicitly since they are ignored by
        // Jackson, used by ModelOptionsUtils.
        if (runtimeOptions != null) {
            requestOptions.setHttpHeaders(
                    mergeHttpHeaders(runtimeOptions.getHttpHeaders(), this.defaultOptions.getHttpHeaders()));
            requestOptions.setInternalToolExecutionEnabled(
                    ModelOptionsUtils.mergeOption(runtimeOptions.isInternalToolExecutionEnabled(),
                            this.defaultOptions.isInternalToolExecutionEnabled()));
            requestOptions.setToolNames(ToolCallingChatOptions.mergeToolNames(runtimeOptions.getToolNames(),
                    this.defaultOptions.getToolNames()));
            requestOptions.setToolCallbacks(ToolCallingChatOptions.mergeToolCallbacks(runtimeOptions.getToolCallbacks(),
                    this.defaultOptions.getToolCallbacks()));
            requestOptions.setToolContext(ToolCallingChatOptions.mergeToolContext(runtimeOptions.getToolContext(),
                    this.defaultOptions.getToolContext()));
        } else {
            requestOptions.setHttpHeaders(this.defaultOptions.getHttpHeaders());
            requestOptions.setInternalToolExecutionEnabled(this.defaultOptions.isInternalToolExecutionEnabled());
            requestOptions.setToolNames(this.defaultOptions.getToolNames());
            requestOptions.setToolCallbacks(this.defaultOptions.getToolCallbacks());
            requestOptions.setToolContext(this.defaultOptions.getToolContext());
        }

        ToolCallingChatOptions.validateToolCallbacks(requestOptions.getToolCallbacks());

        return new Prompt(prompt.getInstructions(), requestOptions);
    }

    private Map<String, String> mergeHttpHeaders(Map<String, String> runtimeHttpHeaders,
                                                 Map<String, String> defaultHttpHeaders) {
        var mergedHttpHeaders = new HashMap<>(defaultHttpHeaders);
        mergedHttpHeaders.putAll(runtimeHttpHeaders);
        return mergedHttpHeaders;
    }

    /**
     * Accessible for testing.
     */
    ChatCompletionRequest createRequest(Prompt prompt, boolean stream) {

        List<ChatCompletionMessage> chatCompletionMessages = prompt.getInstructions().stream().map(message -> {
            if (message.getMessageType() == MessageType.USER || message.getMessageType() == MessageType.SYSTEM) {
                Object content = message.getText();
                if (message instanceof UserMessage userMessage) {
                    if (!CollectionUtils.isEmpty(userMessage.getMedia())) {
                        List<MediaContent> contentList = new ArrayList<>(List.of(new MediaContent(message.getText())));

                        contentList.addAll(userMessage.getMedia().stream().map(this::mapToMediaContent).toList());

                        content = contentList;
                    }
                }

                return List.of(new ChatCompletionMessage(content,
                        ChatCompletionMessage.Role.valueOf(message.getMessageType().name())));
            } else if (message.getMessageType() == MessageType.ASSISTANT) {
                var assistantMessage = (AssistantMessage) message;
                List<ToolCall> toolCalls = null;
                if (!CollectionUtils.isEmpty(assistantMessage.getToolCalls())) {
                    toolCalls = assistantMessage.getToolCalls().stream().map(toolCall -> {
                        var function = new ChatCompletionFunction(toolCall.name(), toolCall.arguments());
                        return new ToolCall(toolCall.id(), toolCall.type(), function);
                    }).toList();
                }
                AudioOutput audioOutput = null;
                if (!CollectionUtils.isEmpty(assistantMessage.getMedia())) {
                    Assert.isTrue(assistantMessage.getMedia().size() == 1,
                            "Only one media content is supported for assistant messages");
                    audioOutput = new AudioOutput(assistantMessage.getMedia().get(0).getId(), null, null, null);

                }
                return List.of(new ChatCompletionMessage(assistantMessage.getText(), null, null,
                        ChatCompletionMessage.Role.ASSISTANT, null, null, toolCalls, null, audioOutput));
            } else if (message.getMessageType() == MessageType.TOOL) {
                ToolResponseMessage toolMessage = (ToolResponseMessage) message;

                toolMessage.getResponses()
                        .forEach(response -> Assert.isTrue(response.id() != null, "ToolResponseMessage must have an id"));
                return toolMessage.getResponses()
                        .stream()
                        .map(tr -> new ChatCompletionMessage(tr.responseData(), null, null, ChatCompletionMessage.Role.TOOL, tr.name(),
                                tr.id(), null, null, null))
                        .toList();
            } else {
                throw new IllegalArgumentException("Unsupported message type: " + message.getMessageType());
            }
        }).flatMap(List::stream).toList();

        ChatCompletionRequest request = new ChatCompletionRequest(chatCompletionMessages, stream);

        OpenAiChatOptions requestOptions = (OpenAiChatOptions) prompt.getOptions();
        request = ModelOptionsUtils.merge(requestOptions, request, ChatCompletionRequest.class);

        // Add the tool definitions to the request's tools parameter.
        List<ToolDefinition> toolDefinitions = this.toolCallingManager.resolveToolDefinitions(requestOptions);
        if (!CollectionUtils.isEmpty(toolDefinitions)) {
            request = ModelOptionsUtils.merge(
                    OpenAiChatOptions.builder().tools(this.getFunctionTools(toolDefinitions)).build(), request,
                    ChatCompletionRequest.class);
        }

        // Remove `streamOptions` from the request if it is not a streaming request
        if (request.streamOptions() != null && !stream) {
            logger.warn("Removing streamOptions from the request as it is not a streaming request!");
            request = request.streamOptions(null);
        }

        return request;
    }

    private MediaContent mapToMediaContent(Media media) {
        var mimeType = media.getMimeType();
        if (MimeTypeUtils.parseMimeType("audio/mp3").equals(mimeType)) {
            return new MediaContent(
                    new MediaContent.InputAudio(fromAudioData(media.getData()), MediaContent.InputAudio.Format.MP3));
        }
        if (MimeTypeUtils.parseMimeType("audio/wav").equals(mimeType)) {
            return new MediaContent(
                    new MediaContent.InputAudio(fromAudioData(media.getData()), MediaContent.InputAudio.Format.WAV));
        } else {
            return new MediaContent(
                    new MediaContent.ImageUrl(this.fromMediaData(media.getMimeType(), media.getData())));
        }
    }

    private String fromAudioData(Object audioData) {
        if (audioData instanceof byte[] bytes) {
            return Base64.getEncoder().encodeToString(bytes);
        }
        throw new IllegalArgumentException("Unsupported audio data type: " + audioData.getClass().getSimpleName());
    }

    private String fromMediaData(MimeType mimeType, Object mediaContentData) {
        if (mediaContentData instanceof byte[] bytes) {
            // Assume the bytes are an image. So, convert the bytes to a base64 encoded
            // following the prefix pattern.
            return String.format("data:%s;base64,%s", mimeType.toString(), Base64.getEncoder().encodeToString(bytes));
        } else if (mediaContentData instanceof String text) {
            // Assume the text is a URLs or a base64 encoded image prefixed by the user.
            return text;
        } else {
            throw new IllegalArgumentException(
                    "Unsupported media data type: " + mediaContentData.getClass().getSimpleName());
        }
    }

    private List<org.springframework.ai.openai.api.OpenAiApi.FunctionTool> getFunctionTools(List<ToolDefinition> toolDefinitions) {
        return toolDefinitions.stream().map(toolDefinition -> {
            var function = new org.springframework.ai.openai.api.OpenAiApi.FunctionTool.Function(toolDefinition.description(), toolDefinition.name(),
                    toolDefinition.inputSchema());
            return new org.springframework.ai.openai.api.OpenAiApi.FunctionTool(function);
        }).toList();
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return OpenAiChatOptions.fromOptions(this.defaultOptions);
    }

    @Override
    public String toString() {
        return "OpenAiChatModel [defaultOptions=" + this.defaultOptions + "]";
    }

    /**
     * Use the provided convention for reporting observation data
     *
     * @param observationConvention The provided convention
     */
    public void setObservationConvention(ChatModelObservationConvention observationConvention) {
        Assert.notNull(observationConvention, "observationConvention cannot be null");
        this.observationConvention = observationConvention;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private OpenAiApi openAiApi;

        private OpenAiChatOptions defaultOptions = OpenAiChatOptions.builder()
                .model(DEFAULT_CHAT_MODEL)
                .temperature(0.7)
                .build();

        private ToolCallingManager toolCallingManager;

        private FunctionCallbackResolver functionCallbackResolver;

        private List<FunctionCallback> toolFunctionCallbacks;

        private RetryTemplate retryTemplate = RetryUtils.DEFAULT_RETRY_TEMPLATE;

        private ObservationRegistry observationRegistry = ObservationRegistry.NOOP;

        private Builder() {
        }

        public Builder openAiApi(OpenAiApi openAiApi) {
            this.openAiApi = openAiApi;
            return this;
        }

        public Builder defaultOptions(OpenAiChatOptions defaultOptions) {
            this.defaultOptions = defaultOptions;
            return this;
        }

        public Builder toolCallingManager(ToolCallingManager toolCallingManager) {
            this.toolCallingManager = toolCallingManager;
            return this;
        }

        @Deprecated
        public Builder functionCallbackResolver(FunctionCallbackResolver functionCallbackResolver) {
            this.functionCallbackResolver = functionCallbackResolver;
            return this;
        }

        @Deprecated
        public Builder toolFunctionCallbacks(List<FunctionCallback> toolFunctionCallbacks) {
            this.toolFunctionCallbacks = toolFunctionCallbacks;
            return this;
        }

        public Builder retryTemplate(RetryTemplate retryTemplate) {
            this.retryTemplate = retryTemplate;
            return this;
        }

        public Builder observationRegistry(ObservationRegistry observationRegistry) {
            this.observationRegistry = observationRegistry;
            return this;
        }

        public OpenAiChatModel build() {
            if (toolCallingManager != null) {
                Assert.isNull(functionCallbackResolver,
                        "functionCallbackResolver cannot be set when toolCallingManager is set");
                Assert.isNull(toolFunctionCallbacks,
                        "toolFunctionCallbacks cannot be set when toolCallingManager is set");

                return new OpenAiChatModel(openAiApi, defaultOptions, toolCallingManager, retryTemplate,
                        observationRegistry);
            }

            if (functionCallbackResolver != null) {
                Assert.isNull(toolCallingManager,
                        "toolCallingManager cannot be set when functionCallbackResolver is set");
                List<FunctionCallback> toolCallbacks = this.toolFunctionCallbacks != null ? this.toolFunctionCallbacks
                        : List.of();

                return new OpenAiChatModel(openAiApi, defaultOptions, functionCallbackResolver, toolCallbacks,
                        retryTemplate, observationRegistry);
            }

            return new OpenAiChatModel(openAiApi, defaultOptions, DEFAULT_TOOL_CALLING_MANAGER, retryTemplate,
                    observationRegistry);
        }

    }

}
