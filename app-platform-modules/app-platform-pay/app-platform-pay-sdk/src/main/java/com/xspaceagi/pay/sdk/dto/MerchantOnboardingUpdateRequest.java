package com.xspaceagi.pay.sdk.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class MerchantOnboardingUpdateRequest extends MerchantOnboardingUpsertRequest {
    private Long id;
}
