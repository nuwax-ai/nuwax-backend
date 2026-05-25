package com.xspaceagi.pay.spec.exception;

import com.xspaceagi.pay.spec.PayGatewayMessages;

/**
 * 调用 nuwax-pay 支付网关失败。若响应体为 {@code ApiResponse} JSON，{@link #gatewayCode} 为网关 {@code code}，
 * {@link #getMessage()} 为网关 {@code message}；无 {@code gatewayCode} 且为传输类失败时为 {@link PayGatewayMessages#CONNECT_FAILED}。
 */
public class PayGatewayClientException extends RuntimeException {

    private final String gatewayCode;

    /** 传输/协议类失败，对外固定文案。 */
    public PayGatewayClientException() {
        this(null, PayGatewayMessages.CONNECT_FAILED);
    }

    public PayGatewayClientException(String message) {
        this(null, message);
    }

    public PayGatewayClientException(String gatewayCode, String gatewayMessage) {
        super(gatewayMessage != null && !gatewayMessage.isBlank() ? gatewayMessage : PayGatewayMessages.CONNECT_FAILED);
        this.gatewayCode = gatewayCode;
    }

    public String getGatewayCode() {
        return gatewayCode;
    }

    public String getGatewayMessage() {
        return getMessage();
    }

    public boolean hasGatewayApiError() {
        return gatewayCode != null && !gatewayCode.isBlank();
    }
}
