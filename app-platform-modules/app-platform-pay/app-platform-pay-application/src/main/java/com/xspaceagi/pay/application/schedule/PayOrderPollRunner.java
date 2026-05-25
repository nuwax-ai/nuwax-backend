package com.xspaceagi.pay.application.schedule;

import com.xspaceagi.bill.sdk.rpc.IBillRpcService;
import com.xspaceagi.pay.application.support.PayOrderBillNotifySupport;
import com.xspaceagi.pay.application.support.PayOrderGatewayQueryMerger;
import com.xspaceagi.pay.domain.gateway.PaymentScanGateway;
import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.domain.repository.PayOrderRepository;
import com.xspaceagi.pay.sdk.dto.ScanOrderStatusQueryResponse;
import com.xspaceagi.pay.spec.enums.PayBizNotifyStatus;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import com.xspaceagi.pay.spec.enums.PayOrderGatewaySyncStatus;
import com.xspaceagi.pay.application.support.PayGatewayOutboundExecutor;
import com.xspaceagi.pay.spec.exception.PayGatewayClientException;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 阶段 1：建单成功后高频轮询（约 1 小时），终态或超时后通知业务。
 * 网关长时间未终态的补偿同步见 {@link PayOrderGatewayReconcileRunner}。
 */
@Slf4j
@Service
public class PayOrderPollRunner {

    public static final int MAX_POLL_TICKS = 720; // 1 hour
    public static final String TASK_BEAN_ID = "payOrderPollTaskExecuteService";

    private final PayOrderRepository payOrderRepository;
    private final PaymentScanGateway paymentScanGateway;
    private final PayGatewayOutboundExecutor payGatewayOutboundExecutor;
    private final IBillRpcService billRpcService;
    private final ScheduleTaskApiService scheduleTaskApiService;

    public PayOrderPollRunner(
            PayOrderRepository payOrderRepository,
            PaymentScanGateway paymentScanGateway,
            PayGatewayOutboundExecutor payGatewayOutboundExecutor,
            IBillRpcService billRpcService,
            ScheduleTaskApiService scheduleTaskApiService) {
        this.payOrderRepository = payOrderRepository;
        this.paymentScanGateway = paymentScanGateway;
        this.payGatewayOutboundExecutor = payGatewayOutboundExecutor;
        this.billRpcService = billRpcService;
        this.scheduleTaskApiService = scheduleTaskApiService;
    }

    public static String taskIdForPayOrder(Long payOrderId) {
        return "pay-order-poll-" + payOrderId;
    }

