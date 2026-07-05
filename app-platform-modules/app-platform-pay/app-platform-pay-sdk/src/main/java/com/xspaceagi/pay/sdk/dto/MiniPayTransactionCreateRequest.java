package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayClientScene;
import lombok.Data;

@Data
public class MiniPayTransactionCreateRequest {
    private String clientId;
    private Long timestamp;
    private String nonce;
    private String signature;
    private String gatewayPaymentOrderNo;
    private PayChannel payChannel;
    /** WxPay minipay：channel_params.sub_appid */
    private String subAppid;
    /** WxPay minipay：channel_params.open_id */
    private String openId;
    /** AliPay minipay：channel_params.buyer_id */
    private String buyerId;
    private String clientIp;
    /** 调起场景 */
    private PayClientScene payClientScene;
}
