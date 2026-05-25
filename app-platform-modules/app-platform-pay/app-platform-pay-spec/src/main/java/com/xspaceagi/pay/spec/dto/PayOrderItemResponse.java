package com.xspaceagi.pay.spec.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayMode;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import com.xspaceagi.pay.spec.enums.PayBizNotifyStatus;
import com.xspaceagi.pay.spec.enums.PayOrderGatewaySyncStatus;
import java.math.BigDecimal;
import java.util.Date;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayOrderItemResponse {

    private Long id;
    private Long tenantId;
    private String bizOrderNo;
    private String bizScene;
    /** 订单金额（元） */
    private BigDecimal orderAmount;
    private String subject;
    private PayMode payMode;
    private PayChannel payChannel;
    /** 平台服务费（元） */
    private BigDecimal platformFee;
    /** 渠道手续费（元） */
    private BigDecimal providerFee;
    /** 实收金额（元） */
    private BigDecimal netAmount;
    private String gatewayPaymentOrderNo;
    private PayOrderGatewaySyncStatus gatewaySyncStatus;
    private PayBizNotifyStatus bizNotifyStatus;
    private String gatewayOrderStatus;
    private PaymentStatus paymentStatus;
    private Date paidAt;
    private Date created;
    private Date modified;
}
