package com.xspaceagi.pay.sdk.sign;

import com.xspaceagi.pay.sdk.dto.AppOrderAndTransactionCreateRequest;
import com.xspaceagi.pay.sdk.dto.AppOrderCreateRequest;
import com.xspaceagi.pay.sdk.dto.AppTransactionCreateRequest;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryRequest;

/** App 原生支付 Open API 路径与签名辅助。 */
public final class OpenApiPaymentAppSign {

    public static final String BASE = "/open-api/payment/app";
    public static final String PATH_CREATE_ORDER_AND_TRANSACTION = BASE + "/create-order-and-transaction";
    public static final String PATH_CREATE_ORDER = BASE + "/create-order";
    public static final String PATH_CREATE_TRANSACTION = BASE + "/create-transaction";
    public static final String PATH_STATUS = BASE + "/status";

    private OpenApiPaymentAppSign() {}

    public static byte[] signCreateOrderAndTransaction(
            AppOrderAndTransactionCreateRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_CREATE_ORDER_AND_TRANSACTION, request, clientSecret);
    }

    public static byte[] signCreateOrder(AppOrderCreateRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_CREATE_ORDER, request, clientSecret);
    }

    public static byte[] signCreateTransaction(AppTransactionCreateRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_CREATE_TRANSACTION, request, clientSecret);
    }

    public static byte[] signQueryStatus(PaymentStatusQueryRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_STATUS, request, clientSecret);
    }
}
