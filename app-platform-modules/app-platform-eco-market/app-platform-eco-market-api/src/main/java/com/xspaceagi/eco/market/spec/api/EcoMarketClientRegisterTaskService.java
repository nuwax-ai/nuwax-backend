package com.xspaceagi.eco.market.spec.api;

import com.xspaceagi.eco.market.domain.service.IEcoMarketClientSecretDomainService;
import com.xspaceagi.eco.market.sdk.constant.EcoMarketRegisterTaskConstant;
import com.xspaceagi.eco.market.sdk.service.IEcoMarketSecretRpcService;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * 租户生态市场客户端注册任务：创建租户后异步注册，失败自动重试直至成功。
 */
@Slf4j
@Service(EcoMarketRegisterTaskConstant.BEAN_ID)
public class EcoMarketClientRegisterTaskService extends AbstractTaskExecuteService {

    @Resource
    private IEcoMarketSecretRpcService ecoMarketSecretRpcService;

    @Resource
    private IEcoMarketClientSecretDomainService ecoMarketClientSecretDomainService;

    @Value("${eco-market.server.cancelHB:}")
    private String cancelHB;

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTask) {
        if (StringUtils.isNotBlank(cancelHB)) {
            return true;
        }
        Map<String, Object> params = (Map<String, Object>) scheduleTask.getParams();
        if (params == null || params.get("tenantId") == null) {
            log.error("Eco market register task missing tenantId, taskId={}", scheduleTask.getTaskId());
            return true;
        }
        Long tenantId = Long.parseLong(params.get("tenantId").toString());
        String name = params.get("name") != null ? params.get("name").toString() : "EcoMarket-Client-" + tenantId;
        String description = params.get("description") != null ? params.get("description").toString() : "";
        if (StringUtils.isBlank(description)) {
            description = "生态市场客户端 - " + name;
        }

        RequestContext.setThreadTenantId(tenantId);
        try {
            if (ecoMarketClientSecretDomainService.existsClientSecret(tenantId)) {
                log.info("Eco market client already registered, tenantId={}", tenantId);
                return true;
            }
            ecoMarketSecretRpcService.registerClient(tenantId, name, description);
            log.info("Eco market client registered via schedule task, tenantId={}", tenantId);
            return true;
        } catch (Exception e) {
            log.warn("Eco market client register failed, will retry, tenantId={}, taskId={}",
                    tenantId, scheduleTask.getTaskId(), e);
            return false;
        } finally {
            RequestContext.remove();
        }
    }
}
