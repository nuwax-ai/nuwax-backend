package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用项目支付 - 收银台模式响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneralProjectCashierResponse {

    /** 业务订单号（GP1_ 或 GP2_ 前缀） */
    private String orderNo;

    /** 网关支付订单号 */
    private String gatewayOrderNo;

    /** 收银台跳转地址（浏览器直接跳转） */
    private String cashierUrl;
}
