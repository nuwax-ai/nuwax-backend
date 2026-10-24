package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 开放支付 - 查单响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenPayStatusResponse {

    private String orderNo;

    private String gatewayOrderNo;

    /** INIT / PENDING / PAID / FAILED / CLOSED */
    private String status;

    /** WxPay / AliPay（未支付为 null） */
    private String payChannel;

    /** scan / h5 / app / minipay */
    private String payMode;

    /** 订单金额（单位：分） */
    private Long orderAmount;

    /** 支付完成时间（ISO，未支付为 null） */
    private String paidAt;

}
