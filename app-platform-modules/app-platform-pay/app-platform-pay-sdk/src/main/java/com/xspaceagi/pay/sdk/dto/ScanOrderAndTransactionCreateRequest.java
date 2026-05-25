package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import lombok.Data;

@Data
public class ScanOrderAndTransactionCreateRequest {
    private String clientId;

    private Long timestamp;

    private String nonce;

    private String signature;

    private String bizOrderNo;

    private PayChannel payChannel;

    private Long orderAmount;

    private String subject;

    private String ext;
}
