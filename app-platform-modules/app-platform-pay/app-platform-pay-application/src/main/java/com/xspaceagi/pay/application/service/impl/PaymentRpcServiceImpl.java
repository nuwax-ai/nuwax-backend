package com.xspaceagi.pay.application.service.impl;

import com.xspaceagi.bill.sdk.rpc.IBillRpcService;
import com.xspaceagi.pay.application.schedule.PayOrderPollRunner;
import com.xspaceagi.pay.application.support.PayGatewayOutboundExecutor;
import com.xspaceagi.pay.application.support.PayOrderExtSupport;
import com.xspaceagi.pay.application.support.PayOrderGatewayQueryMerger;
import com.xspaceagi.pay.application.support.PayOrderGatewaySyncFailureSupport;
import com.xspaceagi.pay.application.support.PayOrderSettlementSyncService;
import com.xspaceagi.pay.application.support.PayOrderStatusQuerySupport;
import com.xspaceagi.pay.spec.PayOrderScheduleConstants;
import com.xspaceagi.pay.domain.gateway.PaymentAppGateway;
import com.xspaceagi.pay.domain.gateway.PaymentH5Gateway;
import com.xspaceagi.pay.domain.gateway.PaymentMiniPayGateway;
import com.xspaceagi.pay.domain.gateway.PaymentScanGateway;
import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.domain.repository.PayOrderRepository;
import com.xspaceagi.pay.sdk.dto.AppOrderRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.AppTransactionRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateRequest;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateResponse;
import com.xspaceagi.pay.sdk.dto.H5OrderRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.H5TransactionRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.MiniPayOrderRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.MiniPayTransactionRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.OrderAndTransactionCreateResponse;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryRequest;
import com.xspaceagi.pay.sdk.dto.ScanOrderCreateRequest;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryResponse;
import com.xspaceagi.pay.sdk.service.IPaymentRpcService;
import com.xspaceagi.pay.spec.enums.PayBizNotifyStatus;
import com.xspaceagi.pay.sdk.dto.OrderCreateResponse;
import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayClientScene;
import com.xspaceagi.pay.sdk.enums.PayMode;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
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
import org.springframework.util.StringUtils;

import java.net.URI;
import java.time.Instant;
import java.util.Date;

@Service
public class PaymentRpcServiceImpl implements IPaymentRpcService {

    @Resource
    private PayOrderRepository payOrderRepository;

    @Resource
    private PaymentScanGateway paymentScanGateway;

    @Resource
    private PaymentMiniPayGateway paymentMiniPayGateway;

    @Resource
    private PaymentH5Gateway paymentH5Gateway;

    @Resource
    private PaymentAppGateway paymentAppGateway;

    @Resource
    private PayOrderStatusQuerySupport payOrderStatusQuerySupport;

    @Resource
    private PayGatewayOutboundExecutor payGatewayOutboundExecutor;

    @Resource
    private PayOrderPollRunner payOrderPayPollRunner;

    @Resource
    private IBillRpcService billRpcService;

    @Resource
    private PayOrderSettlementSyncService payOrderSettlementSyncService;

    @Override
    public OrderCreateResponse createOrderForScan(ScanOrderCreateRequest request) {
        return createPayOrder(
                PayMode.scan,
                request.getBizOrderNo(),
                request.getOrderAmount(),
                request.getSubject(),
                request.getExt());
    }

    @Override
    public OrderCreateResponse createOrderForMiniPay(MiniPayOrderRpcCreateRequest request) {
        return createPayOrder(
                PayMode.minipay,
                request.getBizOrderNo(),
                request.getOrderAmount(),
                request.getSubject(),
                request.getExt());
    }

    @Override
    public OrderCreateResponse createOrderForH5(H5OrderRpcCreateRequest request) {
        return createPayOrder(
                PayMode.h5,
                request.getBizOrderNo(),
                request.getOrderAmount(),
                request.getSubject(),
                request.getExt());
    }

    @Override
    public OrderCreateResponse createOrderForApp(AppOrderRpcCreateRequest request) {
        return createPayOrder(
                PayMode.app,
                request.getBizOrderNo(),
                request.getOrderAmount(),
                request.getSubject(),
                request.getExt());
    }

