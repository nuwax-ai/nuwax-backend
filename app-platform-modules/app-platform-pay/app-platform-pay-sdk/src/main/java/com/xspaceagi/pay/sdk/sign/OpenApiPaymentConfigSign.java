package com.xspaceagi.pay.sdk.sign;

import com.xspaceagi.pay.sdk.dto.PayConfigQueryRequest;

/** 支付配置 Open API 路径与签名辅助 */
public final class OpenApiPaymentConfigSign {

    public static final String BASE = "/open-api/payment/config";
    public static final String PATH_QUERY = BASE + "/query";

    private OpenApiPaymentConfigSign() {}

    public static byte[] signQuery(PayConfigQueryRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_QUERY, request, clientSecret);
    }
}
