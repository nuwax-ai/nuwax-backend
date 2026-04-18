package com.xspaceagi.agent.core.infra.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class ComputerPodClient {

    @Resource
    private SandboxServerConfigService sandboxServerConfigService;

    /**
     * 启动容器
     */
    public Map<String, Object> ensurePod(Long cId, Long userId) {
        SandboxServerConfig.SandboxServer sandboxServer = selectSandboxServer(cId);
        String url = sandboxServer.getServerAgentUrl() + "/computer/pod/ensure";
        log.info("[ComputerPodClient] ensurePod userId={} cId={} , url={}", userId, cId, url);

        Map<String, Object> body = new HashMap<>();
        body.put("project_id", String.valueOf(cId));
        body.put("user_id", String.valueOf(userId));
        body.put("resource_limits", null);

        return doPost(url, body, sandboxServer, userId + "_" + cId + "_ensure");
    }

    /**
     * 容器保活
     */
    public Map<String, Object> keepalive(Long cId, Long userId) {
        SandboxServerConfig.SandboxServer sandboxServer = selectSandboxServer(cId);
        String url = sandboxServer.getServerAgentUrl() + "/computer/pod/keepalive";
        log.info("[ComputerPodClient] keepalive userId={} cId={} , url={}", userId, cId, url);

        Map<String, Object> body = new HashMap<>();
        body.put("project_id", String.valueOf(cId));
        body.put("user_id", String.valueOf(userId));

        return doPost(url, body, sandboxServer, userId + "_" + cId + "_keepalive");
    }

    /**
     * 重启容器（销毁后重建）
     */
    public Map<String, Object> restart(Long cId, Long userId) {
        SandboxServerConfig.SandboxServer sandboxServer = selectSandboxServer(cId);
        String url = sandboxServer.getServerAgentUrl() + "/computer/pod/restart";
        if (sandboxServer.getScope() == SandboxScopeEnum.USER) {
            url = sandboxServer.getServerAgentUrl() + "/admin/services/restart";
        }
        log.info("[ComputerPodClient] restart userId={} cId={} , url={}", userId, cId, url);

        Map<String, Object> body = new HashMap<>();
        body.put("project_id", String.valueOf(cId));
        body.put("user_id", String.valueOf(userId));
        body.put("resource_limits", null);

        return doPost(url, body, sandboxServer, userId + "_" + cId + "_restart");
    }

    /**
     * 查询容器 VNC 服务状态
     */
    public Map<String, Object> getVncStatus(Long cId, Long userId) {
        SandboxServerConfig.SandboxServer sandboxServer = selectSandboxServer(cId);
        if (sandboxServer.getScope() == SandboxScopeEnum.USER) {
            Map<String, Object> result = new HashMap<>();
            result.put("vnc_ready", true);
            result.put("novnc_ready", true);
            result.put("message", "连接用户个人电脑");
            return Map.of("code", "0000", "data", result);
        }
        String baseUrl = sandboxServer.getServerAgentUrl();

        String url = org.springframework.web.util.UriComponentsBuilder.fromHttpUrl(baseUrl + "/computer/pod/vnc-status")
                .queryParam("project_id", cId)
                .queryParam("user_id", userId)
                .toUriString();

        log.info("[ComputerPodClient] getVncStatus userId={} cId={} , url={}", userId, cId, url);

        return doGet(url, sandboxServer, userId + "_" + cId + "_vnc_status");
    }

    /**
     * 选择沙箱服务器
     */
    private SandboxServerConfig.SandboxServer selectSandboxServer(Long cId) {
        SandboxServerConfig.SandboxServer sandboxServer;
        try {
            sandboxServer = sandboxServerConfigService.selectServer(cId);
        } catch (Exception e) {
            log.warn("[ComputerPodClient] selectServer failed cId={}", cId, e);
            throw BizException.of(BizExceptionCodeEnum.agentDependencyServiceError);
        }
        if (sandboxServer == null || sandboxServer.getServerAgentUrl() == null) {
            throw BizException.of(BizExceptionCodeEnum.agentSandboxServerStoppedOrRemoved);
        }
        return sandboxServer;
    }

    private Map<String, Object> doPost(String url, Map<String, Object> body, SandboxServerConfig.SandboxServer sandboxServer, String logId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 所有下游请求增加 x-api-key 头，与代理保持一致
        headers.set("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey());
        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    }
            );
            Map<String, Object> responseBody = entity.getBody();
            log.info("[ComputerPodClient] logId={} call OK, response={}", logId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[ComputerPodClient] logId={} call failed, status={}, responseBody={}", logId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(logId, e);
        }
    }

    private Map<String, Object> doGet(String url, SandboxServerConfig.SandboxServer sandboxServer, String logId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 所有下游请求增加 x-api-key 头，与代理保持一致
        headers.set("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey());
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    requestEntity,
                    new ParameterizedTypeReference<Map<String, Object>>() {
                    }
            );
            Map<String, Object> responseBody = entity.getBody();
            log.info("[ComputerPodClient] logId={} call OK, response={}", logId, responseBody);
            return responseBody;
        } catch (HttpClientErrorException e) {
            log.warn("[ComputerPodClient] logId={} call failed, status={}, responseBody={}", logId, e.getStatusCode(), e.getResponseBodyAsString());
            return parseClientErr(logId, e);
        }
    }

    // 捕获4xx错误，尝试解析响应体
    private Map<String, Object> parseClientErr(String logId, HttpClientErrorException e) {
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("success", false);
        resultMap.put("message", e.getMessage());
        try {
            String responseBody = e.getResponseBodyAsString();
            if (responseBody != null && !responseBody.isEmpty()) {
                ObjectMapper objectMapper = new ObjectMapper();
                @SuppressWarnings("unchecked")
                Map<String, Object> errorResponse = objectMapper.readValue(responseBody, Map.class);
                if (errorResponse.containsKey("code")) {
                    resultMap.put("code", errorResponse.get("code"));
                }
                if (errorResponse.containsKey("message")) {
                    resultMap.put("message", errorResponse.get("message"));
                }
            }
        } catch (Exception parseException) {
            log.error("[ComputerPodClient] logId={} 解析错误响应体失败", logId, parseException);
        }
        return resultMap;
    }


}