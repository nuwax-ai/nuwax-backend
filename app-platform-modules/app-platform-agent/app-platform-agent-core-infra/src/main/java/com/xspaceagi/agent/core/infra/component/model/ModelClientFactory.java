package com.xspaceagi.agent.core.infra.component.model;

import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.model.embed.OpenAiEmbeddingModel;
import com.xspaceagi.agent.core.infra.component.model.openai.OpenAiApi;
import com.xspaceagi.agent.core.infra.component.model.openai.OpenAiChatModel;
import com.xspaceagi.agent.core.infra.component.model.strategy.WeightedRoundRobinStrategy;
import com.xspaceagi.agent.core.infra.rpc.ModelApiProxyRpcService;
import com.xspaceagi.agent.core.spec.enums.ModelApiProtocolEnum;
import com.xspaceagi.modelproxy.sdk.service.dto.BackendModelDto;
import com.xspaceagi.modelproxy.sdk.service.dto.FrontendModelDto;
import io.micrometer.observation.ObservationRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.model.SimpleApiKey;
import org.springframework.ai.model.tool.DefaultToolCallingManager;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ModelClientFactory {

    private final RestClient.Builder restClientBuilder = RestClient.builder().requestFactory(getRequestFactory());

    private final WebClient.Builder webClientBuilder = WebClient.builder();

    private final ExecutorService ex = Executors.newCachedThreadPool(new ThreadFactory() {

        private final AtomicInteger nextId = new AtomicInteger();

        public Thread newThread(@NotNull Runnable r) {
            String name = "HttpClient-Custom-Worker-" + this.nextId.getAndIncrement();
            Thread t = new Thread((ThreadGroup) null, r, name, 0L, false);
            t.setDaemon(true);
            return t;
        }
    });

    private ClientHttpRequestFactory getRequestFactory() {
        HttpComponentsClientHttpRequestFactory factory = new HttpComponentsClientHttpRequestFactory();
        factory.setConnectTimeout(15000);
        factory.setConnectionRequestTimeout(Duration.ofSeconds(60));
        return factory;
    }

    private ModelApiProxyRpcService modelApiProxyRpcService;

    @Resource
    private WeightedRoundRobinStrategy weightedRoundRobinStrategy;

    @PostConstruct
    private void init() {
        // 禁用HttpClient的keep-alive功能，有些网络环境下可能会导致连接超时
        System.setProperty("jdk.httpclient.keepalive.timeout", "0");
        webClientBuilder.filter(WebClientRequestAndResponseFilter.requestFilter)
                .filter(WebClientRequestAndResponseFilter.responseFilter)
                .clientConnector(new JdkClientHttpConnector(ex));
    }

    @Autowired
    public void setModelApiProxyRpcService(ModelApiProxyRpcService modelApiProxyRpcService) {
        this.modelApiProxyRpcService = modelApiProxyRpcService;
    }

    private WebClient.Builder cloneWebClientBuilder() {
        return webClientBuilder.clone();
    }

    public ChatClient createChatClient(ModelConfigDto model) {
        return createChatClient(null, model, List.of());
    }

    public EmbeddingModel createEmbeddingModel(ModelConfigDto model) {
        Assert.isTrue(!model.getApiInfoList().isEmpty(), "模型配置异常");
        ModelConfigDto.ApiInfo apiInfo = weightedRoundRobinStrategy.selectApi(model);
        if (apiInfo == null) {
            return null;
        }
        String baseUrl = completeBaseUrl(apiInfo.getUrl());
        String requestId = UUID.randomUUID().toString().replace("-", "");
        String traceId = UUID.randomUUID().toString().replace("-", "");
        OpenAiApi openAiApi = new OpenAiApi(baseUrl, new SimpleApiKey(apiInfo.getKey()),
                CollectionUtils.toMultiValueMap(Map.of("Request-Id", List.of(requestId), "X-Trace-Id", List.of(traceId))),
                "/chat/completions", "/embeddings", restClientBuilder.clone(), cloneWebClientBuilder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
        return new OpenAiEmbeddingModel(openAiApi, MetadataMode.EMBED,
                OpenAiEmbeddingOptions.builder()
                        .model(model.getModel())
                        .dimensions(model.getDimension())
                        .build());
    }

    public ChatClient createChatClient(ModelContext modelContext, ModelConfigDto model, List<ToolCallback> functionCallbacks) {
        return createChatClient(modelContext, model, functionCallbacks, true);
    }

    public ChatClient createChatClient(ModelContext modelContext, ModelConfigDto model, List<ToolCallback> functionCallbacks, Boolean withProxyToolCalls) {
        Assert.isTrue(!model.getApiInfoList().isEmpty(), "模型配置异常");
        ModelConfigDto.ApiInfo apiInfo = weightedRoundRobinStrategy.selectApi(model);
        if (apiInfo == null) {
            return null;
        }

        Double temperature = model.getTemperature() == null || model.getTemperature() > 1 || model.getTemperature() <= 0 ? 0.7 : model.getTemperature();
        Double topP = model.getTopP() == null || model.getTopP() <= 0 || model.getTopP() > 1 ? 1.0 : model.getTopP();
        ChatModel chatModel = null;
        Integer isReasonModel = model.getIsReasonModel() == null ? 0 : model.getIsReasonModel();
        String requestId = modelContext == null || modelContext.getRequestId() == null ? "" : modelContext.getRequestId();
        String traceId = modelContext == null || modelContext.getTraceId() == null ? "" : modelContext.getTraceId();

        //全局模型走代理模式，为用户生成独立的key
        if (model.getScope() == ModelConfig.ModelScopeEnum.Tenant && modelContext != null) {
            BackendModelDto backendModelDto = new BackendModelDto();
            if (model.getApiProtocol() == ModelApiProtocolEnum.OpenAI) {
                String baseUrl = completeBaseUrl(apiInfo.getUrl());
                backendModelDto.setBaseUrl(baseUrl);
            } else {
                backendModelDto.setBaseUrl(apiInfo.getUrl());
            }
            backendModelDto.setApiKey(apiInfo.getKey());
            backendModelDto.setModelName(model.getModel());
            backendModelDto.setProtocol(model.getApiProtocol().name());
            backendModelDto.setScope(model.getScope().name());
            backendModelDto.setModelId(model.getId());
            backendModelDto.setUserName(modelContext.getAgentContext() == null ? "" : modelContext.getAgentContext().getUserName());
            backendModelDto.setConversationId(modelContext.getConversationId());
            backendModelDto.setRequestId(requestId);
            AgentContext agentContext = modelContext.getAgentContext();
            String siteUrl = agentContext == null || agentContext.getTenantConfig() == null ? "" : agentContext.getTenantConfig().getSiteUrl();
            FrontendModelDto frontendModelDto = modelApiProxyRpcService.generateUserFrontendModelConfig(model.getTenantId(), agentContext == null || agentContext.getUser() == null ? -1L : agentContext.getUser().getId()
                    , agentContext == null || agentContext.getAgentConfig() == null ? -1L : agentContext.getAgentConfig().getId(), backendModelDto, siteUrl);
            apiInfo = new ModelConfigDto.ApiInfo();
            apiInfo.setKey(frontendModelDto.getApiKey());
            apiInfo.setUrl(frontendModelDto.getBaseUrl());
        } else {
            String baseUrl = model.getApiProtocol() == ModelApiProtocolEnum.OpenAI
                    ? completeBaseUrl(apiInfo.getUrl())
                    : model.getApiProtocol() == ModelApiProtocolEnum.Anthropic
                    ? normalizeAnthropicBaseUrl(apiInfo.getUrl())
                    : apiInfo.getUrl();
            String apiKey = apiInfo.getKey();
            apiInfo = new ModelConfigDto.ApiInfo();
            apiInfo.setKey(apiKey);
            apiInfo.setUrl(baseUrl);
        }

        if (model.getApiProtocol() == ModelApiProtocolEnum.OpenAI) {
            var promptOptions = OpenAiChatOptions.builder()
                    .model(model.getModel())
                    .internalToolExecutionEnabled(withProxyToolCalls)
                    .maxTokens(model.getMaxTokens())
                    .temperature(temperature)
                    .topP(topP)
                    .build();
            OpenAiApi api = new OpenAiApi(apiInfo.getUrl(), new SimpleApiKey(apiInfo.getKey()),
                    CollectionUtils.toMultiValueMap(Map.of("X-Reason-Model", List.of(isReasonModel.toString()), "Request-Id", List.of(requestId), "X-Trace-Id", List.of(traceId))),
                    "/chat/completions", "/embeddings", restClientBuilder.clone(), cloneWebClientBuilder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
            api.isReasonModel = isReasonModel.equals(1);
            chatModel = new OpenAiChatModel(api, promptOptions, DefaultToolCallingManager.builder().build(), RetryUtils.DEFAULT_RETRY_TEMPLATE, ObservationRegistry.NOOP);
        }
        if (model.getApiProtocol() == ModelApiProtocolEnum.Ollama) {
            OllamaOptions promptOptions = OllamaOptions.builder()
                    .model(model.getModel())
                    .internalToolExecutionEnabled(withProxyToolCalls)
                    .temperature(temperature)
                    .topP(topP)
                    .build();
            promptOptions.setMaxTokens(model.getMaxTokens());
            OllamaApi api = new OllamaApi(apiInfo.getUrl(), restClientBuilder.clone().defaultHeader("X-Reason-Model", isReasonModel.toString()).defaultHeader("Request-Id", requestId).defaultHeader("X-Trace-Id", traceId), webClientBuilder.clone());
            chatModel = OllamaChatModel.builder().defaultOptions(promptOptions).ollamaApi(api).build();
        }
        if (model.getApiProtocol() == ModelApiProtocolEnum.Anthropic) {
            AnthropicChatOptions anthropicChatOptions = AnthropicChatOptions.builder()
                    .model(model.getModel())
                    .internalToolExecutionEnabled(withProxyToolCalls)
                    .temperature(temperature)
                    .topP(topP)
                    .maxTokens(model.getMaxTokens())
                    .build();
            AnthropicApi api = new AnthropicApi(apiInfo.getUrl(), apiInfo.getKey(), "2023-06-01", restClientBuilder.clone().defaultHeader("X-Reason-Model", isReasonModel.toString()).defaultHeader("Request-Id", requestId).defaultHeader("X-Trace-Id", traceId), cloneWebClientBuilder(), RetryUtils.DEFAULT_RESPONSE_ERROR_HANDLER);
            chatModel = new AnthropicChatModel(api, anthropicChatOptions, ToolCallingManager.builder().build(), RetryUtils.DEFAULT_RETRY_TEMPLATE, ObservationRegistry.NOOP);
        }

        Assert.notNull(chatModel, "chatModel must be non-null");
        ChatClient.Builder builder = ChatClient.builder(chatModel);
        if (functionCallbacks != null) {
            for (ToolCallback functionCallback : functionCallbacks) {
                builder.defaultTools(functionCallback);
            }
        }
        return builder.build();
    }

    private static String completeBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String v = extractVersionFromUrl(baseUrl);
        if (v == null) {
            if (!hasVersion(baseUrl)) {
                baseUrl = baseUrl + "/v1";
            }
        }
        return baseUrl;
    }

    private static String normalizeAnthropicBaseUrl(String baseUrl) {
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl;
    }

    public static String extractVersionFromUrl(String url) {
        // 定义正则表达式，匹配URL中的版本信息（如v1、v2、v3）
        String regex = "/(v\\d+)";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(url);

        if (matcher.find()) {
            return matcher.group(1); // 返回匹配到的版本信息
        } else {
            return null; // 如果未找到匹配项，返回null
        }
    }

    public static boolean hasVersion(String url) {
        String regex = "(/([^\\s\\.]*)((-v|_v|version|_ver|-ver)\\d+))";
        Pattern pattern = Pattern.compile(regex);
        Matcher matcher = pattern.matcher(url);

        if (matcher.find()) {
            return true;
        } else {
            return false;
        }
    }

    @PreDestroy
    private void destroy() {
        try {
            ex.shutdown();
        } catch (Exception e) {
            // ignore
        }
    }
}
