package com.xspaceagi.agent.core.infra.component.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.dto.AgentPublishVersionDto;
import com.xspaceagi.agent.core.adapter.dto.ChatMessageDto;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentComponentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.bind.McpBindConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.bind.PluginBindConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.bind.WorkflowBindConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import com.xspaceagi.agent.core.infra.component.agent.dto.AgentRequest;
import com.xspaceagi.agent.core.infra.component.im.ImChatIdentity;
import com.xspaceagi.agent.core.infra.component.model.dto.CallMessage;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentExecuteResult;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentExecutingDto;
import com.xspaceagi.agent.core.infra.component.model.strategy.WeightedRoundRobinStrategy;
import com.xspaceagi.agent.core.infra.rpc.*;
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
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.AgentException;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.spec.utils.JsonTruncateUtil;
import com.xspaceagi.system.spec.utils.MD5;
import com.xspaceagi.system.spec.utils.RedisUtil;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;
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

    /**
     * 可预览文件的扩展名白名单（<task-result> 只输出浏览器可直接预览的文件，
     * 排除二进制/源码/压缩包等无法在线预览的类型）。
     */
    private static final Set<String> PREVIEWABLE_EXTENSIONS = Set.of(
            "html", "htm", "md", "txt", "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "csv", "json", "xml", "svg", "png", "jpg", "jpeg", "gif", "webp", "mp3", "mp4");
    private static final String USE_IM_SKILL_PROMPT = """
            <system-reminder>
            请使用 nuwax-im 技能回复用户，会话直接输出的内容用户不会看到，如果执行的任务比较长，可以发一些安慰语句或中间产物，但不要刷屏。
            请在每个阶段性任务完成后或适当的时候检查是否有追加新消息（每10轮工具调用里至少有一次检查），如果有就追加到上下文，使用 nuwax-im 技能中的 agent-pending-list 与 agent-pending-clear 获取队列中的新消息，取到消息后请调用 agent-pending-clear 进行清除。
            </system-reminder>
            """;

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

    private static final JSONObject askQuestionMcp = JSON.parseObject("""
            {
              "command": "npx",
              "args": ["-y", "nuwax-ask-question-mcp"]
            }
            """);

    private static final JSONObject openUIMcp = JSON.parseObject("""
            {
              "command": "npx",
              "args": ["-y", "@nuwax-ai/openui-mcp"]
            }
            """);
    private static final JSONObject appDevDBMcp = JSON.parseObject("""
            {
                "command": "npx",
                "args": [
                    "-y",
                    "@dbx-app/mcp-server"
                ]
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

    /**
     * 组件占位块的切分模式 —— 前端把流式渲染的组件（工作流 / 工具调用 / 计划…）写回消息正文的形式：
     *
     * <pre>{@code <div><markdown-custom-process executeId="call_xx" type="Workflow" status="FINISHED" name="灵珠设备控制"></markdown-custom-process></div>}</pre>
     *
     * <p>三种形态都要吃：带 {@code <div>} 包裹的（正常路径，故 {@code <div>} 与 {@code </div>} 都是可选的）、
     * 裸标签的（历史上落库过），以及自闭合写法 {@code <markdown-custom-process ... />}
     * ——最后一种没有结束标签，所以标签体与结束标签写成「二选一」的交替，而不是把结束标签设为可选
     * （若只把结束标签设为可选，一个自闭合标签会把后面直到<b>下一个</b>结束标签之间的正文整段吃掉）。
     *
     * <p>用法见 {@link #takeLastSegmentAfterCustomProcess(String)}。
     */
    private static final Pattern CUSTOM_PROCESS_BLOCK_PATTERN = Pattern.compile(
            "(?:<div>\\s*)?<markdown-custom-process\\b[^>]*?(?:/>|>[\\s\\S]*?</markdown-custom-process\\s*>)\\s*(?:</div>)?",
            Pattern.CASE_INSENSITIVE);

    /**
     * 从 URL 里取 host 用（见 {@link #extractHost(String)}）：可选协议头 + 可选 userinfo +
     * （IPv6 方括号形态 | 普通主机名/IPv4）。分组 1 = 去括号的 IPv6，分组 2 = 其余主机名/IPv4。
     *
     * <p>不用 {@link URI#getHost()} 是因为配置里存在不带协议头的写法（{@code 127.0.0.1:8000/v1}），
     * 那种字符串按 URI 解析取不到 host —— 而它恰恰是最需要判成本机的形态。
     */
    private static final Pattern HOST_PATTERN = Pattern.compile(
            "(?:[a-zA-Z][a-zA-Z0-9+.-]*://)?(?:[^@/]*@)?(?:\\[([^\\]]+)\\]|([^/:?#]+))");

    /**
     * 127.0.0.0/8 整段都是回环（RFC 1122），不只是 127.0.0.1。
     */
    private static final Pattern LOOPBACK_V4_PATTERN = Pattern.compile("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$");

    /**
     * 显式使用 HTTP/1.1：默认的 HTTP_2 对明文目标会发 Upgrade: h2c，
     * 经过 openresty 反代时存在响应丢失的线上问题。
     */
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();

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
        // Remote SSE subscription
        AtomicReference<SseSubscription> sseSubscriptionAtomicReference = new AtomicReference<>();
        Long startTime = System.currentTimeMillis();
        StringBuilder finalText = new StringBuilder();
        Flux<CallMessage> sseFlux = Flux.create(sink -> {
            // Get sandbox service
            SandboxServerConfig.SandboxServer sandboxServer = sandboxServerConfigService.selectServer(agentContext.getTenantConfig(), agentContext.getUserId(), agentContext.getConversation().getSandboxServerId());
            sandboxServer.setCurrentConversation(agentContext.getConversation());
            String projectId = agentContext.getConversationId();
            if (agentContext.isUseSate()) {
                projectId = agentContext.getConversationId() + "_" + agentContext.getAgentConfig().getId();
            }
            final String projectId0 = projectId;
            // Start sandbox service
            if (sandboxServer.getScope() == SandboxScopeEnum.USER) {
                checkAgentIfAlive0(sandboxServer, projectId)
                        .doOnSuccess(res0 -> {
                            // agent not started, append prompt
                            try {
                                executeTask(agentContext, sandboxServer, sink, sseSubscriptionAtomicReference, finalText, res0 != null && !res0);
                            } catch (Exception e) {
                                sink.error(e);
                            }
                        })
                        .onErrorResume(throwable -> Mono.just(false))
                        .subscribe();
            } else {
                startSandbox(agentContext, sandboxServer)
                        .doOnSuccess(res -> {
                            if (CollectionUtils.isEmpty(agentContext.getContextMessages())) {
                                try {
                                    executeTask(agentContext, sandboxServer, sink, sseSubscriptionAtomicReference, finalText, false);
                                } catch (Exception e) {
                                    sink.error(e);
                                }
                                return;
                            }
                            checkAgentIfAlive0(sandboxServer, projectId0)
                                    .doOnSuccess(res0 -> {
                                        // agent not started, append prompt
                                        try {
                                            executeTask(agentContext, sandboxServer, sink, sseSubscriptionAtomicReference, finalText, res0 != null && !res0);
                                        } catch (Exception e) {
                                            sink.error(e);
                                        }
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
                        return Mono.error(new BizException(I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Error.ComputerConnectFailed")));
                    }
                    return Mono.error(throwable);
                })
                .doOnComplete(() -> buildAndSetModelResult(agentContext, finalText.toString(), startTime))
                .doOnError(throwable -> buildAndSetModelResult(agentContext, finalText.toString(), startTime));
        return sseFlux;
    }

    private Mono<Boolean> checkAgentIfAlive(AgentContext agentContext, SandboxServerConfig.SandboxServer sandboxServer) {
        return Mono.create(sink -> {
            Map<String, String> sessionMap = createSessionMap(sandboxServer);
            if (!sessionMap.isEmpty()) {
                for (Map.Entry<String, String> entry : sessionMap.entrySet()) {
                    Boolean block = checkAgentIfAlive0(sandboxServer, entry.getKey()).timeout(Duration.ofSeconds(10)).block();
                    if (block != null && block) {
                        sink.success(true);
                        return;
                    }
                }
            }
            Boolean block = checkAgentIfAlive0(sandboxServer, agentContext.getConversationId()).timeout(Duration.ofSeconds(10)).block();
            sink.success(block != null && block);
        });
    }

    private Mono<Boolean> checkAgentIfAlive0(SandboxServerConfig.SandboxServer sandboxServer, String projectId) {
        return Mono.create(sink -> {
            //Query agent startup status/computer/agent/status
            Long userId = sandboxServer.getCurrentConversation().getUserId();
            log.info("Checking if session Agent status is started, userId {}, projectId {}", userId, projectId);
            String apiUrl = sandboxServer.getServerAgentUrl() + "/computer/agent/status";
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(rewriteComputerBaseUrl(sandboxServer.getCurrentConversation(), apiUrl)))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(Map.of("user_id", userId.toString(), "project_id", projectId))))
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();
            httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenAccept(response -> {
                String body = response.body();
                log.info("Query Agent status result, userId {}, projectId {}, body {}", userId, projectId, body);
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
                log.error("Query Agent status exception", throwable);
                sink.success(null);
                return null;
            });
        });
    }

    /**
     * CompletableFuture#orTimeout 抛出的 TimeoutException 不带 message，
     * 统一换成可读文案，避免日志和前端正文出现字面量 "null"。
     */
    private static Throwable normalizeTimeoutError(Throwable throwable, String message) {
        Throwable cause = throwable;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof TimeoutException) {
            return new TimeoutException(StringUtils.defaultIfBlank(cause.getMessage(), message));
        }
        return cause;
    }

    private Mono<Boolean> startSandbox(AgentContext agentContext, SandboxServerConfig.SandboxServer sandboxServer) {
        // 上下文解析放在取锁前：解析失败（容器路由类型缺项目标识等）直接抛出，不持有/泄漏沙箱锁
        WorkspaceServiceContext serviceContext = WorkspaceServiceSupport.resolveServiceContext(
                Long.parseLong(agentContext.getConversationId()), sandboxServer);
        //Redis lock to avoid concurrent startup by the same user, wait up to 60 seconds
        final String lockKey = "sandbox_lock_" + agentContext.getUserId();
        try {
            redisUtil.lock(lockKey, 60000);
        } catch (Exception e) {
            // ignore
            log.error("Failed to acquire lock", e);
        }
        Mono<Boolean> mono = Mono.create(sink -> {
            //查询sandbox启动状态
            log.info("Query sandbox status, userId {}, conversationId {}", agentContext.getUserId(), agentContext.getConversationId());
            UriComponentsBuilder statusUrlBuilder = UriComponentsBuilder.fromHttpUrl(sandboxServer.getServerAgentUrl() + "/computer/pod/status")
                    .queryParam("user_id", agentContext.getUserId())
                    .queryParam("project_id", agentContext.getConversationId());
            WorkspaceServiceSupport.applyPodServiceParams(statusUrlBuilder, serviceContext, null);
            String statusUrl = statusUrlBuilder.toUriString();
            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(statusUrl))
                    .header("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey())
                    .timeout(java.time.Duration.ofSeconds(10))
                    .build();
            CompletableFuture<HttpResponse<String>> httpResponseCompletableFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            httpResponseCompletableFuture.thenAccept(httpResponse -> {
                        String body = httpResponse.body();
                        log.info("Query sandbox status result, userId {}, conversationId {}, body {}", agentContext.getUserId(), agentContext.getConversationId(), body);
                        if (!JSON.isValid(body)) {
                            sink.error(new IllegalArgumentException(I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Error.SandboxStatusQueryFailed")));
                            return;
                        }
                        JSONObject jsonObject = JSON.parseObject(body);
                        if (!jsonObject.containsKey("code") || !"0000".equals(jsonObject.getString("code"))) {
                            sink.error(new BizException(jsonObject.getString("message")));
                            return;
                        }
                        JSONObject data = jsonObject.getJSONObject("data");
                        if (data == null) {
                            sink.error(new BizException(I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Error.StatusQueryResponseInvalid")));
                            return;
                        }
                        if (data.getBoolean("alive") != null && data.getBoolean("alive")) {
                            sink.success(true);
                            return;
                        }
                        ComponentExecutingDto sandboxStartingDto = buildSandboxStartingDto(agentContext.getUser().getLangMap());
                        if (agentContext.getComponentExecutingConsumer() != null) {
                            agentContext.getComponentExecutingConsumer().accept(sandboxStartingDto);
                        }

                        int perUserCpuCores = sandboxServer.getPerUserCpuCores();
                        double perUserMemoryGB = sandboxServer.getPerUserMemoryGB();
                        String storageGB = "50Gi";
                        UserDataPermissionDto userDataPermission = agentContext.getUserDataPermission();
                        if (userDataPermission != null) {
                            //上限由管理员自己决定
                            if (userDataPermission.getAgentComputerMemoryGb() != null && userDataPermission.getAgentComputerMemoryGb() > 0) {
                                perUserMemoryGB = userDataPermission.getAgentComputerMemoryGb();
                            }
                            if (userDataPermission.getAgentComputerCpuCores() != null && userDataPermission.getAgentComputerCpuCores() > 0) {
                                perUserCpuCores = userDataPermission.getAgentComputerCpuCores();
                            }
                            if (userDataPermission.getAgentComputerStorageLimitGb() != null && userDataPermission.getAgentComputerStorageLimitGb().intValue() > 0) {
                                storageGB = userDataPermission.getAgentComputerStorageLimitGb().intValue() + "Gi";
                            }
                        }

                        Map<String, Object> ensureBody = new HashMap<>();
                        ensureBody.put("user_id", agentContext.getUserId().toString());
                        ensureBody.put("project_id", agentContext.getConversationId());
                        ensureBody.put("resource_limits", Map.of(
                                "cpu", perUserCpuCores,
                                "memory", perUserMemoryGB * 1024 * 1024 * 1024,
                                "swap", perUserMemoryGB * 1024 * 1024 * 1024 * 2,
                                "storage_size", storageGB
                        ));
                        WorkspaceServiceSupport.applyPodServiceParams(ensureBody, serviceContext, null);

                        HttpRequest startRequest = HttpRequest.newBuilder().uri(URI.create(sandboxServer.getServerAgentUrl() + "/computer/pod/ensure"))
                                .header("Content-Type", "application/json")
                                .header("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey())
                                .timeout(Duration.ofSeconds(180))
                                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(ensureBody))).build();
                        log.info("Sandbox starting, userId {}, conversationId {}", agentContext.getUserId(), agentContext.getConversationId());
                        CompletableFuture<HttpResponse<String>> startHttpResponseCompletableFuture = httpClient.sendAsync(startRequest, HttpResponse.BodyHandlers.ofString());
                        startHttpResponseCompletableFuture.thenAccept(startHttpResponse -> {
                                    String startBody = startHttpResponse.body();
                                    log.info("Sandbox startup result, userId {}, conversationId {}, body {}", agentContext.getUserId(), agentContext.getConversationId(), startBody);
                                    sandboxStartingDto.getResult().setEndTime(System.currentTimeMillis());
                                    if (!JSON.isValid(startBody)) {
                                        if (agentContext.getComponentExecutingConsumer() != null) {
                                            sandboxStartingDto.setStatus(ExecuteStatusEnum.FAILED);
                                            sandboxStartingDto.getResult().setError(startBody);
                                            agentContext.getComponentExecutingConsumer().accept(sandboxStartingDto);
                                        }
                                        sink.error(new BizException(I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Error.ComputerStartupFailed")));
                                        return;
                                    }
                                    JSONObject startJsonObject = JSON.parseObject(startBody);
                                    if (!startJsonObject.containsKey("code") || !"0000".equals(startJsonObject.getString("code"))) {
                                        if (agentContext.getComponentExecutingConsumer() != null) {
                                            sandboxStartingDto.setStatus(ExecuteStatusEnum.FAILED);
                                            sandboxStartingDto.getResult().setError(startJsonObject.getString("message"));
                                            agentContext.getComponentExecutingConsumer().accept(sandboxStartingDto);
                                        }
                                        sink.error(new BizException(I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Error.ComputerStartupFailed")));
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
                                    Throwable error = normalizeTimeoutError(throwable, I18nUtil.systemMessage(
                                            agentContext.getUser().getLangMap(), "Backend.Sandbox.Error.ComputerStartupFailed"));
                                    if (agentContext.getComponentExecutingConsumer() != null) {
                                        sandboxStartingDto.setStatus(ExecuteStatusEnum.FAILED);
                                        sandboxStartingDto.getResult().setError(error.getMessage());
                                        sandboxStartingDto.getResult().setEndTime(System.currentTimeMillis());
                                        agentContext.getComponentExecutingConsumer().accept(sandboxStartingDto);
                                    }
                                    sink.error(error);
                                    return null;
                                });
                    })
                    .orTimeout(10, TimeUnit.SECONDS)
                    .exceptionally(throwable -> {
                        sink.error(normalizeTimeoutError(throwable, I18nUtil.systemMessage(
                                agentContext.getUser().getLangMap(), "Backend.Sandbox.Error.SandboxStatusQueryFailed")));
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
                        String error = I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Error.SystemOverloaded");
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
        String systemPrompt = buildSystemPrompt(agentContext, conversation);
        String userPrompt = contextPromptBuilder + agentContext.getMessage();
        if (isGroupMessage(agentContext)) {
            userPrompt = userPrompt + buildGroupReminder(agentContext);
        }
        if (isP2pMessage(agentContext)) {
            userPrompt = userPrompt + buildP2pReminder(agentContext);
        }
        if (isProjectConv(conversation)) {
            Map<String, Object> variables = conversation.getVariables();
            if (variables != null) {
                userPrompt = userPrompt + Prompts.PROJECT_SYSTEM_REMINDER.replace("${repoSlugId}", variables.getOrDefault("PROJECT_FOLDER_SLUG_ID", "").toString())
                        .replace("${planSlugId}", variables.getOrDefault("PROJECT_PLAN_SLUG_ID", "").toString());
            }
        }
        if (conversation.getVariables() != null && conversation.getVariables().containsKey("ENABLED_CONNECTORS")) {
            userPrompt = userPrompt + Prompts.ENABLED_CONNECTORS_REMINDER.replace("${enabled-connectors}", conversation.getVariables().getOrDefault("ENABLED_CONNECTORS", "").toString());
        }
        if (!isLocalModelUrl(apiInfo.getUrl()) && (apiInfo.getUseProxy() == null || apiInfo.getUseProxy().equals(YesOrNoEnum.Y.getKey()))) {
            // 非本机模型走代理模式，为用户生成独立的key；本机模型（本地开发）拿不到代理地址，原样直连
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
            backendModelDto.setTraceContext(agentContext.getTraceContext().next(TraceContext.TraceTargetType.Model, modelConfig.getId().toString(), modelConfig.getModel(), modelConfig.getName(), null, agentContext.isDefaultModelChanged() ? agentContext.getUserId() : null));
            List<TraceContext.TraceTarget> traceTargets = backendModelDto.getTraceContext().getTraceTargets();
            traceTargets.get(traceTargets.size() - 1).setSpaceId(modelConfig.getSpaceId());
            String siteUrl = agentContext.getTenantConfig() != null ? agentContext.getTenantConfig().getSiteUrl() : "";
            FrontendModelDto frontendModelDto = modelApiProxyRpcService.generateUserFrontendModelConfig(agentContext.getUser().getTenantId(), agentContext.getUser().getId()
                    , agentContext.getAgentConfig().getId(), backendModelDto, siteUrl);
            apiInfo = new ModelConfigDto.ApiInfo();
            apiInfo.setKey(frontendModelDto.getApiKey());
            apiInfo.setUrl(frontendModelDto.getBaseUrl());
        }

        String sessionId = null;
        if (agentContext.isUseSate()) {
            Map<String, Object> sessionState = agentContext.getSessionState();
            Object o = sessionState.get("sandboxSessionId");
            if (o != null) {
                sessionId = o.toString();
            }
        } else if (conversation != null && StringUtils.isNotBlank(conversation.getSandboxSessionId()) && !agentContext.isDebug()) {
            sessionId = conversation.getSandboxSessionId();
        }


        String projectId = agentContext.getConversationId();
        if (agentContext.isUseSate()) {
            projectId = agentContext.getConversationId() + "_" + agentContext.getAgentConfig().getId();
        }

        int perUserCpuCores = sandboxServer.getPerUserCpuCores();
        double perUserMemoryGB = sandboxServer.getPerUserMemoryGB();
        String storageGB = "50Gi";
        UserDataPermissionDto userDataPermission = agentContext.getUserDataPermission();
        if (userDataPermission != null) {
            //上限由管理员自己决定
            if (userDataPermission.getAgentComputerMemoryGb() != null && userDataPermission.getAgentComputerMemoryGb() > 0) {
                perUserMemoryGB = userDataPermission.getAgentComputerMemoryGb();
            }
            if (userDataPermission.getAgentComputerCpuCores() != null && userDataPermission.getAgentComputerCpuCores() > 0) {
                perUserCpuCores = userDataPermission.getAgentComputerCpuCores();
            }
            if (userDataPermission.getAgentComputerStorageLimitGb() != null && userDataPermission.getAgentComputerStorageLimitGb().intValue() > 0) {
                storageGB = userDataPermission.getAgentComputerStorageLimitGb().intValue() + "Gi";
            }
        }

        String appId = null;
        String serviceType = null;
        String agentWorkDir = agentContext.getConversationId();
        // 全类型携带 service_type（容器按此路由/识别）
        // agent_work_dir：userApp=appId、常规项目=projectId，容器按 service_type + 该值定位项目工作区；
        // app_id 仅 userApp——下游 chat/pod 契约：app_id 与非 userapp 的 service_type 互斥
        WorkspaceServiceType wsType = conversation == null || StringUtils.isBlank(conversation.getDevTargetType())
                ? null
                : WorkspaceServiceType.fromDevTargetType(conversation.getDevTargetType());
        if (wsType != null) {
            serviceType = wsType.getContainerType();
            if (wsType == WorkspaceServiceType.USERAPP || wsType == WorkspaceServiceType.NORMAL_PROJECT) {
                if (StringUtils.isBlank(conversation.getDevTargetId())) {
                    throw new BizException(wsType.getWorkspaceType() + " conversation missing project id, cId="
                            + agentContext.getConversationId());
                }
                if (wsType == WorkspaceServiceType.USERAPP) {
                    appId = conversation.getDevTargetId();
                }
                agentWorkDir = conversation.getDevTargetId();
            }
        }
        // agent 执行容器内工作路径
        if (conversation != null && StringUtils.isNotBlank(conversation.getAgentWorkspacePath())) {
            agentWorkDir = conversation.getAgentWorkspacePath();
        }

        AgentRequest agentRequest = AgentRequest.builder()
                .user_id(agentContext.getUserId().toString())
                .project_id(projectId)
                .app_id(appId)
                .service_type(serviceType)
                .agent_work_dir(agentWorkDir)
                .session_id(sessionId).prompt(userPrompt)
                .original_user_prompt(agentContext.getOriginalMessage())
                .open_long_memory(false)
                .request_id(agentContext.getRequestId())
                .attachments(null)
                .data_source_attachments(null)
                .model_provider(null)
                .system_prompt(systemPrompt)
                .user_prompt(null)
                .model_provider(AgentRequest.ModelProvider.builder()
                        .id(modelConfig.getId().toString())
                        .default_model(modelConfig.getModel())
                        .name(modelConfig.getName())
                        .requires_openai_auth(true)
                        .base_url(apiInfo.getUrl().replace("SESSION_ID", agentContext.getConversationId()))// 官方代理接口通过这个id做上下文保持在一个后端key
                        .api_key(apiInfo.getKey())
                        .api_protocol(modelConfig.getApiProtocol().name().toLowerCase())
                        .build())
                .agent_config(AgentRequest.AgentConfig.builder()
                        .agent_server(buildAgentServer(agentContext, sandboxServer, modelConfig))
                        .context_servers(buildContextServers(agentContext))
                        .resource_limits(AgentRequest.ResourceLimits.builder()
                                .cpu(perUserCpuCores)
                                .memory((long) (perUserMemoryGB * 1024 * 1024 * 1024))
                                .swap((long) perUserMemoryGB * 1024 * 1024 * 1024 * 2)
                                .storage_size(storageGB)
                                .build())
                        .build())
                .build();
        String requestBody = JSON.toJSONString(agentRequest);
        String apiUrl = sandboxServer.getServerAgentUrl() + "/computer/chat";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(rewriteComputerBaseUrl(agentContext.getConversation(), apiUrl))).header("Content-Type", "application/json")
                .header("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey())
                .timeout(Duration.ofSeconds(180))
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8)).build();
        log.info("Initiate conversation request, agentId {}, conversationId {}, requestBody {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), requestBody);
        try {
            CompletableFuture<HttpResponse<String>> httpResponseCompletableFuture = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            httpResponseCompletableFuture.thenAccept(httpResponse -> {
                int status = httpResponse.statusCode();
                if (status != 200 && status != 201 && status != 202 && status != 206) {
                    sink.error(new RuntimeException("Failed to connect to SSE stream. Unexpected status code: " + status));
                    log.error("Failed to initiate conversation request, agentId {}, conversationId {}, status {}, body {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), status, httpResponse.body());
                    return;
                }
                String body = httpResponse.body();
                log.info("Initiate conversation request returned, agentId {}, conversationId {}, body {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), body);
                JSONObject jsonObject = JSON.parseObject(body);
                if (!jsonObject.containsKey("code") || !"0000".equals(jsonObject.getString("code"))) {
                    if (jsonObject.containsKey("error")) {
                        boolean isInternalError = false;
                        String error = jsonObject.getString("error");
                        if (error.contains("Internal error")) {
                            error = I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Error.InternalError") + "(" + error + ")";
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

                if (agentContext.isUseSate()) {
                    agentContext.getSessionState().put("sandboxSessionId", sessionId0);
                } else if (conversation != null && agentContext.getSandboxSessionCreatedConsumer() != null) {
                    conversation.setSandboxSessionId(sessionId0);
                    conversation.setSandboxServerId(sandboxServer.getServerId());
                    agentContext.getSandboxSessionCreatedConsumer().accept(conversation);
                }
                Map<String, ComponentExecutingDto> componentExecutingDtoMap = new LinkedHashMap<>();
                Map<String, ComponentExecutingDto> eventMap = new LinkedHashMap<>();
                Set<ComponentExecutingDto.SubEventTypeEnum> subEventTypeEnumSet = new HashSet<>();
                AtomicBoolean completed = new AtomicBoolean(false);
                String apiUrl0 = sandboxServer.getServerAgentUrl() + "/computer/progress/" + sessionId0;
                SseSubscription subscription = subscribe(sandboxServer, rewriteComputerBaseUrl(agentContext.getConversation(), apiUrl0), new SseEventHandler() {
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
                            String reason = "EndTurn";
                            if (data != null && data.getString("reason") != null) {
                                reason = data.getString("reason");
                            }
                            sink.next(buildChatFinishedMessage(agentContext, reason));
                            completed.set(true);
                            sink.complete();
                            if (sseSubscriptionAtomicReference.get() != null) {
                                sseSubscriptionAtomicReference.get().cancel();
                            }
                            return;
                        }
                        if ("sessionPromptEnd".equals(jsonObject.getString("messageType"))) {
                            checkUnfinishedToolCall(agentContext, componentExecutingDtoMap);
                            checkAndCompleteFinalResultFile(agentContext, componentExecutingDtoMap, finalText, sink);
                            if (data == null) {
                                completed.set(true);
                                sink.complete();
                                if (sseSubscriptionAtomicReference.get() != null) {
                                    sseSubscriptionAtomicReference.get().cancel();
                                }
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
                                    message = I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Error.InternalError");
                                }
                                boolean isInternalError = false;
                                if (message.contains("Internal error")) {
                                    message = I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Error.InternalError");
                                    isInternalError = true;
                                }
                                callMessage.setText("\n```\n" + message + "\n```");
                                if (message.contains(CONTEXT_LIMIT_REACHED_MSG) || message.contains("model_context_window_exceeded")) {
                                    callMessage.setText("\n```\n" + I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Error.ContextLimitReached") + "\n```");
                                    agentStop(agentContext.getConversationId());
                                }
                                if (isInternalError) {
                                    callMessage.setText("\n```\n" + I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Error.ModelExecutionError") + "\n```");
                                    agentStop(agentContext.getConversationId());
                                    // 异常会话无法恢复时清除之前关联的会话
                                    if (agentContext.isUseSate()) {
                                        agentContext.getSessionState().remove("sandboxSessionId");
                                    } else if (conversation != null && agentContext.getSandboxSessionCreatedConsumer() != null) {
                                        conversation.setSandboxSessionId("");
                                        conversation.setSandboxServerId(sandboxServer.getServerId());
                                        agentContext.getSandboxSessionCreatedConsumer().accept(conversation);
                                    }
                                }
                                sink.next(callMessage);
                                sink.error(new AgentException("ERROR", message));
                            } else if ("cancelled".equals(event.type)) {
                                //sink.error(new AgentException("0001", data.containsKey("error_message") ? data.getString("error_message") : "cancelled"));
                                agentContext.setInterrupted(true);
                                completed.set(true);
                                sink.complete();
                            } else {
                                completed.set(true);
                                sink.complete();
                            }
                            if (sseSubscriptionAtomicReference.get() != null) {
                                sseSubscriptionAtomicReference.get().cancel();
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
                            String text = content == null ? null : content.getString("text");
                            if (text != null) {
                                chatMessageDto.setText(text);
                                finalText.append(text);
                                sink.next(chatMessageDto);
                            }
                        }

                        if ("agent_thought_chunk".equals(jsonObject.getString("subType"))) {
                            CallMessage chatMessageDto = new CallMessage();
                            chatMessageDto.setId(data.getString("request_id"));
                            chatMessageDto.setType(MessageTypeEnum.THINK);
                            chatMessageDto.setRole(ChatMessageDto.Role.ASSISTANT);
                            JSONObject content = data.getJSONObject("content");
                            String text = content == null ? null : content.getString("text");
                            if (text != null) {
                                chatMessageDto.setText(text);
                                finalText.append(text);
                                sink.next(chatMessageDto);
                            }
                        }

                        if (event.type.equals("tool_call") || "tool_call".equals(jsonObject.getString("subType")) || event.type.equals("tool_call_update") || "tool_call_update".equals(data.getString("subType"))) {
                            Consumer<ComponentExecutingDto> componentExecutingConsumer = agentContext.getComponentExecutingConsumer();
                            if (componentExecutingConsumer != null && data.getString("toolCallId") != null) {
                                String status = data.getString("status");
                                ComponentExecutingDto componentExecutingDto = buildComponentExecutingDto(data, componentExecutingDtoMap, agentContext.getUser().getLangMap());
                                if (data.getString("title") != null && data.getString("title").contains("nuwax_ask_question")) {
                                    Object input = data.get("rawInput");
                                    componentExecutingDto = buildExecutingEvent(eventMap, I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Event.AskQuestion"), ComponentExecutingDto.SubEventTypeEnum.ASK_QUESTION, input, data.getString("toolCallId"));
                                    if ("completed".equals(status) || "failed".equals(status)) {
                                        componentExecutingConsumer.accept(componentExecutingDto);
                                    }
                                } else if (data.getString("title") != null && data.getString("title").contains("nuwax_render_openui")) {
                                    Object input = data.get("rawInput");
                                    componentExecutingDto = buildExecutingEvent(eventMap, I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Event.renderUI"), ComponentExecutingDto.SubEventTypeEnum.RENDER_UI, input, data.getString("toolCallId"));
                                    if ("completed".equals(status) || "failed".equals(status)) {
                                        componentExecutingConsumer.accept(componentExecutingDto);
                                    }
                                } else {
                                    ComponentExecutingDto componentExecutingDto1 = eventMap.get(data.getString("toolCallId"));
                                    // 跳过 REQUEST_PERMISSION 事件：它的 input 是审批 payload（含 options 审批选项），
                                    // 不能被 tool_call 的 rawInput 覆盖。external_directory 预检权限与它保护的
                                    // write/edit/bash 共用同一个 toolCallId，若在此覆盖，前端会丢失 options、
                                    // 渲染不出审批框，导致 agent 卡死。命中 REQUEST_PERMISSION 时落到 else，
                                    // 按 tool_call 自身的 ToolCall 事件正常处理。
                                    if (componentExecutingDto1 != null
                                            && componentExecutingDto1.getSubEventType() != ComponentExecutingDto.SubEventTypeEnum.REQUEST_PERMISSION) {
                                        componentExecutingDto = componentExecutingDto1;
                                        Object input = data.get("rawInput");
                                        if (input instanceof String && JSON.isValid(input.toString())) {
                                            input = JSON.parseObject(input.toString());
                                        }
                                        if (input != null) {
                                            componentExecutingDto.getResult().setInput(input);
                                        }
                                        Object output = data.get("rawOutput");
                                        if (output instanceof String && JSON.isValid(output.toString())) {
                                            output = JSON.parseObject(output.toString());
                                        }
                                        componentExecutingDto.getResult().setData(output);
                                        componentExecutingDto.getResult().setEndTime(System.currentTimeMillis());
                                        if ("completed".equals(status) || "failed".equals(status)) {
                                            componentExecutingDto.getResult().setSuccess("completed".equals(status));
                                            componentExecutingConsumer.accept(componentExecutingDto);
                                        }
                                    } else {
                                        // truncate long text
                                        truncateLongText(data);
                                        componentExecutingConsumer.accept(componentExecutingDto);
                                    }
                                }
                                // 添加桌面打开事件
                                if (agentContext.getAgentConfig().getHideDesktop() == null || agentContext.getAgentConfig().getHideDesktop() != 1) {
                                    if (data.getString("title") != null && (data.getString("title").contains("mcp__chrome") || data.getString("title").contains("chrome-tools") || data.getString("title").contains("navigate_page")) && !subEventTypeEnumSet.contains(ComponentExecutingDto.SubEventTypeEnum.OPEN_DESKTOP)) {
                                        subEventTypeEnumSet.add(ComponentExecutingDto.SubEventTypeEnum.OPEN_DESKTOP);
                                        componentExecutingDto = buildExecutingEvent(eventMap, I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Event.OpenDesktop"), ComponentExecutingDto.SubEventTypeEnum.OPEN_DESKTOP, null, null);
                                        componentExecutingConsumer.accept(componentExecutingDto);
                                    }
                                }
                            }
                        }
                        if (event.type.equals("acpRequestPermission") || "request_permission".equals(jsonObject.getString("subType"))) {
                            Consumer<ComponentExecutingDto> componentExecutingConsumer = agentContext.getComponentExecutingConsumer();
                            String toolCallId = data.getString("tool_call_id");
                            ComponentExecutingDto componentExecutingDto = buildExecutingEvent(eventMap, I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Event.RequestPermission"), ComponentExecutingDto.SubEventTypeEnum.REQUEST_PERMISSION, data, toolCallId);
                            @SuppressWarnings("unchecked")
                            List<Map<String, Object>> permissionRequests = (List<Map<String, Object>>) agentContext.getVariableParams().computeIfAbsent("permissionRequests", k -> new ArrayList<>());
                            permissionRequests.add(Map.of(
                                    "toolCallId", toolCallId,
                                    "agentId", agentContext.getAgentConfig().getId().toString(),
                                    "useState", agentContext.isUseSate(),
                                    "data", data
                            ));
                            componentExecutingConsumer.accept(componentExecutingDto);
                        }
                        if (event.type.equals("plan") || "plan".equals(jsonObject.getString("subType"))) {
                            Consumer<ComponentExecutingDto> componentExecutingConsumer = agentContext.getComponentExecutingConsumer();
                            if (componentExecutingConsumer != null) {
                                ComponentExecutingDto componentExecutingDto = buildComponentExecutingPlan(data, agentContext.getUser().getLangMap());
                                if (componentExecutingDto == null) {
                                    return;
                                }
                                componentExecutingConsumer.accept(componentExecutingDto);
                            }
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        if (!completed.get()) {
                            sink.error(error);
                            log.error("subscribe error", error);
                        } else {
                            log.info("subscribe complete, onError");
                        }
                    }

                    @Override
                    public void onComplete() {
                        if (!completed.get()) {
                            sink.complete();
                        }
                    }
                });
                sseSubscriptionAtomicReference.set(subscription);
            }).exceptionally(throwable -> {
                log.error("subscribe error", throwable);
                if (throwable.getCause() != null && throwable.getCause() instanceof TimeoutException) {
                    String timeoutMsg = I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Sandbox.Error.InitializationTimeout");
                    CallMessage callMessage = buildChatFinishedMessage(agentContext, "error");
                    callMessage.setText("\n```\n" + timeoutMsg + "\n```");
                    sink.next(callMessage);
                    sink.error(new TimeoutException(timeoutMsg));
                    return null;
                }
                sink.error(throwable);
                return null;
            });
        } catch (Throwable e) {
            sink.error(e);
        }
    }

    private boolean isProjectConv(ConversationDto conversation) {
        if (ProjectTypeEnum.NormalProject.name().equals(conversation.getDevTargetType()) || ProjectTypeEnum.UserApp.name().equals(conversation.getDevTargetType())) {
            return true;
        }
        return false;
    }

    /**
     * 组装沙箱要用的系统提示词：智能体自己的系统提示词 + 依赖安装说明 + <b>场景段</b>。
     *
     * <p>场景段两种，<b>互斥</b>：
     * <ul>
     *   <li>IM 聊天（群聊 / 私聊）→《智能体参与聊天方案》正文 + 回复纪律，见 {@link #imSystemPromptTail}；</li>
     *   <li>其余且不是资料库文档编辑 → {@link Prompts#TASK_AGENT_OUTPUT_PROMPT}。</li>
     * </ul>
     * 互斥是这里的<b>关键不变量</b>：两段都是「该怎么回话」的指令，同一次运行两段都带，
     * 沙箱里的智能体就同时收到两套口径（一套要求走 IM 技能发消息、一套要求把结果写进会话输出）。
     *
     * <p>★抽成静态方法同样只为<b>可测</b>：判据原本埋在 {@link #executeTask} 里，要真起沙箱才能跑到；
     * 而挂错一段提示词<b>不会报错</b>，只会让智能体悄悄换一副脾气 —— 属于必须被单测钉住的静默失败。
     * 逻辑与原内联写法逐句等价，未做任何语义调整。
     */
    public static String buildSystemPrompt(AgentContext agentContext, ConversationDto conversation) {
        String systemPrompt = agentContext.getAgentConfig().getSystemPrompt();
        if (!isGroupMessage(agentContext) && !isP2pMessage(agentContext) && (conversation.getVariables() == null || StringUtils.isBlank(conversation.getVariables().getOrDefault("SLUG_ID", "").toString()))) {
            // 非资料库文档编辑时 且 非IM聊天
            systemPrompt = systemPrompt + "\n" + Prompts.TASK_AGENT_OUTPUT_PROMPT;
        }
        // ★IM 聊天场景的方案正文 + 回复纪律一起追加；非 IM 聊天返回空串（判据未变，见 imSystemPromptTail）
        return systemPrompt + imSystemPromptTail(agentContext);
    }

    /**
     * IM 聊天场景追加到系统提示词末尾的整段：《智能体参与聊天方案》正文 + 回复纪律。
     *
     * <p>★抽成静态方法只为<b>可测</b>：这段拼装原本埋在 {@link #executeTask} 里（要真起沙箱才能跑到），
     * 而提示词漏掉一句不会报错，只会让「模型把 msgId、错误码抄进回复」这个毛病悄悄回来 ——
     * 属于必须被单测钉住的静默失败。
     *
     * <p>回复纪律与原生工具腿（{@code ImChatToolProvider#buildReminder}）共用
     * {@link Prompts#IM_REPLY_DISCIPLINE_PROMPT} 同一份常量，两条腿不会漂移。
     */
    public static String imChatSystemPromptSuffix() {
        return Prompts.AGENT_IM_CHAT_SYSTEM_PROMPT + "\n" + Prompts.IM_REPLY_DISCIPLINE_PROMPT;
    }

    /**
     * 系统提示词里「IM 聊天场景」那一整段追加内容；<b>非 IM 聊天返回空串</b>。
     *
     * <p>★把闸门（是不是 IM 聊天）连同内容一起抽出来，是为了让「该带的时候确实带上了」可测：
     * 判据埋在 {@link #executeTask} 里就得真起沙箱才能验，而漏挂提示词不会报错。
     * 判据沿用原有的 {@link #isGroupMessage} / {@link #isP2pMessage}，语义一字未改。
     */
    public static String imSystemPromptTail(AgentContext agentContext) {
        if (agentContext == null || (!isGroupMessage(agentContext) && !isP2pMessage(agentContext))) {
            return "";
        }
        return "\n" + imChatSystemPromptSuffix();
    }

    private String buildP2pReminder(AgentContext agentContext) {
        Map<String, Object> variables = agentContext.getImVars();
        if (variables == null) {
            return "";
        }
        StringBuilder stringBuilder = new StringBuilder("\n<p2p-reminder>\n");
        // ★一行一个键，行尾的 "\n" 一个都不能少：原来 SENDER_ID 那行漏了换行，产物是
        //   「P2P_MESSAGE_SENDER_ID: 1P2P_CONV_ID: 2256...」—— 两个键黏成一行，
        //   SENDER_ID 的值被读成怪字符串、P2P_CONV_ID 根本不成其为行首键。
        //   提示词拼错【不会报错】，只会让沙箱里的智能体拿着黏在一起的怪值去调技能。
        // ★私聊的发送者 id 只能取本次运行的登录用户：IM 的 GROUP_SENDER_ID 是【群聊专属】键
        //   （nuwax-im ImAgentChatService#variableParams 注释「私聊【不送】」），
        //   而私聊派发时 4.10 的 .userId(真人) 就是发消息的那个人本人。
        stringBuilder.append("P2P_MESSAGE_SENDER_ID: ").append(agentContext.getUser().getId()).append("（如果用户没有特别指向说明，请使用该值作为 to-user-id 参数进行回复）").append("\n");
        // ★名字两分支都送（imSenderName 在 variableParams 的公共位置），原来只有群提醒用了它：
        //   私聊里模型同样需要知道「在跟谁说话」——称呼、以及按人分开存长期记忆都要靠它。
        stringBuilder.append("P2P_MESSAGE_SENDER_NAME: ").append(orEmpty(variables.get("imSenderName"))).append("\n");
        stringBuilder.append("P2P_CONV_ID: ").append(orEmpty(variables.get("imConvId")));
        if (variables.containsKey("SPACE_ID")) {
            stringBuilder.append("\n").append("优先使用的资料库spaceId: ").append(orEmpty(variables.get("SPACE_ID")));
        }
        // ★闭合标签原来写成 <p2p-reminder>（少一个 "/"）：这个块永远不闭合。
        //   标签不闭合【不会报错】，只会让后面追加的整段提示词都被读成这个块的内部约束。
        stringBuilder.append("\n</p2p-reminder>\n");
        stringBuilder.append("\n").append(USE_IM_SKILL_PROMPT);
        return stringBuilder.toString();
    }

    private String buildGroupReminder(AgentContext agentContext) {
        Map<String, Object> variables = agentContext.getImVars();
        if (variables == null) {
            return "";
        }
        StringBuilder stringBuilder = new StringBuilder("\n<group-reminder>\n");
        // ★缺 GROUP_SENDER_ID 时原来回落到 AGENT_VIRTUAL_USER_ID —— 那等于告诉模型「在群里说话的人
        //   是你自己」，它据此会把别人的提问当成自己的发言、也会把回复对象搞错。宁可留空：
        //   留空是「我不知道是谁说的」，回落是「我知道，是你」——后者是错的且【不报错】。
        //   （GROUP_SENDER_ID 是群聊专属键，IM 侧群分支一定会送，走到留空说明上游出了问题。）
        stringBuilder.append("GROUP_MESSAGE_SENDER_ID: ").append(orEmpty(variables.get("GROUP_SENDER_ID")));
        stringBuilder.append("\nGROUP_MESSAGE_SENDER_NAME: ").append(orEmpty(variables.get("imSenderName")));
        stringBuilder.append("\n").append("GROUP_CONV_ID: ").append(orEmpty(variables.get("GROUP_CONV_ID"))).append("（如果用户没有特别指向说明，请使用该值作为 conv-id 参数进行回复）");
        if (variables.containsKey("SPACE_ID")) {
            stringBuilder.append("\n").append("群资料库spaceId: ").append(orEmpty(variables.get("SPACE_ID")));
        }
        // ★闭合标签原来同样少一个 "/"，块永远不闭合
        stringBuilder.append("\n</group-reminder>\n");
        // ★不用 getOrDefault(...).equals(...)：键在、值为 null 时它交出 null，这里会直接 NPE，
        //   而这段拼在每轮群聊对话的主链路上（炸掉整轮对话）。常量在前也顺带躲开这个坑。
        if (ImChatIdentity.MODE_MENTION.equals(orEmpty(variables.get("imMode")))) {
            stringBuilder.append("\n").append(USE_IM_SKILL_PROMPT);
        } else {
            stringBuilder.append("\n").append("<system-reminder>你现在在群里处于监听模式，无需每个请求都进行回复，消息与你无关或你无法提供有价值的信息时不需要回复，需要你回复时请使用 nuwax-im 技能回复，会话直接输出的内容用户不会看到</system-reminder>");
        }
        return stringBuilder.toString();
    }

    /**
     * 提示词里的取值统一走这里：null → 空串。
     *
     * <p>★刻意不用 {@code Map#getOrDefault}：它在<b>键存在、值为 null</b> 时照样交出 null，
     * 于是提示词里会出现「null」四个字（模型会把它当成一个真实的 id / 名字读）。
     * 这类错误<b>不报错</b>，只会让沙箱里的智能体拿着字面量 {@code null} 去调技能。
     *
     * <p>返回 {@code Object} 是为了让 {@code StringBuilder#append(Object)} 直接吃，调用点不必再转型。
     */
    private static Object orEmpty(Object value) {
        return value == null ? "" : value;
    }

    private void truncateLongText(JSONObject jsonObject) {
        //截断长文本，每个value如果是字符串类型，超过1k，则截断，保留开始、结束各400字符，中间用 ...[truncated]... 代替
        JsonTruncateUtil.truncate(jsonObject);
    }

    private static AgentRequest.AgentServer buildAgentServer(AgentContext agentContext, SandboxServerConfig.SandboxServer sandboxServer, ModelConfigDto modelConfig) {
        //需要审批的工具汇总
        List<Map<String, Object>> toolApprovalRules = new ArrayList<>();
        List<String> patterns = new ArrayList<>();
        toolApprovalRules.add(Map.of("action", "ask", "patterns", patterns));
        toolApprovalRules.add(Map.of("action", "allow", "patterns", List.of("*ask_question*", "*nuwax_render_openui*")));
        agentContext.getAgentConfig().getAgentComponentConfigList().forEach(agentComponentConfigDto -> {
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Mcp && agentComponentConfigDto.getBindConfig() instanceof McpBindConfigDto mcpBindConfig) {
                if (YesOrNoEnum.Y.getKey().equals(mcpBindConfig.getCallApproval())) {
                    patterns.add("*" + mcpBindConfig.getToolName());
                }
            } else if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Plugin && agentComponentConfigDto.getTargetConfig() instanceof PluginDto plugin
                    && agentComponentConfigDto.getBindConfig() instanceof PluginBindConfigDto pluginBindConfig) {
                if (YesOrNoEnum.Y.getKey().equals(pluginBindConfig.getCallApproval())) {
                    String functionName = plugin.getFunctionName();
                    if (StringUtils.isBlank(functionName)) {
                        functionName = "plugin" + plugin.getId();
                    }
                    patterns.add("*" + functionName);
                }
            } else if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Workflow && agentComponentConfigDto.getTargetConfig() instanceof WorkflowConfigDto workflowConfig
                    && agentComponentConfigDto.getBindConfig() instanceof WorkflowBindConfigDto workflowBindConfig) {
                if (YesOrNoEnum.Y.getKey().equals(workflowBindConfig.getCallApproval())) {
                    String functionName = workflowConfig.getFunctionName();
                    if (StringUtils.isBlank(functionName)) {
                        functionName = "workflow" + workflowConfig.getId();
                    }
                    patterns.add("*" + functionName);
                }
            }
        });

        Map<String, String> env = new HashMap<>();
        //追加环境变量
        String logPath = "{PREFIX_WORKSPACE_DIR}/" + agentContext.getConversationId() + "/logs";
        if (agentContext.getConversation().getType() == Conversation.ConversationType.DevDebug) {
            logPath = "{PREFIX_WORKSPACE_DIR}/" + agentContext.getAgentConfig().getDevAgentConversationId() + "/.logs";
        }
        TenantConfigDto tenantConfig = agentContext.getTenantConfig();
        String siteUrl = tenantConfig.getSiteUrl();
        siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
        env.put("PLATFORM_BASE_URL", siteUrl);
        env.put("AGENT_ID", String.valueOf(agentContext.getAgentConfig().getId()));
        env.put("SANDBOX_ID", sandboxServer.getServerId());
        env.put("SYS_USER_ID", String.valueOf(agentContext.getUserId()));
        env.put("CONVERSATION_ID", agentContext.getConversationId());
        env.put("SYS_USER_UID", String.valueOf(agentContext.getUser().getUid()));
        env.put("SYS_USER_LANG", String.valueOf(agentContext.getUser().getLang()));
        if (agentContext.getVariableParams().containsKey("SANDBOX_ACCESS_KEY")) {
            env.put("SANDBOX_ACCESS_KEY", agentContext.getVariableParams().get("SANDBOX_ACCESS_KEY").toString());
        }
        // IM 相关环境变量：群聊/私聊触发时才有（非 IM 运行返回空 Map，一个键都不加），见 buildImEnv
        env.putAll(buildImEnv(agentContext));
        //Agent开发项目时，将对应的环境变量添加到容器中
        ConversationDto conversation = agentContext.getConversation();
        if (conversation.getDevTargetType() != null) {
            // 通用项目标识：支付开放接口等用 (DEV_APP_ID, DEV_APP_TYPE) 定位项目
            env.put("DEV_APP_ID", String.valueOf(conversation.getDevTargetId()));
            env.put("DEV_APP_TYPE", conversation.getDevTargetType());
            // 各类型专属变量（向后兼容，已有技能可能引用）
            if (conversation.getDevTargetType().equalsIgnoreCase(ProjectTypeEnum.Agent.name())) {
                env.put("DEV_AGENT_ID", String.valueOf(conversation.getDevTargetId()));
            }
            if (conversation.getDevTargetType().equalsIgnoreCase(ProjectTypeEnum.Skill.name())) {
                env.put("DEV_SKILL_ID", String.valueOf(conversation.getDevTargetId()));
            }
            if (conversation.getDevTargetType().equalsIgnoreCase(ProjectTypeEnum.Plugin.name())) {
                env.put("DEV_PLUGIN_ID", String.valueOf(conversation.getDevTargetId()));
            }
            if (conversation.getDevTargetType().equalsIgnoreCase(ProjectTypeEnum.Connector.name())) {
                env.put("DEV_PROVIDER_ID", String.valueOf(conversation.getDevTargetId()));
            }
            env.put("DEV_SPACE_ID", String.valueOf(conversation.getDevSpaceId()));
        }

        Map<String, Object> extra = agentContext.getAgentConfig().getExtra();
        if (extra != null && extra.containsKey("createdFrom") && "VibeCoding".equalsIgnoreCase(extra.get("createdFrom").toString())) {
            Map<String, Map<String, String>> platforms = null;
            AgentPublishVersionDto latestVersion = null;
            String workspacePrefix = "/home/user/";
            if (sandboxServer.getScope() == SandboxScopeEnum.USER) {
                workspacePrefix = "{PREFIX_WORKSPACE_DIR}/";
            }
            String cmd = workspacePrefix + agentContext.getAgentConfig().getDevAgentConversationId() + "/node_modules/.bin/tsx";
            String arg = workspacePrefix + agentContext.getAgentConfig().getDevAgentConversationId() + "/src/index.ts";
            if (agentContext.getConversation().getType() != Conversation.ConversationType.DevDebug) {
                List<AgentPublishVersionDto> publishVersion = agentContext.getAgentConfig().getPublishVersion();
                if (publishVersion == null || publishVersion.isEmpty()) {
                    throw new RuntimeException("Missing agent installation package");
                }
                latestVersion = publishVersion.stream().filter(agentPublishVersionDto -> agentPublishVersionDto.getLatest() != null && agentPublishVersionDto.getLatest()).findFirst().orElse(publishVersion.get(0));
                platforms = new HashMap<>();
                for (AgentPublishVersionDto.PackageArtifact packageArtifact : latestVersion.getPackages()) {
                    platforms.put(packageArtifact.getPlatform(), Map.of("url", packageArtifact.getUrl()));
                }
                arg = workspacePrefix + "acp-agent/" + agentContext.getAgentConfig().getId() + "/" + latestVersion.getVersion() + "/dist/bundle.mjs";
                cmd = "node";
            }

            env.put("MAX_TOKENS", String.valueOf(modelConfig.getMaxTokens()));
            env.put("MAX_CONTEXT_TOKENS", String.valueOf(modelConfig.getMaxContextTokens()));
            env.put("API_PROTOCOL", modelConfig.getApiProtocol().name());//OpenAI、Anthropic
            if (modelConfig.getApiProtocol() == ModelApiProtocolEnum.OpenAI) {
                env.put("OPENAI_API_KEY", "{MODEL_PROVIDER_API_KEY}");
                env.put("OPENAI_BASE_URL", "{MODEL_PROVIDER_BASE_URL}");
                env.put("OPENAI_MODEL", modelConfig.getModel());
            } else if (modelConfig.getApiProtocol() == ModelApiProtocolEnum.Anthropic) {
                env.put("ANTHROPIC_API_KEY", "{MODEL_PROVIDER_API_KEY}");
                if (sandboxServer.getScope() == SandboxScopeEnum.USER) {
                    env.put("ANTHROPIC_AUTH_TOKEN", "{MODEL_PROVIDER_API_KEY}");
                }
                env.put("ANTHROPIC_BASE_URL", "{MODEL_PROVIDER_BASE_URL}");
                env.put("ANTHROPIC_MODEL", modelConfig.getModel());
                env.put("CLAUDE_CODE_DISABLE_EXPLORE_INHERIT_CAP", "1");
            }

            if (modelConfig.getIsReasonModel() != null && modelConfig.getIsReasonModel() == 1) {
                int maxThinkingTokens = modelConfig.getMaxTokens() == null ? 2048 : modelConfig.getMaxTokens() / 2;
                env.put("MAX_THINKING_TOKENS", maxThinkingTokens > 4096 ? "4096" : Integer.toString(maxThinkingTokens));
                env.put("DISABLE_THINKING", "0");
            } else {
                env.put("DISABLE_THINKING", "1");
            }
            env.put("LOG_LEVEL", agentContext.getConversation().getType() == Conversation.ConversationType.DevDebug ? "debug" : "info");
            env.put("LOG_DIR", logPath);

            return AgentRequest.AgentServer.builder()
                    .agent_id(agentContext.getAgentConfig().getId().toString())
                    .command(cmd)
                    .args(List.of(arg))
                    .env(env)
                    .agent_mode(agentContext.getAgentMode())
                    .version(latestVersion == null ? null : latestVersion.getVersion())
                    .platforms(platforms)
                    .tool_approval_rules(toolApprovalRules)
                    .build();
        }

        if (agentContext.getAgentConfig().getSystemPrompt() != null && agentContext.getAgentConfig().getSystemPrompt().contains("<ENGINE_USE_CODEX>")
                && modelConfig.getApiProtocol() == ModelApiProtocolEnum.OpenAI) {
            env.put("CODEX_MODEL", modelConfig.getModel());
            env.put("CODEX_BASE_URL", "{MODEL_PROVIDER_BASE_URL}");
            env.put("CODEX_API_KEY", "{MODEL_PROVIDER_API_KEY}");
            env.put("CODEX_MODEL_CONTEXT_WINDOW", String.valueOf(modelConfig.getMaxContextTokens()));
            env.put("CODEX_WIRE_API", agentContext.getAgentConfig().getSystemPrompt().contains("<API_USE_RESPONSES>") ? "responses" : "chat");
            env.put("RUST_LOG", "info,codex_rust_genai_bridge=debug");
            env.put("CODEX_LOG_DIR", logPath);
            return AgentRequest.AgentServer.builder()
                    .agent_id("nuwax-codex-acp-ts")
                    .command("nuwax-codex-acp-ts")
                    .args(List.of())
                    .env(env)
                    .agent_mode(agentContext.getAgentMode())
                    .tool_approval_rules(toolApprovalRules)
                    .build();
        }

        // 不支持claudecode的模型直接使用opencode
        if (!isClaudeCodeSupport(modelConfig)) {
            env.put("OPENCODE_LOG_DIR", logPath);
            env.put("OPENCODE_MAX_TOKENS", String.valueOf(modelConfig.getMaxTokens()));
            env.put("OPENCODE_EXPERIMENTAL_OUTPUT_TOKEN_MAX", String.valueOf(modelConfig.getMaxTokens()));
            env.put("OPENCODE_MAX_CONTEXT_TOKENS", String.valueOf(modelConfig.getMaxContextTokens()));
            env.put("OPENCODE_PERMISSION", "{\"bash\":\"ask\",\"edit\":\"ask\",\"question\":\"deny\"}");
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
            if (CollectionUtils.isNotEmpty(modelConfig.getTypes())) {
                env.put("OPENCODE_FORCE_INPUT_MODALITIES", String.join(",", modelConfig.getTypes().stream().map(t -> t.name().toLowerCase()).toList()));
            }

            return AgentRequest.AgentServer.builder()
                    .agent_id("nuwaxcode")
                    .command("nuwaxcode")
                    .args(List.of("acp"))
                    .env(env)
                    .agent_mode(agentContext.getAgentMode())
                    .tool_approval_rules(toolApprovalRules)
                    .build();
        } else {
            env.put("ANTHROPIC_API_KEY", "{MODEL_PROVIDER_API_KEY}");
            // 用户个人电脑运行时增加ANTHROPIC_AUTH_TOKEN
            if (sandboxServer.getScope() == SandboxScopeEnum.USER) {
                env.put("ANTHROPIC_AUTH_TOKEN", "{MODEL_PROVIDER_API_KEY}");
            }
            env.put("ANTHROPIC_BASE_URL", "{MODEL_PROVIDER_BASE_URL}");
            env.put("ANTHROPIC_MODEL", modelConfig.getModel());
            env.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
            env.put("DISABLE_ERROR_REPORTING", "1");
            env.put("DISABLE_GROWTHBOOK", "1");
            env.put("CLAUDE_CODE_DISABLE_1M_CONTEXT", "1");
            env.put("CLAUDE_CODE_DISABLE_EXPLORE_INHERIT_CAP", "1");
            if (modelConfig.getMaxContextTokens() != null && modelConfig.getMaxContextTokens() > 0) {
                env.put("CLAUDE_CODE_AUTO_COMPACT_WINDOW", String.valueOf(modelConfig.getMaxContextTokens()));
                env.put("CLAUDE_AUTOCOMPACT_PCT_OVERRIDE", "90");
            }
            if (modelConfig.getIsReasonModel() != null && modelConfig.getIsReasonModel() == 1) {
                int maxThinkingTokens = modelConfig.getMaxTokens() == null ? 2048 : modelConfig.getMaxTokens() / 2;
                env.put("MAX_THINKING_TOKENS", maxThinkingTokens > 4096 ? "4096" : Integer.toString(maxThinkingTokens));//最大值暂时支持4096
            } else {
                env.put("CLAUDE_CODE_DISABLE_THINKING", "1");
            }
        }
        return AgentRequest.AgentServer.builder()
                .agent_id("claude-code-acp-ts")
                .command("claude-code-acp-ts")
                .args(List.of())
                .env(env)
                .agent_mode(agentContext.getAgentMode())
                .tool_approval_rules(toolApprovalRules)
                .build();
    }

    /**
     * 把本次运行的 IM 身份写进沙箱进程的环境变量（{@code IM_*} 前缀）。
     *
     * <p>★为什么要进环境变量：沙箱里的技能脚本（如 nuwax-im）要自己决定「往哪个群回、回给谁、
     * 引用哪条消息」，而这批信息此前<b>只以提示词文本</b>（{@code <group-reminder>} /
     * {@code <p2p-reminder>}，见 {@link #buildGroupReminder} / {@link #buildP2pReminder}）交给模型，
     * <b>脚本层面读不到</b> —— 进程内没有第二个稳定可见的通道。环境变量就是那个通道。
     *
     * <p>★口径必须与那两段提醒严格一致，两条腿不能漂移：
     * <ul>
     *   <li>场景判据用 {@link ImChatIdentity#isGroupScenario()}（「群键在不在」留下的痕迹），
     *       <b>不用</b> {@code IS_GROUP_MESSAGE} —— 那是上游写入、可被伪造的布尔值；</li>
     *   <li>群场景的发送者<b>只认</b> {@code GROUP_SENDER_ID}，读不到就<b>不写这个键</b>，
     *       绝不回落到智能体自己的身份 —— 回落等于告诉脚本「群里说话的是你自己」；</li>
     *   <li>私聊的发送者就是本次运行的登录用户（IM 私聊<b>不送</b> {@code GROUP_SENDER_ID}，
     *       而 4.10 的 userId 正是发消息的那个人）。</li>
     * </ul>
     *
     * <p>★缺失的键一律<b>不写</b>：不写空串、更不写字面量 {@code "null"} ——
     * 脚本拿到 {@code "null"} 会当成一个真实 id 用（与提示词侧 {@link #orEmpty} 是同一个坑）。
     *
     * <p>★不注入 {@code AGENT_VIRTUAL_USER_ID} / {@code GROUP_VIRTUAL_USER_ID}：那两个是
     * 「以谁的名义发消息」的身份，由服务端按 AK 的 ext 判定（见 {@code NuwaxImSandboxController}），
     * 沙箱既不需要自己传、传了也不算数；写进环境变量只是把身份 id 白送出去。这里只给
     * <b>脚本做决策真正要用</b>的业务上下文。
     *
     * @return 非 IM 触发（普通网页会话、定时任务等）返回<b>空 Map</b>，调用方 putAll 后一个键都不多
     */
    public static Map<String, String> buildImEnv(AgentContext agentContext) {
        ImChatIdentity identity = ImChatIdentity.of(agentContext);
        if (!identity.isImVarsPresent()) {
            // 没有 imVars 就不是 IM 派发进来的：写入任何 IM_* 键都会让脚本误以为自己在群里/私聊里
            return Map.of();
        }
        Map<String, String> imEnv = new LinkedHashMap<>();
        // 场景恒写：本方法只在 imVars 非空时走到这里，而 group / p2p 是脚本分流的第一判据
        imEnv.put("IM_SCENARIO", identity.isGroupScenario() ? "group" : "p2p");
        putIfPresent(imEnv, "IM_MODE", identity.getMode());
        putIfPresent(imEnv, "IM_CONV_ID", identity.getConvId());
        putIfPresent(imEnv, "IM_MSG_ID", identity.getTriggerMsgId());
        putIfPresent(imEnv, "IM_SENDER_NAME", identity.getSenderName());
        putIfPresent(imEnv, "IM_SPACE_ID", identity.getSpaceId());
        if (identity.isGroupScenario()) {
            putIfPresent(imEnv, "IM_GROUP_CONV_ID", identity.getGroupConvId());
            putIfPresent(imEnv, "IM_SENDER_ID", identity.getSenderId());
        } else {
            putIfPresent(imEnv, "IM_SENDER_ID", identity.getSelfUserId());
        }
        return imEnv;
    }

    /**
     * 值非 null 且非空串才写进环境变量。
     *
     * <p>★刻意<b>不</b>用 {@code String.valueOf(null)} 那种写法：它产出字面量 {@code "null"}，
     * 而脚本会把 {@code "null"} 当成一个真实的 id / 名字用 —— 这类错误不报错，
     * 只会让沙箱里的智能体拿着四个字母去调技能。缺键比错值安全：缺了脚本能判「不知道」，
     * 错了它只会照做。
     */
    private static void putIfPresent(Map<String, String> env, String key, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value);
        if (!text.isEmpty()) {
            env.put(key, text);
        }
    }

    private static boolean isClaudeCodeSupport(ModelConfigDto modelConfig) {
        if (modelConfig.getDescription() != null && modelConfig.getDescription().contains("use nuwax_cli")) {
            return false;
        }
        return modelConfig.getApiProtocol() == ModelApiProtocolEnum.Anthropic;
    }

    private static boolean isGroupMessage(AgentContext agentContext) {
        Map<String, Object> variables = agentContext.getImVars();
        log.debug("variables {}", variables);
        try {
            if (variables != null && variables.containsKey("IS_GROUP_MESSAGE") && Boolean.valueOf(variables.get("IS_GROUP_MESSAGE").toString())) {
                log.debug("isGroupMessage variables {}", variables);
                return true;
            }
        } catch (Exception e) {
            log.warn("Boolean.valueOf() ERROR", e);
        }
        return false;
    }

    private static boolean isP2pMessage(AgentContext agentContext) {
        Map<String, Object> variables = agentContext.getImVars();
        log.debug("variables {}", variables);
        if (variables != null && variables.containsKey("AGENT_VIRTUAL_USER_ID")) {
            log.debug("isP2pMessage variables {}", variables);
            return true;
        }
        return false;
    }

    /**
     * 模型 BaseUrl 是否指向本机 —— 本机模型（本地开发起的推理服务）走不了共享代理，
     * 只有非本机地址才需要改写成「代理地址 + 用户独立 key」（见 {@code executeTask}）。
     *
     * <p>★判据只看 host，且只用字面量/正则判，<b>不做 DNS 解析</b>：每次模型调用都解析一次域名既慢、
     * 又会把「判本机」悄悄变成「判内网」（内网自建模型仍应走代理）。
     *
     * <p>★为什么不能用 {@code String#contains}：它把「出现在任何位置」当成「就是本机」，
     * 于是 {@code https://localhost.example.com/v1}、{@code https://api.x.com/v1?upstream=127.0.0.1}
     * 都会被判成本机而<b>跳过换 key</b>，把全局 key 直发出去；反过来
     * {@code http://[::1]:8000}、{@code http://127.0.0.2:8000} 又漏判成本机、被硬塞进代理。
     *
     * <p>判不出 host（空值/畸形）时按「非本机」处理 —— 宁可多走一次代理，也不放过换 key。
     */
    private static boolean isLocalModelUrl(String url) {
        String host = extractHost(url);
        return host != null && (host.equalsIgnoreCase("localhost")
                || host.equals("::1")
                || LOOPBACK_V4_PATTERN.matcher(host).matches());
    }

    /**
     * 取 URL 的 host，只服务于 {@link #isLocalModelUrl(String)} 的判定，不追求完整 URL 解析。
     *
     * <p>兼容：不带协议头（{@code 127.0.0.1:8000/v1}）、userinfo、IPv6 方括号、端口、路径、query、
     * 首尾空白，以及 FQDN 末尾的点（{@code localhost.} 等价于 {@code localhost}）。
     * 取不到时返回 {@code null}。
     */
    private static String extractHost(String url) {
        if (StringUtils.isBlank(url)) {
            return null;
        }
        var matcher = HOST_PATTERN.matcher(url.trim());
        if (!matcher.lookingAt()) {
            return null;
        }
        String host = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
        if (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host.isEmpty() ? null : host;
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
                    text = message.getText().replaceAll("<user-prompt>[\\s\\S]*?</user-prompt>", "")
                            .replaceAll("<user-reminder>[\\s\\S]*?</user-reminder>", "").trim();
                    if (text.contains("<user-message>") && text.contains("</user-message>")) {
                        text = text.replace("<user-message>", "").replace("</user-message>", "").trim();
                    }
                }
                contextPromptBuilder.append(message.getMessageType()).append(": ").append(takeLastSegmentAfterCustomProcess(text)).append("\n");
            });
            contextPromptBuilder.append("\n</context-message>\n");
        }
    }

    /**
     * ★只取「最后一个组件占位块之后」的那段正文。
     *
     * <p>前端把流式渲染的组件（工作流 / 工具调用 / 计划…）以
     * {@code <div><markdown-custom-process ...></markdown-custom-process></div>} 的形式写回消息正文，
     * 于是落库的一条助手消息常长这样：
     *
     * <pre>
     * 我开始进行设置
     *
     * &lt;div&gt;&lt;markdown-custom-process executeId="call_00_AMsAW57fTdMHv12fHZaq9572" type="Workflow" status="FINISHED" name="灵珠设备控制"&gt;&lt;/markdown-custom-process&gt;&lt;/div&gt;
     *
     * 好的，音量已调到十。
     * </pre>
     *
     * <p>这个占位块是<b>UI 回执</b>、不是模型输出，对下一轮推理没有语义；原样回灌还会让模型
     * 模仿这种格式「演」组件。所以按它切分正文、只保留最后一段 —— 上面那例的结果就是
     * {@code 好的，音量已调到十。}（切分点落在占位块上，块前的铺垫一并丢掉）。
     *
     * <p>三个刻意的取舍：
     * <ul>
     *   <li><b>取最后一个「非空」段，而不是字面上最后一段</b>：正文以占位块结尾时
     *       （{@code "查到了\n\n<div>…</div>"}）字面最后一段是空串，会把整条消息抹平；
     *       退到最后一个非空段才符合「丢噪声、留人话」的本意。</li>
     *   <li><b>整条都是占位块时返回空串</b>：这类消息本就没有正文，留空即可。</li>
     *   <li><b>不含占位块的消息原样返回、不做 trim</b>：绝大多数消息走这条路，
     *       保持既有行为不变（在这里顺手 trim 会悄改所有历史消息的形态）。</li>
     * </ul>
     *
     * @param text 消息正文，可为 null
     * @return 最后一个组件占位块之后的正文；无占位块时原样返回
     */
    private static String takeLastSegmentAfterCustomProcess(String text) {
        // 快速通道：绝大多数消息不含占位块，原样返回、连正则都不跑（每条上下文消息都会走这里）。
        // ★大小写必须与 CUSTOM_PROCESS_BLOCK_PATTERN 的 CASE_INSENSITIVE 对齐，否则大写形态
        // 会被这条守卫拦下、绕过正则（两者口径一旦不一致，就是一个只在脏数据上复现的静默 bug）。
        if (StringUtils.isBlank(text) || !StringUtils.containsIgnoreCase(text, "<markdown-custom-process")) {
            return text;
        }
        String[] segments = CUSTOM_PROCESS_BLOCK_PATTERN.split(text);
        for (int i = segments.length - 1; i >= 0; i--) {
            if (StringUtils.isNotBlank(segments[i])) {
                return segments[i].trim();
            }
        }
        return text;
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
        if (agentContext.getConversation().getType() == Conversation.ConversationType.Development) {
            return;
        }
        if (agentContext.getConversation().getVariables() != null && StringUtils.isNotBlank(agentContext.getConversation().getVariables().getOrDefault("SLUG_ID", "").toString())) {
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
                Object path = ((Map<?, ?>) location).get("path");
                if (path == null) {
                    path = ((Map<?, ?>) location).get("filePath");
                }
                // 只输出可预览的文件（html/md/txt/pdf/doc/docx/xls/xlsx/ppt/pptx 等白名单扩展名）
                if (path != null && isPreviewableFile(path.toString()) && path.toString().contains("/" + agentContext.getConversationId() + "/")
                        && !path.toString().contains("/.")) {
                    text.append(resultFileText.replace("{description}", path.toString()).replace("{file}", path.toString()));
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

    /**
     * 判断 path 是否为可在线预览的文件（扩展名命中 {@link #PREVIEWABLE_EXTENSIONS} 白名单）。
     * <p>path 可能为 URL（含查询参数），先去除查询串再取扩展名，如
     * {@code https://.../report.pdf?x=1} → {@code pdf}。无扩展名或不在白名单返回 false。</p>
     */
    private static boolean isPreviewableFile(String path) {
        if (StringUtils.isBlank(path)) {
            return false;
        }
        String lower = path.toLowerCase();
        int queryIdx = lower.indexOf('?');
        if (queryIdx >= 0) {
            lower = lower.substring(0, queryIdx);
        }
        int dotIdx = lower.lastIndexOf('.');
        if (dotIdx < 0 || dotIdx == lower.length() - 1) {
            return false;
        }
        return PREVIEWABLE_EXTENSIONS.contains(lower.substring(dotIdx + 1));
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


    private static ComponentExecutingDto buildSandboxStartingDto(Map<String, String> langMap) {
        ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
        componentExecuteResult.setExecuteId(UUID.randomUUID().toString().replace("-", ""));
        componentExecuteResult.setStartTime(System.currentTimeMillis());
        componentExecuteResult.setId(-1L);
        componentExecuteResult.setName(I18nUtil.systemMessage(langMap, "Backend.Sandbox.SandboxStarting"));
        componentExecuteResult.setType(ComponentTypeEnum.SandboxStart);
        ComponentExecutingDto componentExecutingDto = new ComponentExecutingDto();
        componentExecutingDto.setTargetId(-1L);
        componentExecutingDto.setName(componentExecuteResult.getName());
        componentExecutingDto.setType(ComponentTypeEnum.SandboxStart);
        componentExecutingDto.setStatus(ExecuteStatusEnum.EXECUTING);
        componentExecutingDto.setResult(componentExecuteResult);
        return componentExecutingDto;
    }

    private static ComponentExecutingDto buildComponentExecutingPlan(JSONObject data, Map<String, String> langMap) {
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
        String title = I18nUtil.systemMessage(langMap, "Backend.Sandbox.PlanTitle", String.valueOf(completedEntries.size()), String.valueOf(entries.size()));
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
        if (YesOrNoEnum.Y.getKey().equals(agentContext.getAgentConfig().getEnableAskQuestion())) {
            contextServers.put("ask-question", askQuestionMcp);
        }
        ConversationDto conversation = agentContext.getConversation();
        if (conversation != null && ProjectTypeEnum.UserApp.name().equals(conversation.getDevTargetType())) {
            contextServers.put("dbx", appDevDBMcp);
        }

        //TODO 加配置
        contextServers.put("nuwax-openui", openUIMcp);

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

    private static ComponentExecutingDto buildComponentExecutingDto(JSONObject jsonObject, Map<String, ComponentExecutingDto> componentExecutingDtoMap, Map<String, String> langMap) {
        String toolCallId = jsonObject.getString("toolCallId");
        ComponentExecutingDto componentExecutingDto = componentExecutingDtoMap.get(toolCallId);
        ComponentExecuteResult componentExecuteResult;
        if (componentExecutingDto == null) {
            componentExecutingDto = new ComponentExecutingDto();
            componentExecutingDto.setTargetId(-1L);
            componentExecutingDto.setType(ComponentTypeEnum.ToolCall);
            componentExecutingDtoMap.put(toolCallId, componentExecutingDto);
            componentExecuteResult = new ComponentExecuteResult();
            componentExecuteResult.setExecuteId(toolCallId);
            componentExecuteResult.setStartTime(System.currentTimeMillis());
            componentExecuteResult.setId(-1L);
            componentExecuteResult.setType(ComponentTypeEnum.ToolCall);
            componentExecutingDto.setResult(componentExecuteResult);
        } else {
            componentExecuteResult = componentExecutingDto.getResult();
        }

        String title = jsonObject.getString("title");
        if (title != null) {
            componentExecutingDto.setOriginalTitle(title);
        } else {
            title = componentExecutingDto.getOriginalTitle();
        }
        if (title != null && title.contains("mcp__chrome")) {
            title = title.replace("mcp__chrome-devtools", "mcp__browser");
        }

        Object rawInput = jsonObject.get("rawInput");
        if (rawInput instanceof JSONObject && !((JSONObject) rawInput).isEmpty()) {
            componentExecuteResult.setInput(rawInput);
            if (((JSONObject) rawInput).getString("description") != null) {
                title = ((JSONObject) rawInput).getString("description");
            }
        }
        if (rawInput instanceof JSONArray && !((JSONArray) rawInput).isEmpty()) {
            componentExecuteResult.setInput(rawInput);
        }

        //kind
        String kind = jsonObject.getString("kind");
        if (kind != null) {
            componentExecuteResult.setKind(kind);
        } else {
            kind = componentExecuteResult.getKind();
        }

        // 文件操作
        if ("edit".equals(kind) && CollectionUtils.isEmpty(componentExecuteResult.getLocations())) {
            List<Object> locations = jsonObject.getJSONArray("locations");
            if (locations != null) {
                componentExecuteResult.setLocations(locations);
            } else {
                Object content = jsonObject.get("content");
                String path = null;
                if (content instanceof JSONArray && !((JSONArray) content).isEmpty()) {
                    List<Object> locations0 = new ArrayList<>();
                    for (Object item : ((JSONArray) content)) {
                        if (item instanceof JSONObject) {
                            JSONObject location = new JSONObject();
                            path = ((JSONObject) item).getString("filePath");
                            if (path == null) {
                                continue;
                            }
                            location.put("path", path);
                            location.put("filePath", path);
                            location.put("file_path", path);
                            locations0.add(location);
                        }
                    }
                    componentExecuteResult.setLocations(locations0);
                }
                if (path != null && title != null && (title.startsWith("Write") || title.startsWith("Edit") || title.startsWith("Read"))) {
                    title = title + " " + path;
                    componentExecutingDto.setOriginalTitle(title);
                }
            }
        }

        if ("execute".equals(kind) && title != null && !title.startsWith("Terminal")) {
            title = "Terminal " + title;
        }

        if (title != null && (title.startsWith("Write") || title.startsWith("Edit"))) {
            if (componentExecuteResult.getInput() == null) {
                componentExecuteResult.setInput(jsonObject.get("content"));
            }
            if (jsonObject.get("rawOutput") != null) {
                componentExecuteResult.setData(jsonObject.get("rawOutput"));
            }
        }

        if (StringUtils.isNotBlank(title)) {
            title = rewriteTitle(title, jsonObject, langMap);
            if (title != null) {
                componentExecuteResult.setName(title);
                componentExecutingDto.setName(title);
            }
        }
        componentExecuteResult.setKind(kind);

        String status = jsonObject.getString("status");
        if (status != null && (status.equals("completed") || status.equals("failed"))) {
            componentExecuteResult.setEndTime(System.currentTimeMillis());
            componentExecuteResult.setSuccess(!"failed".equals(jsonObject.getString("status")));
            componentExecutingDto.setStatus(status.equals("completed") ? ExecuteStatusEnum.FINISHED : ExecuteStatusEnum.FAILED);
            if (jsonObject.get("content") != null) {
                componentExecuteResult.setData(jsonObject.get("content"));
            }
            return componentExecutingDto;
        }

        componentExecutingDto.setStatus(ExecuteStatusEnum.EXECUTING);
        return componentExecutingDto;
    }

    private static String rewriteTitle(String title, JSONObject jsonObject, Map<String, String> langMap) {
        if ("Skill".equalsIgnoreCase(title)) {
            Object o = jsonObject.get("rawInput");
            if (o instanceof JSONObject) {
                String skillName = ((JSONObject) o).getString("skill");
                String skillName0 = ((JSONObject) o).getString("name");
                if (StringUtils.isNotBlank(skillName)) {
                    return I18nUtil.systemMessage(langMap, "Backend.Sandbox.LoadSkill") + " " + skillName;
                } else if (StringUtils.isNotBlank(skillName0)) {
                    return I18nUtil.systemMessage(langMap, "Backend.Sandbox.LoadSkill") + " " + skillName0;
                } else {
                    return I18nUtil.systemMessage(langMap, "Backend.Sandbox.LoadSkill");
                }
            }
            if (jsonObject.getString("rawOutput") != null && jsonObject.getString("rawOutput").contains("Launching skill")) {
                return I18nUtil.systemMessage(langMap, "Backend.Sandbox.LoadSkill") + " " + jsonObject.getString("rawOutput").replace("Launching skill:", "").trim();
            }
            return null;
        } else if (title.startsWith("Write")) {
            title = title.replaceFirst("Write", I18nUtil.systemMessage(langMap, "Backend.Sandbox.WriteFile"));
        } else if (title.startsWith("write")) {
            title = title.replaceFirst("write", I18nUtil.systemMessage(langMap, "Backend.Sandbox.WriteFile"));
        } else if (title.startsWith("Edit")) {
            title = title.replaceFirst("Edit", I18nUtil.systemMessage(langMap, "Backend.Sandbox.EditFile"));
        } else if (title.startsWith("edit")) {
            title = title.replaceFirst("edit", I18nUtil.systemMessage(langMap, "Backend.Sandbox.EditFile"));
        } else if (title.startsWith("read")) {
            title = title.replaceFirst("read", I18nUtil.systemMessage(langMap, "Backend.Sandbox.ReadFile"));
        } else if (title.startsWith("bash")) {
            title = title.replaceFirst("bash", I18nUtil.systemMessage(langMap, "Backend.Sandbox.ExecuteCommand"));
        } else if (title.startsWith("Terminal")) {
            title = title.replaceFirst("Terminal", I18nUtil.systemMessage(langMap, "Backend.Sandbox.TerminalExecute"));
        } else if (jsonObject.getString("kind") != null && jsonObject.getString("kind").equals("execute")) {
            title = I18nUtil.systemMessage(langMap, "Backend.Sandbox.TerminalExecute") + " " + title;
        }
        if (title.startsWith("read") || title.startsWith("edit") || title.startsWith("write")) {
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
            title = title.replaceFirst("glob", I18nUtil.systemMessage(langMap, "Backend.Sandbox.SearchFiles"));
        }

        if (title.startsWith("Read File")) {
            title = title.replaceFirst("Read File", I18nUtil.systemMessage(langMap, "Backend.Sandbox.ReadFile"));
            if (jsonObject.get("rawInput") != null && jsonObject.get("rawInput") instanceof JSONObject) {
                if (jsonObject.getJSONObject("rawInput").getString("file_path") != null) {
                    title = title + " " + jsonObject.getJSONObject("rawInput").getString("file_path");
                }
            }
        }
        if (title.startsWith("mcp__chrome-tools__") || title.startsWith("chrome-tools_")) {
            if ((title.equals("mcp__chrome-tools__navigate_page") || title.equals("mcp__chrome-tools__new_page") || title.equals("chrome-tools_new_page")) && jsonObject.get("rawInput") != null
                    && jsonObject.get("rawInput") instanceof JSONObject && jsonObject.getJSONObject("rawInput").getString("url") != null) {
                title = title + " " + jsonObject.getJSONObject("rawInput").getString("url");
            }
            title = title.replaceFirst("mcp__chrome-tools__", I18nUtil.systemMessage(langMap, "Backend.Sandbox.BrowserOperation").trim() + " ");
            title = title.replaceFirst("chrome-tools_", I18nUtil.systemMessage(langMap, "Backend.Sandbox.BrowserOperation").trim() + " ");
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

    private static ComponentExecutingDto buildExecutingEvent(Map<String, ComponentExecutingDto> eventMap, String title, ComponentExecutingDto.SubEventTypeEnum subEventType, Object input, String toolCallId) {
        ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
        componentExecuteResult.setStartTime(System.currentTimeMillis());
        componentExecuteResult.setEndTime(componentExecuteResult.getStartTime());
        componentExecuteResult.setId(-1L);
        componentExecuteResult.setName(title);
        componentExecuteResult.setType(ComponentTypeEnum.Event);
        if (input instanceof String && JSON.isValid(input.toString())) {
            input = JSON.parseObject(input.toString());
        }
        componentExecuteResult.setInput(input);
        componentExecuteResult.setExecuteId(toolCallId == null ? UUID.randomUUID().toString().replace("-", "") : toolCallId);
        ComponentExecutingDto componentExecutingDto = new ComponentExecutingDto();
        componentExecutingDto.setTargetId(-1L);
        componentExecutingDto.setName(title);
        componentExecutingDto.setType(ComponentTypeEnum.Event);
        componentExecutingDto.setStatus(ExecuteStatusEnum.FINISHED);
        componentExecutingDto.setResult(componentExecuteResult);
        componentExecutingDto.setSubEventType(subEventType);
        eventMap.put(toolCallId, componentExecutingDto);
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

    private String rewriteComputerBaseUrl(ConversationDto conversation, String apiUrl) {
        if (conversation.getType() == Conversation.ConversationType.DevDebug) {
            return apiUrl.replace("/computer/", "/devcomputer/");
        }
        return apiUrl;
    }

    public void chatCancel(String conversationId) {
        SandboxServerConfig.SandboxServer sandboxServer = sandboxServerConfigService.selectServer(Long.parseLong(conversationId));
        if (sandboxServer == null || sandboxServer.getCurrentConversation() == null) {
            return;
        }
        Map<String, String> sessionMap = createSessionMap(sandboxServer);
        if (!sessionMap.isEmpty()) {
            sessionMap.forEach((key, value) -> chatCancel0(sandboxServer, key, value));
            return;
        }
        if (sandboxServer.getCurrentConversation().getSandboxSessionId() == null) {
            return;
        }
        chatCancel0(sandboxServer, conversationId, sandboxServer.getCurrentConversation().getSandboxSessionId());
    }

    //针对AgentGroup和AgentFlow下的多个会话进行处理
    private Map<String, String> createSessionMap(SandboxServerConfig.SandboxServer sandboxServer) {
        Map<String, Object> variables = sandboxServer.getCurrentConversation().getVariables();
        Map<String, String> sesssionMap = new HashMap<>();
        if (variables != null) {
            variables.forEach((key, value) -> {
                if (key.startsWith("_agent") && value instanceof Map<?, ?> && ((Map<?, ?>) value).containsKey("sandboxSessionId")) {
                    sesssionMap.put(key.replace("_agent", sandboxServer.getCurrentConversation().getId() + "_"), (String) ((Map<?, ?>) value).get("sandboxSessionId"));
                }
            });
        }
        return sesssionMap;
    }

    private void chatCancel0(SandboxServerConfig.SandboxServer sandboxServer, String projectId, String sandboxSessionId) {
        ConversationDto conversation = sandboxServer.getCurrentConversation();
        String apiUrl = sandboxServer.getServerAgentUrl() + "/computer/agent/session/cancel?project_id=" + projectId + "&session_id=" + sandboxSessionId + "&user_id=" + conversation.getUserId();
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(rewriteComputerBaseUrl(conversation, apiUrl)))
                .header("Content-Type", "application/json")
                .header("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey())
                .timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<String> send;
        try {
            send = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("chat cancel response: {}", send.body());
        } catch (Throwable e) {
            //  ignore
            log.error("chat cancel error", e);
        }
    }

    public void agentStop(String conversationId) {
        SandboxServerConfig.SandboxServer sandboxServer;
        try {
            sandboxServer = sandboxServerConfigService.selectServer(Long.parseLong(conversationId));
        } catch (Exception e) {
            // ignore
            log.error("agentStop error", e);
            return;
        }
        if (sandboxServer == null || sandboxServer.getCurrentConversation() == null) {
            return;
        }
        Map<String, String> sessionMap = createSessionMap(sandboxServer);
        if (!sessionMap.isEmpty()) {
            sessionMap.forEach((key, value) -> agentStop0(sandboxServer, key, value));
            return;
        }
        try {
            agentStop0(sandboxServer, conversationId, sandboxServer.getCurrentConversation().getSandboxSessionId());
        } catch (Exception e) {
            //  ignore
        }
    }

    public void agentStop0(SandboxServerConfig.SandboxServer sandboxServer, String projectId, String sandboxSessionId) {
        if (sandboxServer == null || sandboxServer.getCurrentConversation() == null || sandboxSessionId == null) {
            return;
        }
        String apiUrl = sandboxServer.getServerAgentUrl() + "/computer/agent/stop";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(rewriteComputerBaseUrl(sandboxServer.getCurrentConversation(), apiUrl)))
                .header("Content-Type", "application/json")
                .header("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey())
                .timeout(Duration.ofSeconds(60)).POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(Map.of("user_id", sandboxServer.getCurrentConversation().getUserId().toString(), "session_id", sandboxSessionId, "project_id", projectId)))).build();
        HttpResponse<String> send;
        try {
            send = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            log.info("agent stop response: {}", send.body());
        } catch (Throwable e) {
            //  ignore
            log.error("agent stop error", e);
        }
    }

    public void notifyResolved(String conversationId, String toolCallId, Map<String, String> outcome) {
        SandboxServerConfig.SandboxServer sandboxServer = sandboxServerConfigService.selectServer(Long.parseLong(conversationId));
        if (sandboxServer == null || sandboxServer.getCurrentConversation() == null) {
            return;
        }
        String projectId = conversationId;
        AtomicReference<String> sessionId = new AtomicReference<>();
        if (sandboxServer.getCurrentConversation().getSandboxSessionId() != null) {
            sessionId.set(sandboxServer.getCurrentConversation().getSandboxSessionId());
        }
        Map<String, Object> variables = sandboxServer.getCurrentConversation().getVariables();
        if (variables != null) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> permissionRequests = (List<Map<String, Object>>) variables.computeIfAbsent("permissionRequests", k -> new ArrayList<>());
            for (Map<String, Object> permissionRequest : permissionRequests) {
                if (permissionRequest.get("toolCallId").equals(toolCallId) && permissionRequest.get("useState") != null && (Boolean) permissionRequest.get("useState")) {
                    projectId = conversationId + "_" + permissionRequest.get("agentId");
                    String agentId = permissionRequest.get("agentId").toString();
                    variables.forEach((key, value) -> {
                        if (key.startsWith("_agent" + agentId) && value instanceof Map<?, ?> && ((Map<?, ?>) value).containsKey("sandboxSessionId")) {
                            sessionId.set((String) ((Map<?, ?>) value).get("sandboxSessionId"));
                        }
                    });
                }
            }
        }
        if (sessionId.get() == null) {
            return;
        }
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("user_id", sandboxServer.getCurrentConversation().getUserId().toString());
        requestBody.put("project_id", projectId);
        Map<String, Object> permissionResolveRequest = new HashMap<>();
        Map<String, Object> requestPermissionResponse = new HashMap<>();
        requestPermissionResponse.put("outcome", outcome);
        permissionResolveRequest.put("request_permission_response", requestPermissionResponse);
        permissionResolveRequest.put("session_id", sessionId.get());
        permissionResolveRequest.put("tool_call_id", toolCallId);
        requestBody.put("permission_resolve_request", permissionResolveRequest);

        String apiUrl = sandboxServer.getServerAgentUrl() + "/computer/notify-resolved";
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(rewriteComputerBaseUrl(sandboxServer.getCurrentConversation(), apiUrl)))
                .header("Content-Type", "application/json")
                .header("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey())
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJSONString(requestBody)))
                .build();
        try {
            HttpResponse<String> send = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            String body = send.body();
            log.info("notify resolved response: {}", body);
            if (JSON.isValid(body)) {
                JSONObject res = JSON.parseObject(body);
                if (!"0000".equals(res.getString("code"))) {
                    throw new BizException(res.getString("message") != null ? res.getString("message") : "Permission failed");
                }
            }
        } catch (BizException e) {
            throw e;
        } catch (Throwable e) {
            log.error("notify resolved error", e);
            throw new BizException("Permission failed: " + e.getMessage());
        }
    }

    public boolean checkAgentIfAlive(String conversationId) {
        SandboxServerConfig.SandboxServer sandboxServer = sandboxServerConfigService.selectServer(Long.parseLong(conversationId));
        if (sandboxServer == null || sandboxServer.getCurrentConversation() == null) {
            return false;
        }
        AgentContext agentContext = new AgentContext();
        agentContext.setConversationId(conversationId);
        agentContext.setUserId(sandboxServer.getCurrentConversation().getUserId());
        return Boolean.TRUE.equals(checkAgentIfAlive(agentContext, sandboxServer).timeout(Duration.ofSeconds(10)).block());
    }

    public static SseSubscription subscribe(SandboxServerConfig.SandboxServer sandboxServer, String url, SseEventHandler eventHandler) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "text/event-stream")
                .header("Cache-Control", "no-cache")
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
                log.info("Session ended, url {}", url);
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
                log.warn("Heartbeat detection, SSE connection disconnected, url {}", url);

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
            if (future != null) {
                try {
                    future.cancel(true);
                } catch (Exception e) {
                    log.warn("Cancel SSE subscription failed", e);
                    // Ignore
                }
            }
            if (subscription.get() != null) {
                subscription.get().cancel();
            }
            heartbeatDisposable.dispose();
        }
    }
}
