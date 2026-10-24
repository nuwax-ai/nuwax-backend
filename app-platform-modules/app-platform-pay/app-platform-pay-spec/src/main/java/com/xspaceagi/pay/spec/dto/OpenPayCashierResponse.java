package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 开放支付 - 收银台下单响应
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenPayCashierResponse {

    /** 平台业务订单号（OPEN_ 前缀） */
    private String orderNo;

    /** 网关支付订单号（查单/幂等键） */
    private String gatewayOrderNo;

    /** 收银台地址：三方前端打开（PC 跳转/新窗口，移动端浏览器直接跳转） */
    private String cashierUrl;

}
