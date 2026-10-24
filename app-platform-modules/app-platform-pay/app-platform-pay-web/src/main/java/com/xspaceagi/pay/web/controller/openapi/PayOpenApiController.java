package com.xspaceagi.pay.web.controller.openapi;

import com.xspaceagi.pay.domain.model.PayOrderModel;
import com.xspaceagi.pay.domain.repository.PayOrderRepository;
import com.xspaceagi.pay.sdk.dto.AppOrderRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.AppTransactionRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateRequest;
import com.xspaceagi.pay.sdk.dto.CashierSessionCreateResponse;
import com.xspaceagi.pay.sdk.dto.H5OrderRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.H5TransactionRpcCreateRequest;
import com.xspaceagi.pay.sdk.dto.OrderAndTransactionCreateResponse;
import com.xspaceagi.pay.sdk.dto.OrderCreateResponse;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryRequest;
import com.xspaceagi.pay.sdk.dto.PaymentStatusQueryResponse;
import com.xspaceagi.pay.sdk.dto.ScanOrderCreateRequest;
import com.xspaceagi.pay.sdk.enums.PayChannel;
import com.xspaceagi.pay.sdk.enums.PayClientScene;
import com.xspaceagi.pay.sdk.service.IPaymentRpcService;
import com.xspaceagi.pay.spec.dto.OpenPayAppCreateRequest;
import com.xspaceagi.pay.spec.dto.OpenPayAppPayRequest;
import com.xspaceagi.pay.spec.dto.OpenPayCashierRequest;
import com.xspaceagi.pay.spec.dto.OpenPayCashierResponse;
import com.xspaceagi.pay.spec.dto.OpenPayH5CreateRequest;
import com.xspaceagi.pay.spec.dto.OpenPayH5PayRequest;
import com.xspaceagi.pay.spec.dto.OpenPayOrderCreateResponse;
import com.xspaceagi.pay.spec.dto.OpenPayPayInvokeResponse;
import com.xspaceagi.pay.spec.dto.OpenPayStatusRequest;
import com.xspaceagi.pay.spec.dto.OpenPayStatusResponse;
import com.xspaceagi.pay.application.support.OpenPayNotifyService;
import com.xspaceagi.pay.web.service.OpenPayAccessChecker;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * 支付开放接口（OpenApi AK 鉴权，/api/v1 免登录态、ApiKeyInterceptor 强制 AK）。
 *
 * <p>接入身份：请求体 appId = user_project 登记表主键；AK 用户须为该项目空间成员、
 * 项目须属于 AK 所属租户。
 *
 * <p>端点按客户端形态拆分（环境选择权与渠道合规责任在接入方，不做请求头环境守卫）：
 * cashier=PC/通用收银台；h5=移动端浏览器；app=接入方 App 内原生调起。
 * 微信 H5/App 下单需要最终用户 IP：三方后端转发场景请在 h5/pay 透传 clientIp。
 *
 * <p>商户订单号 outTradeNo 为幂等键，平台业务单号 OPEN_{appId}_{outTradeNo}。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/pay/open")
@Tag(name = "支付-开放接口（OpenApi AK）")
public class PayOpenApiController {

    @Resource
    private IPaymentRpcService paymentRpcService;

    @Resource
    private PayOrderRepository payOrderRepository;

    @Resource
    private OpenPayAccessChecker accessChecker;

    @Resource
    private OpenPayNotifyService openPayNotifyService;

