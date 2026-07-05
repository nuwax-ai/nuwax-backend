package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayClientScene;
import lombok.Data;

/** 对已有 minipay 支付单调渠道下单，返回调起支付参数。 */
@Data
public class MiniPayTransactionRpcCreateRequest {
    private String bizOrderNo;
    private PayChannel payChannel;
    /** WxPay minipay：小程序或公众号 AppID（sub_appid） */
    private String subAppid;
    /** WxPay minipay：用户 OpenID */
    private String openId;
    /** AliPay minipay：用户 buyer_id */
    private String buyerId;
    private String clientIp;
    /** minipay 调起场景：小程序 / 微信JSAPI，写入 pay_order.ext */
    private PayClientScene payClientScene;
}
