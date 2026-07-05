package com.xspaceagi.pay.application.schedule;

import com.xspaceagi.bill.sdk.rpc.IBillRpcService;
import com.xspaceagi.pay.application.support.PayGatewayOutboundExecutor;
import com.xspaceagi.pay.application.support.PayOrderBillNotifySupport;
import com.xspaceagi.pay.application.support.PayOrderBizNotifySupport;
import com.xspaceagi.pay.application.support.PayOrderGatewayQueryMerger;
import com.xspaceagi.pay.application.support.PayOrderGatewaySyncFailureSupport;
import com.xspaceagi.pay.application.support.PayOrderStatusQuerySupport;
import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.domain.repository.PayOrderRepository;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryResponse;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import com.xspaceagi.pay.spec.enums.PayBizNotifyStatus;
import com.xspaceagi.pay.spec.exception.PayGatewayClientException;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.pay.spec.PayOrderScheduleConstants;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 阶段 2：低频补偿。① 建单未拿到网关单号（超时 PENDING 等）→ 落失败终态并尽力通知 Bill（不校验结果，标 NOTIFIED）；
 * ② 已有网关单号但本地状态未终态 → 查 nuwax-pay 同步并按 {@code bizNotifyStatus} 决定是否补通知。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayOrderGatewayReconcileRunner {

    public static final String TASK_ID = "pay-order-gateway-reconcile";
    public static final String TASK_BEAN_ID = "payOrderGatewayReconcileTask";

    /** 单次扫描上限，避免一次 tick 打满网关 */
    public static final int BATCH_SIZE = 100;

    /** 仅补偿最近 N 天内创建的订单 */
    public static final int LOOKBACK_DAYS = 7;

    private final PayOrderRepository payOrderRepository;
    private final PayOrderStatusQuerySupport payOrderStatusQuerySupport;
    private final PayGatewayOutboundExecutor payGatewayOutboundExecutor;
    private final IBillRpcService billRpcService;

    /**
     * @return 本 tick 实际处理条数
     */
    public int reconcileBatch() {
        Date createdAfter = Date.from(Instant.now().minus(LOOKBACK_DAYS, ChronoUnit.DAYS));
        Date pendingStaleBefore = Date.from(Instant.now().minus(PayOrderScheduleConstants.STALE_GATEWAY_SYNC_PENDING));
        List<PayOrderModel> syncFailures =
                TenantFunctions.callWithIgnoreCheck(
                        () -> payOrderRepository.listGatewaySyncFailureCandidates(createdAfter, pendingStaleBefore, BATCH_SIZE));
        List<PayOrderModel> candidates =
                TenantFunctions.callWithIgnoreCheck(
                        () -> payOrderRepository.listGatewayReconcileCandidates(createdAfter, BATCH_SIZE));
        int processed = 0;
        for (PayOrderModel row : syncFailures) {
            try {
                RequestContext.setThreadTenantId(row.getTenantId());
                if (PayOrderGatewaySyncFailureSupport.closeLocally(payOrderRepository, billRpcService, row)) {
                    processed++;
                }
            } catch (Exception e) {
                log.warn(
                        "[pay-reconcile] sync-failure error payOrderId={} tenantId={} msg={}",
                        row.getId(),
                        row.getTenantId(),
                        e.getMessage(),
                        e);
            } finally {
                RequestContext.remove();
            }
        }
        for (PayOrderModel row : candidates) {
            try {
                RequestContext.setThreadTenantId(row.getTenantId());
                if (reconcileOne(row)) {
                    processed++;
                }
            } catch (Exception e) {
                log.warn(
                        "[pay-reconcile] unexpected error payOrderId={} tenantId={} msg={}",
                        row.getId(),
                        row.getTenantId(),
                        e.getMessage(),
                        e);
            } finally {
                RequestContext.remove();
            }
        }
        log.info(
                "[pay-reconcile] batch done syncFailures={} gatewayCandidates={} processed={}",
                syncFailures.size(),
                candidates.size(),
                processed);
        return processed;
    }

    private boolean reconcileOne(PayOrderModel row) {
        PayGatewayOutbound outbound;
        try {
            outbound = payGatewayOutboundExecutor.resolveCached(row.getTenantId());
        } catch (PayGatewayClientException e) {
            log.warn(
                    "[pay-reconcile] resolve failed payOrderId={} tenantId={} gatewayNo={} msg={}",
                    row.getId(),
                    row.getTenantId(),
                    row.getGatewayPaymentOrderNo(),
                    e.getMessage());
            return false;
        }
        PaymentStatusQueryResponse last;
        try {
            last = payOrderStatusQuerySupport.queryOrderStatus(outbound, row, true);
        } catch (PayGatewayClientException e) {
            log.warn(
                    "[pay-reconcile] query failed payOrderId={} tenantId={} gatewayNo={} gatewayCode={} gatewayMessage={}",
                    row.getId(),
                    row.getTenantId(),
                    row.getGatewayPaymentOrderNo(),
                    e.getGatewayCode(),
                    e.getGatewayMessage());
            return false;
        }
        PayOrderGatewayQueryMerger.mergeIntoRow(row, last);
        row = payOrderRepository.save(row);

        PaymentStatus st = last.getStatus();
        if (!isGatewayTerminal(st)) {
            return true;
        }
        if (!shouldNotifyBizOnTerminal(row, st)) {
            return true;
        }
        return tryNotifyTerminalAndMarkNotified(row, last, row.getBizNotifyStatus());
    }

    /** @see PayOrderBizNotifySupport#shouldNotifyBizOnTerminal */
    private static boolean shouldNotifyBizOnTerminal(PayOrderModel row, PaymentStatus gatewayStatus) {
        return PayOrderBizNotifySupport.shouldNotifyBizOnTerminal(row, gatewayStatus);
    }

    private static boolean isGatewayTerminal(PaymentStatus st) {
        return st == PaymentStatus.PAID || st == PaymentStatus.FAILED || st == PaymentStatus.CLOSED;
    }

    private boolean tryNotifyTerminalAndMarkNotified(
            PayOrderModel row, PaymentStatusQueryResponse last, PayBizNotifyStatus expectedCurrent) {
        if (!PayOrderBillNotifySupport.notifyBillPaymentCallback(billRpcService, row, last, false)) {
            log.warn("[pay-reconcile] bill notify failed payOrderId={}, will retry next tick", row.getId());
            return false;
        }
        if (payOrderRepository.casTransitionBizNotifyStatus(row.getId(), expectedCurrent, PayBizNotifyStatus.NOTIFIED)) {
            log.info(
                    "[pay-reconcile] notify biz payOrderId={} {} -> NOTIFIED gatewayStatus={} bizOrderNo={}",
                    row.getId(),
                    expectedCurrent,
                    last.getStatus() != null ? last.getStatus().name() : null,
                    row.getBizOrderNo());
            return true;
        }
        if (payOrderRepository.findById(row.getId()).map(r -> r.getBizNotifyStatus() == PayBizNotifyStatus.NOTIFIED).orElse(false)) {
            return true;
        }
        log.warn("[pay-reconcile] cas {} -> NOTIFIED failed payOrderId={}", expectedCurrent, row.getId());
        return false;
    }
}
