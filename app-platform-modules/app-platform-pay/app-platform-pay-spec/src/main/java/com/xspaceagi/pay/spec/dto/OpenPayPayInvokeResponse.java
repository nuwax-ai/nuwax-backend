package com.xspaceagi.pay.spec.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import com.xspaceagi.pay.sdk.dto.WxPayInvokeParams;
import lombok.NoArgsConstructor;

/**
 * 开放支付 - 调起响应（H5/App 共用，字段按渠道可空）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OpenPayPayInvokeResponse {

    private String orderNo;

    private String gatewayOrderNo;

    /** WxPay / AliPay */
    private String payChannel;

    /** FORM_HTML / REDIRECT_URL / QRCODE_FALLBACK */
    private String invokeType;

    /** H5 微信返回自动提交表单（invokeType=FORM_HTML 时写入页面） */
    private String formHtml;

    /** 跳转地址：H5 为渠道链接；App 微信（当前渠道）为 weixin:// 小程序拉起串，支付宝为 qr.alipay.com/alipays:// */
    private String redirectUrl;

    /** 其他微信渠道的 prepay 参数（当前安心付渠道为 null） */
    private WxPayInvokeParams wxPayParams;

    /** 支付宝 QRCODE_FALLBACK 时的二维码内容 */
    private String qrCodeContent;

    /** 部分支付宝 SDK 使用的交易号，可空 */
    private String alipayTradeNo;

    /** 调起后订单状态，一般 PENDING */
    private String status;

}
