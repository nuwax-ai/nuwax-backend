package com.xspaceagi.pay.application.support;

import com.xspaceagi.pay.domain.gateway.PaymentAppGateway;
import com.xspaceagi.pay.domain.gateway.PaymentH5Gateway;
import com.xspaceagi.pay.domain.gateway.PaymentMiniPayGateway;
import com.xspaceagi.pay.domain.gateway.PaymentScanGateway;
import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryResponse;
import com.xspaceagi.pay.sdk.enums.PayMode;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 查询支付状态。 */
@Component
@RequiredArgsConstructor
public class PayOrderStatusQuerySupport {

    private final PaymentScanGateway paymentScanGateway;
    private final PaymentMiniPayGateway paymentMiniPayGateway;
    private final PaymentH5Gateway paymentH5Gateway;
    private final PaymentAppGateway paymentAppGateway;

    public PaymentStatusQueryResponse queryOrderStatus(
            PayGatewayOutbound outbound, PayOrderModel row, boolean syncFromChannel) {
        if (row.getPayMode() == PayMode.minipay) {
            return paymentMiniPayGateway.queryOrderStatus(outbound, row.getGatewayPaymentOrderNo(), syncFromChannel);
        }
        if (row.getPayMode() == PayMode.h5) {
            return paymentH5Gateway.queryOrderStatus(outbound, row.getGatewayPaymentOrderNo(), syncFromChannel);
        }
        if (row.getPayMode() == PayMode.app) {
            return paymentAppGateway.queryOrderStatus(outbound, row.getGatewayPaymentOrderNo(), syncFromChannel);
        }
        return paymentScanGateway.queryOrderStatus(outbound, row.getGatewayPaymentOrderNo(), syncFromChannel);
    }
}
