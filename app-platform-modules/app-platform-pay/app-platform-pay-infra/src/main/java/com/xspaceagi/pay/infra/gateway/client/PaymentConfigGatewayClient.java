package com.xspaceagi.pay.infra.gateway.client;

import com.xspaceagi.pay.domain.gateway.PaymentConfigGateway;
import com.xspaceagi.pay.infra.gateway.utils.PayGatewayClientUtils;
import com.xspaceagi.pay.sdk.dto.ApiResponse;
import com.xspaceagi.pay.sdk.dto.PayConfigQueryRequest;
import com.xspaceagi.pay.sdk.dto.PayConfigResponse;
import com.xspaceagi.pay.sdk.sign.OpenApiPaymentConfigSign;
import com.xspaceagi.pay.spec.dto.PayGatewayConnectivityResponse;
import com.xspaceagi.pay.spec.exception.PayGatewayClientException;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class PaymentConfigGatewayClient implements PaymentConfigGateway {

    private static final ParameterizedTypeReference<ApiResponse<PayConfigResponse>> CONFIG_TYPE =
            new ParameterizedTypeReference<ApiResponse<PayConfigResponse>>() {};

    private static final ParameterizedTypeReference<ApiResponse<Long>> HEALTH_TYPE =
            new ParameterizedTypeReference<ApiResponse<Long>>() {};

    private final RestTemplate payGatewayRestTemplate;

    public PaymentConfigGatewayClient(@Qualifier("payGatewayRestTemplate") RestTemplate payGatewayRestTemplate) {
        this.payGatewayRestTemplate = payGatewayRestTemplate;
    }

    @Override
    public PayConfigResponse queryPayConfig(PayGatewayOutbound outbound) {
        String url = outbound.baseUrl() + OpenApiPaymentConfigSign.PATH_QUERY;
        PayConfigQueryRequest body = new PayConfigQueryRequest();
        body.setClientId(outbound.clientId());
        byte[] signed = OpenApiPaymentConfigSign.signQuery(body, outbound.clientSecret());
        log.info("[pay-gateway] POST {} clientId={}", url, outbound.clientId());
        return PayGatewayClientUtils.postSigned(payGatewayRestTemplate, url, signed, CONFIG_TYPE, true);
    }

    @Override
    public PayGatewayConnectivityResponse checkConnectivity(PayGatewayOutbound outbound) {
        String url = outbound.baseUrl() + "/health";
        log.info("[pay-gateway] GET {} clientId={}", url, outbound.clientId());
        long start = System.currentTimeMillis();
        try {
            Long serverTime = PayGatewayClientUtils.get(payGatewayRestTemplate, url, HEALTH_TYPE, true);
            long latency = System.currentTimeMillis() - start;
            return PayGatewayConnectivityResponse.builder()
                    .reachable(true)
                    .gatewayBaseUrl(outbound.baseUrl())
                    .gatewayServerTimeMillis(serverTime)
                    .latencyMillis(latency)
                    .build();
        } catch (PayGatewayClientException e) {
            return PayGatewayConnectivityResponse.builder()
                    .reachable(false)
                    .gatewayBaseUrl(outbound.baseUrl())
                    .message(e.getMessage())
                    .build();
        }
    }
}
