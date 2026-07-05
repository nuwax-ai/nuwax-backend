package com.xspaceagi.bill.app.service;

import com.xspaceagi.bill.sdk.dto.AppNativeInvokeRequest;
import com.xspaceagi.bill.sdk.dto.CreateOrderRequest;
import com.xspaceagi.bill.sdk.dto.H5WebInvokeRequest;
import com.xspaceagi.bill.sdk.dto.MiniPayInvokeRequest;
import com.xspaceagi.bill.sdk.dto.OrderDTO;
import com.xspaceagi.bill.sdk.dto.OrderPageDTO;
import com.xspaceagi.bill.sdk.dto.OrderQueryRequest;
import com.xspaceagi.bill.sdk.dto.OrderSettlementStatusResponse;
import com.xspaceagi.bill.sdk.dto.WeChatJsapiInvokeRequest;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateResponse;
import com.xspaceagi.pay.sdk.dto.OrderAndTransactionCreateResponse;

public interface BillOrderAppService {

    OrderDTO createOrder(CreateOrderRequest request);

    /** 对已有 Bill 订单调起小程序支付，返回 wx.requestPayment / my.tradePay 参数。 */
    OrderAndTransactionCreateResponse invokeMiniPay(long userId, MiniPayInvokeRequest request, String clientIp);

    /** 微信内 H5 JSAPI 调起支付，返回 WeixinJSBridge 所需 wxPayParams。 */
    OrderAndTransactionCreateResponse invokeWeChatJsapi(long userId, WeChatJsapiInvokeRequest request, String clientIp);

    /** 系统浏览器 H5 调起支付，返回 invokeType + formHtml/redirectUrl。 */
    OrderAndTransactionCreateResponse invokeH5Web(long userId, H5WebInvokeRequest request, String clientIp);

    /** App 原生 SDK 调起支付，返回 wxPayParams / redirectUrl。 */
    OrderAndTransactionCreateResponse invokeAppNative(long userId, AppNativeInvokeRequest request, String clientIp);

    /** 扫码/H5 收银台调起支付。 */
    CashierSessionCreateResponse invokeScanCashier(long userId, Long orderId, String returnUrl);

    boolean paymentCallback(Long tenantId, Long orderId, String payStatus);

    OrderPageDTO queryOrders(OrderQueryRequest query);

    OrderDTO queryOrder(Long orderId);

    /** 支付中间结算页轮询：当前用户须为订单所属用户。 */
    OrderSettlementStatusResponse getOrderSettlementStatus(long userId, Long orderId);
}
