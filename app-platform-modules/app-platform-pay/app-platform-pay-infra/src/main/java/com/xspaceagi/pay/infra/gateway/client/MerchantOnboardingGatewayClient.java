package com.xspaceagi.pay.infra.gateway.client;

import com.xspaceagi.pay.domain.gateway.MerchantOnboardingGateway;
import com.xspaceagi.pay.infra.gateway.utils.PayGatewayClientUtils;
import com.xspaceagi.pay.sdk.dto.ApiResponse;
import com.xspaceagi.pay.sdk.dto.ByClientIdRequest;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingResponse;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingUpdateRequest;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingUpsertRequest;
import com.xspaceagi.pay.sdk.sign.OpenApiMerchantOnboardingSign;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Component
public class MerchantOnboardingGatewayClient implements MerchantOnboardingGateway {

    private static final ParameterizedTypeReference<ApiResponse<MerchantOnboardingResponse>> ONBOARDING_RESPONSE_TYPE =
            new ParameterizedTypeReference<ApiResponse<MerchantOnboardingResponse>>() {};

    private final RestTemplate payGatewayRestTemplate;

    public MerchantOnboardingGatewayClient(@Qualifier("payGatewayRestTemplate") RestTemplate payGatewayRestTemplate) {
        this.payGatewayRestTemplate = payGatewayRestTemplate;
    }

    @Override
    public MerchantOnboardingResponse add(PayGatewayOutbound outbound, MerchantOnboardingUpsertRequest request) {
        String url = outbound.baseUrl() + OpenApiMerchantOnboardingSign.PATH_ADD;
        byte[] body = OpenApiMerchantOnboardingSign.signAdd(request, outbound.clientSecret());
        log.info("[pay-gateway] POST {} clientId={}", url, request.getClientId());
        return PayGatewayClientUtils.postSigned(payGatewayRestTemplate, url, body, ONBOARDING_RESPONSE_TYPE, true);
    }

    @Override
    public MerchantOnboardingResponse update(PayGatewayOutbound outbound, MerchantOnboardingUpdateRequest request) {
        String url = outbound.baseUrl() + OpenApiMerchantOnboardingSign.PATH_UPDATE;
        byte[] body = OpenApiMerchantOnboardingSign.signUpdate(request, outbound.clientSecret());
        log.info("[pay-gateway] POST {} clientId={}", url, request.getClientId());
        return PayGatewayClientUtils.postSigned(payGatewayRestTemplate, url, body, ONBOARDING_RESPONSE_TYPE, true);
    }

    @Override
    public MerchantOnboardingResponse getByClientId(PayGatewayOutbound outbound) {
        String url = outbound.baseUrl() + OpenApiMerchantOnboardingSign.PATH_GET_BY_CLIENT_ID;
        ByClientIdRequest body = new ByClientIdRequest();
        body.setClientId(outbound.clientId());
        byte[] signed = OpenApiMerchantOnboardingSign.signGetByClientId(body, outbound.clientSecret());
        log.info("[pay-gateway] POST {} clientId={}", OpenApiMerchantOnboardingSign.PATH_GET_BY_CLIENT_ID, outbound.clientId());
        return PayGatewayClientUtils.postSigned(payGatewayRestTemplate, url, signed, ONBOARDING_RESPONSE_TYPE, false);
    }
}
