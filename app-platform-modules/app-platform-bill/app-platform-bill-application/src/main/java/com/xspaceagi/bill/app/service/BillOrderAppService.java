package com.xspaceagi.bill.app.service;

import com.xspaceagi.bill.sdk.dto.CreateOrderRequest;
import com.xspaceagi.bill.sdk.dto.OrderDTO;
import com.xspaceagi.bill.sdk.dto.OrderPageDTO;
import com.xspaceagi.bill.sdk.dto.OrderQueryRequest;
import com.xspaceagi.bill.sdk.dto.OrderSettlementStatusResponse;

public interface BillOrderAppService {

    OrderDTO createOrder(CreateOrderRequest request);

    boolean paymentCallback(Long tenantId, Long orderId, String payStatus);

    OrderPageDTO queryOrders(OrderQueryRequest query);

    OrderDTO queryOrder(Long orderId);

    /** 支付中间结算页轮询：当前用户须为订单所属用户。 */
    OrderSettlementStatusResponse getOrderSettlementStatus(long userId, Long orderId);
}
