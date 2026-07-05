package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用项目支付 - 查询支付状态响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneralProjectPaymentStatusResponse {

    /** 支付状态：INIT / PENDING / PAID / FAILED / CLOSED */
    private String status;

    /** 支付渠道：WxPay / AliPay / UnionPay */
    private String payChannel;

    /** 支付模式：scan / h5 / minipay */
    private String payMode;

    /** 订单金额（单位：分） */
    private Long orderAmount;

    /** 支付完成时间 */
    private String paidAt;
}
