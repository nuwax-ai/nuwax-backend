package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 开放支付 - H5 调起请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenPayH5PayRequest {

    /** 接入应用 ID（必传） */
    private String appId;

    /** 应用类型（可选）：传了按 (类型, 业务ID) 查 user_project；不传按 dbId 查 */
    private String appType;

    /** 下单返回的 orderNo */
    private String orderNo;

    /** 支付渠道：WxPay / AliPay */
    private String payChannel;

    /** 支付完成后浏览器回跳地址（三方页面，http(s):// 开头） */
    private String frontNotifyUrl;

    /** 最终用户真实 IP（可选）：三方后端转发场景从其前端请求解析后透传；缺省取请求来源 IP（网关 H5 下单要求用户 IP） */
    private String clientIp;

}
