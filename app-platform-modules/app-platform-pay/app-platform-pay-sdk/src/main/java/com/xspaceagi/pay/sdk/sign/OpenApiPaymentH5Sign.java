package com.xspaceagi.pay.sdk.sign;

import com.xspaceagi.pay.sdk.dto.H5OrderCreateRequest;
import com.xspaceagi.pay.sdk.dto.H5TransactionCreateRequest;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryRequest;

public final class OpenApiPaymentH5Sign {

    public static final String BASE = "/open-api/payment/h5";
    public static final String PATH_CREATE_ORDER = BASE + "/create-order";
    public static final String PATH_CREATE_TRANSACTION = BASE + "/create-transaction";
    public static final String PATH_STATUS = BASE + "/status";

    private OpenApiPaymentH5Sign() {}

    public static byte[] signCreateOrder(H5OrderCreateRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_CREATE_ORDER, request, clientSecret);
    }

    public static byte[] signCreateTransaction(H5TransactionCreateRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_CREATE_TRANSACTION, request, clientSecret);
    }

    public static byte[] signQueryStatus(PaymentStatusQueryRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_STATUS, request, clientSecret);
    }
}
