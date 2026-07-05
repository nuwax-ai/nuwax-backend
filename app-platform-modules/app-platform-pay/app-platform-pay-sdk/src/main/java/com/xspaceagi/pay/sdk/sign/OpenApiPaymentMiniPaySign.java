package com.xspaceagi.pay.sdk.sign;

import com.xspaceagi.pay.sdk.dto.MiniPayOrderAndTransactionCreateRequest;
import com.xspaceagi.pay.sdk.dto.MiniPayOrderCreateRequest;
import com.xspaceagi.pay.sdk.dto.MiniPayTransactionCreateRequest;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryRequest;

/** 微信/支付宝小程序支付（minipay）Open API 路径与签名辅助 */
public final class OpenApiPaymentMiniPaySign {

    public static final String BASE = "/open-api/payment/minipay";
    public static final String PATH_CREATE_ORDER_AND_TRANSACTION = BASE + "/create-order-and-transaction";
    public static final String PATH_CREATE_ORDER = BASE + "/create-order";
    public static final String PATH_CREATE_TRANSACTION = BASE + "/create-transaction";
    public static final String PATH_STATUS = BASE + "/status";

    private OpenApiPaymentMiniPaySign() {}

    public static byte[] signCreateOrderAndTransaction(
            MiniPayOrderAndTransactionCreateRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_CREATE_ORDER_AND_TRANSACTION, request, clientSecret);
    }

    public static byte[] signCreateOrder(MiniPayOrderCreateRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_CREATE_ORDER, request, clientSecret);
    }

    public static byte[] signCreateTransaction(MiniPayTransactionCreateRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_CREATE_TRANSACTION, request, clientSecret);
    }

    public static byte[] signQueryStatus(PaymentStatusQueryRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_STATUS, request, clientSecret);
    }
}
