package com.xspaceagi.pay.domain.gateway;

import com.xspaceagi.pay.sdk.dto.OrderAndTransactionCreateResponse;
import com.xspaceagi.pay.sdk.dto.OrderCreateResponse;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryResponse;
import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;

/** 调用 nuwax-pay H5 Open API；用于 H5支付。 */
public interface PaymentH5Gateway {

    OrderCreateResponse createOrder(
            PayGatewayOutbound outbound, String bizOrderNo, long orderAmount, String subject, String ext);

    OrderAndTransactionCreateResponse createTransaction(
            PayGatewayOutbound outbound,
            String gatewayPaymentOrderNo,
            PayChannel payChannel,
            String clientIp,
            String frontNotifyUrl);

    PaymentStatusQueryResponse queryOrderStatus(
            PayGatewayOutbound outbound, String gatewayPaymentOrderNo, boolean syncFromChannel);
}
