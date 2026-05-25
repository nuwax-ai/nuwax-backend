package com.xspaceagi.bill.web.controller;

import com.xspaceagi.bill.app.service.BillOrderAppService;
import com.xspaceagi.bill.app.service.BillRevenueAppService;
import com.xspaceagi.bill.app.service.BillWithdrawAppService;
import com.xspaceagi.bill.app.service.ResourceStatAppService;
import com.xspaceagi.bill.sdk.dto.*;
import com.xspaceagi.bill.spec.enums.PayStatusEnum;
import com.xspaceagi.bill.spec.enums.ResourceStatTypeEnum;
import com.xspaceagi.bill.spec.enums.RevenueTargetTypeEnum;
import com.xspaceagi.bill.spec.enums.RevenueTypeEnum;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateRequest;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateResponse;
import com.xspaceagi.pay.sdk.service.IPaymentRpcService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/bill")
@Tag(name = "用户账单相关接口")
public class BillController {

    @Resource
    private BillOrderAppService billOrderAppService;

    @Resource
    private BillRevenueAppService billRevenueAppService;

    @Resource
    private BillWithdrawAppService billWithdrawAppService;

    @Resource
    private IPaymentRpcService iPaymentRpcService;

    @Resource
    private ResourceStatAppService resourceStatAppService;

