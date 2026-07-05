package com.xspaceagi.pay.spec.dto;

import com.xspaceagi.pay.sdk.dto.WxPayInvokeParams;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 通用项目支付 - App 原生第二步：调起渠道支付响应。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GeneralProjectAppPayResponse {

    private String orderNo;

    private String gatewayOrderNo;

    /** WxPay | AliPay */
    private String payChannel;

    /** 微信：其他渠道 prepay JSON（当前安心付渠道为 null）；支付宝：不使用 */
    private WxPayInvokeParams wxPayParams;

    /**
     * 微信：weixin://dl/business/...
     * 支付宝：https://qr.alipay.com/... 或 alipays://
     */
    private String redirectUrl;

    /** 支付宝 tradeNO（部分 SDK 使用） */
    private String alipayTradeNo;

    /** REDIRECT_URL | QRCODE_FALLBACK */
    private String invokeType;

    /** 支付宝 invokeType=QRCODE_FALLBACK 时的二维码内容 */
    private String qrCodeContent;

    private String status;
}
