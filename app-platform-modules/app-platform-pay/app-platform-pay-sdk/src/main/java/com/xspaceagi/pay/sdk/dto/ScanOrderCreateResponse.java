package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayMode;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ScanOrderCreateResponse {
    private String gatewayPaymentOrderNo;
    private String gatewayPaymentTxNo;
    /** 渠道服务费，单位分 */
    private Long providerFee;
    /** 平台服务费，单位分 */
    private Long platformFee;
    /** 净额，单位分（订单金额扣减手续费后） */
    private Long netAmount;
    private PaymentStatus status;
    private PayChannel payChannel;
    private PayMode payMode;
    private String qrCodeContent;
    private String expiredAt;
}
