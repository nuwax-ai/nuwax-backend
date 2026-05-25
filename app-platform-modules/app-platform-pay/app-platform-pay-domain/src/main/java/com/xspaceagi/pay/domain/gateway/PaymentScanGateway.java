package com.xspaceagi.pay.domain.gateway;

import com.xspaceagi.pay.sdk.dto.CashierSessionCreateResponse;
import com.xspaceagi.pay.sdk.dto.PaymentOrderCreateResponse;
import com.xspaceagi.pay.sdk.dto.ScanOrderStatusQueryResponse;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;

/** 调用 nuwax-pay 扫码支付 Open API；出站上下文由应用层解析后传入。 */
public interface PaymentScanGateway {

    PaymentOrderCreateResponse createOrder(
            PayGatewayOutbound outbound, String bizOrderNo, long orderAmount, String subject, String ext);

    CashierSessionCreateResponse createCashierSession(
            PayGatewayOutbound outbound,
            String gatewayPaymentOrderNo,
            long orderAmount,
            String subject,
            String bizRedirectUrl);

    /**
     * @param syncFromChannel true 时网关会向支付渠道查询并落库
     */
    ScanOrderStatusQueryResponse queryOrderStatus(
            PayGatewayOutbound outbound, String gatewayPaymentOrderNo, boolean syncFromChannel);
}
