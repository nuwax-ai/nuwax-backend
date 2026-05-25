package com.xspaceagi.pay.spec.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDateTime;
import lombok.Data;

@Data
public class PayOrderPageQueryRequest {

    /** 创建时间起始（含），可选 */
    private LocalDateTime createdStart;

    /** 创建时间结束（含），可选 */
    private LocalDateTime createdEnd;

    /** 支付状态 */
    private PaymentStatus paymentStatus;

    /** 支付渠道，可选 */
    private PayChannel payChannel;

    /** 业务订单号，可选 */
    private String bizOrderNo;

    /** 网关支付单号，可选 */
    private String gatewayPaymentOrderNo;

    @Min(1)
    private Integer page;

    @Min(1)
    @Max(200)
    private Integer pageSize;
}