    @Override
    public OrderAndTransactionCreateResponse createMiniPayTransaction(MiniPayTransactionRpcCreateRequest request) {
        Assert.hasText(request.getBizOrderNo(), "bizOrderNo cannot be left blank");
        Assert.notNull(request.getPayChannel(), "payChannel cannot be null");
        validateMiniPayChannelParams(request);

        long tenantId = resolveTenantId();
        PayOrderModel row = payOrderRepository
                .findByTenantIdAndBizOrderNo(tenantId, request.getBizOrderNo().trim())
                .orElse(null);
        if (row == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_order_not_found);
        }
        if (row.getPayMode() != PayMode.minipay) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "非小程序支付单");
        }
        if (row.getGatewaySyncStatus() != PayOrderGatewaySyncStatus.SUCCESS) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "支付单尚未同步网关，无法调起支付");
        }

        try {
            OrderAndTransactionCreateResponse resp = payGatewayOutboundExecutor.invoke(
                    tenantId,
                    outbound -> paymentMiniPayGateway.createTransaction(
                            outbound,
                            row.getGatewayPaymentOrderNo(),
                            request.getPayChannel(),
                            request.getSubAppid(),
                            request.getOpenId(),
                            request.getBuyerId(),
                            request.getClientIp(),
                            request.getPayClientScene()));
            mergeTransactionResponseIntoRow(row, resp);
            if (request.getPayClientScene() != null) {
                row.setExt(PayOrderExtSupport.mergePayClientScene(row.getExt(), request.getPayClientScene()));
            }
            if (row.getBizNotifyStatus() != PayBizNotifyStatus.POLLING) {
                row.setBizNotifyStatus(PayBizNotifyStatus.POLLING);
            }
            payOrderRepository.save(row);
            // 重试调起时若调度任务已结束，同 taskId 的 start 会将其拉回 CREATE 并继续轮询
            payOrderPayPollRunner.startPoll(row.getId(), row.getTenantId());
            return resp;
        } catch (PayGatewayClientException e) {
            throw PayGatewayExceptionMapper.toBizException(e);
        }
    }

    @Override
    public OrderAndTransactionCreateResponse createH5Transaction(H5TransactionRpcCreateRequest request) {
        Assert.hasText(request.getBizOrderNo(), "bizOrderNo cannot be left blank");
        Assert.notNull(request.getPayChannel(), "payChannel cannot be null");
        Assert.hasText(request.getFrontNotifyUrl(), "frontNotifyUrl cannot be left blank");
        String frontNotifyUrl = validateFrontNotifyUrl(request.getFrontNotifyUrl().trim());

        long tenantId = resolveTenantId();
        PayOrderModel row = payOrderRepository
                .findByTenantIdAndBizOrderNo(tenantId, request.getBizOrderNo().trim())
                .orElse(null);
        if (row == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_order_not_found);
        }
        if (row.getPayMode() != PayMode.h5) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "非H5支付单");
        }
        if (row.getGatewaySyncStatus() != PayOrderGatewaySyncStatus.SUCCESS) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "支付单尚未同步网关，无法调起支付");
        }

        try {
            OrderAndTransactionCreateResponse resp = payGatewayOutboundExecutor.invoke(
                    tenantId,
                    outbound -> paymentH5Gateway.createTransaction(
                            outbound,
                            row.getGatewayPaymentOrderNo(),
                            request.getPayChannel(),
                            request.getClientIp(),
                            frontNotifyUrl));
            mergeTransactionResponseIntoRow(row, resp);
            row.setExt(PayOrderExtSupport.mergePayClientScene(row.getExt(), PayClientScene.H5_WEB));
            if (row.getBizNotifyStatus() != PayBizNotifyStatus.POLLING) {
                row.setBizNotifyStatus(PayBizNotifyStatus.POLLING);
            }
            payOrderRepository.save(row);
            payOrderPayPollRunner.startPoll(row.getId(), row.getTenantId());
            return resp;
        } catch (PayGatewayClientException e) {
            throw PayGatewayExceptionMapper.toBizException(e);
        }
    }

    @Override
    public OrderAndTransactionCreateResponse createAppTransaction(AppTransactionRpcCreateRequest request) {
        Assert.hasText(request.getBizOrderNo(), "bizOrderNo cannot be left blank");
        Assert.notNull(request.getPayChannel(), "payChannel cannot be null");
        validateAppPayChannel(request.getPayChannel());

        long tenantId = resolveTenantId();
        PayOrderModel row = payOrderRepository
                .findByTenantIdAndBizOrderNo(tenantId, request.getBizOrderNo().trim())
                .orElse(null);
        if (row == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_order_not_found);
        }
        if (row.getPayMode() != PayMode.app) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "非 App 原生支付单");
        }
        if (row.getGatewaySyncStatus() != PayOrderGatewaySyncStatus.SUCCESS) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "支付单尚未同步网关，无法调起支付");
        }

        try {
            OrderAndTransactionCreateResponse resp = payGatewayOutboundExecutor.invoke(
                    tenantId,
                    outbound -> paymentAppGateway.createTransaction(
                            outbound,
                            row.getGatewayPaymentOrderNo(),
                            request.getPayChannel(),
                            request.getClientIp(),
                            request.getPayClientScene() != null
                                    ? request.getPayClientScene()
                                    : PayClientScene.NATIVE_APP));
            mergeTransactionResponseIntoRow(row, resp);
            row.setExt(PayOrderExtSupport.mergePayClientScene(row.getExt(), PayClientScene.NATIVE_APP));
            if (row.getBizNotifyStatus() != PayBizNotifyStatus.POLLING) {
                row.setBizNotifyStatus(PayBizNotifyStatus.POLLING);
            }
            payOrderRepository.save(row);
            payOrderPayPollRunner.startPoll(row.getId(), row.getTenantId());
            return resp;
        } catch (PayGatewayClientException e) {
            throw PayGatewayExceptionMapper.toBizException(e);
        }
    }

    @Override
    public CashierSessionCreateResponse createCashierSession(CashierSessionCreateRequest request) {
        Assert.notNull(request, "request cannot be null");
        Assert.hasText(request.getGatewayPaymentOrderNo(), "gatewayPaymentOrderNo cannot be left blank");

        long tenantId = resolveTenantId();
        String gatewayNo = request.getGatewayPaymentOrderNo().trim();
        PayOrderModel row = payOrderRepository.findByTenantIdAndGatewayPaymentOrderNo(tenantId, gatewayNo).orElse(null);
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

    @Override
    public PaymentStatusQueryResponse queryStatus(PaymentStatusQueryRequest request) {
        Assert.hasText(request.getGatewayPaymentOrderNo(), "gatewayPaymentOrderNo cannot be left blank");

        long tenantId = resolveTenantId();
        PayOrderModel row = payOrderRepository
                .findByTenantIdAndGatewayPaymentOrderNo(tenantId, request.getGatewayPaymentOrderNo())
                .orElse(null);
        if (row == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_order_not_found);
        }
        PaymentStatusQueryResponse resp = payGatewayOutboundExecutor.invoke(
                tenantId,
                outbound -> payOrderStatusQuerySupport.queryOrderStatus(outbound, row, true));
        PayOrderGatewayQueryMerger.mergeIntoRow(row, resp);
        payOrderRepository.save(row);
        return resp;
    }

    @Override
    public boolean syncSettlementForBizOrderNo(String bizOrderNo) {
        long tenantId = resolveTenantId();
        return payOrderSettlementSyncService.syncSettlementForBizOrderNo(tenantId, bizOrderNo);
    }

    private OrderCreateResponse createPayOrder(
            PayMode payMode, String bizOrderNo, Long orderAmount, String subject, String ext) {
        Assert.hasText(bizOrderNo, "bizOrderNo cannot be left blank");
        Assert.notNull(orderAmount, "orderAmount cannot be left blank");
        Assert.isTrue(orderAmount > 0, "orderAmount must be greater than 0");
        Assert.hasText(subject, "subject cannot be left blank");

        long tenantId = resolveTenantId();
        PayOrderModel existing =
                payOrderRepository.findByTenantIdAndBizOrderNo(tenantId, bizOrderNo).orElse(null);
        if (existing != null) {
            if (existing.getPayMode() != null && existing.getPayMode() != payMode) {
                return recreateForSwitchedPayMode(existing, payMode, orderAmount, subject, ext);
            }
            return resumeExistingOrder(existing, payMode);
        }

        PayOrderModel row = PayOrderModel.builder()
                .tenantId(tenantId)
                .bizOrderNo(bizOrderNo)
                .bizScene(null)
                .orderAmount(orderAmount)
                .subject(subject)
                .ext(ext)
                .payMode(payMode)
                .platformFee(0L)
                .providerFee(0L)
                .netAmount(0L)
                .gatewaySyncStatus(PayOrderGatewaySyncStatus.PENDING)
                .build();
        try {
            row = payOrderRepository.save(row);
        } catch (DuplicateKeyException e) {
            PayOrderModel race = payOrderRepository.findByTenantIdAndBizOrderNo(tenantId, bizOrderNo).orElse(null);
            if (race == null) {
                throw new BizException(ErrorCodeEnum.SYS_ERROR.getCode(), "创建订单冲突，请重试");
            }
            assertPayMode(race, payMode);
            return resumeExistingOrder(race, payMode);
        }
        return callGatewayAndFinish(row, payMode);
    }

    private static void assertPayMode(PayOrderModel row, PayMode expectedPayMode) {
        if (row.getPayMode() == null || row.getPayMode() != expectedPayMode) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "订单支付方式不匹配");
        }
    }

    private OrderCreateResponse recreateForSwitchedPayMode(
            PayOrderModel row, PayMode targetPayMode, long orderAmount, String subject, String ext) {
        row.setPayMode(targetPayMode);
        row.setOrderAmount(orderAmount);
        row.setSubject(subject);
        row.setExt(ext);
        row.setPayChannel(null);
        row.setPlatformFee(0L);
        row.setProviderFee(0L);
        row.setNetAmount(0L);
        row.setGatewayPaymentOrderNo(null);
        row.setGatewayOrderStatus(null);
        row.setGatewayLastError(null);
        row.setPaidAt(null);
        row.setGatewaySyncStatus(PayOrderGatewaySyncStatus.PENDING);
        row.setBizNotifyStatus(null);
        payOrderRepository.save(row);
        return callGatewayAndFinish(row, targetPayMode);
    }

    /**
     * 已存在行的续跑逻辑。FAILED→PENDING 使用 CAS（仅一行匹配 FAILED 才更新），降低多实例同时重试时对网关的重复调用。
     * 网关已 SUCCESS 时幂等返回已有单号（minipay 可能尚未调起渠道）。
     */
    private OrderCreateResponse resumeExistingOrder(PayOrderModel row, PayMode payMode) {
        if (PayOrderGatewaySyncStatus.SUCCESS == row.getGatewaySyncStatus()) {
            return toOrderCreateResponse(row);
        }
        if (PayOrderGatewaySyncStatus.PENDING == row.getGatewaySyncStatus()) {
            if (!isStalePending(row)) {
                throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "订单处理中，请稍后再试");
            }
            return callGatewayAndFinish(row, payMode);
        }
        if (PayOrderGatewaySyncStatus.FAILED == row.getGatewaySyncStatus()) {
            return casRetryFailedToPendingThenCallGateway(row, payMode);
        }
        throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "订单状态异常，无法创建");
    }

    private OrderCreateResponse casRetryFailedToPendingThenCallGateway(PayOrderModel row, PayMode payMode) {
        int n = payOrderRepository.casUpdateGatewaySyncFromFailedToPending(row.getId(), row.getTenantId(), row.getBizOrderNo());
        if (n == 1) {
            row.setGatewaySyncStatus(PayOrderGatewaySyncStatus.PENDING);
            row.setGatewayLastError(null);
            return callGatewayAndFinish(row, payMode);
        }
        PayOrderModel fresh = payOrderRepository
                .findByTenantIdAndBizOrderNo(row.getTenantId(), row.getBizOrderNo())
                .orElse(null);
        if (fresh == null) {
            throw new BizException(ErrorCodeEnum.SYS_ERROR.getCode(), "订单冲突，请重试");
        }
        assertPayMode(fresh, payMode);
        return resumeExistingOrder(fresh, payMode);
    }

    private static OrderCreateResponse toOrderCreateResponse(PayOrderModel row) {
        PaymentStatus status = null;
        if (StringUtils.hasText(row.getGatewayOrderStatus())) {
            try {
                status = PaymentStatus.valueOf(row.getGatewayOrderStatus());
            } catch (IllegalArgumentException ignored) {
                // ignore unmapped gateway status string
            }
        }
        return OrderCreateResponse.builder()
                .gatewayPaymentOrderNo(row.getGatewayPaymentOrderNo())
                .bizOrderNo(row.getBizOrderNo())
                .status(status)
                .payMode(row.getPayMode())
                .build();
    }

    private static boolean isStalePending(PayOrderModel row) {
        Date anchor = row.getModified() != null ? row.getModified() : row.getCreated();
        if (anchor == null) {
            return true;
        }
        return anchor.toInstant().plus(PayOrderScheduleConstants.STALE_GATEWAY_SYNC_PENDING).isBefore(Instant.now());
    }

    private OrderCreateResponse callGatewayAndFinish(PayOrderModel row, PayMode payMode) {
        try {
            OrderCreateResponse resp;
            if (payMode == PayMode.minipay) {
                resp = paymentMiniPayGateway.createOrder(
                        payGatewayOutboundExecutor.resolve(row.getTenantId()),
                        row.getBizOrderNo(),
                        row.getOrderAmount(),
                        row.getSubject(),
                        row.getExt());
            } else if (payMode == PayMode.h5) {
                resp = paymentH5Gateway.createOrder(
                        payGatewayOutboundExecutor.resolve(row.getTenantId()),
                        row.getBizOrderNo(),
                        row.getOrderAmount(),
                        row.getSubject(),
                        row.getExt());
            } else if (payMode == PayMode.app) {
                resp = paymentAppGateway.createOrder(
                        payGatewayOutboundExecutor.resolve(row.getTenantId()),
                        row.getBizOrderNo(),
                        row.getOrderAmount(),
                        row.getSubject(),
                        row.getExt());
            } else {
                resp = paymentScanGateway.createOrder(
                        payGatewayOutboundExecutor.resolve(row.getTenantId()),
                        row.getBizOrderNo(),
                        row.getOrderAmount(),
                        row.getSubject(),
                        row.getExt());
            }
            row.setGatewayPaymentOrderNo(resp.getGatewayPaymentOrderNo());
            row.setGatewayOrderStatus(resp.getStatus() != null ? resp.getStatus().name() : null);
            row.setGatewaySyncStatus(PayOrderGatewaySyncStatus.SUCCESS);
            row.setGatewayLastError(null);
            payOrderRepository.save(row);
            if (payMode == PayMode.scan) {
                row.setBizNotifyStatus(PayBizNotifyStatus.POLLING);
                payOrderRepository.save(row);
                payOrderPayPollRunner.startPoll(row.getId(), row.getTenantId());
            }
            return resp;
        } catch (PayGatewayClientException e) {
            PayOrderGatewaySyncFailureSupport.finishGatewayCreateFailed(
                    payOrderRepository, billRpcService, row, abbreviate(e.getGatewayMessage(), 1000));
            throw PayGatewayExceptionMapper.toBizException(e);
        }
    }

    private static void validateMiniPayChannelParams(MiniPayTransactionRpcCreateRequest request) {
        if (request.getPayChannel() == PayChannel.WxPay) {
            Assert.hasText(request.getSubAppid(), "subAppid cannot be left blank for WxPay");
            Assert.hasText(request.getOpenId(), "openId cannot be left blank for WxPay");
            return;
        }
        if (request.getPayChannel() == PayChannel.AliPay) {
            Assert.hasText(request.getBuyerId(), "buyerId cannot be left blank for AliPay");
            return;
        }
        throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "小程序支付仅支持 WxPay 与 AliPay");
    }

    private static void validateAppPayChannel(PayChannel payChannel) {
        if (payChannel != PayChannel.WxPay && payChannel != PayChannel.AliPay) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "App 原生支付仅支持 WxPay 与 AliPay");
        }
    }

    private static void mergeTransactionResponseIntoRow(PayOrderModel row, OrderAndTransactionCreateResponse resp) {
        if (resp.getPlatformFee() != null) {
            row.setPlatformFee(resp.getPlatformFee());
        }
        if (resp.getProviderFee() != null) {
            row.setProviderFee(resp.getProviderFee());
        }
        if (resp.getNetAmount() != null) {
            row.setNetAmount(resp.getNetAmount());
        }
        if (resp.getPayChannel() != null) {
            row.setPayChannel(resp.getPayChannel());
        }
        if (resp.getStatus() != null) {
            row.setGatewayOrderStatus(resp.getStatus().name());
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

    private static String validateFrontNotifyUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "frontNotifyUrl must start with http or https");
            }
            String host = uri.getHost();
            if (host == null || host.isBlank()) {
                throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "Invalid frontNotifyUrl");
            }
            return url;
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "Invalid frontNotifyUrl");
        }
    }
}
