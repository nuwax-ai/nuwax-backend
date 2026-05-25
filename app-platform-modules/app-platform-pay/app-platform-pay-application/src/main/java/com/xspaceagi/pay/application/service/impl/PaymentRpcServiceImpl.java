package com.xspaceagi.pay.application.service.impl;

import com.xspaceagi.bill.sdk.rpc.IBillRpcService;
import com.xspaceagi.pay.application.schedule.PayOrderPollRunner;
import com.xspaceagi.pay.application.support.PayGatewayOutboundExecutor;
import com.xspaceagi.pay.application.support.PayOrderGatewayQueryMerger;
import com.xspaceagi.pay.application.support.PayOrderGatewaySyncFailureSupport;
import com.xspaceagi.pay.application.support.PayOrderSettlementSyncService;
import com.xspaceagi.pay.spec.PayOrderScheduleConstants;
import com.xspaceagi.pay.domain.gateway.PaymentScanGateway;
import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.domain.repository.PayOrderRepository;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateRequest;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateResponse;
import com.xspaceagi.pay.sdk.dto.PaymentOrderCreateResponse;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryRequest;
import com.xspaceagi.pay.sdk.dto.ScanOrderCreateRequest;
import com.xspaceagi.pay.sdk.dto.ScanOrderStatusQueryResponse;
import com.xspaceagi.pay.sdk.service.IPaymentRpcService;
import com.xspaceagi.pay.spec.enums.PayBizNotifyStatus;
import com.xspaceagi.pay.sdk.enums.PayMode;
import com.xspaceagi.pay.spec.enums.PayOrderGatewaySyncStatus;
import com.xspaceagi.pay.spec.exception.PayGatewayClientException;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.pay.infra.gateway.utils.PayGatewayExceptionMapper;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.time.Instant;
import java.util.Date;

@Service
public class PaymentRpcServiceImpl implements IPaymentRpcService {

    @Resource
    private PayOrderRepository payOrderRepository;

    @Resource
    private PaymentScanGateway paymentScanGateway;

    @Resource
    private PayGatewayOutboundExecutor payGatewayOutboundExecutor;

    @Resource
    private PayOrderPollRunner payOrderPayPollRunner;

    @Resource
    private IBillRpcService billRpcService;

    @Resource
    private PayOrderSettlementSyncService payOrderSettlementSyncService;

    public PaymentOrderCreateResponse createOrderForScan(ScanOrderCreateRequest request) {
        Assert.hasText(request.getBizOrderNo(), "bizOrderNo cannot be left blank");
        Assert.notNull(request.getOrderAmount(), "orderAmount cannot be left blank");
        Assert.isTrue(request.getOrderAmount() > 0, "orderAmount must be greater than 0");
        Assert.hasText(request.getSubject(), "subject cannot be left blank");

        long tenantId = resolveTenantId();
        PayOrderModel existing =
                payOrderRepository.findByTenantIdAndBizOrderNo(tenantId, request.getBizOrderNo()).orElse(null);
        if (existing != null) {
            return resumeExistingOrder(existing);
        }

        PayOrderModel row = PayOrderModel.builder()
                .tenantId(tenantId)
                .bizOrderNo(request.getBizOrderNo())
                .bizScene(null)
                .orderAmount(request.getOrderAmount())
                .subject(request.getSubject())
                .ext(request.getExt())
                .payMode(PayMode.scan)
                .platformFee(0L)
                .providerFee(0L)
                .netAmount(0L)
                .gatewaySyncStatus(PayOrderGatewaySyncStatus.PENDING)
                .build();
        try {
            row = payOrderRepository.save(row);
        } catch (DuplicateKeyException e) {
            PayOrderModel race = payOrderRepository.findByTenantIdAndBizOrderNo(tenantId, request.getBizOrderNo()).orElse(null);
            if (race == null) {
                throw new BizException(ErrorCodeEnum.SYS_ERROR.getCode(), "创建订单冲突，请重试");
            }
            return reconcileAfterDuplicate(race);
        }
        return callGatewayAndFinish(row);
    }