    @GetMapping("/order/my")
    @Operation(summary = "我的订单")
    public ReqResult<OrderPageDTO> getMyOrders(
            @RequestParam(required = false) String orderStatus,
            @RequestParam(required = false) String payStatus,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        OrderQueryRequest query = new OrderQueryRequest();
        query.setUserId(RequestContext.get().getUserId());
        query.setOrderStatus(orderStatus);
        query.setPayStatus(payStatus);
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);
        return ReqResult.success(billOrderAppService.queryOrders(query));
    }

    @GetMapping("/revenue/daily")
    @Operation(summary = "我的每日收益")
    public ReqResult<List<DailyRevenueDTO>> getMyDailyRevenue(
            @RequestParam(required = false) RevenueTypeEnum type,
            @RequestParam(required = false) RevenueTargetTypeEnum targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) String dt) {
        RevenueQueryRequest query = new RevenueQueryRequest();
        query.setType(type);
        query.setTargetType(targetType);
        query.setTargetId(targetId);
        query.setUserId(RequestContext.get().getUserId());
        query.setDt(dt);
        return ReqResult.success(billRevenueAppService.queryDailyRevenue(query));
    }

    @GetMapping("/revenue/detail")
    @Operation(summary = "我的收益明细")
    public ReqResult<RevenueDetailPageDTO> getMyRevenueDetail(
            @RequestParam(required = false) RevenueTypeEnum type,
            @RequestParam(required = false) RevenueTargetTypeEnum targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) String dt,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        RevenueQueryRequest query = new RevenueQueryRequest();
        query.setUserId(RequestContext.get().getUserId());
        query.setType(type);
        query.setTargetType(targetType);
        query.setTargetId(targetId);
        query.setDt(dt);
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);
        return ReqResult.success(billRevenueAppService.queryRevenueDetail(query));
    }

    @PostMapping("/withdraw/create")
    @Operation(summary = "创建提现申请")
    public ReqResult<WithdrawApplicationDTO> createWithdraw() {
        RequestContext<Object> ctx = RequestContext.get();
        return ReqResult.success(billWithdrawAppService.createWithdrawApplication(ctx.getTenantId(), ctx.getUserId()));
    }

    @GetMapping("/withdraw/records")
    @Operation(summary = "我的提现记录")
    public ReqResult<WithdrawApplicationPageDTO> getMyWithdrawRecords(
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        WithdrawQueryRequest query = new WithdrawQueryRequest();
        query.setUserId(RequestContext.get().getUserId());
        query.setPageNum(pageNum);
        query.setPageSize(pageSize);
        return ReqResult.success(billWithdrawAppService.queryWithdrawApplications(query));
    }

    @GetMapping("/revenue/stats")
    @Operation(summary = "我的收益统计")
    public ReqResult<RevenueStatsDTO> getMyRevenueStats() {
        return ReqResult.success(billRevenueAppService.getRevenueStats(RequestContext.get().getUserId()));
    }

    @GetMapping("/order/settlement-status")
    @Operation(
            summary = "支付结算页轮询订单状态",
            description = "中间结算页使用；非终态时会主动同步网关并通知 Bill；settled=true 表示可安全回跳业务页")
    public ReqResult<OrderSettlementStatusResponse> getOrderSettlementStatus(@RequestParam Long orderId) {
        Assert.notNull(orderId, "orderId can not be null");
        return ReqResult.success(
                billOrderAppService.getOrderSettlementStatus(RequestContext.get().getUserId(), orderId));
    }

    @GetMapping("/order/pay/cashier")
    @Operation(summary = "获取订单收银台地址",
            description = "returnUrl 为前端结算页完整地址（支付完成后收银台跳转目标），须自行携带 orderId、业务回跳 callback/returnUrl 等 query；"
                            + "示例：{前端域名}/pay/settlement?orderId={orderId}&returnUrl={业务页编码}")
    public ReqResult<CashierSessionCreateResponse> getCashierUrl(
            @RequestParam Long orderId, @RequestParam String returnUrl) {
        Assert.notNull(orderId, "orderId can not be null");
        Assert.hasText(returnUrl, "returnUrl can not be blank");
        OrderDTO orderDTO = billOrderAppService.queryOrder(orderId);
        if (orderDTO == null) {
            throw new IllegalArgumentException("order not found");
        }
        if (!RequestContext.get().getUserId().equals(orderDTO.getUserId())) {
            throw new BizException("USER_NOT_MATCH", "The order data does not match the current user.");
        }
        if (orderDTO.getPayStatus() != PayStatusEnum.PENDING) {
            throw new BizException("ORDER_NOT_PENDING", "The order status is incorrect.");
        }
        if (orderDTO.getExtra().get("gatewayPaymentOrderNo") == null) {
            throw new BizException("The order data is incomplete.");
        }
        CashierSessionCreateRequest paymentCreateCashierSessionRequest = new CashierSessionCreateRequest();
        paymentCreateCashierSessionRequest.setGatewayPaymentOrderNo(String.valueOf(orderDTO.getExtra().get("gatewayPaymentOrderNo")));
        paymentCreateCashierSessionRequest.setBizRedirectUrl(validateSettlementPageUrl(returnUrl.trim()));
        CashierSessionCreateResponse cashierSession = iPaymentRpcService.createCashierSession(paymentCreateCashierSessionRequest);
        return ReqResult.success(cashierSession);
    }

    /** 校验前端传入的结算页 URL（http/https）。 */
    private static String validateSettlementPageUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "returnUrl must start with http or https");
            }
            if (!StringUtils.hasText(uri.getHost())) {
                throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "Invalid returnUrl");
            }
            return url;
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "Invalid returnUrl");
        }
    }

    @GetMapping("/resource-stat/my")
    @Operation(summary = "我的资源统计明细")
    public ReqResult<ResourceStatPageDTO> getMyResourceStat(
            @RequestParam(required = false) ResourceStatTypeEnum type,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) Long targetId,
            @RequestParam(required = false) String dtStart,
            @RequestParam(required = false) String dtEnd,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "20") Integer pageSize) {
        RequestContext<Object> ctx = RequestContext.get();
        return ReqResult.success(resourceStatAppService.queryResourceStats(ctx.getTenantId(), ctx.getUserId(), type != null ? type.getCode() : null, targetType, targetId, dtStart, dtEnd, pageNum, pageSize));
    }

    @GetMapping("/resource-stat/my-summary")
    @Operation(summary = "我的资源统计汇总")
    public ReqResult<ResourceStatSummaryDTO> getMyResourceStatSummary(
            @RequestParam(required = false) String dtStart,
            @RequestParam(required = false) String dtEnd) {
        RequestContext<Object> ctx = RequestContext.get();
        return ReqResult.success(resourceStatAppService.getResourceStatSummary(
                ctx.getTenantId(), ctx.getUserId(), dtStart, dtEnd));
    }

    @GetMapping("/withdraw/config")
    @Operation(summary = "查询提现配置")
    public ReqResult<WithdrawConfigDTO> getWithdrawConfig() {
        return ReqResult.success(billWithdrawAppService.getWithdrawConfig(RequestContext.get().getTenantId()));
    }
}
