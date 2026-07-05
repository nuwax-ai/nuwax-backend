package com.xspaceagi.bill.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "小程序调起支付请求（微信内 H5 JSAPI 请使用 WeChatJsapiInvokeRequest）")
public class MiniPayInvokeRequest implements Serializable {

    @Schema(description = "Bill 订单 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long orderId;

    @Schema(description = "支付渠道：WxPay / AliPay", requiredMode = Schema.RequiredMode.REQUIRED)
    private PayChannel payChannel;

    @Schema(description = "WxPay：wx.login() 返回的临时 code（5 分钟有效，一次性）")
    private String wxLoginCode;

    @Schema(description = "AliPay：my.getAuthCode() 返回的授权码")
    private String alipayAuthCode;
}