    public CashierSessionCreateResponse createCashierSession(CashierSessionCreateRequest request) {
        Assert.notNull(request, "request cannot be null");
        Assert.hasText(request.getGatewayPaymentOrderNo(), "gatewayPaymentOrderNo cannot be left blank");

        long tenantId = resolveTenantId();
        PayOrderModel row;
        String gatewayNo = request.getGatewayPaymentOrderNo().trim();
        row = payOrderRepository.findByTenantIdAndGatewayPaymentOrderNo(tenantId, gatewayNo).orElse(null);
        if (row == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_order_not_found);
        }
        if (row.getGatewaySyncStatus() != PayOrderGatewaySyncStatus.SUCCESS) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "支付单尚未同步网关，无法打开收银台");
        }
        return payGatewayOutboundExecutor.invoke(
                tenantId,
                outbound -> paymentScanGateway.createCashierSession(
                        outbound,
                        gatewayNo,
                        row.getOrderAmount(),
                        row.getSubject(),
                        request.getBizRedirectUrl()));
    }

    public ScanOrderStatusQueryResponse queryStatus(PaymentStatusQueryRequest request) {
        Assert.hasText(request.getGatewayPaymentOrderNo(), "gatewayPaymentOrderNo cannot be left blank");

        long tenantId = resolveTenantId();
        PayOrderModel row = payOrderRepository
                .findByTenantIdAndGatewayPaymentOrderNo(tenantId, request.getGatewayPaymentOrderNo())
                .orElse(null);
        if (row == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_order_not_found);
        }
        ScanOrderStatusQueryResponse resp = payGatewayOutboundExecutor.invoke(
                tenantId,
                outbound -> paymentScanGateway.queryOrderStatus(outbound, request.getGatewayPaymentOrderNo(), true));
        PayOrderGatewayQueryMerger.mergeIntoRow(row, resp);
        payOrderRepository.save(row);
        return resp;
    }

    @Override
    public boolean syncSettlementForBizOrderNo(String bizOrderNo) {
        long tenantId = resolveTenantId();
        return payOrderSettlementSyncService.syncSettlementForBizOrderNo(tenantId, bizOrderNo);
    }

    private PaymentOrderCreateResponse reconcileAfterDuplicate(PayOrderModel race) {
        return resumeExistingOrder(race);
    }

    /**
     * 已存在行的续跑逻辑。FAILED→PENDING 使用 CAS（仅一行匹配 FAILED 才更新），降低多实例同时重试时对网关的重复调用。
     */
    private PaymentOrderCreateResponse resumeExistingOrder(PayOrderModel row) {
        if (PayOrderGatewaySyncStatus.SUCCESS == row.getGatewaySyncStatus()) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "订单已存在");
        }
        if (PayOrderGatewaySyncStatus.PENDING == row.getGatewaySyncStatus()) {
            if (!isStalePending(row)) {
                throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "订单处理中，请稍后再试");
            }
            // 长时间pending,可能是因为即将调用网关时发生了重启，没有调下游，允许重新调用，依赖下游幂等
            return callGatewayAndFinish(row);
        }
        if (PayOrderGatewaySyncStatus.FAILED == row.getGatewaySyncStatus()) {
            return casRetryFailedToPendingThenCallGateway(row);
        }
        throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "订单状态异常，无法创建");
    }

    private PaymentOrderCreateResponse casRetryFailedToPendingThenCallGateway(PayOrderModel row) {
        int n = payOrderRepository.casUpdateGatewaySyncFromFailedToPending(row.getId(), row.getTenantId(), row.getBizOrderNo());
        if (n == 1) {
            row.setGatewaySyncStatus(PayOrderGatewaySyncStatus.PENDING);
            row.setGatewayLastError(null);
            return callGatewayAndFinish(row);
        }
        PayOrderModel fresh = payOrderRepository
                .findByTenantIdAndBizOrderNo(row.getTenantId(), row.getBizOrderNo())
                .orElse(null);
        if (fresh == null) {
            throw new BizException(ErrorCodeEnum.SYS_ERROR.getCode(), "订单冲突，请重试");
        }
        return resumeExistingOrder(fresh);
    }

    private static boolean isStalePending(PayOrderModel row) {
        Date anchor = row.getModified() != null ? row.getModified() : row.getCreated();
        if (anchor == null) {
            return true;
        }
        return anchor.toInstant().plus(PayOrderScheduleConstants.STALE_GATEWAY_SYNC_PENDING).isBefore(Instant.now());
    }

    private PaymentOrderCreateResponse callGatewayAndFinish(PayOrderModel row) {
        try {
            PaymentOrderCreateResponse resp =
                    paymentScanGateway.createOrder(
                            payGatewayOutboundExecutor.resolve(row.getTenantId()),
                            row.getBizOrderNo(),
                            row.getOrderAmount(),
                            row.getSubject(),
                            row.getExt());
            row.setGatewayPaymentOrderNo(resp.getGatewayPaymentOrderNo());
            row.setGatewayOrderStatus(resp.getStatus() != null ? resp.getStatus().name() : null);
            row.setGatewaySyncStatus(PayOrderGatewaySyncStatus.SUCCESS);
            row.setGatewayLastError(null);
            row.setBizNotifyStatus(PayBizNotifyStatus.POLLING);
            payOrderRepository.save(row);
            payOrderPayPollRunner.startPoll(row.getId(), row.getTenantId());
            return resp;
        } catch (PayGatewayClientException e) {
            PayOrderGatewaySyncFailureSupport.finishGatewayCreateFailed(
                    payOrderRepository, billRpcService, row, abbreviate(e.getGatewayMessage(), 1000));
            throw PayGatewayExceptionMapper.toBizException(e);
        }
    }

    private static long resolveTenantId() {
        Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
        if (tenantId == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.systemGetTenantFailed);
        }
        return tenantId;
    }

    private static String abbreviate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