    @PostMapping("/orders/cashier")
    @Operation(summary = "收银台下单（PC/通用）", description = "创建扫码订单并返回收银台地址；三方前端自行打开（PC 跳转/新窗口，移动端浏览器直接跳转）")
    public ReqResult<OpenPayCashierResponse> cashier(@RequestBody OpenPayCashierRequest request) {
        Assert.notNull(request, "request must not be null");
        String canonicalId = accessChecker.checkAppAccessAndGetCanonicalId(request.getAppId(), request.getAppType());
        Assert.notNull(request.getOrderAmount(), "orderAmount must not be null");
        Assert.hasText(request.getSubject(), "subject must not be blank");
        Assert.hasText(request.getOutTradeNo(), "outTradeNo must not be blank");

        String bizOrderNo = accessChecker.buildBizOrderNo(canonicalId, request.getOutTradeNo());
        assertNotPaidBeforeCreate(bizOrderNo);
        String ext = accessChecker.buildOpenExt(request.getAppId(), request.getAppType(), request.getNotifyUrl());

        ScanOrderCreateRequest scanReq = new ScanOrderCreateRequest();
        scanReq.setBizOrderNo(bizOrderNo);
        scanReq.setOrderAmount(request.getOrderAmount());
        scanReq.setSubject(request.getSubject());
        scanReq.setExt(ext);
        OrderCreateResponse orderResp = paymentRpcService.createOrderForScan(scanReq);

        openPayNotifyService.createTaskForBizOrderNo(RequestContext.get().getTenantId(), bizOrderNo);

        CashierSessionCreateRequest cashierReq = new CashierSessionCreateRequest();
        cashierReq.setGatewayPaymentOrderNo(orderResp.getGatewayPaymentOrderNo());
        cashierReq.setOrderAmount(request.getOrderAmount());
        cashierReq.setSubject(request.getSubject());
        cashierReq.setBizRedirectUrl(request.getFrontNotifyUrl());
        CashierSessionCreateResponse cashierResp = paymentRpcService.createCashierSession(cashierReq);

        OpenPayCashierResponse resp = OpenPayCashierResponse.builder()
                .orderNo(bizOrderNo)
                .gatewayOrderNo(orderResp.getGatewayPaymentOrderNo())
                .cashierUrl(cashierResp.getCashierUrl())
                .build();
        return ReqResult.success(resp);
    }

    @PostMapping("/orders/h5/create")
    @Operation(summary = "H5 下单（移动端浏览器）", description = "创建 H5 订单（不调渠道），再调 h5/pay 调起。渠道合规要求：仅移动端系统浏览器使用，App 内请走 app 端点")
    public ReqResult<OpenPayOrderCreateResponse> createH5Order(@RequestBody OpenPayH5CreateRequest request) {
        Assert.notNull(request, "request must not be null");
        String canonicalId = accessChecker.checkAppAccessAndGetCanonicalId(request.getAppId(), request.getAppType());
        Assert.notNull(request.getOrderAmount(), "orderAmount must not be null");
        Assert.hasText(request.getSubject(), "subject must not be blank");
        Assert.hasText(request.getOutTradeNo(), "outTradeNo must not be blank");

        String bizOrderNo = accessChecker.buildBizOrderNo(canonicalId, request.getOutTradeNo());
        assertNotPaidBeforeCreate(bizOrderNo);
        String ext = accessChecker.buildOpenExt(request.getAppId(), request.getAppType(), request.getNotifyUrl());

        H5OrderRpcCreateRequest h5Req = new H5OrderRpcCreateRequest();
        h5Req.setBizOrderNo(bizOrderNo);
        h5Req.setOrderAmount(request.getOrderAmount());
        h5Req.setSubject(request.getSubject());
        h5Req.setExt(ext);
        OrderCreateResponse orderResp = paymentRpcService.createOrderForH5(h5Req);
        openPayNotifyService.createTaskForBizOrderNo(RequestContext.get().getTenantId(), bizOrderNo);

        return ReqResult.success(OpenPayOrderCreateResponse.builder()
                .orderNo(bizOrderNo)
                .gatewayOrderNo(orderResp.getGatewayPaymentOrderNo())
                .build());
    }

