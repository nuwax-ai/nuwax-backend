package com.xspaceagi.pay.application.schedule;

import com.xspaceagi.pay.application.support.OpenPayNotifyService;
import com.xspaceagi.pay.domain.model.PayOpenNotifyTaskModel;
import com.xspaceagi.pay.domain.repository.PayOpenNotifyTaskRepository;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.Date;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 开放支付 webhook 推送任务：每分钟扫描可运行任务（WAIT_PAY 查单 / PENDING 到期重试）。
 * 单任务异常不阻断；每行处理前设租户上下文
 */
@Slf4j
@Service("openPayNotifyTaskExecuteService")
public class OpenPayNotifyRunner extends AbstractTaskExecuteService {

    private static final int BATCH_LIMIT = 50;

    /** WAIT_PAY 任务 24h 未支付视为过期（对账任务兜底关闭订单后清理） */
    private static final long STALE_WAIT_MS = 24 * 3600_000L;

    @Resource
    private PayOpenNotifyTaskRepository notifyTaskRepository;

    @Resource
    private OpenPayNotifyService openPayNotifyService;

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @PostConstruct
    private void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("openPayNotifyPush")
                .beanId("openPayNotifyTaskExecuteService")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERY_MINUTE.getCron())
                .params(Map.of())
                .build());
    }

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTaskDto) {
        List<PayOpenNotifyTaskModel> tasks = TenantFunctions.callWithIgnoreCheck(
                () -> notifyTaskRepository.listRunnable(BATCH_LIMIT));
        for (PayOpenNotifyTaskModel task : tasks) {
            try {
                RequestContext.setThreadTenantId(task.getTenantId());
                openPayNotifyService.tick(task);
            } catch (Exception e) {
                log.warn("[pay-open-notify] tick failed, gatewayOrderNo={}", task.getGatewayOrderNo(), e);
            } finally {
                RequestContext.remove();
            }
        }
        // 过期等待清理：长期未支付的等待任务取消（订单侧关单后不会 PAID）
        List<PayOpenNotifyTaskModel> stale = TenantFunctions.callWithIgnoreCheck(
                () -> notifyTaskRepository.listStaleWaiting(new Date(System.currentTimeMillis() - STALE_WAIT_MS), BATCH_LIMIT));
        for (PayOpenNotifyTaskModel task : stale) {
            try {
                RequestContext.setThreadTenantId(task.getTenantId());
                // 24h 到期先查一次单：可能在 LIMIT 窗口外已 PAID 但 webhook 未被扫到——直接发而不是取消
                openPayNotifyService.tick(task);
                // tick 后仍 WAIT_PAY（订单仍非终态）才取消
                PayOpenNotifyTaskModel reloaded = notifyTaskRepository.findById(task.getId()).orElse(null);
                if (reloaded != null && PayOpenNotifyTaskModel.NOTIFY_WAIT_PAY.equals(reloaded.getStatus())) {
                    reloaded.setStatus(PayOpenNotifyTaskModel.NOTIFY_CANCELED);
                    reloaded.setLastError("wait-pay timeout");
                    notifyTaskRepository.save(reloaded);
                }
            } catch (Exception e) {
                log.warn("[pay-open-notify] stale cleanup failed, gatewayOrderNo={}", task.getGatewayOrderNo(), e);
            } finally {
                RequestContext.remove();
            }
        }
        return false; // CONTINUE：循环执行（true=COMPLETE，任务永久停止）
    }
}
