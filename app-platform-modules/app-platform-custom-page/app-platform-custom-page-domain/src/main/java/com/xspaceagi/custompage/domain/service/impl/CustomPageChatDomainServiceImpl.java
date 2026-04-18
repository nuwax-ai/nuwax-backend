package com.xspaceagi.custompage.domain.service.impl;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.custompage.domain.gateway.AiAgentClient;
import com.xspaceagi.custompage.domain.gateway.PageFileBuildClient;
import com.xspaceagi.custompage.domain.keepalive.IKeepAliveService;
import com.xspaceagi.custompage.domain.proxypath.ICustomPageProxyPathService;
import com.xspaceagi.custompage.domain.repository.ICustomPageBuildRepository;
import com.xspaceagi.custompage.domain.repository.ICustomPageConfigRepository;
import com.xspaceagi.custompage.domain.service.ICustomPageChatDomainService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class CustomPageChatDomainServiceImpl implements ICustomPageChatDomainService {

    @jakarta.annotation.Resource
    private AiAgentClient aiAgentClient;
    @jakarta.annotation.Resource
    private IAgentRpcService agentRpcService;
    @jakarta.annotation.Resource
    private IKeepAliveService keepAliveService;
    @jakarta.annotation.Resource
    private PageFileBuildClient pageFileBuildClient;
    @jakarta.annotation.Resource
    private SpacePermissionService spacePermissionService;
    @jakarta.annotation.Resource
    private ModelApplicationService modelApplicationService;
    @jakarta.annotation.Resource
    private ICustomPageBuildRepository customPageBuildRepository;
    @jakarta.annotation.Resource
    private ICustomPageConfigRepository customPageConfigRepository;
    @jakarta.annotation.Resource
    private ICustomPageProxyPathService customPageProxyPathService;

    private final static ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Override
    public SseEmitter startAgentSessionSse(String sessionId, UserContext userContext) {
        if (StringUtils.isBlank(sessionId)) {
            throw new IllegalArgumentException("sessionId is required");
        }

        SseEmitter emitter = new SseEmitter(10L * 60 * 1000); // 超时时间10分钟

        emitter.onCompletion(() -> {
            log.info("[Domain] SSE connection completed, session Id={}", sessionId);
        });

        emitter.onTimeout(() -> {
            log.warn("[Domain] SSE connection timeout, session Id={}", sessionId);
        });

        emitter.onError((throwable) -> {
            log.error("[Domain] SSE connection error, session Id={}", sessionId, throwable);
        });

        aiAgentClient.subscribeSessionSse(sessionId, emitter);
        return emitter;
    }

    @Override
    public ReqResult<Map<String, Object>> agentSessionCancel(String projectId, String sessionId,
            UserContext userContext) {
        if (StringUtils.isBlank(projectId)) {
            return ReqResult.error("0001", "projectId is required");
        }

        Map<String, Object> resp = aiAgentClient.sessionCancel(projectId, sessionId);
        if (resp == null) {
            return ReqResult.error("9999", "Failed to cancel task: AI Agent returned no response");
        }

        Object code = resp.get("code");
        if (code == null || !"0000".equals(String.valueOf(code))) {
            String message = resp.get("message") == null ? "Failed to cancel task" : String.valueOf(resp.get("message"));
            return ReqResult.error("9999", message);
        }

        // 提取data字段并转换为Map
        Object data = resp.get("data");
        Map<String, Object> dataMap = new HashMap<>();

        if (data != null) {
            try {
                // 如果data已经是Map类型，直接使用
                if (data instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingMap = (Map<String, Object>) data;
                    dataMap = existingMap;
                } else {
                    // 将JSON字符串转换为Map
                    String dataJson = data.toString();
                    dataMap = objectMapper.readValue(dataJson, new TypeReference<Map<String, Object>>() {
                    });
                }

            } catch (Exception e) {
                log.warn("Failed to parse data JSON, using raw payload", e);
                // 解析失败时，将原始data包装在Map中
                dataMap.put("data", data);
            }
        }

        if (resp.get("tid") != null) {
            dataMap.put("tid", resp.get("tid"));
        }
        if (resp.get("message") != null) {
            dataMap.put("message", resp.get("message"));
        }
        if (resp.get("code") != null) {
            dataMap.put("code", resp.get("code"));
        }
        return ReqResult.success(dataMap);
    }

    @Override
    public ReqResult<Map<String, Object>> getAgentStatus(String projectId, UserContext userContext) {
        if (StringUtils.isBlank(projectId)) {
            return ReqResult.error("0001", "projectId is required");
        }

        Map<String, Object> resp = aiAgentClient.getAgentStatus(projectId);
        if (resp == null) {
            return ReqResult.error("9999", "Failed to query Agent status: AI Agent returned no response");
        }

        Object code = resp.get("code");
        if (code == null || !"0000".equals(String.valueOf(code))) {
            String message = resp.get("message") == null ? "Failed to query Agent status" : String.valueOf(resp.get("message"));
            return ReqResult.error("9999", message);
        }

        // 提取data字段并转换为Map
        Object data = resp.get("data");
        Map<String, Object> dataMap = new HashMap<>();

        if (data != null) {
            try {
                // 如果data已经是Map类型，直接使用
                if (data instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingMap = (Map<String, Object>) data;
                    dataMap = existingMap;
                } else {
                    // 将JSON字符串转换为Map
                    String dataJson = data.toString();
                    dataMap = objectMapper.readValue(dataJson, new TypeReference<Map<String, Object>>() {
                    });
                }

            } catch (Exception e) {
                log.warn("Failed to parse data JSON, using raw payload", e);
                // 解析失败时，将原始data包装在Map中
                dataMap.put("data", data);
            }
        }

        if (resp.get("tid") != null) {
            dataMap.put("tid", resp.get("tid"));
        }
        if (resp.get("message") != null) {
            dataMap.put("message", resp.get("message"));
        }
        if (resp.get("code") != null) {
            dataMap.put("code", resp.get("code"));
        }
        return ReqResult.success(dataMap);
    }

    @Override
    public ReqResult<Map<String, Object>> stopAgent(String projectId, UserContext userContext) {
        if (StringUtils.isBlank(projectId)) {
            return ReqResult.error("0001", "projectId is required");
        }

        Map<String, Object> resp = aiAgentClient.stopAgent(projectId);
        if (resp == null) {
            return ReqResult.error("9999", "Failed to stop Agent service: AI Agent returned no response");
        }

        Object code = resp.get("code");
        if (code == null || !"0000".equals(String.valueOf(code))) {
            String message = resp.get("message") == null ? "Failed to stop Agent service" : String.valueOf(resp.get("message"));
            return ReqResult.error("9999", message);
        }

        // 提取data字段并转换为Map
        Object data = resp.get("data");
        Map<String, Object> dataMap = new HashMap<>();

        if (data != null) {
            try {
                // 如果data已经是Map类型，直接使用
                if (data instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingMap = (Map<String, Object>) data;
                    dataMap = existingMap;
                } else {
                    // 将JSON字符串转换为Map
                    String dataJson = data.toString();
                    dataMap = objectMapper.readValue(dataJson, new TypeReference<Map<String, Object>>() {
                    });
                }

            } catch (Exception e) {
                log.warn("Failed to parse data JSON, using raw payload", e);
                // 解析失败时，将原始data包装在Map中
                dataMap.put("data", data);
            }
        }

        if (resp.get("tid") != null) {
            dataMap.put("tid", resp.get("tid"));
        }
        if (resp.get("message") != null) {
            dataMap.put("message", resp.get("message"));
        }
        if (resp.get("code") != null) {
            dataMap.put("code", resp.get("code"));
        }
        return ReqResult.success(dataMap);
    }

}
