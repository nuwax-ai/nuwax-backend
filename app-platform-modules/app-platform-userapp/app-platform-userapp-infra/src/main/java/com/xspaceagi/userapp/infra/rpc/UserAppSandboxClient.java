package com.xspaceagi.userapp.infra.rpc;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import org.apache.commons.lang3.StringUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.infra.rpc.SandboxServerConfigService;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.agent.core.infra.rpc.SandboxConfigEndpoints;
import com.xspaceagi.sandbox.sdk.support.SandboxEndpointConverter.SandboxEndpoint;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.userapp.domain.gateway.IUserAppSandboxClient;
import com.xspaceagi.userapp.domain.gateway.UserAppDeployCommand;
import com.xspaceagi.userapp.domain.gateway.UserAppLogsQueryCommand;
import com.xspaceagi.userapp.domain.gateway.UserAppSandboxHandle;
import com.xspaceagi.userapp.domain.gateway.UserAppSseEvent;
import com.xspaceagi.userapp.domain.model.UserAppModel;
import com.xspaceagi.userapp.sdk.dto.UserAppCallResult;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class UserAppSandboxClient implements IUserAppSandboxClient {

    private static final String PATH_DEV_START = "/api/v1/userapp/dev/start";
    private static final String PATH_DEV_STOP = "/api/v1/userapp/dev/stop";
    private static final String PATH_DEV_RESTART = "/api/v1/userapp/dev/restart";
    private static final String PATH_BUILD = "/api/v1/userapp/build";
    /** 产物下载：路径 %s = appId，query 依次为 userId、releaseId（按版本精确取包，缺省取最新） */
    private static final String PATH_STATIC = "/api/v1/userapp/static/%s?user_id=%s&release_id=%s";
    /** 任务详情：路径 %s = taskId，query 依次为 appId、userId（均必填） */
    private static final String PATH_TASK = "/api/v1/userapp/tasks/%s?app_id=%s&user_id=%s";
    /** 取消任务：路径 %s = taskId，query 依次为 appId、userId（均必填） */
    private static final String PATH_TASK_CANCEL = "/api/v1/userapp/tasks/%s/cancel?app_id=%s&user_id=%s";
    /** 任务进度 SSE 流：路径 %s = taskId，query 依次为 appId、userId、fromSeq（回放起点，含该 seq） */
    private static final String PATH_TASK_LOGS_STREAM = "/api/v1/userapp/tasks/%s/logs/stream?app_id=%s&user_id=%s&from_seq=%s";
    /** 生产启动：%s = appId（user_id 必填，走请求体） */
    private static final String PATH_PROD_START = "/api/v1/userapp/%s/start";
    /** 生产停止：路径 %s = appId，query %s = userId（必填；无请求体） */
    private static final String PATH_PROD_STOP = "/api/v1/userapp/%s/stop?user_id=%s";
    /** 生产重启：%s = appId（user_id 必填，走请求体） */
    private static final String PATH_PROD_RESTART = "/api/v1/userapp/%s/restart";
    /** 重置数据库账号密码：%s = appStage（dev/prod） */
    private static final String PATH_DB_RESET_PASSWORD = "/api/v1/userapp/db/%s/reset-password";

    /** 日志查询：%s = appId, %s = appStage（dev/prod）, %s = userId */
    private static final String PATH_LOGS_QUERY = "/api/v1/userapp/%s/%s/logs/query?user_id=%s";

    /** 日志源查询 */
    private static final String PATH_LOGS_SOURCES_QUERY = "/api/v1/userapp/%s/%s/logs/sources/query?user_id=%s";

    /** 就绪探测：路径依次为 appId、appStage（dev/prod） */
    private static final String PATH_READINESS = "/api/v1/userapp/%s/%s/readiness?user_id=%s";

    /** dbx 数据库就绪探测 */
    private static final String PATH_DBX_READINESS = "/api/v1/userapp/%s/%s/dbx/readiness?user_id=%s";

    @Resource
    private SandboxConfigEndpoints sandboxConfigEndpoints;
    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;
    @Resource
    private SandboxServerConfigService sandboxServerConfigService;

    private final RestTemplate restTemplate = buildRestTemplate();

    /** 产物下载专用：zip 可能较大，放宽读超时 */
    private final RestTemplate downloadRestTemplate = buildRestTemplate(120_000);

    /** 生产部署/重启专用：下游拉镜像+启动+执行 SQL 可能数分钟，读超时 360s */
    private final RestTemplate prodRestTemplate = buildRestTemplate(360_000);

    private static final AtomicLong THREAD_COUNTER = new AtomicLong();

    /** SSE 流消费线程池：守护线程，空闲回收（长连接阻塞在 read 上，不占队列） */
    private static final ExecutorService SSE_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread thread = new Thread(r, "userapp-task-sse-" + THREAD_COUNTER.incrementAndGet());
        thread.setDaemon(true);
        return thread;
    });

    private static RestTemplate buildRestTemplate() {
        return buildRestTemplate(30_000);
    }

    private static RestTemplate buildRestTemplate(int readTimeoutMillis) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(readTimeoutMillis);
        return new RestTemplate(factory);
    }

    @Override
    public UserAppSandboxHandle resolveByProject(UserAppModel project) {
        // 沙箱以项目绑定为准——不做任何回退
        if (project.getSandboxId() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappSandboxServerNotFound);
        }
        UserAppSandboxHandle handle = resolveConfigBySandboxId(project.getSandboxId(), project.getCreatorId());
        if (handle == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userappSandboxServerNotFound);
        }
        return handle;
    }

    @Override
    public Long allocateCloudSandbox(Long tenantId, Long userId) {
        // 云端沙箱分配：Redis 用户级粘性 + 租户全局池容量感知轮询（selectServer 内部会回写粘性）
        TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(tenantId);
        SandboxServerConfig.SandboxServer server = sandboxServerConfigService.selectServer(tenantConfig, userId, "-1");
        return Long.parseLong(server.getServerId());
    }

    @Override
    public UserAppSandboxHandle resolveBySandboxId(Long sandboxId, Long expectedUserId) {
        return resolveConfigBySandboxId(sandboxId, expectedUserId);
    }

    @Override
    public UserAppCallResult devStart(UserAppSandboxHandle handle, Long appId, Long userId, UserAppDeployCommand command) {
        return doPost(handle, PATH_DEV_START, actionBodyWithPg(appId, userId, command), "userapp_dev_start_" + appId);
    }

    @Override
    public UserAppCallResult devStop(UserAppSandboxHandle handle, Long appId, Long userId) {
        return doPost(handle, PATH_DEV_STOP, actionBody(appId, userId), "userapp_dev_stop_" + appId);
    }

    @Override
    public UserAppCallResult devRestart(UserAppSandboxHandle handle, Long appId, Long userId, UserAppDeployCommand command) {
        return doPost(handle, PATH_DEV_RESTART, actionBodyWithPg(appId, userId, command), "userapp_dev_restart_" + appId);
    }

    @Override
    public UserAppCallResult build(UserAppSandboxHandle handle, Long appId, Long userId) {
        return doPost(handle, PATH_BUILD, actionBody(appId, userId), "userapp_build_" + appId);
    }

    @Override
    public UserAppCallResult prodStart(UserAppSandboxHandle handle, Long appId, UserAppDeployCommand command) {
        return doPostProd(handle, String.format(PATH_PROD_START, appId), deployBody(command),
                "userapp_prod_start_" + appId);
    }

    @Override
    public UserAppCallResult prodStop(UserAppSandboxHandle handle, Long appId, UserAppDeployCommand command) {
        // stop = scale 0，下游无请求体，user_id 走必填 query；command 仅取 userId
        return doPost(handle, String.format(PATH_PROD_STOP, appId, command.getUserId()), new HashMap<>(),
                "userapp_prod_stop_" + appId);
    }

    @Override
    public UserAppCallResult prodRestart(UserAppSandboxHandle handle, Long appId, UserAppDeployCommand command) {
        return doPostProd(handle, String.format(PATH_PROD_RESTART, appId), deployBody(command),
                "userapp_prod_restart_" + appId);
    }

    @Override
    public UserAppCallResult resetDbPassword(UserAppSandboxHandle handle, Long appId, Long userId, String appStage,
            String username, String password) {
        Map<String, Object> body = new HashMap<>();
        body.put("app_id", String.valueOf(appId));
        body.put("user_id", String.valueOf(userId));
        body.put("username", username);
        body.put("password", password);
        return doPost(handle, String.format(PATH_DB_RESET_PASSWORD, appStage), body,
                "userapp_db_reset_" + appStage + "_" + appId);
    }

    @Override
    public UserAppCallResult queryLogs(UserAppSandboxHandle handle, Long appId, Long userId, String appStage,
            UserAppLogsQueryCommand command) {
        // 响应可能包含大量日志行，不整段写应用日志
        return doPost(handle, String.format(PATH_LOGS_QUERY, appId, appStage, userId), logsQueryBody(command),
                "userapp_logs_query_" + appStage + "_" + appId, false);
    }

    @Override
    public UserAppCallResult queryLogSources(UserAppSandboxHandle handle, Long appId, Long userId, String appStage) {
        // 无过滤参数，仅 user_id 必填 query；清单随服务数×日志文件数增长（含容器内路径），不整段写应用日志
        return doPost(handle, String.format(PATH_LOGS_SOURCES_QUERY, appId, appStage, userId), new HashMap<>(),
                "userapp_logs_sources_query_" + appStage + "_" + appId, false);
    }

    @Override
    public UserAppCallResult readiness(UserAppSandboxHandle handle, Long appId, Long userId, String appStage) {
        // ready=false 也是成功查询（code=0000），由前端按 data.ready 判断
        return doGet(handle, String.format(PATH_READINESS, appId, appStage, userId),
                "userapp_readiness_" + appStage + "_" + appId);
    }

    @Override
    public UserAppCallResult dbxReadiness(UserAppSandboxHandle handle, Long appId, Long userId, String appStage) {
        return doGet(handle, String.format(PATH_DBX_READINESS, appId, appStage, userId),
                "userapp_dbx_readiness_" + appStage + "_" + appId);
    }

    /**
     * 日志查询请求体（过滤条件全部可选，null 原样下发由下游按缺省处理）
     */
    private Map<String, Object> logsQueryBody(UserAppLogsQueryCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("cursor", command.getCursor());
        body.put("keyword", command.getKeyword());
        body.put("levels", command.getLevels());
        if (command.getSelectors() != null) {
            List<Map<String, Object>> selectors = new ArrayList<>();
            for (UserAppLogsQueryCommand.LogSelector selector : command.getSelectors()) {
                Map<String, Object> sel = new LinkedHashMap<>();
                sel.put("service_id", selector.getServiceId());
                sel.put("source_ids", selector.getSourceIds());
                selectors.add(sel);
            }
            body.put("selectors", selectors);
        }
        body.put("since", command.getSince());
        body.put("tail", command.getTail());
        body.put("until", command.getUntil());
        return body;
    }

    @Override
    public byte[] downloadArtifact(UserAppSandboxHandle handle, Long appId, Long userId, String releaseId) {
        String url = handle.getBaseUrl() + String.format(PATH_STATIC, appId, userId, releaseId);
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-api-key", handle.getApiKey());
        try {
            ResponseEntity<byte[]> entity = downloadRestTemplate.exchange(url, HttpMethod.GET,
                    new HttpEntity<>(headers), byte[].class);
            byte[] body = entity.getBody();
            if (body == null || body.length == 0) {
                throw BizException.of(ErrorCodeEnum.ERROR_REQUEST,
                        BizExceptionCodeEnum.userappBuildArtifactDownloadFailed);
            }
            log.info("[UserAppClient] appId={} artifact downloaded, size={}", appId, body.length);
            return body;
        } catch (HttpStatusCodeException e) {
            log.warn("[UserAppClient] appId={} artifact download failed, status={}, responseBody={}",
                    appId, e.getStatusCode(), e.getResponseBodyAsString());
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST,
                    BizExceptionCodeEnum.userappBuildArtifactDownloadFailed);
        } catch (RestClientException e) {
            log.warn("[UserAppClient] appId={} artifact download error", appId, e);
            throw BizException.of(ErrorCodeEnum.ERROR_REQUEST,
                    BizExceptionCodeEnum.userappBuildArtifactDownloadFailed);
        }
    }

    @Override
    public UserAppCallResult cancelTask(UserAppSandboxHandle handle, Long appId, String taskId, Long userId) {
        return doPost(handle, String.format(PATH_TASK_CANCEL, taskId, appId, userId), new HashMap<>(),
                "userapp_task_cancel_" + taskId);
    }

    @Override
    public UserAppCallResult getTask(UserAppSandboxHandle handle, Long appId, String taskId, Long userId) {
        return doGet(handle, String.format(PATH_TASK, taskId, appId, userId), "userapp_task_get_" + taskId);
    }

    private UserAppCallResult doGet(UserAppSandboxHandle handle, String path, String logId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", handle.getApiKey());
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(handle.getBaseUrl() + path,
                    HttpMethod.GET, new HttpEntity<>(headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            log.info("[UserAppClient] logId={} query OK, response={}", logId, entity.getBody());
            return toResult(entity.getBody());
        } catch (HttpStatusCodeException e) {
            log.warn("[UserAppClient] logId={} query failed, status={}, responseBody={}",
                    logId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseErrorResult(e);
        } catch (RestClientException e) {
            log.warn("[UserAppClient] logId={} query error", logId, e);
            return UserAppCallResult.builder().message(e.getMessage()).build();
        }
    }

    @Override
    public void streamTaskLogs(UserAppSandboxHandle handle, Long appId, String taskId, Long userId, long fromSeq,
            Consumer<UserAppSseEvent> onEvent, Runnable onFinished, Consumer<String> onError) {
        String url = handle.getBaseUrl()
                + String.format(PATH_TASK_LOGS_STREAM, taskId, appId, userId, fromSeq);
        SSE_EXECUTOR.submit(() -> consumeTaskLogStream(url, handle.getApiKey(), taskId, onEvent, onFinished, onError));
    }

    /** 消费下游任务进度 SSE 流：逐行读帧（id:/event:/data: 字段，空行派发一条事件），流结束/中断转回调 */
    private void consumeTaskLogStream(String url, String apiKey, String taskId,
            Consumer<UserAppSseEvent> onEvent, Runnable onFinished, Consumer<String> onError) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(5_000);
            // 编译事件间隔可能很长（读超时置 0），靠流中断异常感知断连
            connection.setReadTimeout(0);
            connection.setRequestProperty("Accept", "text/event-stream");
            connection.setRequestProperty("x-api-key", apiKey);
            int status = connection.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) {
                notifyError(onError, "下游返回 " + status + "：" + readErrorBody(connection));
                return;
            }
            boolean consumerAborted = false;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                String id = null;
                String event = null;
                StringBuilder data = new StringBuilder();
                boolean hasData = false;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        // 空行：派发当前帧（仅含 data 字段的帧才是事件）
                        if (hasData) {
                            try {
                                onEvent.accept(new UserAppSseEvent(id, event, data.toString()));
                            } catch (Exception consumerException) {
                                // 消费端故障：中止下游订阅，且不再回调 onFinished/onError
                                log.warn("[UserAppClient] taskId={} sse consumer failed", taskId, consumerException);
                                consumerAborted = true;
                                break;
                            }
                        }
                        id = null;
                        event = null;
                        data.setLength(0);
                        hasData = false;
                        continue;
                    }
                    if (line.startsWith(":")) {
                        // SSE 注释行（心跳等），跳过
                        continue;
                    }
                    int colon = line.indexOf(':');
                    String field = colon < 0 ? line : line.substring(0, colon);
                    String value = colon < 0 ? "" : line.substring(colon + 1);
                    if (value.startsWith(" ")) {
                        value = value.substring(1);
                    }
                    switch (field) {
                        case "id" -> id = value;
                        case "event" -> event = value;
                        case "data" -> {
                            if (hasData) {
                                data.append('\n');
                            }
                            data.append(value);
                            hasData = true;
                        }
                        default -> {
                        }
                    }
                }
            }
            if (!consumerAborted) {
                // 下游正常关流（终态事件后）
                try {
                    onFinished.run();
                } catch (Exception consumerException) {
                    log.warn("[UserAppClient] taskId={} sse onFinished callback failed", taskId, consumerException);
                }
            }
        } catch (Exception e) {
            log.warn("[UserAppClient] taskId={} sse stream broken", taskId, e);
            notifyError(onError, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** onError 回调吞掉自身异常，避免回调故障串扰消费端状态 */
    private void notifyError(Consumer<String> onError, String message) {
        try {
            onError.accept(message);
        } catch (Exception e) {
            log.warn("[UserAppClient] sse onError callback failed", e);
        }
    }

    /** 读下游错误响应体（最多 1KB，防大 body 占内存） */
    private String readErrorBody(HttpURLConnection connection) {
        try (InputStream errorStream = connection.getErrorStream()) {
            if (errorStream == null) {
                return "";
            }
            byte[] buffer = new byte[1024];
            int n = errorStream.readNBytes(buffer, 0, buffer.length);
            return n <= 0 ? "" : new String(buffer, 0, n, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, Object> actionBody(Long appId, Long userId) {
        Map<String, Object> body = new HashMap<>();
        body.put("app_id", String.valueOf(appId));
        body.put("user_id", String.valueOf(userId));
        return body;
    }

    /** dev 启停请求体 + pg 凭证块 */
    private Map<String, Object> actionBodyWithPg(Long appId, Long userId, UserAppDeployCommand command) {
        Map<String, Object> body = actionBody(appId, userId);
        Map<String, Object> pg = new HashMap<>();
        pg.put("username", command == null ? null : command.getDbUsername());
        pg.put("password", command == null ? null : command.getDbPassword());
        body.put("pg", pg);
        return body;
    }

    /**
     * 生产操作请求体
     */
    private Map<String, Object> deployBody(UserAppDeployCommand command) {
        Map<String, Object> pg = new HashMap<>();
        pg.put("username", command.getDbUsername());
        pg.put("password", command.getDbPassword());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("auto_execute_sql", command.getAutoExecuteSql() == null ? Boolean.TRUE : command.getAutoExecuteSql());
        body.put("pg", pg);
        body.put("release_id", command.getReleaseId());
        body.put("url", command.getPackageUrl());
        body.put("user_id", String.valueOf(command.getUserId()));
        // 闲置回收超时（下游语义：0=不回收常驻）；-1/null=不限制，走下游默认
        Integer idleTimeoutSeconds = command.getIdleTimeoutSeconds();
        if (idleTimeoutSeconds != null && idleTimeoutSeconds >= 0) {
            body.put("idle_timeout_seconds", idleTimeoutSeconds);
        }
        return body;
    }

    /** path 为不含 baseUrl 的路径（常量或 String.format 后的占位路径），完整 URL 在此统一拼接 */
    private UserAppCallResult doPost(UserAppSandboxHandle handle, String path, Map<String, Object> body, String logId) {
        return doPost(handle, path, body, logId, true);
    }

    /** 生产部署/重启：与 doPost 同语义，走 360s 读超时的 prodRestTemplate */
    private UserAppCallResult doPostProd(UserAppSandboxHandle handle, String path, Map<String, Object> body,
            String logId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", handle.getApiKey());
        try {
            ResponseEntity<Map<String, Object>> entity = prodRestTemplate.exchange(handle.getBaseUrl() + path,
                    HttpMethod.POST, new HttpEntity<>(body, headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            log.info("[UserAppClient] logId={} call OK, response={}", logId, entity.getBody());
            return toResult(entity.getBody());
        } catch (HttpStatusCodeException e) {
            log.warn("[UserAppClient] logId={} call failed, status={}, responseBody={}",
                    logId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseErrorResult(e);
        } catch (RestClientException e) {
            log.warn("[UserAppClient] logId={} call error", logId, e);
            return UserAppCallResult.builder().message(e.getMessage()).build();
        }
    }

    /** logResponse=false 用于日志查询等大响应场景，避免整段响应体刷入应用日志 */
    private UserAppCallResult doPost(UserAppSandboxHandle handle, String path, Map<String, Object> body, String logId,
            boolean logResponse) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("x-api-key", handle.getApiKey());
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(handle.getBaseUrl() + path,
                    HttpMethod.POST, new HttpEntity<>(body, headers),
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    });
            if (logResponse) {
                log.info("[UserAppClient] logId={} call OK, response={}", logId, entity.getBody());
            } else {
                log.info("[UserAppClient] logId={} call OK", logId);
            }
            return toResult(entity.getBody());
        } catch (HttpStatusCodeException e) {
            log.warn("[UserAppClient] logId={} call failed, status={}, responseBody={}",
                    logId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseErrorResult(e);
        } catch (RestClientException e) {
            log.warn("[UserAppClient] logId={} call error", logId, e);
            return UserAppCallResult.builder().message(e.getMessage()).build();
        }
    }

    /** 按ID取配置并做租户/属主校验：不匹配视为不可用（数据安全红线，fail-closed；校验与URL组装统一在 SandboxConfigEndpoints） */
    private UserAppSandboxHandle resolveConfigBySandboxId(Long sandboxId, Long expectedUserId) {
        SandboxEndpoint endpoint = sandboxConfigEndpoints.resolveBySandboxIdForOwner(sandboxId, expectedUserId);
        return endpoint == null ? null : new UserAppSandboxHandle(endpoint.sandboxId(), endpoint.agentBaseUrl(), endpoint.apiKey(), endpoint.vncBaseUrl());
    }

    private UserAppCallResult toResult(Map<String, Object> body) {
        if (body == null) {
            return UserAppCallResult.builder().build();
        }
        Object data = body.get("data");
        @SuppressWarnings("unchecked")
        Map<String, Object> dataMap = data instanceof Map ? (Map<String, Object>) data : null;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> dataList = data instanceof List ? (List<Map<String, Object>>) data : null;
        return UserAppCallResult.builder()
                .code(body.get("code") == null ? null : String.valueOf(body.get("code")))
                .message(body.get("message") == null ? null : String.valueOf(body.get("message")))
                .data(dataMap)
                .dataList(dataList)
                .build();
    }

    // 捕获 4xx/5xx，尝试解析下游错误体（对齐 ComputerPodClient.parseClientErr）
    @SuppressWarnings("unchecked")
    private UserAppCallResult parseErrorResult(HttpStatusCodeException e) {
        UserAppCallResult.UserAppCallResultBuilder builder = UserAppCallResult.builder()
                .message(e.getMessage());
        boolean codeFromBody = false;
        try {
            String responseBody = e.getResponseBodyAsString();
            if (StringUtils.isNotBlank(responseBody)) {
                Map<String, Object> errorResponse = new ObjectMapper().readValue(responseBody, Map.class);
                if (errorResponse.containsKey("code")) {
                    // 前端会把 4010 当登录态失效跳转登录页；转 9999 通用错误，message 照常透传
                    String code = String.valueOf(errorResponse.get("code"));
                    builder.code("4010".equals(code) ? "9999" : code);
                    codeFromBody = true;
                }
                if (errorResponse.containsKey("message")) {
                    builder.message(String.valueOf(errorResponse.get("message")));
                }
            }
        } catch (Exception parseException) {
            log.error("[UserAppClient] parse error response body failed", parseException);
        }
        // 404 且响应体未携带 code：按应用不存处理
        if (!codeFromBody && e.getStatusCode().value() == 404) {
            builder.code(UserAppCallResult.ERR_APP_NOT_FOUND);
        }
        return builder.build();
    }
}
