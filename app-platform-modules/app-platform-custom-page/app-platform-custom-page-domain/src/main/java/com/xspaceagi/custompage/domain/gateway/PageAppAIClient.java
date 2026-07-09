package com.xspaceagi.custompage.domain.gateway;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.domain.repository.ICustomPageConfigRepository;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigValue;
import com.xspaceagi.system.spec.common.UserContext;
import jakarta.annotation.Resource;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.util.UriComponentsBuilder;

@Slf4j
@Component
public class PageAppAIClient {
    @Value("${custom-page.ai-agent.base-url:}")
    private String configuredBaseUrl;
    @Resource
    private ICustomPageConfigRepository customPageConfigRepository;
    @Resource
    private ISandboxConfigRpcService sandboxConfigRpcService;
    @Resource
    @Qualifier("aiAgentProgressExecutor")
    private Executor aiAgentProgressExecutor;

    @Data
    @AllArgsConstructor
    public static class AgentRuntimeContext {
        private final String baseUrl;
        private final Long tenantId;
        private final Long spaceId;
        private final String isolationType;
        private final String podId;
    }

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(30000); // 连接超时 30秒
        factory.setReadTimeout(120_000); // 读取超时 120秒（/chat 接单响应，进度走 SSE）
        return new RestTemplate(factory);
    }

    public Map<String, Object> sendChat(Map<String, Object> chatBody, Long projectId, UserContext userContext) {
        AgentRuntimeContext runtimeContext = buildAgentRuntimeContext(projectId, userContext);
        String url = buildBaseUrl(runtimeContext) + "/chat";
        Map<String, Object> requestBody = appendRuntimeParamsToBody(chatBody, runtimeContext);
        log.info("[Infra] call AI Agent /chat, url={}, request={}", url, requestBody);
        RestTemplate restTemplate = createRestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(
                url,
                HttpMethod.POST,
                requestEntity,
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        Map<String, Object> body = entity.getBody();
        log.info("[Infra] call AI Agent /chat, response={}", body);
        return body;
    }

    public void subscribeSessionSse(String sessionId, SseEmitter emitter, Long projectId, UserContext userContext,
            BiConsumer<String, String> eventConsumer) {
        subscribeSessionSse(sessionId, emitter, projectId, userContext, eventConsumer, null, null);
    }

    public void subscribeSessionSse(String sessionId, SseEmitter emitter, Long projectId, UserContext userContext,
            BiConsumer<String, String> eventConsumer, Runnable onStreamFinished) {
        subscribeSessionSse(sessionId, emitter, projectId, userContext, eventConsumer, onStreamFinished, null);
    }

    public void subscribeSessionSse(String sessionId, SseEmitter emitter, Long projectId, UserContext userContext,
            BiConsumer<String, String> eventConsumer, Runnable onStreamFinished, Consumer<String> onSubscribeError) {
        AgentRuntimeContext runtimeContext = buildAgentRuntimeContext(projectId, userContext);
        AtomicBoolean clientDisconnected = new AtomicBoolean(false);
        aiAgentProgressExecutor.execute(() -> {
            HttpURLConnection connection = null;
            try {
                String urlStr = appendRuntimeParams(UriComponentsBuilder.fromHttpUrl(buildBaseUrl(runtimeContext) + "/agent/progress/" + sessionId), runtimeContext).toUriString();
                log.info("[Infra] start subscribing to AI Agent SSE, url={}", urlStr);
                URL url = new URL(urlStr);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Accept", "text/event-stream");
                connection.setRequestProperty("Cache-Control", "no-cache");
                connection.setRequestProperty("Connection", "keep-alive");
                connection.setDoInput(true);
                connection.setConnectTimeout(30000); // 连接超时时间30秒
                connection.setReadTimeout(0); // SSE 长连接

                int status = connection.getResponseCode();
                if (status != 200) {
                    String errorMessage = "SSE subscribe failed, httpStatus=" + status;
                    notifySubscribeError(onSubscribeError, errorMessage);
                    if (emitter != null) {
                        try {
                            emitter.completeWithError(new IllegalStateException(errorMessage));
                        } catch (Exception ignore) {
                        }
                    }
                    return;
                }
                try (InputStream inputStream = connection.getInputStream();
                     BufferedReader reader = new BufferedReader(
                             new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                    String line;
                    String currentEvent = null;
                    StringBuilder dataBuilder = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("event:")) {
                            currentEvent = line.substring(6).trim();
                        } else if (line.startsWith("data:")) {
                            if (dataBuilder.length() > 0) {
                                dataBuilder.append('\n');
                            }
                            dataBuilder.append(line.substring(5).trim());
                        } else if (line.isEmpty()) {
                            if (dataBuilder.length() > 0) {
                                try {
                                    // 使用SSE协议中的event字段作为事件名称，如果没有则使用默认名称
                                    String eventName = currentEvent;
                                    if (eventName == null) {
                                        eventName = "message"; // 默认事件名称
                                    }

                                    SseEmitter.SseEventBuilder eventBuilder = SseEmitter.event();
                                    if (eventName != null) {
                                        eventBuilder.name(eventName);
                                    }
                                    eventBuilder.data(dataBuilder.toString());
                                    if (eventConsumer != null) {
                                        eventConsumer.accept(eventName, dataBuilder.toString());
                                    }
                                    if (emitter != null && !clientDisconnected.get()) {
                                        try {
                                            emitter.send(eventBuilder);
                                        } catch (Exception sendEx) {
                                            if (isClientDisconnected(sendEx)) {
                                                clientDisconnected.set(true);
                                                log.warn("[Infra] SSE send to frontend failed, client disconnected, continue consuming agent stream, session Id={}",
                                                        sessionId);
                                            } else {
                                                log.warn("[Infra] SSE send to frontend failed, session Id={}", sessionId, sendEx);
                                            }
                                        }
                                    }
                                } catch (Exception sendEx) {
                                    log.warn("[Infra] SSE event handling failed, session Id={}", sessionId, sendEx);
                                }
                            }
                            currentEvent = null;
                            dataBuilder.setLength(0);
                        }
                    }
                }
                if (emitter != null) {
                    try {
                        emitter.complete();
                    } catch (Exception ignore) {
                    }
                }
            } catch (Exception e) {
                log.error("[Infra] AI Agent SSE exception", e);
                notifySubscribeError(onSubscribeError, e.getMessage());
                if (emitter != null) {
                    try {
                        emitter.completeWithError(e);
                    } catch (Exception ignore) {
                    }
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
                if (onStreamFinished != null) {
                    try {
                        onStreamFinished.run();
                    } catch (Exception callbackEx) {
                        log.warn("[Infra] AI Agent SSE onStreamFinished callback failed, session Id={}", sessionId,
                                callbackEx);
                    }
                }
            }
        });
    }

    private void notifySubscribeError(Consumer<String> onSubscribeError, String message) {
        if (onSubscribeError == null || message == null) {
            return;
        }
        try {
            onSubscribeError.accept(message);
        } catch (Exception e) {
            log.warn("[Infra] onSubscribeError callback failed", e);
        }
    }

    private boolean isClientDisconnected(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String lowerMsg = msg.toLowerCase();
        return lowerMsg.contains("broken pipe") || lowerMsg.contains("connection reset");
    }

    public Map<String, Object> sessionCancel(Long projectId, String sessionId, UserContext userContext) {
        AgentRuntimeContext runtimeContext = buildAgentRuntimeContext(projectId, userContext);
        UriComponentsBuilder urlBuilder = UriComponentsBuilder.fromHttpUrl(buildBaseUrl(runtimeContext) + "/agent/session/cancel").queryParam("project_id", projectId);
        if (sessionId != null && !sessionId.isBlank()) {
            urlBuilder = urlBuilder.queryParam("session_id", sessionId);
        }
        String url = appendRuntimeParams(urlBuilder, runtimeContext).toUriString();
        log.info("[Infra] callcancel agent task API, url={}, project Id={}, session Id={}", url, projectId, sessionId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("project_id", String.valueOf(projectId));
        if (sessionId != null && !sessionId.isBlank()) {
            requestBody.put("session_id", sessionId);
        }
        Map<String, Object> finalRequestBody = appendRuntimeParamsToBody(requestBody, runtimeContext);
        log.info("[Infra] callcancel agent task API, request={}", finalRequestBody);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(
                finalRequestBody, headers);

        RestTemplate restTemplate = createRestTemplate();
        ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(
                url,
                HttpMethod.POST,
                requestEntity,
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        Map<String, Object> body = entity.getBody();
        log.info("[Infra] callcancel agent task API, response={}", body);
        return body;
    }

    public Map<String, Object> getAgentStatus(Long projectId, UserContext userContext) {
        AgentRuntimeContext runtimeContext = buildAgentRuntimeContext(projectId, userContext);
        String url = appendRuntimeParams(UriComponentsBuilder.fromHttpUrl(buildBaseUrl(runtimeContext) + "/agent/status/" + projectId), runtimeContext).toUriString();
        log.info("[Infra] callquery Agent status API, url={}, project Id={}", url, projectId);
        RestTemplate restTemplate = createRestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(
                url,
                HttpMethod.GET,
                requestEntity,
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        Map<String, Object> body = entity.getBody();
        log.info("[Infra] callquery Agent status API, response={}", body);
        return body;
    }

    public Map<String, Object> stopAgent(Long projectId, UserContext userContext) {
        AgentRuntimeContext runtimeContext = buildAgentRuntimeContext(projectId, userContext);
        String url = appendRuntimeParams(
                UriComponentsBuilder.fromHttpUrl(buildBaseUrl(runtimeContext) + "/agent/stop").queryParam("project_id", projectId), runtimeContext).toUriString();
        log.info("[Infra] callstop Agent service API, url={}, project Id={}", url, projectId);
        RestTemplate restTemplate = createRestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("project_id", String.valueOf(projectId));
        Map<String, Object> finalRequestBody = appendRuntimeParamsToBody(requestBody, runtimeContext);
        log.info("[Infra] callstop Agent service API, request={}", finalRequestBody);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(
                finalRequestBody, headers);

        ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(
                url,
                HttpMethod.POST,
                requestEntity,
                new ParameterizedTypeReference<Map<String, Object>>() {
                });
        Map<String, Object> body = entity.getBody();
        log.info("[Infra] callstop Agent service API, response={}", body);
        return body;
    }

    private AgentRuntimeContext buildAgentRuntimeContext(Long projectId, UserContext userContext) {
        CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
        if (configModel == null) {
            throw new IllegalArgumentException("Project configuration does not exist");
        }
        Long sandboxId = configModel.getSandboxId();
        if (sandboxId == null) {
            // 兼容老项目：未绑定 sandboxId 时，走配置文件中的 AI Agent 地址，且不透传 runtime 参数
            return null;
        }
        SandboxConfigRpcDto sandboxConfig = sandboxConfigRpcService.selectAppDevelopmentSandbox(
                userContext.getTenantId(),
                userContext.getUserId(),
                configModel.getSpaceId(),
                projectId,
                sandboxId);
        if (sandboxConfig == null) {
            throw new IllegalStateException("No available sandbox, sandboxId:" + sandboxId);
        }
        SandboxConfigValue configValue = sandboxConfig.getConfigValue();
        if (configValue == null || configValue.getHostWithScheme() == null || configValue.getHostWithScheme().isBlank()
                || configValue.getAgentPort() <= 0) {
            throw new IllegalStateException("Sandbox config is incomplete, sandboxId:" + sandboxId);
        }

        String baseUrl = configValue.getHostWithScheme() + ":" + configValue.getAgentPort();
        String isolationType = sandboxConfig.getIsolation() == null ? null : sandboxConfig.getIsolation().name();
        String podId = sandboxConfig.getIsolationKey();
        return new AgentRuntimeContext(baseUrl, userContext.getTenantId(), configModel.getSpaceId(), isolationType, podId);
    }

    private UriComponentsBuilder appendRuntimeParams(UriComponentsBuilder builder, AgentRuntimeContext runtimeContext) {
        if (runtimeContext == null) {
            return builder;
        }
        if (runtimeContext.getTenantId() != null) {
            builder.queryParam("tenant_id", runtimeContext.getTenantId());
        }
        if (runtimeContext.getSpaceId() != null) {
            builder.queryParam("space_id", runtimeContext.getSpaceId());
        }
        if (runtimeContext.getIsolationType() != null && !runtimeContext.getIsolationType().isBlank()) {
            builder.queryParam("isolation_type", runtimeContext.getIsolationType());
        }
        if (runtimeContext.getPodId() != null && !runtimeContext.getPodId().isBlank()) {
            builder.queryParam("pod_id", runtimeContext.getPodId());
        }
        return builder;
    }

    private Map<String, Object> appendRuntimeParamsToBody(Map<String, Object> body, AgentRuntimeContext runtimeContext) {
        Map<String, Object> requestBody = body == null ? new HashMap<>() : new HashMap<>(body);
        if (runtimeContext == null) {
            return requestBody;
        }
        if (runtimeContext.getTenantId() != null) {
            requestBody.put("tenant_id", runtimeContext.getTenantId());
        }
        if (runtimeContext.getSpaceId() != null) {
            requestBody.put("space_id", runtimeContext.getSpaceId());
        }
        if (runtimeContext.getIsolationType() != null && !runtimeContext.getIsolationType().isBlank()) {
            requestBody.put("isolation_type", runtimeContext.getIsolationType());
        }
        if (runtimeContext.getPodId() != null && !runtimeContext.getPodId().isBlank()) {
            requestBody.put("pod_id", runtimeContext.getPodId());
        }
        return requestBody;
    }

    private String buildBaseUrl(AgentRuntimeContext runtimeContext) {
        String baseUrl = runtimeContext == null ? null : runtimeContext.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = configuredBaseUrl;
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("AI Agent baseUrl is required");
        }
        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

}
