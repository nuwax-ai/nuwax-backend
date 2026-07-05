package com.xspaceagi.pay.sdk.service;

import com.xspaceagi.pay.sdk.dto.AppOrderRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.AppTransactionRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateRequest;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateResponse;
import com.xspaceagi.pay.sdk.dto.H5OrderRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.H5TransactionRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.MiniPayOrderRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.MiniPayTransactionRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.OrderAndTransactionCreateResponse;
import com.xspaceagi.pay.sdk.dto.OrderCreateResponse;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryRequest;
import com.xspaceagi.pay.sdk.dto.ScanOrderCreateRequest;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryResponse;

/** 平台内支付 RPC 契约（由 pay-application 实现，供 bill 等外部模块依赖调用）。 */
public interface IPaymentRpcService {

    /** 创建扫码支付订单 */
    OrderCreateResponse createOrderForScan(ScanOrderCreateRequest request);

    /** 创建小程序支付单（仅落网关支付单，不调渠道；调起支付见 {@link #createMiniPayTransaction}） */
    OrderCreateResponse createOrderForMiniPay(MiniPayOrderRpcCreateRequest request);

    /** 创建 H5支付单（仅落网关支付单，不调渠道；调起支付见 {@link #createH5Transaction}） */
    OrderCreateResponse createOrderForH5(H5OrderRpcCreateRequest request);

    /** 创建 App 原生支付单（仅落网关支付单，不调渠道；调起支付见 {@link #createAppTransaction}） */
    OrderCreateResponse createOrderForApp(AppOrderRpcCreateRequest request);

    /** 对已有小程序支付单调渠道下单，返回 wx.requestPayment / my.tradePay 参数 */
    OrderAndTransactionCreateResponse createMiniPayTransaction(MiniPayTransactionRpcCreateRequest request);

    /** 对已有 h5 支付单调渠道下单，返回 formHtml / redirectUrl / qrcode 兜底 */
    OrderAndTransactionCreateResponse createH5Transaction(H5TransactionRpcCreateRequest request);

    /** 对已有 App 支付单调渠道下单，返回 wxPayParams / redirectUrl */
    OrderAndTransactionCreateResponse createAppTransaction(AppTransactionRpcCreateRequest request);

    /**
     * 获取收银台地址；会话有过期时间，过期后需重新调用。
     */
    CashierSessionCreateResponse createCashierSession(CashierSessionCreateRequest request);

    /** 查询支付状态（仅更新 pay_order，不通知 Bill） */
    PaymentStatusQueryResponse queryStatus(PaymentStatusQueryRequest request);

    /**
     * 结算页主动同步：按业务单号查网关终态并通知 Bill（与支付轮询任务单 tick 一致，幂等）。
     *
     * @param bizOrderNo 业务订单号，通常为 Bill 订单 id 字符串
     * @return 是否已通知到 Bill 终态或此前已完成通知
     */
    boolean syncSettlementForBizOrderNo(String bizOrderNo);
}
