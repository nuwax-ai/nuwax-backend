package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用项目支付 - H5 第一步：创建订单响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneralProjectH5OrderCreateResponse {

    /** 业务订单号（GP1_ 或 GP2_ 前缀） */
    private String orderNo;

    /** 网关支付订单号 */
    private String gatewayOrderNo;
}
