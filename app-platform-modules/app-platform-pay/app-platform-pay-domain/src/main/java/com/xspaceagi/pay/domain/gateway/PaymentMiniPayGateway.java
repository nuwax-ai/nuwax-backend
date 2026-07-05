package com.xspaceagi.pay.domain.gateway;

import com.xspaceagi.pay.sdk.dto.OrderAndTransactionCreateResponse;
import com.xspaceagi.pay.sdk.dto.OrderCreateResponse;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryResponse;
import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayClientScene;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;

/** 调用 nuwax-pay 小程序支付 Open API；出站上下文由应用层解析后传入。 */
public interface PaymentMiniPayGateway {

    OrderCreateResponse createOrder(
            PayGatewayOutbound outbound, String bizOrderNo, long orderAmount, String subject, String ext);

    OrderAndTransactionCreateResponse createTransaction(
            PayGatewayOutbound outbound,
            String gatewayPaymentOrderNo,
            PayChannel payChannel,
            String subAppid,
            String openId,
            String buyerId,
            String clientIp,
            PayClientScene payClientScene);

    /**
     * @param syncFromChannel true 时网关会向支付渠道查询并落库
     */
    PaymentStatusQueryResponse queryOrderStatus(
            PayGatewayOutbound outbound, String gatewayPaymentOrderNo, boolean syncFromChannel);
}
