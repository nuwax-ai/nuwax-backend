package com.xspaceagi.bill.web.controller;

import com.xspaceagi.bill.app.service.BillOrderAppService;
import com.xspaceagi.bill.app.service.BillRevenueAppService;
import com.xspaceagi.bill.app.service.BillWithdrawAppService;
import com.xspaceagi.bill.app.service.ResourceStatAppService;
import com.xspaceagi.bill.sdk.dto.*;
import com.xspaceagi.bill.spec.enums.ResourceStatTypeEnum;
import com.xspaceagi.bill.spec.enums.RevenueTargetTypeEnum;
import com.xspaceagi.bill.spec.enums.RevenueTypeEnum;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateResponse;
import com.xspaceagi.pay.sdk.dto.OrderAndTransactionCreateResponse;
import com.xspaceagi.pay.sdk.support.PayAppWebViewDetector;
import com.xspaceagi.pay.spec.support.PayAppNativeInAppGuard;
import com.xspaceagi.pay.spec.support.PayH5InAppGuard;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.IPUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
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
        CashierSessionCreateResponse cashierSession = billOrderAppService.invokeScanCashier(
                RequestContext.get().getUserId(),
                orderId,
                validateSettlementPageUrl(returnUrl.trim()));
        return ReqResult.success(cashierSession);
    }

    @PostMapping("/order/pay/minipay")
    @Operation(
            summary = "小程序调起支付",
            description = "调起微信/支付宝小程序支付，返回 wx.requestPayment / my.tradePay 所需参数")
    public ReqResult<OrderAndTransactionCreateResponse> invokeMiniPay(
            @RequestBody MiniPayInvokeRequest request, HttpServletRequest httpServletRequest) {
        Assert.notNull(request, "request cannot be null");
        Assert.notNull(request.getOrderId(), "orderId can not be null");
        String clientIp = IPUtil.getIpAddr(httpServletRequest);
        return ReqResult.success(
                billOrderAppService.invokeMiniPay(RequestContext.get().getUserId(), request, clientIp));
    }

    @PostMapping("/order/pay/wechat-jsapi")
    @Operation(
            summary = "微信内 H5 JSAPI 调起支付",
            description = "仅微信内置浏览器：先 GET /api/system/wechat/oa/oauth-url 完成 snsapi_base 授权，"
                    + "再传 wxOAuthCode；返回 wxPayParams 供 WeixinJSBridge.getBrandWCPayRequest；"
                    + "付后须轮询 GET /api/bill/order/settlement-status")
    public ReqResult<OrderAndTransactionCreateResponse> invokeWeChatJsapi(
            @RequestBody WeChatJsapiInvokeRequest request, HttpServletRequest httpServletRequest) {
        Assert.notNull(request, "request cannot be null");
        Assert.notNull(request.getOrderId(), "orderId can not be null");
        String clientIp = IPUtil.getIpAddr(httpServletRequest);
        return ReqResult.success(
                billOrderAppService.invokeWeChatJsapi(RequestContext.get().getUserId(), request, clientIp));
    }

    @PostMapping("/order/pay/h5-web")
    @Operation(
            summary = "系统浏览器 H5 调起支付",
            description = "调起 h5 支付，返回 invokeType + formHtml/redirectUrl；支付后轮询 settlement-status。"
                    + "App WebView 内禁止调用")
    public ReqResult<OrderAndTransactionCreateResponse> invokeH5Web(
            @RequestBody H5WebInvokeRequest request, HttpServletRequest httpServletRequest) {
        Assert.notNull(request, "request cannot be null");
        Assert.notNull(request.getOrderId(), "orderId can not be null");
        PayH5InAppGuard.assertH5PayAllowed(
                httpServletRequest.getHeader(PayAppWebViewDetector.HEADER_CLIENT_TYPE),
                httpServletRequest.getHeader("User-Agent"));
        String clientIp = IPUtil.getIpAddr(httpServletRequest);
        return ReqResult.success(
                billOrderAppService.invokeH5Web(RequestContext.get().getUserId(), request, clientIp));
    }

    @PostMapping("/order/pay/app-native")
    @Operation(
            summary = "App 原生 SDK 调起支付",
            description = "WebView/App 壳内调起支付。支付后轮询 settlement-status。"
                    + "仅限 App WebView 内调用")
    public ReqResult<OrderAndTransactionCreateResponse> invokeAppNative(
            @RequestBody AppNativeInvokeRequest request, HttpServletRequest httpServletRequest) {
        Assert.notNull(request, "request cannot be null");
        Assert.notNull(request.getOrderId(), "orderId can not be null");
        PayAppNativeInAppGuard.assertAppNativePayRequired(
                httpServletRequest.getHeader(PayAppWebViewDetector.HEADER_CLIENT_TYPE),
                httpServletRequest.getHeader("User-Agent"));
        String clientIp = IPUtil.getIpAddr(httpServletRequest);
        return ReqResult.success(
                billOrderAppService.invokeAppNative(RequestContext.get().getUserId(), request, clientIp));
    }

    @RequestMapping(value = "/order/pay/front-notify", method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(
            summary = "H5 渠道前台回跳中转",
            description = "接收渠道前台回调（GET/POST），统一 302 到前端结算页")
    public void handleH5FrontNotify(
            @RequestParam(required = false) Long orderId,
            @RequestParam String returnUrl,
            HttpServletResponse response) {
        String target = validateSettlementPageUrl(returnUrl.trim());
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", target);
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
