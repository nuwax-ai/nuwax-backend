package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用项目支付 - H5 第二步：调起渠道支付请求。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneralProjectH5PayRequest {

    /** 业务订单号（第一步返回的 orderNo） */
    private String orderNo;

    /** 支付渠道：WxPay / AliPay */
    private String payChannel;

    /** 支付完成后前端回跳地址 */
    private String frontNotifyUrl;
}
