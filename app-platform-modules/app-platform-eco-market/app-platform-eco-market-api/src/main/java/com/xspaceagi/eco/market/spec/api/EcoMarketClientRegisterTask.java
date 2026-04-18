package com.xspaceagi.eco.market.spec.api;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import com.xspaceagi.system.domain.service.UserDomainService;
import com.xspaceagi.system.infra.dao.entity.Tenant;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

import com.xspaceagi.eco.market.domain.service.IEcoMarketClientSecretDomainService;
import com.xspaceagi.eco.market.spec.app.service.IEcoMarketClientSecretApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.TenantStatus;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;

/**
 * 生态市场客户端配置
 */
@Slf4j
@Configuration
public class EcoMarketClientRegisterTask {

    @Resource
    private IEcoMarketClientSecretDomainService ecoMarketClientSecretDomainService;

    @Resource
    private UserDomainService userDomainService;

    @Resource
    private IEcoMarketClientSecretApplicationService clientSecretApplicationService;

    /**
     * 定时重试失败的任务
     * 每5分钟执行一次
     */
    @Scheduled(fixedDelayString = "${eco.market.client.retry.interval:300}", timeUnit = TimeUnit.SECONDS)
    public void scheduledRetryFailedTasks() {
        log.debug("Start eco-market client secret retry");

        // 查询所有的租户
        List<Tenant> tenants = userDomainService.queryTenantsByStatus(TenantStatus.Enabled);
        for (Tenant tenant : tenants) {
            log.debug("Check client secret for tenant [{}]", tenant.getName());
            processTenant(tenant);
        }
    }

    /**
     * 处理单个租户的客户端密钥注册和保存
     *
     * @param tenant 租户信息
     */
    private void processTenant(Tenant tenant) {
        try {
            // 创建系统用户上下文
            UserContext userContext = UserContext.builder()
                    .userId(0L)
                    .userName("system")
                    .tenantId(tenant.getId())
                    .tenantName(tenant.getName())
                    .build();

            // 设置请求上下文
            RequestContext<UserContext> requestContext = new RequestContext<>();
            requestContext.setTenantId(tenant.getId());
            requestContext.setUserContext(userContext);
            RequestContext.set(requestContext);

            var tenantId = tenant.getId();
            if (Objects.isNull(tenantId)) {
                log.error("Tenant [{}] id empty; cannot register client secret", tenant.getName());
                return;
            }

            // 检查并注册客户端密钥
            boolean exists = ecoMarketClientSecretDomainService.existsClientSecret(tenantId);

            if (!exists) {
                log.info("Tenant [{}] has no client secret; registering", tenant.getName());
                try {

                    var clientSecret = ecoMarketClientSecretDomainService.getOrRegisterClientSecret(
                            tenantId,
                            tenant.getName(),
                            "生态市场客户端 - " + tenant.getName());
                    // 使用应用层服务保存客户端密钥到本地数据库
                    if (clientSecret != null) {
                        clientSecretApplicationService.saveClientSecretDTO(clientSecret, userContext);
                        log.info("Tenant [{}] client secret registered and saved", tenant.getName());
                    }
                } catch (Exception e) {
                    // 注册异常
                    log.error("Tenant [{}] client secret register error; queued for retry", tenant.getName(), e);
                }
            } else {
                log.debug("Tenant [{}] client secret already exists", tenant.getName());
            }
        } catch (Exception e) {
            log.error("Client secret handling error for tenant [{}]", tenant.getName(), e);
        } finally {
            // 清除上下文
            RequestContext.remove();
        }
    }
}