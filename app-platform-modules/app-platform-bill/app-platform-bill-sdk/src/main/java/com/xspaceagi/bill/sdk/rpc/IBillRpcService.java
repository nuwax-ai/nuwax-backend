package com.xspaceagi.bill.sdk.rpc;

import com.xspaceagi.bill.sdk.dto.AddRevenueRequest;
import com.xspaceagi.bill.sdk.dto.CreateOrderRequest;
import com.xspaceagi.bill.sdk.dto.OrderDTO;

public interface IBillRpcService {

    /**
     * 创建订单
     */
    OrderDTO createOrder(CreateOrderRequest request);

    /**
     * 支付完成回调
     */
    void paymentCallback(Long tenantId, Long orderId, String payStatus);

    /**
     * 增加收益（幂等，相同bizNo不会重复记录）
     */
    boolean addRevenue(AddRevenueRequest request);
}
