package com.xspaceagi.pay.application.service.impl;

import com.xspaceagi.pay.application.service.PayOrderApplicationService;
import com.xspaceagi.pay.application.support.AmountUtils;
import com.xspaceagi.pay.application.support.PayShanghaiDates;
import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.domain.model.PayOrderPageSlice;
import com.xspaceagi.pay.domain.repository.PayOrderRepository;
import com.xspaceagi.pay.spec.dto.PageResult;
import com.xspaceagi.pay.spec.dto.PayOrderItemResponse;
import com.xspaceagi.pay.spec.dto.PayOrderPageQueryRequest;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PayOrderApplicationServiceImpl implements PayOrderApplicationService {

    @Resource
    private PayOrderRepository payOrderRepository;

    @Override
    public PageResult<PayOrderItemResponse> pagePayOrders(PayOrderPageQueryRequest request) {
        long tenantId = resolveTenantId();
        int page = request.getPage() == null || request.getPage() < 1 ? 1 : request.getPage();
        int pageSize = request.getPageSize() == null || request.getPageSize() < 1 ? 20 : request.getPageSize();
        if (pageSize > 200) {
            pageSize = 200;
        }
        String statusFilter =
                request.getPaymentStatus() != null ? request.getPaymentStatus().name() : null;
        String bizOrderNo =
                StringUtils.hasText(request.getBizOrderNo()) ? request.getBizOrderNo().trim() : null;
        String gatewayPaymentOrderNo =
                StringUtils.hasText(request.getGatewayPaymentOrderNo())
                        ? request.getGatewayPaymentOrderNo().trim()
                        : null;
        PayOrderPageSlice slice =
                payOrderRepository.pageByTenantAndFilters(
                        tenantId,
                        PayShanghaiDates.fromWallShanghai(request.getCreatedStart()),
                        PayShanghaiDates.fromWallShanghai(request.getCreatedEnd()),
                        statusFilter,
                        request.getPayChannel(),
                        bizOrderNo,
                        gatewayPaymentOrderNo,
                        page,
                        pageSize);
        List<PayOrderItemResponse> records = slice.records().stream().map(this::toResponse).toList();
        return PageResult.<PayOrderItemResponse>builder()
                .records(records)
                .total(slice.total())
                .page(page)
                .pageSize(pageSize)
                .build();
    }

    private PayOrderItemResponse toResponse(PayOrderModel m) {
        PaymentStatus paymentStatus = parsePaymentStatus(m.getGatewayOrderStatus());
        return PayOrderItemResponse.builder()
                .id(m.getId())
                .tenantId(m.getTenantId())
                .bizOrderNo(m.getBizOrderNo())
                .bizScene(m.getBizScene())
                .orderAmount(AmountUtils.fenToYuan(m.getOrderAmount()))
                .subject(m.getSubject())
                .payMode(m.getPayMode())
                .payChannel(m.getPayChannel())
                .platformFee(AmountUtils.fenToYuan(m.getPlatformFee()))
                .providerFee(AmountUtils.fenToYuan(m.getProviderFee()))
                .netAmount(AmountUtils.fenToYuan(m.getNetAmount()))
                .gatewayPaymentOrderNo(m.getGatewayPaymentOrderNo())
                .gatewaySyncStatus(m.getGatewaySyncStatus())
                .bizNotifyStatus(m.getBizNotifyStatus())
                .gatewayOrderStatus(m.getGatewayOrderStatus())
                .paymentStatus(paymentStatus)
                .paidAt(m.getPaidAt())
                .created(m.getCreated())
                .modified(m.getModified())
                .build();
    }

    private static PaymentStatus parsePaymentStatus(String gatewayOrderStatus) {
        if (gatewayOrderStatus == null || gatewayOrderStatus.isEmpty()) {
            return null;
        }
        try {
            return PaymentStatus.valueOf(gatewayOrderStatus);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long resolveTenantId() {
        Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
        if (tenantId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGetTenantFailed);
        }
        return tenantId;
    }
}
