package com.xspaceagi.pay.sdk.dto;

import lombok.Data;

@Data
public class ByClientIdRequest {
    private String clientId;

    private Long timestamp;

    private String nonce;

    private String signature;
}
