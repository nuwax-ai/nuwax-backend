package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import lombok.Data;

@Data
public class H5TransactionCreateRequest {
    private String clientId;
    private Long timestamp;
    private String nonce;
    private String signature;
    private String gatewayPaymentOrderNo;
    private PayChannel payChannel;
    private String clientIp;
    private String frontNotifyUrl;
}
