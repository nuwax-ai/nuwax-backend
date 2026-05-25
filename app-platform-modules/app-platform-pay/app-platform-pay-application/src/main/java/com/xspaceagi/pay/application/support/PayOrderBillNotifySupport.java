package com.xspaceagi.pay.application.support;

import com.xspaceagi.bill.sdk.rpc.IBillRpcService;
import com.xspaceagi.bill.spec.enums.PayStatusEnum;
import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.sdk.dto.ScanOrderStatusQueryResponse;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import lombok.extern.slf4j.Slf4j;

/** 支付结果通知 Bill（进程内 RPC）。 */
@Slf4j
public final class PayOrderBillNotifySupport {

    private PayOrderBillNotifySupport() {}

    /**
     * 建单调网关失败时尽力通知 Bill（FAILED），不校验结果；调用方应自行将 {@code bizNotifyStatus} 标为 NOTIFIED。
     */
    public static void notifyBillGatewayCreateFailedBestEffort(IBillRpcService billRpcService, PayOrderModel row) {
        Long orderId = parseNumericBizOrderNo(row.getBizOrderNo());
        if (orderId == null) {
            return;
        }
        String payStatus = PayStatusEnum.FAILED.getCode();
        try {
            billRpcService.paymentCallback(row.getTenantId(), orderId, payStatus);
            log.info(
                    "[pay-notify] bill gateway-create-failed tenantId={} orderId={} payStatus={}",
                    row.getTenantId(),
                    orderId,
                    payStatus);
        } catch (Exception e) {
            log.warn(
                    "[pay-notify] bill gateway-create-failed ignored tenantId={} orderId={} payStatus={} msg={}",
                    row.getTenantId(),
                    orderId,
                    payStatus,
                    e.getMessage());
        }
    }

    /**
     * @param pollTimeout true 时无网关终态则按关单通知（轮询 1h 耗尽场景）
     * @return true 表示业务通知已成功（或无 Bill 回调目标）
     */
    public static boolean notifyBillPaymentCallback(
            IBillRpcService billRpcService, PayOrderModel row, ScanOrderStatusQueryResponse gatewayResp, boolean pollTimeout) {
        Long orderId = parseNumericBizOrderNo(row.getBizOrderNo());
        if (orderId == null) {
            return true;
        }
        PaymentStatus st = gatewayResp != null ? gatewayResp.getStatus() : null;
        String payStatus = toBillPayStatusCode(st, pollTimeout);
        try {
            billRpcService.paymentCallback(row.getTenantId(), orderId, payStatus);
            log.info("[pay-notify] bill paymentCallback tenantId={} orderId={} payStatus={}", row.getTenantId(), orderId, payStatus);
            return true;
        } catch (Exception e) {
            log.warn(
                    "[pay-notify] bill paymentCallback failed tenantId={} orderId={} payStatus={} msg={}",
                    row.getTenantId(),
                    orderId,
                    payStatus,
                    e.getMessage());
            return false;
        }
    }

    public static Long parseNumericBizOrderNo(String bizOrderNo) {
        if (bizOrderNo == null || bizOrderNo.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(bizOrderNo.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String toBillPayStatusCode(PaymentStatus st, boolean pollTimeout) {
        if (st == PaymentStatus.PAID) {
            return PayStatusEnum.SUCCESS.getCode();
        }
        if (st == PaymentStatus.FAILED) {
            return PayStatusEnum.FAILED.getCode();
        }
        if (st == PaymentStatus.CLOSED) {
            return PayStatusEnum.CLOSED.getCode();
        }
        if (pollTimeout) {
            return PayStatusEnum.CLOSED.getCode();
        }
        return PayStatusEnum.PENDING.getCode();
    }
}
