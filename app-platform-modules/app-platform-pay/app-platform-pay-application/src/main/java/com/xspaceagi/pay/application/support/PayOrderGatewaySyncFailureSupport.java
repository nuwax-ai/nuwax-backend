package com.xspaceagi.pay.application.support;

import com.xspaceagi.bill.sdk.rpc.IBillRpcService;
import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.domain.repository.PayOrderRepository;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import com.xspaceagi.pay.spec.enums.PayBizNotifyStatus;
import com.xspaceagi.pay.spec.enums.PayOrderGatewaySyncStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * 建单未拿到网关单号时的终态处理：本地 FAILED、尽力通知 Bill（不校验结果）、{@code bizNotifyStatus=NOTIFIED}。
 */
@Slf4j
public final class PayOrderGatewaySyncFailureSupport {

    private PayOrderGatewaySyncFailureSupport() {}

    /**
     * 建单调网关失败：落库 FAILED、尽力通知 Bill、标 NOTIFIED 后由调用方抛异常。
     */
    public static void finishGatewayCreateFailed(
            PayOrderRepository payOrderRepository,
            IBillRpcService billRpcService,
            PayOrderModel row,
            String gatewayLastError) {
        applyGatewaySyncFailedClose(payOrderRepository, billRpcService, row, gatewayLastError);
        log.info(
                "[pay-sync-fail] gateway create failed payOrderId={} bizOrderNo={} bizNotifyStatus=NOTIFIED",
                row.getId(),
                row.getBizOrderNo());
    }

    /**
     * 补偿关单：超时 PENDING / 未同步成功等，与建单失败相同策略通知 Bill。
     *
     * @return true 表示已闭环或无需处理
     */
    public static boolean closeLocally(
            PayOrderRepository payOrderRepository, IBillRpcService billRpcService, PayOrderModel row) {
        if (row == null || row.getId() == null) {
            return false;
        }
        if (row.getGatewaySyncStatus() == PayOrderGatewaySyncStatus.SUCCESS
                && StringUtils.hasText(row.getGatewayPaymentOrderNo())) {
            return true;
        }
        if (isGatewaySyncFailedClosed(row)) {
            return true;
        }
        applyGatewaySyncFailedClose(payOrderRepository, billRpcService, row, row.getGatewayLastError());
        log.info(
                "[pay-sync-fail] reconcile closed payOrderId={} bizOrderNo={} gatewaySync=FAILED bizNotifyStatus=NOTIFIED",
                row.getId(),
                row.getBizOrderNo());
        return true;
    }

    private static boolean isGatewaySyncFailedClosed(PayOrderModel row) {
        return row.getGatewaySyncStatus() == PayOrderGatewaySyncStatus.FAILED
                && PaymentStatus.FAILED.name().equals(row.getGatewayOrderStatus())
                && row.getBizNotifyStatus() == PayBizNotifyStatus.NOTIFIED;
    }

    private static void applyGatewaySyncFailedClose(
            PayOrderRepository payOrderRepository,
            IBillRpcService billRpcService,
            PayOrderModel row,
            String gatewayLastError) {
        row.setGatewaySyncStatus(PayOrderGatewaySyncStatus.FAILED);
        row.setGatewayOrderStatus(PaymentStatus.FAILED.name());
        if (gatewayLastError != null) {
            row.setGatewayLastError(gatewayLastError);
        }
        PayOrderBillNotifySupport.notifyBillGatewayCreateFailedBestEffort(billRpcService, row);
        row.setBizNotifyStatus(PayBizNotifyStatus.NOTIFIED);
        payOrderRepository.save(row);
    }
}
