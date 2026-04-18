package com.xspaceagi.mcp.application.service;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.mcp.adapter.domain.McpConfigDomainService;
import com.xspaceagi.mcp.adapter.repository.entity.McpConfig;
import com.xspaceagi.mcp.sdk.dto.McpConfigDto;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.TaskExecuteService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@Slf4j
@Service("sseMcpUpdateTaskService")
public class SSEMcpUpdateTaskServiceImpl extends AbstractDeployTaskService implements TaskExecuteService {


    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private McpConfigDomainService mcpConfigDomainService;

    @PostConstruct
    public void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("sseMcpUpdateTaskService")
                .beanId("sseMcpUpdateTaskService")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERYDAY_0_2.getCron())
                .params(Map.of())
                .build());
    }


    @Override
    public Mono<Boolean> asyncExecute(ScheduleTaskDto scheduleTask) {
        return Mono.fromCallable(() -> execute0(scheduleTask));
    }

    public boolean execute0(ScheduleTaskDto scheduleTask) {
        log.info("SSE update task running");
        Long id = 0L;
        List<McpConfig> mcpConfigs = mcpConfigDomainService.queryDeployedSSEMcpConfigList(id, 100);
        while (!mcpConfigs.isEmpty()) {
            for (McpConfig mcpConfig : mcpConfigs) {
                McpConfigDto mcpConfigDto = JSON.parseObject(mcpConfig.getConfig(), McpConfigDto.class);
                try {
                    RequestContext.set(RequestContext.builder()
                            .tenantId(mcpConfig.getTenantId())
                            .build());
                    updateAndSaveMcpConfig(mcpConfig.getId(), mcpConfigDto.getServerConfig(), true, mcpConfig.getModified());
                    log.info("SSE config updated {}, {}, {}", mcpConfig.getId(), mcpConfig.getName(), mcpConfigDto.getServerConfig());
                } catch (Exception e) {
                    log.error("SSE config update failed {}", mcpConfigDto.getServerConfig(), e);
                } finally {
                    RequestContext.remove();
                }
                id = mcpConfig.getId();
            }
            mcpConfigs = mcpConfigDomainService.queryDeployedSSEMcpConfigList(id, 100);
        }
        return false;
    }
}
