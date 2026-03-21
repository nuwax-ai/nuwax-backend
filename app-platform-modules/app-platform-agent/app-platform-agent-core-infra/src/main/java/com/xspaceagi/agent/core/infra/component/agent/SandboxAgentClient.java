package com.xspaceagi.agent.core.infra.component.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.util.DateUtils;
import com.xspaceagi.agent.core.adapter.dto.ChatMessageDto;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentComponentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.infra.component.agent.dto.AgentRequest;
import com.xspaceagi.agent.core.infra.component.model.dto.CallMessage;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentExecuteResult;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentExecutingDto;
import com.xspaceagi.agent.core.infra.component.model.strategy.WeightedRoundRobinStrategy;
import com.xspaceagi.agent.core.infra.rpc.MarketClientRpcService;
import com.xspaceagi.agent.core.infra.rpc.ModelApiProxyRpcService;
import com.xspaceagi.agent.core.infra.rpc.SandboxServerConfigService;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.agent.core.spec.constant.Prompts;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import com.xspaceagi.agent.core.spec.enums.ExecuteStatusEnum;
import com.xspaceagi.agent.core.spec.enums.MessageTypeEnum;
import com.xspaceagi.agent.core.spec.enums.ModelApiProtocolEnum;
import com.xspaceagi.agent.core.spec.utils.TikTokensUtil;
import com.xspaceagi.agent.core.spec.utils.UrlExtractUtil;
import com.xspaceagi.eco.market.sdk.reponse.ClientSecretResponse;
import com.xspaceagi.eco.market.sdk.request.ClientSecretRequest;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.enums.InstallTypeEnum;
import com.xspaceagi.modelproxy.sdk.service.dto.BackendModelDto;
import com.xspaceagi.modelproxy.sdk.service.dto.FrontendModelDto;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.exception.AgentException;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.MD5;
import com.xspaceagi.system.spec.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class SandboxAgentClient {

    private static final CharSequence CONTEXT_LIMIT_REACHED_MSG = "The model has reached its context window limit";

    static {
        // disable keep alive，暂不使用连接池
        System.setProperty("jdk.httpclient.keepalive.timeout", "0");
    }

    private static final JSONObject chromeMcp = JSON.parseObject("""
            {
              "source": "custom",
              "enabled": true,
              "command": "mcp-proxy",
              "args": [
                "convert",
                "http://127.0.0.1:18099",
                "--protocol",
                "stream"
              ],
              "env": {},
              "metadata": {
                "description": "Chrome DevTools MCP - 通过共享的 MCP Proxy 服务连接浏览器自动化",
                "note": "使用 mcp-proxy convert 连接容器启动时自动运行的共享 chrome-devtools-mcp 服务，多个 agent 复用同一个浏览器实例，加快启动速度",
                "proxy_url": "http://127.0.0.1:18099",
                "capabilities": [
                  "browser_automation",
                  "page_navigation",
                  "dom_manipulation",
                  "screenshot",
                  "chinese_input_method"
                ]
              }
            }
            """);

    /**
     * Pattern to extract the data content from SSE data field lines. Matches lines
     * starting with "data:" and captures the remaining content.
     */
    private static final Pattern EVENT_DATA_PATTERN = Pattern.compile("^data:(.+)$", Pattern.MULTILINE);

    /**
     * Pattern to extract the event ID from SSE id field lines. Matches lines starting
     * with "id:" and captures the ID value.
     */
    private static final Pattern EVENT_ID_PATTERN = Pattern.compile("^id:(.+)$", Pattern.MULTILINE);

    /**
     * Pattern to extract the event type from SSE event field lines. Matches lines
     * starting with "event:" and captures the event type.
     */
    private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile("^event:(.+)$", Pattern.MULTILINE);

    private static final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static final AtomicInteger apiIndex = new AtomicInteger(0);

    public WeightedRoundRobinStrategy weightedRoundRobinStrategy;

    private SandboxServerConfigService sandboxServerConfigService;

    private MarketClientRpcService marketClientRpcService;

    private ModelApiProxyRpcService modelApiProxyRpcService;

    private RedisUtil redisUtil;

    @Autowired
    public void setModelApiProxyRpcService(ModelApiProxyRpcService modelApiProxyRpcService) {
        this.modelApiProxyRpcService = modelApiProxyRpcService;
    }

    @Autowired
    public void setWeightedRoundRobinStrategy(WeightedRoundRobinStrategy weightedRoundRobinStrategy) {
        this.weightedRoundRobinStrategy = weightedRoundRobinStrategy;
    }

    @Autowired
    public void setSandboxServerConfigService(SandboxServerConfigService sandboxServerConfigService) {
        this.sandboxServerConfigService = sandboxServerConfigService;
    }

    @Autowired
    public void setMarketClientRpcService(MarketClientRpcService marketClientRpcService) {
        this.marketClientRpcService = marketClientRpcService;
    }

    @Autowired
    public void setRedisUtil(RedisUtil redisUtil) {
        this.redisUtil = redisUtil;
    }

    public Flux<CallMessage> chat(AgentContext agentContext) {
        // 远程SSE订阅
        AtomicReference<SseSubscription> sseSubscriptionAtomicReference = new AtomicReference<>();
        Long startTime = System.currentTimeMillis();
        StringBuilder finalText = new StringBuilder();
        Flux<CallMessage> sseFlux = Flux.create(sink -> {
            // 获取沙箱服务
            SandboxServerConfig.SandboxServer sandboxServer = sandboxServerConfigService.selectServer(agentContext.getTenantConfig(), agentContext.getUserId(), agentContext.getConversation().getSandboxServerId());
            // 启动沙箱服务
            if (sandboxServer.getScope() == SandboxScopeEnum.USER) {
                checkAgentIfAlive(agentContext, sandboxServer)
                        .doOnSuccess(res0 -> {
                            //agent未启动，追加提示词
                            executeTask(agentContext, sandboxServer, sink, sseSubscriptionAtomicReference, finalText, res0 != null && !res0);
                        })
                        .onErrorResume(throwable -> Mono.just(false))
                        .subscribe();
            } else {
                startSandbox(agentContext, sandboxServer)
                        .doOnSuccess(res -> {
                            if (CollectionUtils.isEmpty(agentContext.getContextMessages())) {
                                executeTask(agentContext, sandboxServer, sink, sseSubscriptionAtomicReference, finalText, false);
                                return;
                            }
                            checkAgentIfAlive(agentContext, sandboxServer)
                                    .doOnSuccess(res0 -> {
                                        //agent未启动，追加提示词
                                        executeTask(agentContext, sandboxServer, sink, sseSubscriptionAtomicReference, finalText, res0 != null && !res0);
                                    })
                                    .onErrorResume(throwable -> Mono.just(false))
                                    .subscribe();
                        })
                        .onErrorResume(Mono::error).doOnError(sink::error)
                        .subscribe();
            }

        });
        sseFlux = sseFlux.doOnCancel(() -> {
                    log.info("cancel subscribe");
                    chatCancel(agentContext.getConversationId());
                    SseSubscription sseSubscription = sseSubscriptionAtomicReference.get();
                    if (sseSubscription != null) {
                        sseSubscription.cancel();
                    }
                    buildAndSetModelResult(agentContext, finalText.toString(), startTime);
                }).onErrorResume(throwable -> {
                    if (throwable instanceof CompletionException && throwable.getCause() instanceof ConnectException) {
                        return Mono.error(new BizException("智能体电脑连接失败"));
                    }
                    return Mono.error(throwable);
                })
                .doOnComplete(() -> buildAndSetModelResult(agentContext, finalText.toString(), startTime))
                .doOnError(throwable -> buildAndSetModelResult(agentContext, finalText.toString(), startTime));
        return sseFlux;
    }

    private static Mono<Boolean> checkAgentIfAlive(AgentContext agentContext, SandboxServerConfig.SandboxServer sandboxServer) {
        return Mono.create(sink -> {
            //查询agent启动状态/computer/agent/status
            log.info("查询会话Agent状态是否启动， userId {}, conversationId {}", agentContext.getUserId(), agentContext.getConversationId());
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(sandboxServer.getServerAgentUrl() + "/computer/agent/status"))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(Map.of("user_id", agentContext.getUserId().toString(), "project_id", agentContext.getConversationId()))))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
                String body = response.body();
                log.info("查询Agent状态结果, userId {}, conversationId {}, body {}", agentContext.getUserId(), agentContext.getConversationId(), body);
                if (JSON.isValidObject(body)) {
                    JSONObject data = JSON.parseObject(body).getJSONObject("data");
                    if (data == null) {
                        sink.success(null);
                        return;
                    }
                    if (data.getBoolean("is_alive") != null && data.getBoolean("is_alive")) {
                        sink.success(true);
                        return;
                    }
                    sink.success(false);
                } else {
                    sink.success(null);
                }
            }).exceptionally(throwable -> {
                log.error("查询Agent状态异常", throwable);
                sink.success(null);
                return null;
            });
        });
    }

    private Mono<Boolean> startSandbox(AgentContext agentContext, SandboxServerConfig.SandboxServer sandboxServer) {
        //redis加锁，避免同一个用户并发启动，最多等待60秒
        final String lockKey = "sandbox_lock_" + agentContext.getUserId();
        try {
            redisUtil.lock(lockKey, 60000);
        } catch (Exception e) {
            // ignore
            log.error("获取锁异常", e);
        }
        Mono<Boolean> mono = Mono.create(sink -> {
            //查询sandbox启动状态
            log.info("查询sandbox状态， userId {}, conversationId {}", agentContext.getUserId(), agentContext.getConversationId());
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(sandboxServer.getServerAgentUrl() + "/computer/pod/status?user_id=" + agentContext.getUserId() + "&project_id=" + agentContext.getConversationId()))
                    .header("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey())
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();
            CompletableFuture<HttpResponse<String>> httpResponseCompletableFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            httpResponseCompletableFuture.thenAccept(httpResponse -> {
                        String body = httpResponse.body();
                        log.info("查询sandbox状态结果， userId {}, conversationId {}, body {}", agentContext.getUserId(), agentContext.getConversationId(), body);
                        if (!JSON.isValid(body)) {
                            sink.error(new IllegalArgumentException("sandbox状态查询失败"));
                            return;
                        }
                        JSONObject jsonObject = JSON.parseObject(body);
                        if (!jsonObject.containsKey("code") || !"0000".equals(jsonObject.getString("code"))) {
                            sink.error(new BizException(jsonObject.getString("message")));
                            return;
                        }
                        JSONObject data = jsonObject.getJSONObject("data");
                        if (data == null) {
                            sink.error(new BizException("状态查询返回数据结构异常"));
                            return;
                        }
                        if (data.getBoolean("alive") != null && data.getBoolean("alive")) {
                            sink.success(true);
                            return;
                        }
                        ComponentExecutingDto sandboxStartingDto = buildSandboxStartingDto();
                        if (agentContext.getComponentExecutingConsumer() != null) {
                            agentContext.getComponentExecutingConsumer().accept(sandboxStartingDto);
                        }

                        int perUserCpuCores = sandboxServer.getPerUserCpuCores();
                        double perUserMemoryGB = sandboxServer.getPerUserMemoryGB();
                        UserDataPermissionDto userDataPermission = agentContext.getUserDataPermission();
                        if (userDataPermission != null) {
                            //上限由管理员自己决定
                            if (userDataPermission.getAgentComputerMemoryGb() != null && userDataPermission.getAgentComputerMemoryGb() > 0) {
                                perUserMemoryGB = userDataPermission.getAgentComputerMemoryGb();
                            }
                            if (userDataPermission.getAgentComputerCpuCores() != null && userDataPermission.getAgentComputerCpuCores() > 0) {
                                perUserCpuCores = userDataPermission.getAgentComputerCpuCores();
                            }
                        }

                        HttpRequest startRequest = HttpRequest.newBuilder().uri(URI.create(sandboxServer.getServerAgentUrl() + "/computer/pod/ensure"))
                                .header("Content-Type", "application/json")
                                .header("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey())
                                .timeout(Duration.ofSeconds(180))
                                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(Map.of(
                                        "user_id", agentContext.getUserId().toString(),
                                        "project_id", agentContext.getConversationId(),
                                        "resource_limits", Map.of("cpu", perUserCpuCores, "memory", perUserMemoryGB * 1024 * 1024 * 1024, "swap", perUserMemoryGB * 1024 * 1024 * 1024 * 2)
                                )))).build();
                        log.info("sandbox开始启动， userId {}, conversationId {}", agentContext.getUserId(), agentContext.getConversationId());
                        CompletableFuture<HttpResponse<String>> startHttpResponseCompletableFuture = httpClient.sendAsync(startRequest, HttpResponse.BodyHandlers.ofString());
                        startHttpResponseCompletableFuture.thenAccept(startHttpResponse -> {
                                    String startBody = startHttpResponse.body();
                                    log.info("sandbox启动结果， userId {}, conversationId {}, body {}", agentContext.getUserId(), agentContext.getConversationId(), startBody);
                                    sandboxStartingDto.getResult().setEndTime(System.currentTimeMillis());
                                    if (!JSON.isValid(startBody)) {
                                        if (agentContext.getComponentExecutingConsumer() != null) {
                                            sandboxStartingDto.setStatus(ExecuteStatusEnum.FAILED);
                                            sandboxStartingDto.getResult().setError(startBody);
                                            agentContext.getComponentExecutingConsumer().accept(sandboxStartingDto);
                                        }
                                        sink.error(new BizException("智能体电脑启动失败，请重试"));
                                        return;
                                    }
                                    JSONObject startJsonObject = JSON.parseObject(startBody);
                                    if (!startJsonObject.containsKey("code") || !"0000".equals(startJsonObject.getString("code"))) {
                                        if (agentContext.getComponentExecutingConsumer() != null) {
                                            sandboxStartingDto.setStatus(ExecuteStatusEnum.FAILED);
                                            sandboxStartingDto.getResult().setError(startJsonObject.getString("message"));
                                            agentContext.getComponentExecutingConsumer().accept(sandboxStartingDto);
                                        }
                                        sink.error(new BizException("智能体电脑启动失败，请重试"));
                                        return;
                                    }
                                    if (agentContext.getComponentExecutingConsumer() != null) {
                                        sandboxStartingDto.setStatus(ExecuteStatusEnum.FINISHED);
                                        agentContext.getComponentExecutingConsumer().accept(sandboxStartingDto);
                                    }
                                    sink.success(true);
                                })
                                .orTimeout(60, TimeUnit.SECONDS)
                                .exceptionally(throwable -> {
                                    if (agentContext.getComponentExecutingConsumer() != null) {
                                        sandboxStartingDto.setStatus(ExecuteStatusEnum.FAILED);
                                        sandboxStartingDto.getResult().setError(throwable.getMessage());
                                        sandboxStartingDto.getResult().setEndTime(System.currentTimeMillis());
                                        agentContext.getComponentExecutingConsumer().accept(sandboxStartingDto);
                                    }
                                    sink.error(throwable);
                                    return null;
                                });
                    })
                    .orTimeout(10, TimeUnit.SECONDS)
                    .exceptionally(throwable -> {
                        sink.error(throwable);
                        return null;
                    });
        });
        mono = mono.doOnSuccess(result -> redisUtil.unlock(lockKey)).doOnError(throwable -> redisUtil.unlock(lockKey));
        return mono;
    }

    /**
     * 执行任务
     */
    private void executeTask(AgentContext agentContext, SandboxServerConfig.SandboxServer sandboxServer, FluxSink<CallMessage> sink,
                             AtomicReference<SseSubscription> sseSubscriptionAtomicReference, StringBuilder finalText, boolean appendContextPrompt) {
        ModelConfigDto modelConfig = (ModelConfigDto) agentContext.getAgentConfig().getModelComponentConfig().getTargetConfig();

        List<ModelConfigDto.ApiInfo> apiInfoList = new ArrayList<>();
        modelConfig.getApiInfoList().forEach(apiInfo -> {
            for (int i = 0; i < apiInfo.getWeight(); i++) {
                apiInfoList.add(apiInfo);
            }
        });
        ModelConfigDto.ApiInfo apiInfo;
        try {
            long conversationId = Long.parseLong(agentContext.getConversationId());
            apiInfo = apiInfoList.get((int) (conversationId % apiInfoList.size()));
            if (modelConfig.getApiProtocol() == ModelApiProtocolEnum.Anthropic && apiInfoList.size() > 1) {
                // 模型出现可用性异常，则从列表中移除 TokenLogService中记录是否可用
                Object val = redisUtil.get("stop_account_" + MD5.MD5Encode(apiInfo.getKey()));
                while (val != null) {
                    apiInfoList.remove(apiInfo);
                    if (apiInfoList.isEmpty()) {
                        String error = "当前过于火爆，请稍后再试";
                        sink.next(buildChatMessage(agentContext, "\n\n```\n" + error + "\n```\n\n"));
                        sink.error(new AgentException("0001", error));
                        return;
                    }
                    apiInfo = apiInfoList.get((int) (conversationId % apiInfoList.size()));
                    val = redisUtil.get("stop_account_" + MD5.MD5Encode(apiInfo.getKey()));
                }
            }
        } catch (Exception e) {
            apiInfo = apiInfoList.get(apiIndex.getAndIncrement() % apiInfoList.size());
        }

        if (weightedRoundRobinStrategy != null) {
            weightedRoundRobinStrategy.replaceKey(modelConfig, apiInfo);
        }
        apiInfo.setUrl(apiInfo.getUrl().replace("SESSION_ID", agentContext.getConversationId()));
        StringBuilder contextPromptBuilder = new StringBuilder();
        if (appendContextPrompt) {
            buildContextPrompt(agentContext, contextPromptBuilder);
        }
        if (CollectionUtils.isNotEmpty(agentContext.getAutoToolCallMessages())) {
            buildAutoToolCallMessages(agentContext, contextPromptBuilder);
        }
        ConversationDto conversation = agentContext.getConversation();
        String currentTime = DateUtils.format(new Date(), "yyyy-MM-dd HH:mm:ss");
        String currentTimePrompt = Prompts.CURRENT_TIME.replace("{time}", currentTime);
        String systemPrompt = currentTimePrompt + "\n" + Prompts.ANTI_CLAUDE_SYSTEM_PROMPT + "\n" + agentContext.getAgentConfig().getSystemPrompt() + "\n" + Prompts.DEPENDENCY_INSTALLATION_PROMPT + "\n" + Prompts.TASK_AGENT_OUTPUT_PROMPT;
        String userPrompt = contextPromptBuilder + agentContext.getMessage();
        // 全局模型走代理模式，为用户生成独立的key
        if (modelConfig.getScope() == ModelConfig.ModelScopeEnum.Tenant) {
            BackendModelDto backendModelDto = new BackendModelDto();
            backendModelDto.setBaseUrl(apiInfo.getUrl());
            backendModelDto.setApiKey(apiInfo.getKey());
            backendModelDto.setModelName(modelConfig.getModel());
            backendModelDto.setProtocol(modelConfig.getApiProtocol().name());
            backendModelDto.setScope(modelConfig.getScope().name());
            backendModelDto.setModelId(modelConfig.getId());
            backendModelDto.setUserName(agentContext.getUserName());
            backendModelDto.setConversationId(agentContext.getConversationId());
            backendModelDto.setRequestId(agentContext.getRequestId());
            String siteUrl = agentContext.getTenantConfig() != null ? agentContext.getTenantConfig().getSiteUrl() : "";
            FrontendModelDto frontendModelDto = modelApiProxyRpcService.generateUserFrontendModelConfig(agentContext.getUser().getTenantId(), agentContext.getUser().getId()
                    , agentContext.getAgentConfig().getId(), backendModelDto, siteUrl);
            apiInfo = new ModelConfigDto.ApiInfo();
            apiInfo.setKey(frontendModelDto.getApiKey());
            apiInfo.setUrl(frontendModelDto.getBaseUrl());
        }

        AgentRequest agentRequest = AgentRequest.builder().user_id(agentContext.getUserId().toString()).project_id(agentContext.getConversationId()).prompt(userPrompt).original_user_prompt(agentContext.getOriginalMessage()).open_long_memory(false).request_id(agentContext.getRequestId())
                .attachments(null).data_source_attachments(null).model_provider(null).system_prompt(systemPrompt).user_prompt(null).model_provider(AgentRequest.ModelProvider.builder().id(modelConfig.getId().toString()).default_model(modelConfig.getModel()).name(modelConfig.getName()).requires_openai_auth(true).base_url(apiInfo.getUrl().replace("SESSION_ID", agentContext.getConversationId()))// 官方代理接口通过这个id做上下文保持在一个后端key
                        .api_key(apiInfo.getKey())
                        .api_protocol(modelConfig.getApiProtocol().name().toLowerCase()).build())
                .agent_config(AgentRequest.AgentConfig.builder().agent_server(buildAgentServer(agentContext, sandboxServer, modelConfig)).context_servers(buildContextServers(agentContext)).resource_limits(AgentRequest.ResourceLimits.builder().cpu_limit(sandboxServer.getPerUserCpuCores()).memory_limit((long) (sandboxServer.getPerUserMemoryGB() * 1024 * 1024 * 1024)).swap_limit((long) (sandboxServer.getPerUserMemoryGB() * 1024 * 1024 * 1024) * 2).build()).build()).build();
        String requestBody = JSON.toJSONString(agentRequest);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(sandboxServer.getServerAgentUrl() + "/computer/chat")).header("Content-Type", "application/json")
                .header("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey())
                .timeout(Duration.ofSeconds(180))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8)).build();
        log.info("发起会话请求， agentId {}, conversationId {}, requestBody {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), requestBody);
        try {
            CompletableFuture<HttpResponse<String>> httpResponseCompletableFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            httpResponseCompletableFuture.orTimeout(180, TimeUnit.SECONDS).thenAccept(httpResponse -> {
                int status = httpResponse.statusCode();
                if (status != 200 && status != 201 && status != 202 && status != 206) {
                    sink.error(new RuntimeException("Failed to connect to SSE stream. Unexpected status code: " + status));
                    log.error("发起会话请求失败， agentId {}, conversationId {}, status {}, body {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), status, httpResponse.body());
                    return;
                }
                String body = httpResponse.body();
                log.info("发起会话请求返回， agentId {}, conversationId {}, body {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), body);
                JSONObject jsonObject = JSON.parseObject(body);
                if (!jsonObject.containsKey("code") || !"0000".equals(jsonObject.getString("code"))) {
                    if (jsonObject.containsKey("error")) {
                        boolean isInternalError = false;
                        String error = jsonObject.getString("error");
                        if (error.equals("Internal error")) {
                            error = "智能体执行异常，请重试，若持续失败，请重启智能体或新开会话";
                            isInternalError = true;
                        }
                        sink.next(buildChatMessage(agentContext, "\n\n```\n" + error + "\n```\n\n"));
                        sink.error(new AgentException(jsonObject.getString("code"), error));
                        if (isInternalError) {
                            agentStop(agentContext.getConversationId());
                        }
                    } else if (jsonObject.containsKey("message")) {
                        sink.next(buildChatMessage(agentContext, "\n\n```\n" + jsonObject.getString("message") + "\n```\n\n"));
                        sink.error(new AgentException(jsonObject.getString("code"), jsonObject.getString("message")));
                    } else {
                        sink.next(buildChatMessage(agentContext, "\n\n```\nFailed to create agent session\n```\n\n"));
                        sink.error(new AgentException("0001", "Failed to create agent session."));
                    }
                    return;
                }
                String sessionId0 = jsonObject.getJSONObject("data").getString("session_id");
                if (sessionId0 == null) {
                    sink.error(new AgentException("0001", "session_id is null"));
                    return;
                }
                if (conversation != null && agentContext.getSandboxSessionCreatedConsumer() != null) {
                    conversation.setSandboxSessionId(sessionId0);
                    conversation.setSandboxServerId(sandboxServer.getServerId());
                    agentContext.getSandboxSessionCreatedConsumer().accept(conversation);
                }
                Map<String, ComponentExecutingDto> componentExecutingDtoMap = new LinkedHashMap<>();
                Set<ComponentExecutingDto.SubEventTypeEnum> subEventTypeEnumSet = new HashSet<>();
                AtomicBoolean compeleted = new AtomicBoolean(false);
                SseSubscription subscription = subscribe(sandboxServer, sandboxServer.getServerAgentUrl() + "/computer/progress/" + sessionId0, new SseEventHandler() {
                    @Override
                    public void onEvent(SseEvent event) {
                        log.debug("sse event, cid {}, type: {}, data: {}", agentContext.getConversationId(), event.type, event.data);
                        if (agentContext.getIfInterrupted().get()) {
                            chatCancel(agentContext.getConversationId());
                            return;
                        }
                        String eventData = event.data;
                        if (eventData != null) {
                            eventData = eventData.replace(".claude/", "").replace(".claude", "").replace("claude", "").replace("Claude", "");
                        }
                        JSONObject jsonObject = JSON.parseObject(eventData);
                        if (jsonObject == null) {
                            return;
                        }
                        JSONObject data = jsonObject.getJSONObject("data");
                        if (event.type.equals("end_turn") || "end_turn".equals(jsonObject.getString("subType"))) {
                            checkUnfinishedToolCall(agentContext, componentExecutingDtoMap);
                            checkAndCompleteFinalResultFile(agentContext, componentExecutingDtoMap, finalText, sink);
                            if (sseSubscriptionAtomicReference.get() != null) {
                                sseSubscriptionAtomicReference.get().cancel();
                            }

                            String reason = "EndTurn";
                            if (data != null && data.getString("reason") != null) {
                                reason = data.getString("reason");
                            }
                            sink.next(buildChatFinishedMessage(agentContext, reason));
                            compeleted.set(true);
                            sink.complete();
                            return;
                        }
                        if ("sessionPromptEnd".equals(jsonObject.getString("messageType"))) {
                            checkUnfinishedToolCall(agentContext, componentExecutingDtoMap);
                            checkAndCompleteFinalResultFile(agentContext, componentExecutingDtoMap, finalText, sink);
                            if (sseSubscriptionAtomicReference.get() != null) {
                                sseSubscriptionAtomicReference.get().cancel();
                            }
                            if (data == null) {
                                compeleted.set(true);
                                sink.complete();
                                return;
                            }
                            if ("error".equals(event.type) || "error".equals(jsonObject.getString("sub_type")) || "error".equals(jsonObject.getString("subType"))) {
                                CallMessage callMessage = buildChatFinishedMessage(agentContext, "ERROR");
                                String message = data.getString("message");
                                if (message == null && data.containsKey("description")) {
                                    message = data.getString("description");
                                    if (JSON.isValidObject(message)) {
                                        message = JSON.parseObject(message).getString("message");
                                        if (message == null) {
                                            message = data.getString("description");
                                        }
                                    }
                                }
                                if (message == null) {
                                    message = "智能体执行异常，请重试，若持续失败，请重启智能体或新开会话";
                                }
                                boolean isInternalError = false;
                                if (message.contains("Internal error")) {
                                    message = "智能体执行异常，请重试，若持续失败，请重启智能体或新开会话";
                                    isInternalError = true;
                                }
                                callMessage.setText("\n```\n" + message + "\n```");
                                if (message.contains(CONTEXT_LIMIT_REACHED_MSG) || message.contains("model_context_window_exceeded")) {
                                    callMessage.setText("\n```\n模型已达到其上下文窗口限制，请重新发送指令，或开启新会话继续。\n```");
                                    agentStop(agentContext.getConversationId());
                                }
                                if (isInternalError) {
                                    callMessage.setText("\n```\n模型执行异常，请重试，若持续失败，请重启智能体或新开会话\n```");
                                    agentStop(agentContext.getConversationId());
                                }
                                sink.next(callMessage);
                                sink.error(new AgentException("ERROR", message));
                            } else if ("cancelled".equals(event.type)) {
                                sink.error(new AgentException("0001", data.containsKey("error_message") ? data.getString("error_message") : "cancelled"));
                            } else {
                                compeleted.set(true);
                                sink.complete();
                            }
                            return;
                        }
                        if (!JSON.isValid(event.data)) {
                            return;
                        }

                        if ("agent_message_chunk".equals(jsonObject.getString("subType"))) {
                            CallMessage chatMessageDto = new CallMessage();
                            chatMessageDto.setId(data.getString("request_id"));
                            chatMessageDto.setType(MessageTypeEnum.CHAT);
                            chatMessageDto.setRole(ChatMessageDto.Role.ASSISTANT);
                            JSONObject content = data.getJSONObject("content");
                            if (content != null) {
                                chatMessageDto.setText(content.getString("text"));
                                finalText.append(chatMessageDto.getText());
                            }
                            sink.next(chatMessageDto);
                        }

                        if ("agent_thought_chunk".equals(jsonObject.getString("subType"))) {
                            CallMessage chatMessageDto = new CallMessage();
                            chatMessageDto.setId(data.getString("request_id"));
                            chatMessageDto.setType(MessageTypeEnum.THINK);
                            chatMessageDto.setRole(ChatMessageDto.Role.ASSISTANT);
                            JSONObject content = data.getJSONObject("content");
                            if (content != null) {
                                chatMessageDto.setText(content.getString("text"));
                                finalText.append(chatMessageDto.getText());
                            }
                            sink.next(chatMessageDto);
                        }

                        if ((event.type.equals("tool_call") || "tool_call".equals(jsonObject.getString("subType")))
                                || ((event.type.equals("tool_call_update") || "tool_call_update".equals(data.getString("subType"))) && ("in_progress".equals(data.getString("status")) || "completed".equals(data.getString("status")) || "failed".equals(data.getString("status"))))) {
                            Consumer<ComponentExecutingDto> componentExecutingConsumer = agentContext.getComponentExecutingConsumer();
                            if (componentExecutingConsumer != null && data.getString("toolCallId") != null) {
                                ComponentExecutingDto componentExecutingDto = buildComponentExecutingDto(data, componentExecutingDtoMap);
                                componentExecutingConsumer.accept(componentExecutingDto);
                                // 添加桌面打开事件
                                if (agentContext.getAgentConfig().getHideDesktop() == null || agentContext.getAgentConfig().getHideDesktop() != 1) {
                                    if (data.getString("title") != null && (data.getString("title").contains("mcp__chrome") || data.getString("title").contains("chrome-tools") || data.getString("title").contains("navigate_page")) && !subEventTypeEnumSet.contains(ComponentExecutingDto.SubEventTypeEnum.OPEN_DESKTOP)) {
                                        subEventTypeEnumSet.add(ComponentExecutingDto.SubEventTypeEnum.OPEN_DESKTOP);
                                        componentExecutingDto = buildExecutingEvent("打开桌面", ComponentExecutingDto.SubEventTypeEnum.OPEN_DESKTOP, null);
                                        componentExecutingConsumer.accept(componentExecutingDto);
                                    }
                                }
                            }
                        }
                        if (event.type.equals("plan") || "plan".equals(jsonObject.getString("subType"))) {
                            Consumer<ComponentExecutingDto> componentExecutingConsumer = agentContext.getComponentExecutingConsumer();
                            if (componentExecutingConsumer != null) {
                                ComponentExecutingDto componentExecutingDto = buildComponentExecutingPlan(data);
                                if (componentExecutingDto == null) {
                                    return;
                                }
                                componentExecutingConsumer.accept(componentExecutingDto);
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        sink.error(error);
                        log.error("subscribe error", error);
                    }

                    @Override
                    public void onComplete() {
                        if (!compeleted.get()) {
                            sink.complete();
                        }
                    }
                });
                sseSubscriptionAtomicReference.set(subscription);
            }).exceptionally(throwable -> {
                log.error("subscribe error", throwable);
                if (throwable.getCause() != null && throwable.getCause() instanceof TimeoutException) {
                    CallMessage callMessage = buildChatFinishedMessage(agentContext, "error");
                    callMessage.setText("\n```\n智能体初始化超时，请重试（过多的MCP可能导致超时）。如果持续失败请重启智能体电脑（点击PC端右上图标展开后，在“...”里点击重启智能体电脑）\n```");
                    sink.next(callMessage);
                    sink.error(new TimeoutException("智能体初始化超时，请重试（过多的MCP可能导致超时）。如果持续失败请重启智能体电脑（点击PC端右上图标展开后，在“...”里点击重启智能体电脑）"));
                    return null;
                }
                sink.error(throwable);
                return null;
            });
        } catch (Throwable e) {
            sink.error(e);
        }
    }

    private static AgentRequest.AgentServer buildAgentServer(AgentContext agentContext, SandboxServerConfig.SandboxServer sandboxServer, ModelConfigDto modelConfig) {
        Map<String, String> env = new HashMap<>();
        //追加环境变量
        TenantConfigDto tenantConfig = agentContext.getTenantConfig();
        String siteUrl = tenantConfig.getSiteUrl();
        siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        env.put("PLATFORM_BASE_URL", siteUrl);
        env.put("SANDBOX_ID", sandboxServer.getServerId());
        env.put("SYS_USER_ID", String.valueOf(agentContext.getUserId()));
        if (agentContext.getVariableParams().containsKey("SANDBOX_ACCESS_KEY")) {
            env.put("SANDBOX_ACCESS_KEY", agentContext.getVariableParams().get("SANDBOX_ACCESS_KEY").toString());
        }
        // 不支持claudecode的模型直接使用opencode
        if (!isClaudeCodeSupport(modelConfig)) {
            env.put("OPENCODE_LOG_DIR", "/app/container-logs");
            env.put("OPENCODE_MAX_TOKENS", String.valueOf(modelConfig.getMaxTokens()));
            env.put("OPENCODE_MAX_CONTEXT_TOKENS", String.valueOf(modelConfig.getMaxContextTokens()));
            if (modelConfig.getApiProtocol() == ModelApiProtocolEnum.OpenAI) {
                env.put("OPENAI_API_KEY", "{MODEL_PROVIDER_API_KEY}");
                env.put("OPENAI_BASE_URL", "{MODEL_PROVIDER_BASE_URL}");
                env.put("OPENCODE_MODEL", "openai-compatible/" + modelConfig.getModel());
            } else if (modelConfig.getApiProtocol() == ModelApiProtocolEnum.Anthropic) {
                env.put("ANTHROPIC_API_KEY", "{MODEL_PROVIDER_API_KEY}");
                // 用户个人电脑运行时增加ANTHROPIC_AUTH_TOKEN
                if (sandboxServer.getScope() == SandboxScopeEnum.USER) {
                    env.put("ANTHROPIC_AUTH_TOKEN", "{MODEL_PROVIDER_API_KEY}");
                }
                env.put("ANTHROPIC_BASE_URL", "{MODEL_PROVIDER_BASE_URL}");
                env.put("OPENCODE_MODEL", "anthropic-compatible/" + modelConfig.getModel());
            }
            return AgentRequest.AgentServer.builder().agent_id("nuwaxcode").command("nuwaxcode").args(List.of("acp")).env(env).build();
        } else {
            env.put("ANTHROPIC_API_KEY", "{MODEL_PROVIDER_API_KEY}");
            // 用户个人电脑运行时增加ANTHROPIC_AUTH_TOKEN
            if (sandboxServer.getScope() == SandboxScopeEnum.USER) {
                env.put("ANTHROPIC_AUTH_TOKEN", "{MODEL_PROVIDER_API_KEY}");
            }
            env.put("ANTHROPIC_BASE_URL", "{MODEL_PROVIDER_BASE_URL}");
            env.put("ANTHROPIC_MODEL", modelConfig.getModel());
            env.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
            if (modelConfig.getIsReasonModel() != null && modelConfig.getIsReasonModel() == 1) {
                int maxThinkingTokens = modelConfig.getMaxTokens() == null ? 2048 : modelConfig.getMaxTokens() / 2;
                env.put("MAX_THINKING_TOKENS", maxThinkingTokens > 4096 ? "4096" : Integer.toString(maxThinkingTokens));//最大值暂时支持4096
            }
        }
        return AgentRequest.AgentServer.builder().agent_id("claude-code-acp-ts").command("claude-code-acp-ts").args(List.of()).env(env).build();
    }

    private static boolean isClaudeCodeSupport(ModelConfigDto modelConfig) {
        if (modelConfig.getDescription() != null && modelConfig.getDescription().contains("use nuwax_cli")) {
            return false;
        }
        return modelConfig.getApiProtocol() == ModelApiProtocolEnum.Anthropic;
    }

    private static void buildContextPrompt(AgentContext agentContext, StringBuilder contextPromptBuilder) {
        if (agentContext.getConversation().getSummary() != null) {
            contextPromptBuilder.append("<context-summary>\n");
            contextPromptBuilder.append(agentContext.getConversation().getSummary()).append("\n</context-summary>\n");
        }
        if (CollectionUtils.isNotEmpty(agentContext.getContextMessages())) {
            contextPromptBuilder.append("<context-message>\n");
            agentContext.getContextMessages().forEach(message -> {
                String text = message.getText();
                if (message.getMessageType() == MessageType.USER) {
                    text = message.getText().replaceAll("<user-prompt>[\\s\\S]*?</user-prompt>", "").trim();
                    if (text.contains("<user-message>") && text.contains("</user-message>")) {
                        text = text.replace("<user-message>", "").replace("</user-message>", "").trim();
                    }
                }
                contextPromptBuilder.append(message.getMessageType()).append(": ").append(text).append("\n");
            });
            contextPromptBuilder.append("\n</context-message>\n");
        }
        contextPromptBuilder.append("<system-reminder>");
        contextPromptBuilder.append("开始任务之前请先阅读工作目录");
        contextPromptBuilder.append("</system-reminder>\n");
    }

    private static void buildAutoToolCallMessages(AgentContext agentContext, StringBuilder contextPromptBuilder) {
        contextPromptBuilder.append("<reference-information>\n");
        agentContext.getAutoToolCallMessages().forEach(message -> {
            if (message.getMessageType() == MessageType.USER) {
                contextPromptBuilder.append(message.getText()).append("\n\n");
            }
        });
        contextPromptBuilder.append("\n</reference-information>\n");
    }

    private static void checkAndCompleteFinalResultFile(AgentContext agentContext, Map<String, ComponentExecutingDto> componentExecutingDtoMap, StringBuilder finalText, FluxSink<CallMessage> sink) {
        ComponentExecutingDto finalComponentExecutingDto = null;
        for (String toolCallId : componentExecutingDtoMap.keySet()) {
            ComponentExecutingDto componentExecutingDto = componentExecutingDtoMap.get(toolCallId);
            if (componentExecutingDto.getResult().getKind() != null && "edit".equals(componentExecutingDto.getResult().getKind())
                    && CollectionUtils.isNotEmpty(componentExecutingDto.getResult().getLocations())) {
                finalComponentExecutingDto = componentExecutingDto;
            }
        }
        if (finalComponentExecutingDto == null) {
            return;
        }
        // 检查finalText中是否包含 <task-result> 标签
        String finalTextString = finalText.toString();
        if (finalTextString.contains("<task-result>") && finalTextString.contains("</task-result>")) {
            // 已经有结果文件输出，不再追加
            return;
        }
        String resultFileText = """
                <task-result>
                    <description>{description}</description>
                    <file>{file}</file>
                </task-result>
                """;
        StringBuilder text = new StringBuilder();
        finalComponentExecutingDto.getResult().getLocations().forEach(location -> {
            if (location instanceof Map<?, ?>) {
                String path = ((Map<?, ?>) location).get("path").toString();
                if (path != null && path.contains(agentContext.getConversationId())) {
                    text.append(resultFileText.replace("{description}", path).replace("{file}", path));
                }
            }
        });
        CallMessage chatMessageDto = new CallMessage();
        chatMessageDto.setId(agentContext.getRequestId());
        chatMessageDto.setType(MessageTypeEnum.CHAT);
        chatMessageDto.setRole(ChatMessageDto.Role.ASSISTANT);
        chatMessageDto.setText(text.toString());
        sink.next(chatMessageDto);
    }

    private static void checkUnfinishedToolCall(AgentContext agentContext, Map<String, ComponentExecutingDto> componentExecutingDtoMap) {
        if (agentContext.getComponentExecutingConsumer() == null) {
            return;
        }
        componentExecutingDtoMap.forEach((toolCallId, componentExecutingDto) -> {
            if (componentExecutingDto.getStatus() == ExecuteStatusEnum.EXECUTING) {
                componentExecutingDto.setStatus(ExecuteStatusEnum.FINISHED);
                componentExecutingDto.getResult().setSuccess(true);
                componentExecutingDto.getResult().setEndTime(System.currentTimeMillis());
                agentContext.getComponentExecutingConsumer().accept(componentExecutingDto);
            }
        });
    }


    private static ComponentExecutingDto buildSandboxStartingDto() {
        ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
        componentExecuteResult.setExecuteId(UUID.randomUUID().toString().replace("-", ""));
        componentExecuteResult.setStartTime(System.currentTimeMillis());
        componentExecuteResult.setId(-1L);
        componentExecuteResult.setName("智能体电脑启动");
        componentExecuteResult.setType(ComponentTypeEnum.SandboxStart);
        ComponentExecutingDto componentExecutingDto = new ComponentExecutingDto();
        componentExecutingDto.setTargetId(-1L);
        componentExecutingDto.setName(componentExecuteResult.getName());
        componentExecutingDto.setType(ComponentTypeEnum.SandboxStart);
        componentExecutingDto.setStatus(ExecuteStatusEnum.EXECUTING);
        componentExecutingDto.setResult(componentExecuteResult);
        return componentExecutingDto;
    }

    private static ComponentExecutingDto buildComponentExecutingPlan(JSONObject data) {
        JSONArray entries = data.getJSONArray("entries");
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        //过滤出已完成的计划列表;
        List<Object> completedEntries = entries.stream().filter(entry -> {
            if (entry instanceof JSONObject) {
                String status = ((JSONObject) entry).getString("status");
                return "completed".equals(status);
            }
            return false;
        }).toList();
        String title = "执行计划（" + completedEntries.size() + "/" + entries.size() + "已完成）";
        ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
        componentExecuteResult.setExecuteId(UUID.randomUUID().toString().replace("-", ""));
        componentExecuteResult.setStartTime(System.currentTimeMillis());
        componentExecuteResult.setId(-1L);
        componentExecuteResult.setName(title);
        componentExecuteResult.setType(ComponentTypeEnum.Plan);
        componentExecuteResult.setData(entries);
        ComponentExecutingDto componentExecutingDto = new ComponentExecutingDto();
        componentExecutingDto.setTargetId(-1L);
        componentExecutingDto.setName(title);
        componentExecutingDto.setType(ComponentTypeEnum.Plan);
        componentExecutingDto.setStatus(ExecuteStatusEnum.FINISHED);
        componentExecutingDto.setResult(componentExecuteResult);
        return componentExecutingDto;
    }

    private Map<String, JSONObject> buildContextServers(AgentContext agentContext) {
        try {
            return buildContextServers0(agentContext);
        } catch (Exception e) {
            // 忽略
            log.error("buildContextServers error", e);
        }
        Map<String, JSONObject> contextServers = new HashMap<>();
        //添加chrome-tools
        contextServers.put("chrome-tools", chromeMcp);
        return contextServers;
    }

    private Map<String, JSONObject> buildContextServers0(AgentContext agentContext) {
        Map<String, JSONObject> contextServers = new HashMap<>();
        //添加chrome-tools
        contextServers.put("chrome-tools", chromeMcp);

        //添加mcp
        List<AgentComponentConfigDto> mcpComponentConfigs = agentContext.getAgentConfig().getAgentComponentConfigList().stream().filter(agentComponentConfig -> agentComponentConfig.getType() == AgentComponentConfig.Type.Mcp).toList();
        //根据mcpId分组
        Map<Long, List<AgentComponentConfigDto>> mcpComponentConfigsByMcpId = mcpComponentConfigs.stream().collect(Collectors.groupingBy(agentComponentConfigDto -> {
            McpDto mcpDto = (McpDto) agentComponentConfigDto.getTargetConfig();
            return mcpDto.getId();
        }));
        for (Long mcpId : mcpComponentConfigsByMcpId.keySet()) {
            List<AgentComponentConfigDto> agentComponentConfigs = mcpComponentConfigsByMcpId.get(mcpId);
            AgentComponentConfigDto agentComponentConfig = agentComponentConfigs.get(0);

            if (!(agentComponentConfig.getTargetConfig() instanceof McpDto mcpDto)) {
                continue;
            }
            if (mcpDto.getInstallType() == InstallTypeEnum.COMPONENT) {
                continue;
            }
            String serverName = "";
            List<String> args = new ArrayList<>();
            args.add("convert");
            args.add("--config");
            if (mcpDto.getInstallType() == InstallTypeEnum.NPX || mcpDto.getInstallType() == InstallTypeEnum.UVX) {
                String serverConfigStr = mcpDto.getDeployedConfig().getServerConfig();
                if (!JSON.isValid(serverConfigStr)) {
                    continue;
                }
                JSONObject serverConfigJsonObject = JSON.parseObject(serverConfigStr);
                Map<String, JSONObject> serverConfig = lookupServerConfig(serverConfigJsonObject);
                if (serverConfig == null || serverConfig.isEmpty()) {
                    continue;
                }
                for (String key : serverConfig.keySet()) {
                    serverName = key;
                    args.add(JSON.toJSONString(Map.of("mcpServers", Map.of(key, serverConfig.get(key)))));
                    break;
                }
            } else if (mcpDto.getInstallType() == InstallTypeEnum.SSE || mcpDto.getInstallType() == InstallTypeEnum.STREAMABLE_HTTP) {
                List<String> urls = UrlExtractUtil.extractUrls(mcpDto.getDeployedConfig().getServerConfig());
                if (urls.isEmpty()) {
                    continue;
                }
                serverName = mcpDto.getServerName();
                String endpoint = urls.get(0);
                try {
                    if (serverName == null) {
                        JSONObject jsonObject = JSON.parseObject(mcpDto.getDeployedConfig().getServerConfig());
                        for (String key : jsonObject.keySet()) {
                            Object o = jsonObject.get(key);
                            if (o instanceof JSONObject config) {
                                // 兼容 配置里面有 mcpServers字段
                                if (config.size() == 1) {
                                    String keyName = config.keySet().iterator().next();
                                    if (config.get(keyName) instanceof JSONObject) {
                                        String string = config.getJSONObject(keyName).toString();
                                        if (string != null && string.contains("http")) {
                                            serverName = keyName;
                                            break;
                                        }
                                    }
                                } else if (config.toString().contains("http")) {
                                    serverName = key;
                                    break;
                                }
                            }
                        }
                    }
                    ClientSecretResponse clientSecretResponse = marketClientRpcService.queryClientSecret(new ClientSecretRequest(agentContext.getAgentConfig().getTenantId()));
                    if (clientSecretResponse != null) {
                        endpoint = endpoint.replace("TENANT_SECRET", clientSecretResponse.getClientSecret());
                    }
                } catch (Exception e) {
                    // ignore
                    log.warn("queryClientSecret error", e);
                }
                if (serverName == null) {
                    serverName = mcpDto.getName();
                }

                args.add(JSON.toJSONString(Map.of("mcpServers", Map.of(serverName, Map.of("url", endpoint)))));
                Map<String, String> stringStringMap = UrlExtractUtil.extractHeaders(mcpDto.getDeployedConfig().getServerConfig());
                if (stringStringMap != null) {
                    for (String key : stringStringMap.keySet()) {
                        args.add("-H");
                        args.add(key + "=" + stringStringMap.get(key));
                    }
                }
            }

            args.add("--allow-tools");
            args.add(agentComponentConfigs.stream().map(AgentComponentConfigDto::getName).collect(Collectors.joining(",")));
            JSONObject serverConfig = new JSONObject();
            serverConfig.put("source", "custom");
            serverConfig.put("enabled", true);
            serverConfig.put("command", "mcp-proxy");
            serverConfig.put("args", args);
            contextServers.put(serverName, serverConfig);
        }
        if (agentContext.getAgentConfig().getProxyMcpServerConfig() != null) {
            JSONObject serverConfig = new JSONObject();
            List<String> args = new ArrayList<>();
            args.add("convert");
            args.add("--config");
            args.add(agentContext.getAgentConfig().getProxyMcpServerConfig());
            serverConfig.put("source", "custom");
            serverConfig.put("enabled", true);
            serverConfig.put("command", "mcp-proxy");
            serverConfig.put("args", args);
            contextServers.put("platform", serverConfig);
        }
        return contextServers;
    }

    private static Map<String, JSONObject> lookupServerConfig(JSONObject serverConfigJsonObject) {
        if (serverConfigJsonObject == null) {
            return null;
        }

        for (String key : serverConfigJsonObject.keySet()) {
            Object value = serverConfigJsonObject.get(key);
            if (value instanceof JSONObject) {
                String command = ((JSONObject) value).getString("command");
                if (null != command && (command.startsWith("npx") || command.startsWith("uvx"))) {
                    ((JSONObject) value).put("source", "custom");
                    ((JSONObject) value).put("enabled", true);
                    return Map.of(key, (JSONObject) value);
                }
                Map<String, JSONObject> serverConfig = lookupServerConfig((JSONObject) value);
                if (serverConfig != null) {
                    return serverConfig;
                }
            }
        }
        return null;
    }

    private static ComponentExecutingDto buildComponentExecutingDto(JSONObject jsonObject, Map<String, ComponentExecutingDto> componentExecutingDtoMap) {
        String toolCallId = jsonObject.getString("toolCallId");
        ComponentExecutingDto componentExecutingDto = componentExecutingDtoMap.get(toolCallId);
        String status = jsonObject.getString("status");
        if (status != null && (status.equals("completed") || status.equals("failed"))) {
            if (componentExecutingDto == null) {
                return new ComponentExecutingDto();
            }
            ComponentExecuteResult result = componentExecutingDto.getResult();
            result.setEndTime(System.currentTimeMillis());
            result.setSuccess(!"failed".equals(jsonObject.getString("status")));
            componentExecutingDto.setStatus(status.equals("completed") ? ExecuteStatusEnum.FINISHED : ExecuteStatusEnum.FAILED);
            if (jsonObject.get("content") != null) {
                result.setData(jsonObject.get("content"));
            }
            return componentExecutingDto;
        }

        String title = jsonObject.getString("title");
        if (title != null && title.contains("mcp__chrome")) {
            title = title.replace("mcp__chrome-devtools", "mcp__browser");
        }
        if (jsonObject.get("rawInput") != null && jsonObject.get("rawInput") instanceof JSONObject) {
            if (jsonObject.getJSONObject("rawInput").getString("description") != null) {
                title = jsonObject.getJSONObject("rawInput").getString("description");
            }
        }
        String kind = null;
        List<Object> locations = jsonObject.getJSONArray("locations");
        if (locations != null) {
            kind = jsonObject.getString("kind");
        }
        if (StringUtils.isBlank(title) && componentExecutingDto != null && StringUtils.isNotBlank(componentExecutingDto.getName())) {
            title = componentExecutingDto.getName();
        }
        title = rewriteTitle(title, jsonObject);
        ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
        componentExecuteResult.setExecuteId(jsonObject.getString("toolCallId"));
        componentExecuteResult.setStartTime(System.currentTimeMillis());
        componentExecuteResult.setId(-1L);
        componentExecuteResult.setName(title);
        componentExecuteResult.setType(ComponentTypeEnum.ToolCall);
        componentExecuteResult.setInput(jsonObject.get("rawInput"));
        componentExecuteResult.setKind(kind);
        componentExecuteResult.setLocations(locations);
        componentExecutingDto = new ComponentExecutingDto();
        componentExecutingDto.setTargetId(-1L);
        componentExecutingDto.setName(title);
        componentExecutingDto.setType(ComponentTypeEnum.ToolCall);
        componentExecutingDto.setStatus(ExecuteStatusEnum.EXECUTING);
        componentExecutingDto.setResult(componentExecuteResult);
        componentExecutingDtoMap.put(toolCallId, componentExecutingDto);
        return componentExecutingDto;
    }

    private static String rewriteTitle(String title, JSONObject jsonObject) {
        if ("Skill".equalsIgnoreCase(title)) {
            Object o = jsonObject.get("rawInput");
            if (o instanceof JSONObject) {
                String skillName = ((JSONObject) o).getString("skill");
                String skillName0 = ((JSONObject) o).getString("name");
                if (StringUtils.isNotBlank(skillName)) {
                    title = "加载技能 " + skillName;
                } else if (StringUtils.isNotBlank(skillName0)) {
                    title = "加载技能 " + skillName0;
                } else {
                    title = "加载技能";
                }
            }
        } else if (title.startsWith("Write")) {
            title = title.replaceFirst("Write", "写入文件");
        } else if (title.startsWith("write")) {
            title = title.replaceFirst("write", "写入文件");
        } else if (title.startsWith("Edit")) {
            title = title.replaceFirst("Edit", "编辑文件");
        } else if (title.startsWith("edit")) {
            title = title.replaceFirst("edit", "编辑文件");
        } else if (title.startsWith("read")) {
            title = title.replaceFirst("read", "读取文件");
        } else if (title.startsWith("bash")) {
            title = title.replaceFirst("bash", "执行命令");
        } else if (title.startsWith("Terminal")) {
            title = title.replaceFirst("Terminal", "终端执行");
        }
        if (title.startsWith("read") || title.startsWith("edit") || title.startsWith("write") || title.startsWith("读取文件") || title.startsWith("编辑文件") || title.startsWith("写入文件")) {
            if (jsonObject.get("rawInput") != null && jsonObject.get("rawInput") instanceof JSONObject) {
                if (jsonObject.getJSONObject("rawInput").getString("filePath") != null) {
                    title = title + " " + jsonObject.getJSONObject("rawInput").getString("filePath");
                }
            }
        }

        if (title.startsWith("bash")) {
            if (jsonObject.get("rawInput") != null && jsonObject.get("rawInput") instanceof JSONObject) {
                if (jsonObject.getJSONObject("rawInput").getString("command") != null) {
                    title = title + " " + jsonObject.getJSONObject("rawInput").getString("command");
                }
            }
        }

        if (title.startsWith("glob")) {
            title = title.replaceFirst("glob", "搜索文件");
        }

        if (title.startsWith("Read File")) {
            title = title.replaceFirst("Read File", "读取文件");
            if (jsonObject.get("rawInput") != null && jsonObject.get("rawInput") instanceof JSONObject) {
                if (jsonObject.getJSONObject("rawInput").getString("file_path") != null) {
                    title = title + " " + jsonObject.getJSONObject("rawInput").getString("file_path");
                }
            }
        }
        if (title.startsWith("mcp__chrome-tools__") || title.startsWith("chrome-tools_")) {
            if ((title.equals("mcp__chrome-tools__navigate_page") || title.equals("chrome-tools_new_page")) && jsonObject.get("rawInput") != null
                    && jsonObject.get("rawInput") instanceof JSONObject && jsonObject.getJSONObject("rawInput").getString("url") != null) {
                title = title + " " + jsonObject.getJSONObject("rawInput").getString("url");
            }
            title = title.replaceFirst("mcp__chrome-tools__", "浏览器操作 ");
            title = title.replaceFirst("chrome-tools_", "浏览器操作 ");
        }
        if (title.startsWith("mcp__platform__")) {
            title = title.replaceFirst("mcp__platform__", "");
        }
        if (title.startsWith("mcp__")) {
            title = title.replaceFirst("mcp__", "");
        }
        if (title.startsWith("mcp-proxy__")) {
            title = title.replaceFirst("mcp-proxy__", "");
        }
        while (title.startsWith("_")) {
            title = title.replaceFirst("_", "");
        }

        // 智谱服务端搜索title为undefined
        if ("\"undefined\"".equals(title)) {
            title = "WebSearch";
        }
        return title;
    }

    private static ComponentExecutingDto buildExecutingEvent(String title, ComponentExecutingDto.SubEventTypeEnum subEventType, Object input) {
        ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
        componentExecuteResult.setStartTime(System.currentTimeMillis());
        componentExecuteResult.setEndTime(componentExecuteResult.getStartTime());
        componentExecuteResult.setId(-1L);
        componentExecuteResult.setName(title);
        componentExecuteResult.setType(ComponentTypeEnum.Event);
        componentExecuteResult.setInput(input);
        componentExecuteResult.setExecuteId(UUID.randomUUID().toString().replace("-", ""));
        ComponentExecutingDto componentExecutingDto = new ComponentExecutingDto();
        componentExecutingDto.setTargetId(-1L);
        componentExecutingDto.setName(title);
        componentExecutingDto.setType(ComponentTypeEnum.Event);
        componentExecutingDto.setStatus(ExecuteStatusEnum.FINISHED);
        componentExecutingDto.setResult(componentExecuteResult);
        componentExecutingDto.setSubEventType(subEventType);
        return componentExecutingDto;
    }


    private static @NotNull CallMessage buildChatFinishedMessage(AgentContext agentContext, String reason) {
        CallMessage chatMessageDto = new CallMessage();
        chatMessageDto.setId(agentContext.getRequestId());
        chatMessageDto.setType(MessageTypeEnum.CHAT);
        chatMessageDto.setRole(ChatMessageDto.Role.ASSISTANT);
        chatMessageDto.setText("");
        chatMessageDto.setFinished(true);
        chatMessageDto.setFinishReason(reason == null ? "" : reason.toUpperCase());
        return chatMessageDto;
    }

    private static @NotNull CallMessage buildChatMessage(AgentContext agentContext, String text) {
        CallMessage chatMessageDto = new CallMessage();
        chatMessageDto.setId(agentContext.getRequestId());
        chatMessageDto.setType(MessageTypeEnum.CHAT);
        chatMessageDto.setRole(ChatMessageDto.Role.ASSISTANT);
        chatMessageDto.setText(text);
        chatMessageDto.setFinished(false);
        return chatMessageDto;
    }

    private static void buildAndSetModelResult(AgentContext agentContext, String finalText, Long startTime) {
        List<ComponentExecuteResult> componentExecuteResults = agentContext.getAgentExecuteResult().getComponentExecuteResults();
        //工具提示词约10000，其他相同数据假设模型会缓存😓
        AtomicInteger promptTokens = new AtomicInteger(TikTokensUtil.tikTokensCount(agentContext.getMessage()) + TikTokensUtil.tikTokensCount(agentContext.getAgentConfig().getSystemPrompt()) + 10000);
        AtomicInteger completionTokens = new AtomicInteger();
        // 大概计算token使用情况，供参考不准确
        componentExecuteResults.forEach(componentExecuteResult -> {
            if (componentExecuteResult.getInput() != null) {
                int tokensCount = TikTokensUtil.tikTokensCount(JSON.toJSONString(componentExecuteResult.getInput()));
                completionTokens.set(completionTokens.get() + tokensCount);
                if (componentExecuteResult.getData() != null) {
                    tokensCount += TikTokensUtil.tikTokensCount(JSON.toJSONString(componentExecuteResult.getData()));
                }
                promptTokens.set(promptTokens.get() + tokensCount);
            }
        });
        Map<String, Object> modelExecuteInfoMap = new HashMap<>();
        Map<String, Object> modelExecuteInfo = new HashMap<>();
        ModelConfigDto modelConfig = (ModelConfigDto) agentContext.getAgentConfig().getModelComponentConfig().getTargetConfig();
        modelExecuteInfo.put("name", modelConfig.getName());
        modelExecuteInfo.put("userPrompt", agentContext.getMessage());
        modelExecuteInfo.put("outputText", finalText);
        modelExecuteInfo.put("promptTokens", promptTokens.get());
        modelExecuteInfo.put("completionTokens", completionTokens.get() + TikTokensUtil.tikTokensCount(finalText));
        modelExecuteInfo.put("startTime", startTime);
        modelExecuteInfo.put("endTime", System.currentTimeMillis());
        modelExecuteInfoMap.put("modelExecuteInfo", modelExecuteInfo);
        SimpleJvmHashCache.putHash(agentContext.getRequestId(), "modelExecuteInfos", modelExecuteInfoMap, 600);
    }

    public boolean chatCancel(String conversationId) {
        SandboxServerConfig.SandboxServer sandboxServer = sandboxServerConfigService.selectServer(Long.parseLong(conversationId));
        if (sandboxServer == null || sandboxServer.getCurrentConversation() == null || sandboxServer.getCurrentConversation().getSandboxSessionId() == null) {
            return false;
        }
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(sandboxServer.getServerAgentUrl() + "/computer/agent/session/cancel?project_id=" + conversationId + "&session_id=" + sandboxServer.getCurrentConversation().getSandboxSessionId() + "&user_id=" + sandboxServer.getCurrentConversation().getUserId()))
                .header("Content-Type", "application/json")
                .header("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey())
                .timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> send;
        try {
            send = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("chat cancel response: {}", send.body());
            return true;
        } catch (Throwable e) {
            //  ignore
            log.error("chat cancel error", e);
        }
        return false;
    }

    public void agentStop(String conversationId) {
        SandboxServerConfig.SandboxServer sandboxServer = sandboxServerConfigService.selectServer(Long.parseLong(conversationId));
        if (sandboxServer == null || sandboxServer.getCurrentConversation() == null || sandboxServer.getCurrentConversation().getSandboxSessionId() == null) {
            return;
        }
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(sandboxServer.getServerAgentUrl() + "/computer/agent/stop"))
                .header("Content-Type", "application/json")
                .header("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey())
                .timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(Map.of("user_id", sandboxServer.getCurrentConversation().getUserId().toString(), "session_id", sandboxServer.getCurrentConversation().getSandboxSessionId(), "project_id", conversationId)))).build();
        HttpResponse<String> send;
        try {
            send = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("agent stop response: {}", send.body());
        } catch (Throwable e) {
            //  ignore
            log.error("agent stop error", e);
        }
    }

    public boolean checkAgentIfAlive(String conversationId) {
        SandboxServerConfig.SandboxServer sandboxServer = sandboxServerConfigService.selectServer(Long.parseLong(conversationId));
        if (sandboxServer == null || sandboxServer.getCurrentConversation() == null || sandboxServer.getCurrentConversation().getSandboxSessionId() == null) {
            return false;
        }
        AgentContext agentContext = new AgentContext();
        agentContext.setConversationId(conversationId);
        agentContext.setUserId(sandboxServer.getCurrentConversation().getUserId());
        return Boolean.TRUE.equals(checkAgentIfAlive(agentContext, sandboxServer).timeout(Duration.ofSeconds(10)).block());
    }

    public static SseSubscription subscribe(SandboxServerConfig.SandboxServer sandboxServer, String url, SseEventHandler eventHandler) {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).header("Accept", "text/event-stream").header("Cache-Control", "no-cache")
                .header("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey())
                .GET().build();
        StringBuilder eventBuilder = new StringBuilder();
        AtomicReference<String> currentEventId = new AtomicReference<>();
        AtomicReference<String> currentEventType = new AtomicReference<>();
        AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();
        AtomicReference<Long> lastTime = new AtomicReference<>(System.currentTimeMillis());
        AtomicBoolean isFinished = new AtomicBoolean(false);
        Flow.Subscriber<String> lineSubscriber = new Flow.Subscriber<>() {

            @Override
            public void onSubscribe(Flow.Subscription subscription0) {
                subscription.set(subscription0);
                subscription0.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(String line) {
                lastTime.set(System.currentTimeMillis());
                if (line.isEmpty()) {
                    // Empty line means end of event
                    if (!eventBuilder.isEmpty()) {
                        String eventData = eventBuilder.toString();
                        SseEvent event = new SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());
                        eventHandler.onEvent(event);
                        eventBuilder.setLength(0);
                    }
                } else {
                    if (line.startsWith("data:")) {
                        var matcher = EVENT_DATA_PATTERN.matcher(line);
                        if (matcher.find()) {
                            eventBuilder.append(matcher.group(1).trim()).append("\n");
                        }
                    } else if (line.startsWith("id:")) {
                        var matcher = EVENT_ID_PATTERN.matcher(line);
                        if (matcher.find()) {
                            currentEventId.set(matcher.group(1).trim());
                        }
                    } else if (line.startsWith("event:")) {
                        var matcher = EVENT_TYPE_PATTERN.matcher(line);
                        if (matcher.find()) {
                            currentEventType.set(matcher.group(1).trim());
                        }
                    }
                }
                subscription.get().request(1);
            }

            @Override
            public void onError(Throwable throwable) {
                isFinished.set(true);
                eventHandler.onError(throwable);
                try {
                    if (subscription.get() != null) {
                        subscription.get().cancel();
                    }
                } catch (Exception e) {
                    // Ignore
                }
            }

            @Override
            public void onComplete() {
                // Handle any remaining event data
                isFinished.set(true);
                if (!eventBuilder.isEmpty()) {
                    String eventData = eventBuilder.toString();
                    SseEvent event = new SseEvent(currentEventId.get(), currentEventType.get(), eventData.trim());
                    eventHandler.onEvent(event);
                }
                eventHandler.onComplete();
                log.info("会话结束, url {}", url);
            }
        };

        Function<Flow.Subscriber<String>, HttpResponse.BodySubscriber<Void>> subscriberFactory = HttpResponse.BodySubscribers::fromLineSubscriber;
        CompletableFuture<HttpResponse<Void>> future = httpClient.sendAsync(request, info -> subscriberFactory.apply(lineSubscriber));
        future.thenAccept(response -> {
            int status = response.statusCode();
            if (status != 200 && status != 201 && status != 202 && status != 206) {
                throw new RuntimeException("Failed to connect to SSE stream. Unexpected status code: " + status);
            }
        }).exceptionally(throwable -> {
            eventHandler.onError(throwable);
            return null;
        });
        //心跳检测超时
        Disposable disposable = Flux.interval(Duration.ofSeconds(10), Duration.ofSeconds(10)).takeUntil(aLong -> isFinished.get()).subscribe(ct -> {
            if (System.currentTimeMillis() - lastTime.get() > 60000) {
                isFinished.set(true);
                log.warn("心跳检测，sse连接已断开, url {}", url);

                try {
                    future.cancel(true);
                } catch (Exception e) {
                    // Ignore
                }
                if (subscription.get() != null) {
                    subscription.get().cancel();
                }
            }
        });
        return new SseSubscription(future, subscription, disposable);
    }

    public record SseEvent(String id, String type, String data) {
    }

    public interface SseEventHandler {

        /**
         * Called when an SSE event is received.
         *
         * @param event the received SSE event containing id, type, and data
         */
        void onEvent(SseEvent event);

        /**
         * Called when an error occurs during the SSE connection.
         *
         * @param error the error that occurred
         */
        void onError(Throwable error);

        void onComplete();
    }

    public record SseSubscription(CompletableFuture<HttpResponse<Void>> future,
                                  AtomicReference<Flow.Subscription> subscription, Disposable heartbeatDisposable) {

        public void cancel() {
            if (subscription.get() != null) {
                subscription.get().cancel();
            }
            heartbeatDisposable.dispose();
//            future.cancel(true);
        }
    }
}