    @PostMapping("/orders/h5/pay")
    @Operation(summary = "H5 调起渠道", description = "对已有 H5 订单调起微信/支付宝 H5 支付。三方后端转发场景请透传 clientIp（最终用户真实 IP，微信 H5 风控要求）")
    public ReqResult<OpenPayPayInvokeResponse> h5Pay(@RequestBody OpenPayH5PayRequest request,
                                                     HttpServletRequest httpRequest) {
        Assert.notNull(request, "request must not be null");
        String canonicalId = accessChecker.checkAppAccessAndGetCanonicalId(request.getAppId(), request.getAppType());
        String orderNo = requireOwnedOrderNo(canonicalId, request.getOrderNo());
        Assert.hasText(request.getPayChannel(), "payChannel must not be blank");
        Assert.hasText(request.getFrontNotifyUrl(), "frontNotifyUrl must not be blank");

        PayChannel payChannel = PayChannel.parse(request.getPayChannel());
        String clientIp = resolveClientIp(httpRequest, request.getClientIp());
        String frontNotifyUrl = validateFrontNotifyUrl(request.getFrontNotifyUrl().trim());

        H5TransactionRpcCreateRequest txReq = new H5TransactionRpcCreateRequest();
        txReq.setBizOrderNo(orderNo);
        txReq.setPayChannel(payChannel);
        txReq.setClientIp(clientIp);
        // 直传三方回跳地址：内部接口的 relay 包装（h5/front-notify 中转）依赖平台网关代理该域名的
        // /api 路径，三方自有域名下不可达；渠道支付完成后直接回跳三方页面，回跳参数由三方自行负责
        txReq.setFrontNotifyUrl(frontNotifyUrl);
        OrderAndTransactionCreateResponse txResp = paymentRpcService.createH5Transaction(txReq);

        OpenPayPayInvokeResponse resp = OpenPayPayInvokeResponse.builder()
                .orderNo(orderNo)
                .gatewayOrderNo(txResp.getGatewayPaymentOrderNo())
                .payChannel(txResp.getPayChannel() == null ? null : txResp.getPayChannel().name())
                .formHtml(txResp.getFormHtml())
                .redirectUrl(txResp.getRedirectUrl())
                .invokeType(txResp.getInvokeType() == null ? null : txResp.getInvokeType().name())
                .status(txResp.getStatus() == null ? null : txResp.getStatus().name())
                .build();
        return ReqResult.success(resp);
    }

    @PostMapping("/orders/app/create")
    @Operation(summary = "App 下单（接入方 App 内）", description = "创建 App 原生订单（不调渠道）。调起方式由接入方 App 自行实现（微信小程序 SDK / alipays 唤起）")
    public ReqResult<OpenPayOrderCreateResponse> createAppOrder(@RequestBody OpenPayAppCreateRequest request) {
        Assert.notNull(request, "request must not be null");
        String canonicalId = accessChecker.checkAppAccessAndGetCanonicalId(request.getAppId(), request.getAppType());
        Assert.notNull(request.getOrderAmount(), "orderAmount must not be null");
        Assert.hasText(request.getSubject(), "subject must not be blank");
        Assert.hasText(request.getOutTradeNo(), "outTradeNo must not be blank");

        String bizOrderNo = accessChecker.buildBizOrderNo(canonicalId, request.getOutTradeNo());
        assertNotPaidBeforeCreate(bizOrderNo);
        String ext = accessChecker.buildOpenExt(request.getAppId(), request.getAppType(), request.getNotifyUrl());

        AppOrderRpcCreateRequest appReq = new AppOrderRpcCreateRequest();
        appReq.setBizOrderNo(bizOrderNo);
        appReq.setOrderAmount(request.getOrderAmount());
        appReq.setSubject(request.getSubject());
        appReq.setExt(ext);
        OrderCreateResponse orderResp = paymentRpcService.createOrderForApp(appReq);
        openPayNotifyService.createTaskForBizOrderNo(RequestContext.get().getTenantId(), bizOrderNo);

        return ReqResult.success(OpenPayOrderCreateResponse.builder()
                .orderNo(bizOrderNo)
                .gatewayOrderNo(orderResp.getGatewayPaymentOrderNo())
                .build());
    }

