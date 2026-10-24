package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 开放支付 - 下单响应（H5/App 第一步）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenPayOrderCreateResponse {

    /** 平台业务订单号（OPEN_ 前缀） */
    private String orderNo;

    /** 网关支付订单号（查单/幂等键） */
    private String gatewayOrderNo;

}
