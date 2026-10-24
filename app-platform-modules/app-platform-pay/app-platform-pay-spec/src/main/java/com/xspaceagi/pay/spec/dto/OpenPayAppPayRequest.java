package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 开放支付 - App 原生调起请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenPayAppPayRequest {

    /** 接入应用 ID（必传） */
    private String appId;

    /** 应用类型（可选）：传了按 (类型, 业务ID) 查 user_project；不传按 dbId 查 */
    private String appType;

    /** 下单返回的 orderNo */
    private String orderNo;

    /** 支付渠道：WxPay / AliPay */
    private String payChannel;

}
