package com.xspaceagi.pay.sdk.sign;

import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryRequest;
import com.xspaceagi.pay.sdk.dto.ScanOrderAndTransactionCreateRequest;
import com.xspaceagi.pay.sdk.dto.ScanOrderCreateRequest;
import com.xspaceagi.pay.sdk.dto.ScanTransactionCreateRequest;

/** 主扫支付 Open API 路径与签名辅助 */
public final class OpenApiPaymentScanSign {

    public static final String BASE = "/open-api/payment/scan";
    public static final String PATH_CREATE_ORDER_AND_TRANSACTION = BASE + "/create-order-and-transaction";
    public static final String PATH_CREATE_ORDER = BASE + "/create-order";
    public static final String PATH_CREATE_TRANSACTION = BASE + "/create-transaction";
    public static final String PATH_STATUS = BASE + "/status";

    private OpenApiPaymentScanSign() {}

    public static byte[] signCreateOrderAndTransaction(
            ScanOrderAndTransactionCreateRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_CREATE_ORDER_AND_TRANSACTION, request, clientSecret);
    }

    public static byte[] signCreateOrder(ScanOrderCreateRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_CREATE_ORDER, request, clientSecret);
    }

    public static byte[] signCreateTransaction(ScanTransactionCreateRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_CREATE_TRANSACTION, request, clientSecret);
    }

    public static byte[] signQueryStatus(PaymentStatusQueryRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_STATUS, request, clientSecret);
    }
}
