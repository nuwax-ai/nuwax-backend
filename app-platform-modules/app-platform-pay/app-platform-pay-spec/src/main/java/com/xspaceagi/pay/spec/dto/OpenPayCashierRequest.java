package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 开放支付 - 收银台下单请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenPayCashierRequest {

    /** 接入应用 ID（必传）：appType=UserApp 时传 user_app.id（业务ID）；否则传 user_project.dbId */
    private String appId;

    /** 应用类型（可选）：传了按 (类型, 业务ID) 查 user_project；不传按 dbId 查。UserApp/NormalProject/ThirdApp 等 */
    private String appType;

    /** 商户订单号（必传，幂等键）：最终业务单号 OPEN_{appId}_{outTradeNo}；仅限字母数字-_，长度 1-64 */
    private String outTradeNo;

    /** 订单金额（单位：分） */
    private Long orderAmount;

    /** 订单标题/商品描述 */
    private String subject;

    /** 支付完成后浏览器回跳地址，可空（三方页面） */
    private String frontNotifyUrl;

    /** 支付结果 webhook 通知地址，可空；传了在支付成功后平台服务端回调（带签名，有限重试） */
    private String notifyUrl;

}
