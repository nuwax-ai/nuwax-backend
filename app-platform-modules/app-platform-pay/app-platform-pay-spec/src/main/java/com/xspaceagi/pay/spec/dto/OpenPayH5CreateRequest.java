package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 开放支付 - H5 下单请求（移动端浏览器）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenPayH5CreateRequest {

    /** 接入应用 ID（必传） */
    private String appId;

    /** 应用类型（可选）：传了按 (类型, 业务ID) 查 user_project；不传按 dbId 查 */
    private String appType;

    /** 商户订单号（必传，幂等键） */
    private String outTradeNo;

    /** 订单金额（单位：分） */
    private Long orderAmount;

    /** 订单标题 */
    private String subject;

    /** 支付结果 webhook 通知地址，可空 */
    private String notifyUrl;

}
