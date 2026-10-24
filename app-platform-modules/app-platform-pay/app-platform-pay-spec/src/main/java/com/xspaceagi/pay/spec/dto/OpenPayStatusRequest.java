package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 开放支付 - 查单请求（gatewayOrderNo 与 outTradeNo 二选一）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenPayStatusRequest {

    /** 接入应用 ID（必传，归属校验） */
    private String appId;

    /** 应用类型（可选）：传了按 (类型, 业务ID) 查 user_project；不传按 dbId 查 */
    private String appType;

    /** 网关支付订单号（与 outTradeNo 二选一） */
    private String gatewayOrderNo;

    /** 商户订单号（与 gatewayOrderNo 二选一） */
    private String outTradeNo;

}
