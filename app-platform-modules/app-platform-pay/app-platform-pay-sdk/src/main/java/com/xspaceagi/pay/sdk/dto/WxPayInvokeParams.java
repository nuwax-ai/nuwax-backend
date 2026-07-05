package com.xspaceagi.pay.sdk.dto;

import lombok.Builder;
import lombok.Data;

/** 微信小程序 {@code wx.requestPayment} 所需字段（来自渠道 payparam JSON）。 */
@Data
@Builder
public class WxPayInvokeParams {
    private String appId;
    private String timeStamp;
    private String nonceStr;
    /** 对应微信字段 package，值为 prepay_id=... */
    private String packageValue;
    private String signType;
    private String paySign;
}
