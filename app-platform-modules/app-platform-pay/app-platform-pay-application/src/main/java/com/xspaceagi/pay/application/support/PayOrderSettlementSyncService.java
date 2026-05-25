package com.xspaceagi.pay.application.support;

import com.xspaceagi.bill.sdk.rpc.IBillRpcService;
import com.xspaceagi.pay.domain.gateway.PaymentScanGateway;
import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.domain.repository.PayOrderRepository;
import com.xspaceagi.pay.sdk.dto.ScanOrderStatusQueryResponse;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import com.xspaceagi.pay.spec.enums.PayBizNotifyStatus;
import com.xspaceagi.pay.spec.enums.PayOrderGatewaySyncStatus;
import com.xspaceagi.pay.spec.exception.PayGatewayClientException;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 结算页等场景主动拉网关状态并通知 Bill，逻辑与 {@link com.xspaceagi.pay.application.schedule.PayOrderPollRunner} 单 tick 一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayOrderSettlementSyncService {

    private final PayOrderRepository payOrderRepository;
    private final PaymentScanGateway paymentScanGateway;
    private final PayGatewayOutboundExecutor payGatewayOutboundExecutor;
    private final IBillRpcService billRpcService;

    /**
     * @return true 表示 pay 侧已完成业务通知（NOTIFIED）；false 表示未同步、无可通知终态或网关调用失败
     */
    public boolean syncSettlementForBizOrderNo(long tenantId, String bizOrderNo) {
        if (!StringUtils.hasText(bizOrderNo)) {
            return false;
        }
        Optional<PayOrderModel> opt = payOrderRepository.findByTenantIdAndBizOrderNo(tenantId, bizOrderNo.trim());
        if (opt.isEmpty()) {
            log.debug("[pay-settlement-sync] pay order not found tenantId={} bizOrderNo={}", tenantId, bizOrderNo);
            return false;
        }
        PayOrderModel row = opt.get();
        if (row.getBizNotifyStatus() == PayBizNotifyStatus.NOTIFIED) {
            return true;
        }
        if (row.getGatewaySyncStatus() != PayOrderGatewaySyncStatus.SUCCESS
                || !StringUtils.hasText(row.getGatewayPaymentOrderNo())) {
            return false;
        }

        ScanOrderStatusQueryResponse last;
        try {
            PayGatewayOutbound outbound = payGatewayOutboundExecutor.resolveCached(tenantId);
            last = paymentScanGateway.queryOrderStatus(outbound, row.getGatewayPaymentOrderNo(), true);
            PayOrderGatewayQueryMerger.mergeIntoRow(row, last);
            payOrderRepository.save(row);
        } catch (PayGatewayClientException e) {
            log.warn(
                    "[pay-settlement-sync] query failed tenantId={} bizOrderNo={} gatewayNo={} code={} msg={}",
                    tenantId,
                    bizOrderNo,
                    row.getGatewayPaymentOrderNo(),
                    e.getGatewayCode(),
                    e.getGatewayMessage());
            return false;
        }

        PaymentStatus st = last.getStatus();
        if (st != PaymentStatus.PAID && st != PaymentStatus.FAILED && st != PaymentStatus.CLOSED) {
            return false;
        }
        if (!PayOrderBizNotifySupport.shouldNotifyBizOnTerminal(row, st)) {
            return false;
        }
        return notifyBillAndMark(row, last, PayOrderBizNotifySupport.expectedCasCurrent(row));
    }

    private boolean notifyBillAndMark(
            PayOrderModel row, ScanOrderStatusQueryResponse last, PayBizNotifyStatus expectedCurrent) {
        if (!PayOrderBillNotifySupport.notifyBillPaymentCallback(billRpcService, row, last, false)) {
            log.warn("[pay-settlement-sync] bill notify failed bizOrderNo={}", row.getBizOrderNo());
            return false;
        }
        if (payOrderRepository.casTransitionBizNotifyStatus(row.getId(), expectedCurrent, PayBizNotifyStatus.NOTIFIED)) {
            log.info(
                    "[pay-settlement-sync] notified bizOrderNo={} {} -> NOTIFIED status={}",
                    row.getBizOrderNo(),
                    expectedCurrent,
                    last.getStatus() != null ? last.getStatus().name() : null);
            return true;
        }
        PayOrderModel fresh = payOrderRepository.findById(row.getId()).orElse(null);
        return fresh != null && fresh.getBizNotifyStatus() == PayBizNotifyStatus.NOTIFIED;
    }
}
