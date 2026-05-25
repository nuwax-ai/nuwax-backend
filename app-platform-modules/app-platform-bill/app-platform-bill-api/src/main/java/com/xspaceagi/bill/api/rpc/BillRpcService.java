package com.xspaceagi.bill.api.rpc;

import com.xspaceagi.bill.app.service.BillOrderAppService;
import com.xspaceagi.bill.app.service.BillRevenueAppService;
import com.xspaceagi.bill.sdk.dto.AddRevenueRequest;
import com.xspaceagi.bill.sdk.dto.CreateOrderRequest;
import com.xspaceagi.bill.sdk.dto.OrderDTO;
import com.xspaceagi.bill.sdk.rpc.IBillRpcService;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

@Slf4j
@Service
public class BillRpcService implements IBillRpcService {

    @Resource
    private BillOrderAppService billOrderAppService;

    @Resource
    private BillRevenueAppService billRevenueAppService;

    @Override
    public OrderDTO createOrder(CreateOrderRequest request) {
        Assert.notNull(request, "Request cannot be null");
        Assert.notNull(request.getTenantId(), "Tenant ID cannot be empty");
        Assert.notNull(request.getUserId(), "User ID cannot be empty");
        if (RequestContext.get() == null) {
            RequestContext.setThreadTenantId(request.getTenantId());
            try {
                return billOrderAppService.createOrder(request);
            } finally {
                RequestContext.remove();
            }
        }
        return billOrderAppService.createOrder(request);
    }

    @Override
    public void paymentCallback(Long tenantId, Long orderId, String payStatus) {
        Assert.notNull(orderId, "Order ID cannot be empty");
        Assert.notNull(payStatus, "Pay status cannot be empty");
        if (RequestContext.get() == null) {
            RequestContext.setThreadTenantId(tenantId);
            try {
                billOrderAppService.paymentCallback(tenantId, orderId, payStatus);
                return;
            } finally {
                RequestContext.remove();
            }
        }
        billOrderAppService.paymentCallback(tenantId, orderId, payStatus);
    }

    @Override
    public boolean addRevenue(AddRevenueRequest request) {
        Assert.notNull(request, "Request cannot be null");
        Assert.notNull(request.getTenantId(), "Tenant ID cannot be empty");
        Assert.notNull(request.getUserId(), "User ID cannot be empty");
        Assert.notNull(request.getBizNo(), "BizNo cannot be empty");
        if (RequestContext.get() == null) {
            RequestContext.setThreadTenantId(request.getTenantId());
            try {
                return billRevenueAppService.addRevenue(request);
            } finally {
                RequestContext.remove();
            }
        }
        return billRevenueAppService.addRevenue(request);
    }
}
