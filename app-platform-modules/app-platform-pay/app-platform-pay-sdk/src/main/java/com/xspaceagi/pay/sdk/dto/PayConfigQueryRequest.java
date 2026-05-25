package com.xspaceagi.pay.sdk.dto;

import lombok.Data;

@Data
public class PayConfigQueryRequest {
    private String clientId;

    /** 毫秒级 Unix 时间戳 */
    private Long timestamp;

    private String nonce;

    private String signature;
}
