package com.xspaceagi.pay.application.support;

import com.xspaceagi.bill.sdk.rpc.IBillRpcService;
import com.xspaceagi.bill.sdk.dto.AddRevenueRequest;
import com.xspaceagi.bill.spec.enums.PayStatusEnum;
import com.xspaceagi.bill.spec.enums.RevenueTargetTypeEnum;
import com.xspaceagi.bill.spec.enums.RevenueTypeEnum;
import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryResponse;
import com.xspaceagi.pay.sdk.enums.PaymentStatus;
import com.xspaceagi.pay.sdk.support.PayOrderExtKeys;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;

/** 支付结果通知 Bill（进程内 RPC）。 */
@Slf4j
public final class PayOrderBillNotifySupport {

    private PayOrderBillNotifySupport() {}

    /**
     * 建单调网关失败时尽力通知 Bill（FAILED），不校验结果；调用方应自行将 {@code bizNotifyStatus} 标为 NOTIFIED。
     */
    public static void notifyBillGatewayCreateFailedBestEffort(IBillRpcService billRpcService, PayOrderModel row) {
        Long orderId = parseNumericBizOrderNo(row.getBizOrderNo());
        if (orderId == null) {
            return;
        }
        String payStatus = PayStatusEnum.FAILED.getCode();
        try {
            billRpcService.paymentCallback(row.getTenantId(), orderId, payStatus);
            log.info(
                    "[pay-notify] bill gateway-create-failed tenantId={} orderId={} payStatus={}",
                    row.getTenantId(),
                    orderId,
                    payStatus);
        } catch (Exception e) {
            log.warn(
                    "[pay-notify] bill gateway-create-failed ignored tenantId={} orderId={} payStatus={} msg={}",
                    row.getTenantId(),
                    orderId,
                    payStatus,
                    e.getMessage());
        }
    }

    /**
     * @param pollTimeout true 时无网关终态则按关单通知（轮询 1h 耗尽场景）
     * @return true 表示业务通知已成功（或无 Bill 回调目标）
     */
    public static boolean notifyBillPaymentCallback(
            IBillRpcService billRpcService, PayOrderModel row, PaymentStatusQueryResponse gatewayResp, boolean pollTimeout) {
        Long orderId = parseNumericBizOrderNo(row.getBizOrderNo());
        if (orderId == null) {
            // 通用项目订单（GP1_/GP2_）— 无 Bill 回调目标；若已支付则计入开发者收益
            if (gatewayResp != null && gatewayResp.getStatus() == PaymentStatus.PAID) {
                return addGeneralProjectRevenue(billRpcService, row);
            }
            return true;
        }
        PaymentStatus st = gatewayResp != null ? gatewayResp.getStatus() : null;
        String payStatus = toBillPayStatusCode(st, pollTimeout);
        try {
            billRpcService.paymentCallback(row.getTenantId(), orderId, payStatus);
            log.info("[pay-notify] bill paymentCallback tenantId={} orderId={} payStatus={}", row.getTenantId(), orderId, payStatus);
            return true;
        } catch (Exception e) {
            log.warn(
                    "[pay-notify] bill paymentCallback failed tenantId={} orderId={} payStatus={} msg={}",
                    row.getTenantId(),
                    orderId,
                    payStatus,
                    e.getMessage());
            return false;
        }
    }

    /**
     * 通用项目支付成功 → 计入开发者（项目创建者）收益。
     * 从 ext 解析 generalProjectUserId 和 generalProjectId。
     * userId 为 null 时跳过（不阻断通知流程）；addRevenue 幂等，异常返回 false 触发重试。
     *
     * @return true 表示收益已记录或无需记录；false 表示记录失败应重试
     */
    private static boolean addGeneralProjectRevenue(IBillRpcService billRpcService, PayOrderModel row) {
        Map<String, Object> extMap = PayOrderExtSupport.parseExt(row.getExt());
        Long userId = parseLongExt(extMap.get(PayOrderExtKeys.GENERAL_PROJECT_USER_ID));
        Long projectId = parseLongExt(extMap.get(PayOrderExtKeys.GENERAL_PROJECT_ID));
        if (userId == null) {
            log.warn(
                    "[pay-notify] general-project revenue skipped (no userId) payOrderId={} tenantId={}",
                    row.getId(),
                    row.getTenantId());
            return true;
        }
        AddRevenueRequest req = new AddRevenueRequest();
        req.setTenantId(row.getTenantId());
        req.setUserId(userId);
        req.setBizNo(row.getBizOrderNo());
        req.setType(RevenueTypeEnum.PROJECT_PAY);
        req.setTargetType(RevenueTargetTypeEnum.PROJECT);
        if (projectId != null) {
            req.setTargetId(projectId);
        }
        req.setRemark(row.getSubject());
        Map<String, Object> extra = new HashMap<>();
        extra.put("payOrderId", row.getId());
        if (row.getBizOrderNo() != null) {
            extra.put("bizOrderNo", row.getBizOrderNo());
        }
        if (row.getGatewayPaymentOrderNo() != null) {
            extra.put("gatewayPaymentOrderNo", row.getGatewayPaymentOrderNo());
        }
        req.setExtra(extra);
        try {
            if (row.getOrderAmount() == null) {
                log.warn(
                        "[pay-notify] general-project revenue skipped (null orderAmount) payOrderId={} tenantId={}",
                        row.getId(),
                        row.getTenantId());
                return true;
            }
            req.setAmount(new BigDecimal(row.getOrderAmount()).divide(new BigDecimal("100"), 2, RoundingMode.HALF_UP));
            billRpcService.addRevenue(req);
            log.info(
                    "[pay-notify] general-project revenue added payOrderId={} tenantId={} userId={} projectId={} amount={}",
                    row.getId(),
                    row.getTenantId(),
                    userId,
                    projectId,
                    req.getAmount());
            return true;
        } catch (Exception e) {
            log.warn(
                    "[pay-notify] general-project revenue failed payOrderId={} tenantId={} userId={} msg={}",
                    row.getId(),
                    row.getTenantId(),
                    userId,
                    e.getMessage());
            return false;
        }
    }

    /** 安全解析 ext 中的数字字段（Integer / Long / String → Long）。 */
    private static Long parseLongExt(Object val) {
        if (val == null) {
            return null;
        }
        if (val instanceof Number) {
            return ((Number) val).longValue();
        }
        try {
            return Long.parseLong(val.toString().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static Long parseNumericBizOrderNo(String bizOrderNo) {
        if (bizOrderNo == null || bizOrderNo.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(bizOrderNo.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static String toBillPayStatusCode(PaymentStatus st, boolean pollTimeout) {
        if (st == PaymentStatus.PAID) {
            return PayStatusEnum.SUCCESS.getCode();
        }
        if (st == PaymentStatus.FAILED) {
            return PayStatusEnum.FAILED.getCode();
        }
        if (st == PaymentStatus.CLOSED) {
            return PayStatusEnum.CLOSED.getCode();
        }
        if (pollTimeout) {
            return PayStatusEnum.CLOSED.getCode();
        }
        return PayStatusEnum.PENDING.getCode();
    }
}
