package com.xspaceagi.pay.sdk.sign;

import com.xspaceagi.pay.sdk.dto.ByClientIdRequest;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingPageRequest;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingUpdateRequest;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingUpsertRequest;

/** 进件 Open API 路径与签名辅助 */
public final class OpenApiMerchantOnboardingSign {

    public static final String BASE = "/open-api/merchant-onboarding";
    public static final String PATH_ADD = BASE + "/add";
    public static final String PATH_UPDATE = BASE + "/update";
    public static final String PATH_GET_BY_CLIENT_ID = BASE + "/get-by-client-id";
    public static final String PATH_LIST_BY_CLIENT_ID = BASE + "/list-by-client-id";
    public static final String PATH_PAGE = BASE + "/page";

    private OpenApiMerchantOnboardingSign() {}

    public static byte[] signAdd(MerchantOnboardingUpsertRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_ADD, request, clientSecret);
    }

    public static byte[] signUpdate(MerchantOnboardingUpdateRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_UPDATE, request, clientSecret);
    }

    public static byte[] signGetByClientId(ByClientIdRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_GET_BY_CLIENT_ID, request, clientSecret);
    }

    public static byte[] signListByClientId(ByClientIdRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_LIST_BY_CLIENT_ID, request, clientSecret);
    }

    public static byte[] signPage(MerchantOnboardingPageRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_PAGE, request, clientSecret);
    }
}