    @PostMapping("/orders/app/pay")
    @Operation(summary = "App 调起渠道", description = "返回调起参数：微信（当前渠道）invokeType=REDIRECT_URL + redirectUrl(weixin://，需 App 具备拉起小程序能力)；支付宝 redirectUrl 或 QRCODE_FALLBACK 二维码")
    public ReqResult<OpenPayPayInvokeResponse> appPay(@RequestBody OpenPayAppPayRequest request,
                                                      HttpServletRequest httpRequest) {
        Assert.notNull(request, "request must not be null");
        String canonicalId = accessChecker.checkAppAccessAndGetCanonicalId(request.getAppId(), request.getAppType());
        String orderNo = requireOwnedOrderNo(canonicalId, request.getOrderNo());
        Assert.hasText(request.getPayChannel(), "payChannel must not be blank");

        PayChannel payChannel = PayChannel.parse(request.getPayChannel());
        String clientIp = resolveClientIp(httpRequest, null);

        AppTransactionRpcCreateRequest txReq = new AppTransactionRpcCreateRequest();
        txReq.setBizOrderNo(orderNo);
        txReq.setPayChannel(payChannel);
        txReq.setClientIp(clientIp);
        txReq.setPayClientScene(PayClientScene.NATIVE_APP);
        OrderAndTransactionCreateResponse txResp = paymentRpcService.createAppTransaction(txReq);

        OpenPayPayInvokeResponse resp = OpenPayPayInvokeResponse.builder()
                .orderNo(orderNo)
                .gatewayOrderNo(txResp.getGatewayPaymentOrderNo())
                .payChannel(txResp.getPayChannel() == null ? null : txResp.getPayChannel().name())
                .wxPayParams(txResp.getWxPayParams())
                .redirectUrl(txResp.getRedirectUrl())
                .alipayTradeNo(txResp.getAlipayTradeNo())
                .invokeType(txResp.getInvokeType() == null ? null : txResp.getInvokeType().name())
                .qrCodeContent(txResp.getQrCodeContent())
                .status(txResp.getStatus() == null ? null : txResp.getStatus().name())
                .build();
        return ReqResult.success(resp);
    }

