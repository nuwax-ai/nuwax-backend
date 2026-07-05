package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayClientScene;
import lombok.Data;

@Data
public class AppOrderAndTransactionCreateRequest {
    private String clientId;
    private Long timestamp;
    private String nonce;
    private String signature;
    private String bizOrderNo;
    private PayChannel payChannel;
    private Long orderAmount;
    private String subject;
    private String ext;
    private String clientIp;
    private PayClientScene payClientScene;
}
