package com.xspaceagi.pay.application.service;

import com.xspaceagi.pay.spec.dto.PageResult;
import com.xspaceagi.pay.spec.dto.PayOrderItemResponse;
import com.xspaceagi.pay.spec.dto.PayOrderPageQueryRequest;

public interface PayOrderApplicationService {

    PageResult<PayOrderItemResponse> pagePayOrders(PayOrderPageQueryRequest request);
}