    @PostMapping("/orders/status")
    @Operation(summary = "查单（双键）", description = "gatewayOrderNo 与 outTradeNo 二选一；会同步渠道。支付成功判据只有 status=PAID——\"用户回来\"不等于\"已支付\"")
    public ReqResult<OpenPayStatusResponse> queryStatus(@RequestBody OpenPayStatusRequest request) {
        Assert.notNull(request, "request must not be null");
        String canonicalId = accessChecker.checkAppAccessAndGetCanonicalId(request.getAppId(), request.getAppType());
        boolean byGateway = StringUtils.hasText(request.getGatewayOrderNo());
        boolean byOutTrade = StringUtils.hasText(request.getOutTradeNo());
        if (byGateway == byOutTrade) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty,
                    "gatewayOrderNo or outTradeNo (exactly one)");
        }

        long tenantId = RequestContext.get().getTenantId();
        PayOrderModel order;
        if (byGateway) {
            order = payOrderRepository.findByTenantIdAndGatewayPaymentOrderNo(tenantId, request.getGatewayOrderNo().trim())
                    .orElse(null);
        } else {
            String bizOrderNo = accessChecker.buildBizOrderNo(canonicalId, request.getOutTradeNo());
            order = payOrderRepository.findByTenantIdAndBizOrderNo(tenantId, bizOrderNo).orElse(null);
        }
        accessChecker.checkOrderOwnership(order, canonicalId);
        if (!StringUtils.hasText(order.getGatewayPaymentOrderNo())) {
            // 网关建单失败/超时：订单行存在但网关单号未落——透传本地终态（FAILED/CLOSED）而非一律 INIT
            String localStatus = order.getGatewayOrderStatus() != null ? order.getGatewayOrderStatus() : "INIT";
            return ReqResult.success(OpenPayStatusResponse.builder()
                    .orderNo(order.getBizOrderNo())
                    .status(localStatus)
                    .build());
        }

        // 渠道同步降频：网关终态（PAID/FAILED/CLOSED）不再打渠道；仅 PENDING 时同步（防三方高频轮询打爆渠道）
        boolean isTerminal = order.getGatewayOrderStatus() != null
                && !"PENDING".equals(order.getGatewayOrderStatus())
                && !"INIT".equals(order.getGatewayOrderStatus())
                && !"PROCESSING".equals(order.getGatewayOrderStatus());
        PaymentStatusQueryRequest statusReq = new PaymentStatusQueryRequest();
        statusReq.setGatewayPaymentOrderNo(order.getGatewayPaymentOrderNo());
        statusReq.setSyncFromChannel(!isTerminal);
        PaymentStatusQueryResponse statusResp = paymentRpcService.queryStatus(statusReq);

        return ReqResult.success(OpenPayStatusResponse.builder()
                .orderNo(order.getBizOrderNo())
                .gatewayOrderNo(order.getGatewayPaymentOrderNo())
                .status(statusResp.getStatus() == null ? null : statusResp.getStatus().name())
                .payChannel(statusResp.getPayChannel() == null ? null : statusResp.getPayChannel().name())
                .payMode(statusResp.getPayMode() == null ? null : statusResp.getPayMode().name())
                .orderAmount(statusResp.getOrderAmount())
                .paidAt(statusResp.getPaidAt())
                .build());
    }

    /**
     * 按业务单号定位订单并校验归属（h5/pay、app/pay 的 orderNo 入参）。
     * 单号不存在与不属于本应用同按"订单不存在"拒绝。
     */
    private String requireOwnedOrderNo(String appId, String orderNo) {
        Assert.hasText(orderNo, "orderNo must not be blank");
        long tenantId = RequestContext.get().getTenantId();
        PayOrderModel order = payOrderRepository.findByTenantIdAndBizOrderNo(tenantId, orderNo.trim()).orElse(null);
        accessChecker.checkOrderOwnership(order, appId);
        return order.getBizOrderNo();
    }

    /**
     * 用户 IP：透传优先（三方后端转发场景，取其前端请求的真实 IP），缺省取请求来源。
     * 与内部接口不同，开放接口调用方是三方服务端，request.getRemoteAddr() 拿到的是三方服务器 IP。
     */
    private String resolveClientIp(HttpServletRequest request, String forwardedClientIp) {
        if (StringUtils.hasText(forwardedClientIp)) {
            return forwardedClientIp.trim();
        }
        String ip = request.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(ip)) {
            int comma = ip.indexOf(',');
            return comma > 0 ? ip.substring(0, comma).trim() : ip.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 已支付订单不可重建：同一 outTradeNo 跨端点重建（cashier→h5/app）会走
     * recreateForSwitchedPayMode 清空网关单号——PAID 后重建=双收款，在此硬挡。
     */
    private void assertNotPaidBeforeCreate(String bizOrderNo) {
        PayOrderModel existing = payOrderRepository
                .findByTenantIdAndBizOrderNo(RequestContext.get().getTenantId(), bizOrderNo).orElse(null);
        if (existing != null && "PAID".equals(existing.getGatewayOrderStatus())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_open_out_trade_no_paid);
        }
    }

    /** frontNotifyUrl 必须是 http(s) 绝对地址（开放接口的回跳指向三方自己的域名） */
    private String validateFrontNotifyUrl(String url) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty,
                    "frontNotifyUrl must be an absolute http(s) URL");
        }
        try {
            URI.create(url).toURL();
            return url;
        } catch (Exception e) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty,
                    "frontNotifyUrl is malformed");
        }
    }
}
