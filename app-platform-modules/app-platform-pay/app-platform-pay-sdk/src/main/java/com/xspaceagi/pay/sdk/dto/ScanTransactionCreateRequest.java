package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import lombok.Data;

@Data
public class ScanTransactionCreateRequest {
    private String clientId;

    private Long timestamp;

    private String nonce;

    private String signature;

    private String gatewayPaymentOrderNo;

    private PayChannel payChannel;

    private String clientIp;
}
