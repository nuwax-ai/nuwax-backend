package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayMode;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ScanOrderStatusQueryResponse {
    private String gatewayPaymentOrderNo;
    private String gatewayPaymentTxNo;
    private PaymentStatus status;
    private PaymentStatus transactionStatus;
    private PayChannel payChannel;
    private PayMode payMode;
    private Long orderAmount;
    private Long providerFee;
    private Long platformFee;
    private Long netAmount;
    private String qrCodeContent;
    private String paidAt;
}
