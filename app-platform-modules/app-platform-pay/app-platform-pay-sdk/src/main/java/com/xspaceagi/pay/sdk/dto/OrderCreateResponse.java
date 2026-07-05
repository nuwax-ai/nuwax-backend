package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayMode;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class OrderCreateResponse {
    private String gatewayPaymentOrderNo;
    private String bizOrderNo;
    private PaymentStatus status;
    private PayMode payMode;
}