    /**
     * 注册统一调度轮询任务（每 5 秒一次，最多 {@link #MAX_POLL_TICKS} 次，约 60 分钟）
     */
    public void startPoll(long payOrderId, long tenantId) {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId(taskIdForPayOrder(payOrderId))
                .beanId(TASK_BEAN_ID)
                .cron(ScheduleTaskDto.Cron.EVERY_5_SECOND.getCron())
                .maxExecTimes((long) MAX_POLL_TICKS)
                .params(Map.of("payOrderId", payOrderId, "tenantId", tenantId))
                .taskName("支付状态轮询")
                .targetType("PAY_ORDER")
                .targetId(String.valueOf(payOrderId))
                .build());
    }

    /**
     * 单次轮询。返回 true 表示调度任务应结束；false 表示等待下次 cron。
     *
     * @param tenantId 任务参数中的租户；非空时用 {@link PayOrderRepository#findByTenantIdAndId} 加载，避免调度线程上租户上下文与订单不一致时查不到行却误结束任务。
     * @param execTimes 本次执行前调度任务 DTO 中的执行次数（与加锁前拷贝一致，比库中当前值小 1），超时判断使用 {@code execTimes + 1} 与 {@code maxExecTimes} 对齐。
     */
    public boolean pollOnce(Long payOrderId, Long tenantId, long execTimes, long maxExecTimes) {
        PayOrderModel row =
                tenantId != null
                        ? payOrderRepository.findByTenantIdAndId(tenantId, payOrderId).orElse(null)
                        : payOrderRepository.findById(payOrderId).orElse(null);
        if (row == null) {
            log.warn("[pay-poll] pay order not found, stop task payOrderId={} tenantId={}", payOrderId, tenantId);
            return true;
        }
        if (row.getBizNotifyStatus() != PayBizNotifyStatus.POLLING) {
            return true;
        }
        if (row.getGatewaySyncStatus() != PayOrderGatewaySyncStatus.SUCCESS
                || !StringUtils.hasText(row.getGatewayPaymentOrderNo())) {
            log.warn(
                    "[pay-poll] invalid row for polling, stop task payOrderId={} sync={} gatewayNo={}",
                    payOrderId,
                    row.getGatewaySyncStatus(),
                    row.getGatewayPaymentOrderNo());
            return true;
        }

        ScanOrderStatusQueryResponse last = null;
        PayGatewayOutbound outbound;
        try {
            outbound = payGatewayOutboundExecutor.resolveCached(row.getTenantId());
        } catch (PayGatewayClientException e) {
            logPollGatewayFailure("resolve", payOrderId, tenantId, row, execTimes, maxExecTimes, e);
            return false;
        }
        try {
            last = paymentScanGateway.queryOrderStatus(outbound, row.getGatewayPaymentOrderNo(), true);
            PayOrderGatewayQueryMerger.mergeIntoRow(row, last);
            payOrderRepository.save(row);

            PaymentStatus st = last.getStatus();
            if (st == PaymentStatus.PAID) {
                return tryNotifyAndMarkNotified(row, last, payOrderId);
            }
            if (st == PaymentStatus.FAILED || st == PaymentStatus.CLOSED) {
                return tryNotifyAndMarkNotified(row, last, payOrderId);
            }
        } catch (PayGatewayClientException e) {
            logPollGatewayFailure("query", payOrderId, tenantId, row, execTimes, maxExecTimes, e);
        }

        // 调度在加锁时已把 execTimes+1 写入库，DTO 上仍是「本 tick 之前的值」，故用 +1 对齐 maxExecTimes（否则本分支永远不触发，仅靠外层标 COMPLETE 会留下 POLLING）。
        if (execTimes + 1 >= maxExecTimes) {
            PayOrderModel again = reloadRow(payOrderId, tenantId);
            if (again != null && again.getBizNotifyStatus() == PayBizNotifyStatus.POLLING) {
                return tryNotifyTimeoutAndMark(again, last, payOrderId);
            }
            return true;
        }
        return false;
    }

    /**
     * 先通知业务，成功后再 CAS 为 NOTIFIED，避免 NOTIFIED 但业务未收到回调。
     */
    private boolean tryNotifyAndMarkNotified(PayOrderModel row, ScanOrderStatusQueryResponse last, Long payOrderId) {
        if (!PayOrderBillNotifySupport.notifyBillPaymentCallback(billRpcService, row, last, false)) {
            log.warn("[pay-poll] bill notify failed orderId={}, will retry", payOrderId);
            return false;
        }
        if (payOrderRepository.casTransitionBizNotifyStatus(row.getId(), PayBizNotifyStatus.POLLING, PayBizNotifyStatus.NOTIFIED)) {
            log.info("[pay-notify] notified paymentOrderStatus={} bizScene={} bizOrderNo={}",
                    last.getStatus() != null ? last.getStatus().name() : null, row.getBizScene(), row.getBizOrderNo());
            return true;
        }
        if (isBizNotifyStatus(row.getId(), PayBizNotifyStatus.NOTIFIED)) {
            return true;
        }
        log.warn("[pay-poll] cas POLLING->NOTIFIED failed after notify orderId={}, will retry", payOrderId);
        return false;
    }

    private boolean tryNotifyTimeoutAndMark(PayOrderModel row, ScanOrderStatusQueryResponse last, Long payOrderId) {
        if (!PayOrderBillNotifySupport.notifyBillPaymentCallback(billRpcService, row, last, true)) {
            log.warn("[pay-poll] bill notify timeout failed orderId={}, will retry", payOrderId);
            return false;
        }
        if (payOrderRepository.casTransitionBizNotifyStatus(row.getId(), PayBizNotifyStatus.POLLING, PayBizNotifyStatus.TIMEOUT)) {
            log.info("[pay-notify] poll-timeout bizNotifyStatus=TIMEOUT paymentOrderStatus={} bizScene={} bizOrderNo={}",
                    last != null && last.getStatus() != null ? last.getStatus().name() : null,
                    row.getBizScene(),
                    row.getBizOrderNo());
            return true;
        }
        if (isBizNotifyStatus(row.getId(), PayBizNotifyStatus.TIMEOUT)) {
            return true;
        }
        log.warn("[pay-poll] cas POLLING->TIMEOUT failed after notify orderId={}, will retry", payOrderId);
        return false;
    }

    private PayOrderModel reloadRow(Long payOrderId, Long tenantId) {
        return tenantId != null
                ? payOrderRepository.findByTenantIdAndId(tenantId, payOrderId).orElse(null)
                : payOrderRepository.findById(payOrderId).orElse(null);
    }

    private boolean isBizNotifyStatus(long payOrderId, PayBizNotifyStatus expected) {
        return payOrderRepository.findById(payOrderId).map(r -> r.getBizNotifyStatus() == expected).orElse(false);
    }

    private static void logPollGatewayFailure(
            String phase,
            Long payOrderId,
            Long taskTenantId,
            PayOrderModel row,
            long execTimes,
            long maxExecTimes,
            PayGatewayClientException e) {
        log.warn(
                "[pay-poll] phase={} failed payOrderId={} taskTenantId={} orderTenantId={} bizOrderNo={} gatewayPaymentOrderNo={} execTimes={}/{} gatewayCode={} gatewayMessage={}",
                phase,
                payOrderId,
                taskTenantId,
                row.getTenantId(),
                row.getBizOrderNo(),
                row.getGatewayPaymentOrderNo(),
                execTimes,
                maxExecTimes,
                e.getGatewayCode(),
                e.getGatewayMessage(),
                e);
    }

}
