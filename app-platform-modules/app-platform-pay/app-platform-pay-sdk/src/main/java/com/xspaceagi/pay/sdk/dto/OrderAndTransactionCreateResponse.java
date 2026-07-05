package com.xspaceagi.pay.sdk.dto;

import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayInvokeType;
import com.xspaceagi.pay.sdk.enums.PayMode;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import lombok.Builder;
import lombok.Data;

/** 创建支付单/渠道下单后的统一响应 */
@Data
@Builder
public class OrderAndTransactionCreateResponse {
    private String gatewayPaymentOrderNo;
    private String gatewayPaymentTxNo;
    /** 渠道服务费，单位分 */
    private Long providerFee;
    /** 平台服务费，单位分 */
    private Long platformFee;
    /** 净额，单位分（订单金额扣减手续费后） */
    private Long netAmount;
    private PaymentStatus status;
    private PayChannel payChannel;
    private PayMode payMode;
    private PayInvokeType invokeType;
    /** 主扫 scan：渠道返回的二维码/链接原文（payparam） */
    private String qrCodeContent;
    private String formHtml;
    private String redirectUrl;
    /** minipay + WxPay：wx.requestPayment 参数 */
    private WxPayInvokeParams wxPayParams;
    /** minipay + AliPay：tradeNO，用于 my.tradePay */
    private String alipayTradeNo;
    private String expiredAt;
}
