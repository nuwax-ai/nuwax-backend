package com.xspaceagi.pay.sdk.dto;

import lombok.Data;

/** 创建 App 原生支付单（仅落网关支付单，不调渠道）。 */
@Data
public class AppOrderRpcCreateRequest {
    private String bizOrderNo;
    private Long orderAmount;
    private String subject;
    private String ext;
}
