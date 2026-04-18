package com.xspaceagi.knowledge.api.task;

import com.xspaceagi.knowledge.core.application.service.IKnowledgeFullTextBatchSyncService;
import com.xspaceagi.system.domain.service.UserDomainService;
import com.xspaceagi.system.infra.dao.entity.Tenant;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.TenantStatus;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 全文检索定时同步任务
 *
 * <p>每隔30分钟检查所有启用租户的知识库分段数据是否存在未同步记录，
 * 并将其同步到 Quickwit 全文检索服务。</p>
 */
@Slf4j
@Component("knowledgeFullTextPeriodicSyncTask")
public class KnowledgeFullTextPeriodicSyncTask extends AbstractTaskExecuteService {

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private IKnowledgeFullTextBatchSyncService batchSyncService;

    @Resource
    private UserDomainService userDomainService;


    @PostConstruct
    public void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("knowledgeFullTextPeriodicSyncTask")
                .beanId("knowledgeFullTextPeriodicSyncTask")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERY_30_MINUTE.getCron())
                .params(Map.of())
                .build());
    }

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTaskDto) {
        try {
            this.periodicSyncUnsyncedKnowledgeBases();
        } catch (Exception e) {
            log.error("Scheduled task failed", e);
        }
        return false;
    }

    public void periodicSyncUnsyncedKnowledgeBases() {
        log.info("========== 定时任务触发，检查未同步知识库分段数据并同步到全文检索 ==========");
        try {
            List<Tenant> tenants = userDomainService.queryTenantsByStatus(TenantStatus.Enabled);
            log.info("定时任务找到 {} 个启用的租户", tenants.size());

            int totalSuccess = 0;
            int totalFail = 0;

            for (Tenant tenant : tenants) {
                try {
                    log.debug("定时任务开始处理租户: tenantId={}, tenantName={}", tenant.getId(), tenant.getName());

                    // 构造系统用户上下文
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

                    try {
                        // 同步该租户的所有未同步知识库
                        batchSyncService.syncAllUnsyncedKnowledgeBasesToQuickwit(tenant.getId());
                        log.debug("定时任务租户同步成功: tenantId={}, tenantName={}", tenant.getId(), tenant.getName());
                        totalSuccess++;
                    } catch (Exception e) {
                        log.error("定时任务租户同步失败: tenantId={}, tenantName={}", tenant.getId(), tenant.getName(), e);
                        totalFail++;
                    } finally {
                        // 清除上下文
                        RequestContext.remove();
                    }
                } catch (Exception e) {
                    log.error("定时任务处理租户异常: tenantId={}, tenantName={}", tenant.getId(), tenant.getName(), e);
                    totalFail++;
                }
            }

            log.info("========== 定时任务完成: 总租户数={}, 成功={}, 失败={} ==========",
                    tenants.size(), totalSuccess, totalFail);
        } catch (Exception e) {
            log.error("Scheduled task failed", e);
        }
    }
}
