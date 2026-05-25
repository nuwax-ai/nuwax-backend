package com.xspaceagi.pay.sdk.dto;

import lombok.Data;

/** 业务服务端调用：验签通过后创建收银台会话并返回跳转 URL */
@Data
public class CashierSessionCreateRequest {
    private String clientId;

    private Long timestamp;

    private String nonce;

    private String signature;

    private String gatewayPaymentOrderNo;

    private Long orderAmount;

    private String subject;

    /** 支付成功回跳，可空 */
    private String bizRedirectUrl;
}
