package com.xspaceagi.pay.application.service;

import com.xspaceagi.pay.sdk.dto.MerchantOnboardingResponse;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingUpdateRequest;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingUpsertRequest;

public interface MerchantOnboardingApplicationService {

    MerchantOnboardingResponse add(MerchantOnboardingUpsertRequest request);

    MerchantOnboardingResponse update(MerchantOnboardingUpdateRequest request);

    MerchantOnboardingResponse getByTenantId();
}
