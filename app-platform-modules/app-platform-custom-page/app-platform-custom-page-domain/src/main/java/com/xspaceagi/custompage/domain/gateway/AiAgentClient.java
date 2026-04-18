package com.xspaceagi.custompage.domain.gateway;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
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
public class AiAgentClient {

    @Value("${custom-page.ai-agent.base-url}")
    private String baseUrl;

    private RestTemplate createRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000); // 连接超时 5秒
        factory.setReadTimeout(60000); // 读取超时 60秒
        return new RestTemplate(factory);
    }

    public Map<String, Object> sendChat(Map<String, Object> chatBody) {
        String url = baseUrl + "/chat";
        log.info("[Infra] call AI Agent /chat, url={}", url);
        RestTemplate restTemplate = createRestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(chatBody, headers);

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

    public void subscribeSessionSse(String sessionId, SseEmitter emitter) {
        // 在异步线程中订阅 SSE
        new Thread(() -> {
            HttpURLConnection connection = null;
            try {
                String urlStr = baseUrl + "/agent/progress/" + sessionId;
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
                    emitter.completeWithError(new IllegalStateException("SSE subscribe failed, httpStatus=" + status));
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
                                    emitter.send(eventBuilder);
                                } catch (Exception sendEx) {
                                    if (isClientDisconnected(sendEx)) {
                                        log.warn("[Infra] SSE send to frontend failed, client disconnected,session Id={}", sessionId, sendEx);
                                    } else {
                                        log.warn("[Infra] SSE send to frontend failed,session Id={}", sendEx, sessionId, sendEx);
                                    }
                                    break;
                                }
                            }
                            currentEvent = null;
                            dataBuilder.setLength(0);
                        }
                    }
                }
                emitter.complete();
            } catch (Exception e) {
                log.error("[Infra] AI Agent SSE exception", e);
                try {
                    emitter.completeWithError(e);
                } catch (Exception ignore) {
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "ai-agent-sse-" + sessionId).start();
    }

    private boolean isClientDisconnected(Exception e) {
        String msg = e.getMessage();
        if (msg == null) {
            return false;
        }
        String lowerMsg = msg.toLowerCase();
        return lowerMsg.contains("broken pipe") || lowerMsg.contains("connection reset");
    }

    public Map<String, Object> sessionCancel(String projectId, String sessionId) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/agent/session/cancel")
                .queryParam("project_id", projectId);
        if (sessionId != null && !sessionId.isBlank()) {
            builder = builder.queryParam("session_id", sessionId);
        }
        String url = builder.toUriString();
        log.info("[Infra] callcancel agent task API, url={}, project Id={}, session Id={}", url, projectId, sessionId);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

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

    public Map<String, Object> getAgentStatus(String projectId) {
        String url = baseUrl + "/agent/status/" + projectId;
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

    public Map<String, Object> stopAgent(String projectId) {
        String url = baseUrl + "/agent/stop?project_id=" + projectId;
        log.info("[Infra] callstop Agent service API, url={}, project Id={}", url, projectId);
        RestTemplate restTemplate = createRestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

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
}
