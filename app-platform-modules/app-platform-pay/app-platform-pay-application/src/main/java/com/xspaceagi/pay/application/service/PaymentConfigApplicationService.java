package com.xspaceagi.pay.application.service;

import com.xspaceagi.pay.sdk.dto.PayConfigResponse;
import com.xspaceagi.pay.spec.dto.PayGatewayConnectivityResponse;

public interface PaymentConfigApplicationService {

    PayConfigResponse queryPayConfig(long tenantId);

    PayGatewayConnectivityResponse checkGatewayConnectivity(long tenantId);
}
