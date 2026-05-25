package com.xspaceagi.pay.domain.gateway;

import com.xspaceagi.pay.sdk.dto.MerchantOnboardingResponse;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingUpdateRequest;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingUpsertRequest;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;

/** 调用 nuwax-pay {@code /open-api/merchant-onboarding}；出站上下文由应用层解析后传入。 */
public interface MerchantOnboardingGateway {

    MerchantOnboardingResponse add(PayGatewayOutbound outbound, MerchantOnboardingUpsertRequest request);

    MerchantOnboardingResponse update(PayGatewayOutbound outbound, MerchantOnboardingUpdateRequest request);

    MerchantOnboardingResponse getByClientId(PayGatewayOutbound outbound);
}
