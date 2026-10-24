package com.xspaceagi.agent.core.infra.rpc;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.custompage.sdk.dto.CustomPageDto;
import com.xspaceagi.sandbox.sdk.support.SandboxEndpointConverter.SandboxEndpoint;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Component
public class ComputerPodClient {

    @Resource
    private SandboxServerConfigService sandboxServerConfigService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private ICustomPageRpcService iCustomPageRpcService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private IUserDataPermissionRpcService iUserDataPermissionRpcService;
    @Resource
    private SandboxConfigEndpoints sandboxConfigEndpoints;

    @Value("${custom-page.ai-agent.base-url:}")
    private String configuredBaseUrl;

    /**
     * 启动容器
     */
    public Map<String, Object> ensurePod(Long cId, Long userId, String appStage) {
        ConversationDto conversationByCid = conversationApplicationService.getConversationByCid(cId);
        String baseUrl;
        SandboxServerConfig.SandboxServer sandboxServer;
        Map<String, Object> extraBody = new HashMap<>();
        boolean isAgentComputer = conversationByCid != null;
        if (conversationByCid == null) {
            // 同一个ID兼容适配网页应用
            CustomPageDto customPageDto = iCustomPageRpcService.queryDetail(cId);
            if (customPageDto == null) {
                throw new BizException("Incomplete data");
            }
            spacePermissionService.checkSpaceUserPermission(customPageDto.getSpaceId());
            if (customPageDto.getSandboxId() == null) {
                baseUrl = configuredBaseUrl;
                sandboxServer = new SandboxServerConfig.SandboxServer();
                sandboxServer.setServerAgentUrl(baseUrl);
            } else {
                try {
                    SandboxEndpoint endpoint = sandboxConfigEndpoints.resolvePageAppSandbox(
                            customPageDto.getTenantId(), userId, customPageDto.getSpaceId(), customPageDto.getProjectId(), customPageDto.getSandboxId());
                    if (endpoint == null || endpoint.agentBaseUrl() == null) {
                        throw new IllegalArgumentException("sandbox endpoint missing");
                    }
                    baseUrl = endpoint.agentBaseUrl();
                    sandboxServer = new SandboxServerConfig.SandboxServer();
                    sandboxServer.setServerAgentUrl(baseUrl);
                    extraBody.put("tenant_id", customPageDto.getTenantId());
                    extraBody.put("space_id", customPageDto.getSpaceId());
                    if (endpoint.isolationType() != null) {
                        extraBody.put("isolation_type", endpoint.isolationType());
                    }
                    if (endpoint.isolationKey() != null) {
                        extraBody.put("pod_id", endpoint.isolationKey());
                    }
                } catch (Exception e) {
                    log.warn("[workspace-client] selectAppDevelopmentSandbox failed cId={}", cId, e);
                    throw new BizException("The sandbox bound to the project does not exist or has been taken offline.");
                }
            }
            // 网页应用仅传 service_type
            extraBody.put(WorkspaceServiceSupport.POD_SERVICE_TYPE, WorkspaceServiceType.PAGEAPP.getContainerType());
        } else {
            sandboxServer = selectSandboxServer(cId);
            baseUrl = sandboxServer.getServerAgentUrl();
            WorkspaceServiceSupport.applyPodServiceParams(extraBody,
                    WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer), appStage);
        }
        String url = baseUrl + "/computer/pod/ensure";
        log.info("[ComputerPodClient] ensurePod userId={} cId={} , url={}", userId, cId, url);
        int perUserCpuCores = sandboxServer.getPerUserCpuCores();
        double perUserMemoryGB = sandboxServer.getPerUserMemoryGB();
        String storageGB = "50Gi";
        UserDataPermissionDto userDataPermission = iUserDataPermissionRpcService.getUserDataPermission(userId);
        if (userDataPermission != null) {
            //上限由管理员自己决定
            if (userDataPermission.getAgentComputerMemoryGb() != null && userDataPermission.getAgentComputerMemoryGb() > 0) {
                perUserMemoryGB = userDataPermission.getAgentComputerMemoryGb();
            }
            if (userDataPermission.getAgentComputerCpuCores() != null && userDataPermission.getAgentComputerCpuCores() > 0) {
                perUserCpuCores = userDataPermission.getAgentComputerCpuCores();
            }
            if (isAgentComputer && userDataPermission.getAgentComputerStorageLimitGb() != null && userDataPermission.getAgentComputerStorageLimitGb().intValue() > 0) {
                storageGB = userDataPermission.getAgentComputerStorageLimitGb().intValue() + "Gi";
            }
            if (!isAgentComputer && userDataPermission.getPageAppStorageLimitGb() != null && userDataPermission.getPageAppStorageLimitGb().intValue() > 0) {
                storageGB = userDataPermission.getPageAppStorageLimitGb().intValue() + "Gi";
            }
        }
        Map<String, Object> body = new HashMap<>();
        body.put("project_id", String.valueOf(cId));
        body.put("user_id", String.valueOf(userId));
        body.put("resource_limits", Map.of(
                "cpu", perUserCpuCores,
                "memory", perUserMemoryGB * 1024 * 1024 * 1024,
                "swap", perUserMemoryGB * 1024 * 1024 * 1024 * 2,
                "storage_size", storageGB
        ));
        body.putAll(extraBody);
        return doPost(url, body, sandboxServer, userId + "_" + cId + "_ensure");
    }

    /**
     * 容器保活
     */
    public Map<String, Object> keepalive(Long cId, Long userId, String appStage) {
        SandboxServerConfig.SandboxServer sandboxServer = selectSandboxServer(cId);
        String url = sandboxServer.getServerAgentUrl() + "/computer/pod/keepalive";
        log.info("[ComputerPodClient] keepalive userId={} cId={} , url={}", userId, cId, url);

        Map<String, Object> body = new HashMap<>();
        body.put("project_id", String.valueOf(cId));
        body.put("user_id", String.valueOf(userId));
        WorkspaceServiceSupport.applyPodServiceParams(body,
                WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer), appStage);

        return doPost(url, body, sandboxServer, userId + "_" + cId + "_keepalive");
    }

    /**
     * 重启容器（销毁后重建）
     */
    public Map<String, Object> restart(Long cId, Long userId, String appStage) {
        SandboxServerConfig.SandboxServer sandboxServer = selectSandboxServer(cId);
        String url = sandboxServer.getServerAgentUrl() + "/computer/pod/restart";
        boolean adminRestart = sandboxServer.getScope() == SandboxScopeEnum.USER;
        if (adminRestart) {
            url = sandboxServer.getServerAgentUrl() + "/admin/services/restart";
        }
        log.info("[ComputerPodClient] restart userId={} cId={} , url={}", userId, cId, url);

        Map<String, Object> body = new HashMap<>();
        body.put("project_id", String.valueOf(cId));
        body.put("user_id", String.valueOf(userId));
        body.put("resource_limits", null);
        if (!adminRestart) {
            // /admin/services/restart 是客户端接口，不附带项目上下文参数
            WorkspaceServiceSupport.applyPodServiceParams(body,
                    WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer), appStage);
        }

        return doPost(url, body, sandboxServer, userId + "_" + cId + "_restart");
    }

    /**
     * 查询容器 VNC 服务状态
     */
    public Map<String, Object> getVncStatus(Long cId, Long userId, String appStage) {
        SandboxServerConfig.SandboxServer sandboxServer = selectSandboxServer(cId);
        if (sandboxServer.getScope() == SandboxScopeEnum.USER) {
            Map<String, Object> result = new HashMap<>();
            result.put("vnc_ready", true);
            result.put("novnc_ready", true);
            result.put("message", "连接用户个人电脑");
            return Map.of("code", "0000", "data", result);
        }
        String baseUrl = sandboxServer.getServerAgentUrl();

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseUrl + "/computer/pod/vnc-status")
                .queryParam("project_id", cId)
                .queryParam("user_id", userId);
        WorkspaceServiceSupport.applyPodServiceParams(builder,
                WorkspaceServiceSupport.resolveServiceContext(cId, sandboxServer), appStage);
        URI uri = UriEncodingSupport.buildEncodedUri(builder);

        log.info("[ComputerPodClient] getVncStatus userId={} cId={} , url={}", userId, cId, uri);

        return doGet(uri, sandboxServer, userId + "_" + cId + "_vnc_status");
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

    private Map<String, Object> doGet(URI uri, SandboxServerConfig.SandboxServer sandboxServer, String logId) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        // 所有下游请求增加 x-api-key 头，与代理保持一致
        headers.set("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey());
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        RestTemplate restTemplate = new RestTemplate();
        try {
            ResponseEntity<Map<String, Object>> entity = restTemplate.exchange(
                    uri,
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
                    // 前端会把 4010 当登录态失效跳转登录页；转 9999 通用错误，message 照常透传
                    Object code = errorResponse.get("code");
                    resultMap.put("code", "4010".equals(String.valueOf(code)) ? "9999" : code);
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