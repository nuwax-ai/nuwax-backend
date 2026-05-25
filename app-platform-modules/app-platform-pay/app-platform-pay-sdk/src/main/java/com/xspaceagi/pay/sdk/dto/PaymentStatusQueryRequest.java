package com.xspaceagi.pay.sdk.dto;

import lombok.Data;

@Data
public class PaymentStatusQueryRequest {
    private String clientId;

    private Long timestamp;

    private String nonce;

    private String signature;

    private String gatewayPaymentOrderNo;

    /**
     * 为 true 时向支付渠道发起交易查询（如安心付 /query），成功则落库并调用通知状态更新（/update/notify_status）。
     * 默认 false，仅读本地库。
     */
    private Boolean syncFromChannel;
}
