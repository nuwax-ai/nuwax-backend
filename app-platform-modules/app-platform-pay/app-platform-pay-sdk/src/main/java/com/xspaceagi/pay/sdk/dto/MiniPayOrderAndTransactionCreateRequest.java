package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import lombok.Data;

@Data
public class MiniPayOrderAndTransactionCreateRequest {
    private String clientId;
    private Long timestamp;
    private String nonce;
    private String signature;
    private String bizOrderNo;
    private PayChannel payChannel;
    private Long orderAmount;
    private String subject;
    /** 微信小程序 AppID（安心付 channel_params.sub_appid，WxPay 必填） */
    private String subAppid;
    /** 微信：用户 OpenID（安心付 channel_params.open_id） */
    private String openId;
    /** 支付宝小程序：用户 buyer_id（安心付 channel_params.buyer_id） */
    private String buyerId;
    private String ext;
}
