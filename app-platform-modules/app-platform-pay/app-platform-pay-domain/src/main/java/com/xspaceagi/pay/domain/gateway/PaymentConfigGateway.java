package com.xspaceagi.pay.domain.gateway;

import com.xspaceagi.pay.sdk.dto.PayConfigResponse;
import com.xspaceagi.pay.spec.dto.PayGatewayConnectivityResponse;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;

/** 调用 nuwax-pay 支付配置 Open API；出站上下文由应用层解析后传入。 */
public interface PaymentConfigGateway {

    PayConfigResponse queryPayConfig(PayGatewayOutbound outbound);

    PayGatewayConnectivityResponse checkConnectivity(PayGatewayOutbound outbound);
}
