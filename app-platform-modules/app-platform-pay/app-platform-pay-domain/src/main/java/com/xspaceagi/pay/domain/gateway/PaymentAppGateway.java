package com.xspaceagi.pay.domain.gateway;

import com.xspaceagi.pay.sdk.dto.OrderAndTransactionCreateResponse;
import com.xspaceagi.pay.sdk.dto.OrderCreateResponse;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryResponse;
import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayClientScene;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;

/** 调用 nuwax-pay App 原生支付 Open API。 */
public interface PaymentAppGateway {

    OrderCreateResponse createOrder(
            PayGatewayOutbound outbound, String bizOrderNo, long orderAmount, String subject, String ext);

    OrderAndTransactionCreateResponse createTransaction(
            PayGatewayOutbound outbound,
            String gatewayPaymentOrderNo,
            PayChannel payChannel,
            String clientIp,
            PayClientScene payClientScene);

    PaymentStatusQueryResponse queryOrderStatus(
            PayGatewayOutbound outbound, String gatewayPaymentOrderNo, boolean syncFromChannel);
}
