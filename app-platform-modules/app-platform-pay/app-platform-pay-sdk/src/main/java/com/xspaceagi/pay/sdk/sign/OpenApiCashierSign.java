package com.xspaceagi.pay.sdk.sign;

import com.xspaceagi.pay.sdk.dto.CashierSessionCreateRequest;

/** 收银台 Open API 路径常量；{@link #PATH_SESSION} 需 HMAC 验签，其余为免签路径。 */
public final class OpenApiCashierSign {

    public static final String BASE = "/open-api/cashier";
    public static final String PATH_SESSION = BASE + "/session";
    public static final String PATH_SESSION_BOOTSTRAP = BASE + "/session/bootstrap";
    public static final String PATH_SCAN_INIT = BASE + "/scan/init";
    public static final String PATH_SCAN_STATUS = BASE + "/scan/status";

    private OpenApiCashierSign() {}

    public static byte[] signCreateSession(CashierSessionCreateRequest request, String clientSecret) {
        return OpenApiSignSupport.sign(PATH_SESSION, request, clientSecret);
    }
}
