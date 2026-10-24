package com.xspaceagi.agent.core.infra.rpc;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxGlobalConfigDto;
import com.xspaceagi.sandbox.sdk.support.SandboxEndpointConverter;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Slf4j
@Component("sandboxServerConfigService")
public class SandboxServerConfigService extends AbstractTaskExecuteService {

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private ISandboxConfigRpcService iSandboxConfigRpcService;

    @Getter
    @Value("${custom-page.docker-proxy.base-url:}")
    private String defaultPageAppVncUrl;

    private final HttpClient httpClient = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final Map<Long, Map<String, ServerStatus>> serverStatusMap = new ConcurrentHashMap<>();

    private final AtomicInteger index = new AtomicInteger(0);

    @PostConstruct
    public void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("sandboxServerConfigService")
                .beanId("sandboxServerConfigService")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERY_5_SECOND.getCron())
                .params(Map.of())
                .build());
    }

    // Get sandbox server based on cId (automatically get tenantId from session)
    public SandboxServerConfig.SandboxServer selectServer(Long cid) {
        return TenantFunctions.callWithIgnoreCheck(() -> {
            ConversationDto conversation = conversationApplicationService.getConversationByCid(cid);
            if (conversation == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentConversationNotFound);
            }
            TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(conversation.getTenantId());
            String sandboxServerId = conversation.getSandboxServerId();
            SandboxServerConfig.SandboxServer sandboxServer = selectServer(tenantConfig, conversation.getUserId(), sandboxServerId);
            if (conversation.getSandboxServerId() == null || !conversation.getSandboxServerId().equals(sandboxServer.getServerId())) {
                conversationApplicationService.updateConversationSandboxServerId(conversation.getId(), sandboxServer.getServerId());
            }
            conversation.setSandboxServerId(sandboxServer.getServerId());
            sandboxServer.setCurrentConversation(conversation);
            return sandboxServer;
        });
    }

    /** 按沙箱配置 id 解析目标服务器（目录浏览等无会话场景；租户/用户取当前请求上下文） */
    public SandboxServerConfig.SandboxServer selectServerBySandboxId(Long sandboxId) {
        RequestContext requestContext = RequestContext.get();
        TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(requestContext.getTenantId());
        return selectServer(tenantConfig, requestContext.getUserId(), String.valueOf(sandboxId));
    }

    /** 已持有沙箱配置（调用方权限校验时已加载）时复用，跳过重复 queryById；校验与转换规则同 selectServer */
    public SandboxServerConfig.SandboxServer selectServerBySandboxId(SandboxConfigRpcDto sandboxConfigRpcDto) {
        RequestContext requestContext = RequestContext.get();
        TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(requestContext.getTenantId());
        return requireActiveAndConvert(tenantConfig, sandboxConfigRpcDto);
    }

    /** USER 沙箱须启用且在线、GLOBAL 须启用；通过后连同全局配置转换为 SandboxServer */
    private SandboxServerConfig.SandboxServer requireActiveAndConvert(TenantConfigDto tenantConfig,
            SandboxConfigRpcDto sandboxConfigRpcDto) {
        if (sandboxConfigRpcDto.getScope() == SandboxScopeEnum.USER && !sandboxConfigRpcDto.getIsActive()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentClientDisabled);
        } else if (!sandboxConfigRpcDto.getIsActive()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentComputerServerDisabled);
        }
        if (sandboxConfigRpcDto.getScope() == SandboxScopeEnum.USER && !sandboxConfigRpcDto.isOnline()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentClientOffline);
        }
        SandboxGlobalConfigDto globalConfig = iSandboxConfigRpcService.getGlobalConfig(tenantConfig.getTenantId());
        return convertToSandboxServer(sandboxConfigRpcDto, globalConfig);
    }

    public SandboxServerConfig.SandboxServer selectServer(TenantConfigDto tenantConfig, Long userId, String serverId) {

        if (!serverStatusMap.containsKey(tenantConfig.getTenantId())) {
            serverStatusMap.put(tenantConfig.getTenantId(), new ConcurrentHashMap<>());
        }
        if (serverId != null && !serverId.equals("-1")) {// -1 represents global sandboxes
            try {
                SandboxConfigRpcDto sandboxConfigRpcDto = iSandboxConfigRpcService.queryById(Long.parseLong(serverId));
                if (sandboxConfigRpcDto != null) {
                    return requireActiveAndConvert(tenantConfig, sandboxConfigRpcDto);
                }
            } catch (NumberFormatException e) {
                log.warn("Unable to convert serverId to Long type, skip this record: {}", serverId);
                // 忽略
            }
        }

        if (serverId == null || serverId.equals("-1")) { // -1 代表 global sandboxes
            Object o = redisUtil.get("user_sandbox_server_" + userId);
            if (o != null) {
                serverId = o.toString();
            }
        }
        SandboxServerConfig sandboxServerConfig = buildGloabalSandboxServerConfig(tenantConfig);
        for (SandboxServerConfig.SandboxServer sandboxServer : sandboxServerConfig.getSandboxServers()) {
            if (sandboxServer.getServerId().equals(serverId)) {
                return sandboxServer;
            }
        }

        SandboxServerConfig.SandboxServer sandboxServer = selectServer(tenantConfig, new ArrayList<>(sandboxServerConfig.getSandboxServers()));
        redisUtil.set("user_sandbox_server_" + userId, sandboxServer.getServerId(), 86400);
        return sandboxServer;
    }

    public SandboxConfigRpcDto queryById(Long id) {
        return iSandboxConfigRpcService.queryById(id);
    }

    private SandboxServerConfig.SandboxServer selectServer(TenantConfigDto tenantConfig, List<SandboxServerConfig.SandboxServer> sandboxServers) {
        if (sandboxServers.isEmpty()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSandboxCapacityExceeded);// Current agent sandbox resources have reached the upper limit
        }
        SandboxServerConfig.SandboxServer sandboxServer = sandboxServers.get(index.getAndIncrement() % sandboxServers.size());
        Map<String, ServerStatus> statusMap = serverStatusMap.get(tenantConfig.getTenantId());
        ServerStatus serverStatus = statusMap.get(sandboxServer.getServerId());
        if (serverStatus == null) {
            return sandboxServer;
        }
        if (serverStatus.getCurrentUsers() >= sandboxServer.getMaxUsers()) {
            sandboxServers.remove(sandboxServer);
            return selectServer(tenantConfig, sandboxServers);
        }
        return sandboxServer;
    }

    /**
     * 当前租户全局沙箱列表（含 file-server 地址）
     */
    public List<SandboxServerConfig.SandboxServer> listGlobalServers(TenantConfigDto tenantConfig) {
        SandboxServerConfig config = buildGloabalSandboxServerConfig(tenantConfig);
        if (config == null || CollectionUtils.isEmpty(config.getSandboxServers())) {
            return List.of();
        }
        return new ArrayList<>(config.getSandboxServers());
    }

    private SandboxServerConfig buildGloabalSandboxServerConfig(TenantConfigDto tenantConfig) {
        SandboxServerConfig sandboxServerConfig;
        List<SandboxConfigRpcDto> sandboxConfigRpcDtos = iSandboxConfigRpcService.queryGlobalConfigs(tenantConfig.getTenantId());
        if (CollectionUtils.isEmpty(sandboxConfigRpcDtos) && tenantConfig.getTenantId() != 1L) {
            // Read configuration of 1 when global is not configured
            sandboxConfigRpcDtos = iSandboxConfigRpcService.queryGlobalConfigs(1L);
        }
        sandboxServerConfig = new SandboxServerConfig();
        SandboxGlobalConfigDto globalConfig = iSandboxConfigRpcService.getGlobalConfig(tenantConfig.getTenantId());
        try {
            sandboxServerConfig.setPerUserMemoryGB(Double.parseDouble(globalConfig.getPerUserMemoryGB()));
            sandboxServerConfig.setPerUserCpuCores(Integer.parseInt(globalConfig.getPerUserCpuCores()));
        } catch (NumberFormatException e) {
            sandboxServerConfig.setPerUserMemoryGB(4);
            sandboxServerConfig.setPerUserCpuCores(2);
        }
        sandboxServerConfig.setSandboxServers(sandboxConfigRpcDtos.stream().map(sandboxConfigRpcDto -> convertToSandboxServerSafely(sandboxConfigRpcDto, globalConfig)).filter(Objects::nonNull).collect(Collectors.toList()));

        Assert.notEmpty(sandboxServerConfig.getSandboxServers(), "Agent computer not configured");
        sandboxServerConfig.getSandboxServers().forEach(sandboxServer -> {
            if (sandboxServer.getPerUserCpuCores() == 0) {
                sandboxServer.setPerUserCpuCores(sandboxServerConfig.getPerUserCpuCores());
            }
            // Appropriate limit on number of CPU cores
            Assert.isTrue(sandboxServer.getPerUserCpuCores() > 0 && sandboxServer.getPerUserCpuCores() < 32, "User sandbox environment CPU configuration error");
            if (sandboxServer.getPerUserMemoryGB() == 0) {
                sandboxServer.setPerUserMemoryGB(sandboxServerConfig.getPerUserMemoryGB());
            }
            Assert.isTrue(sandboxServer.getPerUserMemoryGB() > 0 && sandboxServer.getPerUserMemoryGB() < 64, "User sandbox environment memory configuration error");
        });
        return sandboxServerConfig;
    }

    /** 列表构建容错版：单条配置非法（端口未配/host 缺失等）跳过并告警，不因一行坏数据中止整个租户沙箱列表；全部无效由 buildGloabalSandboxServerConfig 的 Assert 兜底报错 */
    private SandboxServerConfig.SandboxServer convertToSandboxServerSafely(SandboxConfigRpcDto sandboxConfigRpcDto, SandboxGlobalConfigDto sandboxGlobalConfigDto) {
        try {
            return convertToSandboxServer(sandboxConfigRpcDto, sandboxGlobalConfigDto);
        } catch (IllegalArgumentException e) {
            log.warn("[sandbox-server] skip invalid sandbox config, serverId={}, reason={}",
                    sandboxConfigRpcDto == null ? null : sandboxConfigRpcDto.getId(), e.getMessage());
            return null;
        }
    }

    private SandboxServerConfig.SandboxServer convertToSandboxServer(SandboxConfigRpcDto sandboxConfigRpcDto, SandboxGlobalConfigDto sandboxGlobalConfigDto) {
        SandboxServerConfig.SandboxServer sandboxServer = new SandboxServerConfig.SandboxServer();
        sandboxServer.setServerId(sandboxConfigRpcDto.getId().toString());
        sandboxServer.setServerName(sandboxConfigRpcDto.getName());
        String hostWithScheme = sandboxConfigRpcDto.getConfigValue().getHostWithScheme();
        if (StringUtils.isBlank(hostWithScheme)) {
            throw new IllegalArgumentException("Invalid agent computer configuration");
        }
        sandboxServer.setScope(sandboxConfigRpcDto.getScope());
        // URL 组装复用 sandbox-sdk 统一转换（含尾斜杠/端口校验；端口未配置视为配置非法，不再产出）
        SandboxEndpointConverter.SandboxEndpoint endpoint = SandboxEndpointConverter.toEndpoint(sandboxConfigRpcDto);
        if (endpoint == null || endpoint.agentBaseUrl() == null || endpoint.fileBaseUrl() == null) {
            throw new IllegalArgumentException("Invalid agent computer configuration");
        }
        sandboxServer.setServerAgentUrl(endpoint.agentBaseUrl());
        sandboxServer.setServerVncUrl(endpoint.vncBaseUrl());
        sandboxServer.setServerFileUrl(endpoint.fileBaseUrl());
        sandboxServer.setServerApiKey(endpoint.apiKey());
        sandboxServer.setMaxUsers(sandboxConfigRpcDto.getScope() == SandboxScopeEnum.GLOBAL
                ? sandboxConfigRpcDto.getConfigValue().getMaxUsers() : Integer.MAX_VALUE);
        if (sandboxGlobalConfigDto != null) {
            try {
                sandboxServer.setPerUserMemoryGB(Double.parseDouble(sandboxGlobalConfigDto.getPerUserMemoryGB()));
                sandboxServer.setPerUserCpuCores(Integer.parseInt(sandboxGlobalConfigDto.getPerUserCpuCores()));
            } catch (NumberFormatException e) {
                sandboxServer.setPerUserMemoryGB(4);
                sandboxServer.setPerUserCpuCores(2);
            }
        } else {
            sandboxServer.setPerUserMemoryGB(4);
            sandboxServer.setPerUserCpuCores(2);
        }
        return sandboxServer;
    }

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTaskDto) {
        try {
            this.checkServerStatus();
        } catch (Exception e) {
            log.error("Scheduled task failed", e);
        }
        return false;
    }

    public void checkServerStatus() {
        for (Map.Entry<Long, Map<String, ServerStatus>> entry : serverStatusMap.entrySet()) {
            TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(entry.getKey());
            if (tenantConfig != null) {
                try {
                    if (!JSON.isValid(tenantConfig.getSandboxConfig())) {
                        continue;
                    }
                    SandboxServerConfig sandboxServerConfig = buildGloabalSandboxServerConfig(tenantConfig);
                    Map<String, ServerStatus> serverStatusMap = entry.getValue();
                    for (SandboxServerConfig.SandboxServer sandboxServer : sandboxServerConfig.getSandboxServers()) {
                        ServerStatus serverStatus = serverStatusMap.get(sandboxServer.getServerId());
                        if (serverStatus == null) {
                            serverStatus = new ServerStatus();
                            serverStatusMap.put(sandboxServer.getServerId(), serverStatus);
                        }
                        try {
                            HttpRequest request = HttpRequest.newBuilder().uri(URI.create(sandboxServer.getServerAgentUrl() + "/computer/pod/count"))
                                    .header("x-api-key", sandboxServer.getServerApiKey() == null ? "" : sandboxServer.getServerApiKey())
                                    .timeout(java.time.Duration.ofSeconds(10))
                                    .GET().build();
                            String serverStatusStr = httpClient.send(request, HttpResponse.BodyHandlers.ofString()).body();
                            log.debug("url {}, serverStatusStr: {}", sandboxServer.getServerAgentUrl() + "/computer/pod/count", serverStatusStr);
                            JSONObject jsonObject = JSON.parseObject(serverStatusStr);
                            JSONObject data = jsonObject.getJSONObject("data");
                            serverStatus.currentUsers = data.getInteger("total_count");
                            serverStatus.setServerId(sandboxServer.getServerId());
                            serverStatus.setStatus(1);
                            serverStatus.setFailedTimes(0);
                        } catch (Exception e) {
                            if (serverStatus.getFailedTimes() > 3) {
                                serverStatus.setStatus(0);
                            }
                            log.warn("query server status error {}", sandboxServer.getServerAgentUrl(), e);
                        }

                    }
                } catch (Exception e) {
                    // ignore
                }
            }
        }
    }


    @Data
    private static class ServerStatus {
        private String serverId;
        private int status;
        private int currentUsers;
        private int failedTimes;
    }
}
