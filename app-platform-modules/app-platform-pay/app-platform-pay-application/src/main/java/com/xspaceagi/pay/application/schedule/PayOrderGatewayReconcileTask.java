package com.xspaceagi.pay.application.schedule;

import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 支付网关补偿：无网关单号关单并尽力通知 Bill，以及已有网关单号但本地未终态的查单同步。
 */
@Slf4j
@Service(PayOrderGatewayReconcileRunner.TASK_BEAN_ID)
public class PayOrderGatewayReconcileTask extends AbstractTaskExecuteService {

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private PayOrderGatewayReconcileRunner payOrderGatewayReconcileRunner;

    @PostConstruct
    public void registerReconcileTask() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId(PayOrderGatewayReconcileRunner.TASK_ID)
                .beanId(PayOrderGatewayReconcileRunner.TASK_BEAN_ID)
                .cron(ScheduleTaskDto.Cron.EVERY_10_MINUTE.getCron())
                .maxExecTimes(Long.MAX_VALUE)
                .params(Map.of())
                .taskName("支付网关状态补偿")
                .targetType("PAY_ORDER_RECONCILE")
                .build());
        log.info("[pay-reconcile] registered global task {}", PayOrderGatewayReconcileRunner.TASK_ID);
    }

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTask) {
        try {
            payOrderGatewayReconcileRunner.reconcileBatch();
        } catch (Exception e) {
            log.error("[pay-reconcile] scheduled task failed", e);
        }
        return false;
    }
}
