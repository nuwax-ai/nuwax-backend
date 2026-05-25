package com.xspaceagi.pay.sdk.dto;

import lombok.Data;

@Data
public class ScanOrderCreateRequest {
    private String clientId;

    private Long timestamp;

    private String nonce;

    private String signature;

    private String bizOrderNo;

    private Long orderAmount;

    private String subject;

    private String ext;
}
