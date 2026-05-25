package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.MerchantOnboardingStatus;
import com.xspaceagi.pay.sdk.enums.MerchantOnboardingType;
import lombok.Data;

@Data
public class MerchantOnboardingPageRequest {
    private String clientId;

    private MerchantOnboardingType onboardingType;

    private MerchantOnboardingStatus status;

    private Long timestamp;

    private String nonce;

    private String signature;

    private Integer page;

    private Integer pageSize;
}
