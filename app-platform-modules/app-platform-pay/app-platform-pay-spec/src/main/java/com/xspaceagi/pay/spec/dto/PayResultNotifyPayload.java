package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 支付结果通知载荷：随 {@link com.xspaceagi.pay.spec.event.PayOrderSettlementNotifyEvent} 在系统内投递。 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PayResultNotifyPayload {

    private Long tenantId;

    /** 业务场景标识（平台侧落库字段，与 Open API 建单请求无直接对应） */
    private String bizScene;

    private String bizOrderNo;

    private String gatewayPaymentOrderNo;

    /** {@link com.xspaceagi.pay.spec.enums.PaymentOrderStatus} 名，超时时可能为 null */
    private String paymentOrderStatus;

    private Long orderAmount;

    private String paidAt;
}
