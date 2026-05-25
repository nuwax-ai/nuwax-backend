package com.xspaceagi.pay.sdk;

/** Open API 统一 4 位业务码 */
public final class OpenApiCodes {

    private OpenApiCodes() {
    }

    public static final String SUCCESS = "0000";
    public static final String BAD_REQUEST = "0400";
    public static final String UNAUTHORIZED = "0401";
    public static final String FORBIDDEN = "0403";
    public static final String INTERNAL_ERROR = "0500";
    public static final String BUSINESS_ERROR = "9999";

    public static String fromHttpStatus(int httpStatus) {
        if (httpStatus < 0 || httpStatus > 9999) {
            return INTERNAL_ERROR;
        }
        return String.format("%04d", httpStatus);
    }
}
