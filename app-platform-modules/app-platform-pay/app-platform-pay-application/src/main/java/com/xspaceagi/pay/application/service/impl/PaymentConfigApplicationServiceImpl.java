package com.xspaceagi.pay.application.service.impl;

import com.xspaceagi.pay.application.service.PaymentConfigApplicationService;
import com.xspaceagi.pay.application.support.PayGatewayOutboundExecutor;
import com.xspaceagi.pay.domain.gateway.PaymentConfigGateway;
import com.xspaceagi.pay.sdk.dto.PayConfigResponse;
import com.xspaceagi.pay.spec.dto.PayGatewayConnectivityResponse;
import com.xspaceagi.pay.spec.exception.PayGatewayClientException;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

@Service
public class PaymentConfigApplicationServiceImpl implements PaymentConfigApplicationService {

    @Resource
    private PaymentConfigGateway paymentConfigGateway;

    @Resource
    private PayGatewayOutboundExecutor payGatewayOutboundExecutor;

    @Override
    public PayConfigResponse queryPayConfig(long tenantId) {
        return payGatewayOutboundExecutor.invoke(tenantId, paymentConfigGateway::queryPayConfig);
    }

    @Override
    public PayGatewayConnectivityResponse checkGatewayConnectivity(long tenantId) {
        try {
            return paymentConfigGateway.checkConnectivity(payGatewayOutboundExecutor.resolve(tenantId));
        } catch (PayGatewayClientException e) {
            return PayGatewayConnectivityResponse.builder()
                    .reachable(false)
                    .message(e.getMessage())
                    .build();
        }
    }
}
