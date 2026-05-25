package com.xspaceagi.pay.sdk.service;

import com.xspaceagi.pay.sdk.dto.CashierSessionCreateRequest;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateResponse;
import com.xspaceagi.pay.sdk.dto.PaymentOrderCreateResponse;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryRequest;
import com.xspaceagi.pay.sdk.dto.ScanOrderCreateRequest;
import com.xspaceagi.pay.sdk.dto.ScanOrderStatusQueryResponse;

/** 平台内支付 RPC 契约（由 pay-application 实现，供 bill 等外部模块依赖调用）。 */
public interface IPaymentRpcService {

    /** 创建扫码支付订单 */
    PaymentOrderCreateResponse createOrderForScan(ScanOrderCreateRequest request);

    /**
     * 获取收银台地址；会话有过期时间，过期后需重新调用。
     */
    CashierSessionCreateResponse createCashierSession(CashierSessionCreateRequest request);

    /** 查询支付状态（仅更新 pay_order，不通知 Bill） */
    ScanOrderStatusQueryResponse queryStatus(PaymentStatusQueryRequest request);

    /**
     * 结算页主动同步：按业务单号查网关终态并通知 Bill（与支付轮询任务单 tick 一致，幂等）。
     *
     * @param bizOrderNo 业务订单号，通常为 Bill 订单 id 字符串
     * @return 是否已通知到 Bill 终态或此前已完成通知
     */
    boolean syncSettlementForBizOrderNo(String bizOrderNo);
}
