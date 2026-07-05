package com.xspaceagi.pay.web.controller.user;

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
import com.xspaceagi.pay.sdk.support.PayOrderExtKeys;
import com.xspaceagi.pay.spec.dto.GeneralProjectAppOrderCreateRequest;
import com.xspaceagi.pay.spec.dto.GeneralProjectAppOrderCreateResponse;
import com.xspaceagi.pay.spec.dto.GeneralProjectAppPayRequest;
import com.xspaceagi.pay.spec.dto.GeneralProjectAppPayResponse;
import com.xspaceagi.pay.spec.dto.GeneralProjectCashierRequest;
import com.xspaceagi.pay.spec.dto.GeneralProjectCashierResponse;
import com.xspaceagi.pay.spec.dto.GeneralProjectH5OrderCreateRequest;
import com.xspaceagi.pay.spec.dto.GeneralProjectH5OrderCreateResponse;
import com.xspaceagi.pay.spec.dto.GeneralProjectH5PayRequest;
import com.xspaceagi.pay.spec.dto.GeneralProjectH5PayResponse;
import com.xspaceagi.pay.spec.dto.GeneralProjectPaymentStatusRequest;
import com.xspaceagi.pay.spec.dto.GeneralProjectPaymentStatusResponse;
import com.xspaceagi.pay.spec.support.PayAppNativeInAppGuard;
import com.xspaceagi.pay.spec.support.PayH5InAppGuard;
import com.xspaceagi.pay.sdk.support.PayAppWebViewDetector;
import com.xspaceagi.custompage.sdk.dto.CustomPageDto;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.alibaba.fastjson2.JSON;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 通用项目支付接入（用户端）。
 * 面向项目沙箱/线上前端，无需登录态，projectId 必传（从 DEV_PROJECT_ID 获取）。
 */
@Slf4j
@RestController
@RequestMapping("/api/pay/general")
@Tag(name = "支付-通用项目（用户端）")
public class PayGeneralProjectController {

    @Resource
    private IPaymentRpcService paymentRpcService;

    @Resource
    private ICustomPageRpcService iCustomPageRpcService;

    @PostMapping("/cashier")
    @Operation(summary = "收银台模式", description = "创建扫码支付订单并生成收银台跳转地址")
    public ReqResult<GeneralProjectCashierResponse> cashier(@RequestBody GeneralProjectCashierRequest request) {
        Assert.notNull(request, "request must not be null");
        Assert.hasText(request.getProjectId(), "projectId must not be blank");
        Assert.notNull(request.getOrderAmount(), "orderAmount must not be null");
        Assert.hasText(request.getSubject(), "subject must not be blank");

        String bizOrderNo = resolveBizOrderNo(request.getBizOrderNo(), request.getProjectId());
        String ext = buildGeneralProjectExt(request.getProjectId());

        ScanOrderCreateRequest scanReq = new ScanOrderCreateRequest();
        scanReq.setBizOrderNo(bizOrderNo);
        scanReq.setOrderAmount(request.getOrderAmount());
        scanReq.setSubject(request.getSubject());
        scanReq.setExt(ext);
        OrderCreateResponse orderResp = paymentRpcService.createOrderForScan(scanReq);

        CashierSessionCreateRequest cashierReq = new CashierSessionCreateRequest();
        cashierReq.setGatewayPaymentOrderNo(orderResp.getGatewayPaymentOrderNo());
        cashierReq.setOrderAmount(request.getOrderAmount());
        cashierReq.setSubject(request.getSubject());
        cashierReq.setBizRedirectUrl(request.getFrontNotifyUrl());
        CashierSessionCreateResponse cashierResp = paymentRpcService.createCashierSession(cashierReq);

        GeneralProjectCashierResponse resp = GeneralProjectCashierResponse.builder()
                .orderNo(bizOrderNo)
                .gatewayOrderNo(orderResp.getGatewayPaymentOrderNo())
                .cashierUrl(cashierResp.getCashierUrl())
                .build();
        return ReqResult.success(resp);
    }

    @PostMapping("/app/create-order")
    @Operation(summary = "App原生-创建订单", description = "创建 App 原生支付订单（不调渠道）。仅限 App WebView 内调用")
    public ReqResult<GeneralProjectAppOrderCreateResponse> createAppOrder(@RequestBody GeneralProjectAppOrderCreateRequest request,
                                                                          HttpServletRequest httpRequest) {
        assertAppNativePayRequired(httpRequest);
        Assert.notNull(request, "request must not be null");
        Assert.hasText(request.getProjectId(), "projectId must not be blank");
        Assert.notNull(request.getOrderAmount(), "orderAmount must not be null");
        Assert.hasText(request.getSubject(), "subject must not be blank");

        String bizOrderNo = resolveBizOrderNo(request.getBizOrderNo(), request.getProjectId());
        String ext = buildGeneralProjectExt(request.getProjectId());

        AppOrderRpcCreateRequest appReq = new AppOrderRpcCreateRequest();
        appReq.setBizOrderNo(bizOrderNo);
        appReq.setOrderAmount(request.getOrderAmount());
        appReq.setSubject(request.getSubject());
        appReq.setExt(ext);
        OrderCreateResponse orderResp = paymentRpcService.createOrderForApp(appReq);

        GeneralProjectAppOrderCreateResponse resp = GeneralProjectAppOrderCreateResponse.builder()
                .orderNo(bizOrderNo)
                .gatewayOrderNo(orderResp.getGatewayPaymentOrderNo())
                .build();
        return ReqResult.success(resp);
    }

    @PostMapping("/app/pay")
    @Operation(summary = "App原生-调起渠道支付", description = "仅限 App WebView。微信（当前渠道）：invokeType=REDIRECT_URL + redirectUrl(weixin://...)，前端解析 query 后 wx.miniapp.launchMiniProgram；"
            + "支付宝：invokeType=REDIRECT_URL + redirectUrl(https/alipays) 或 QRCODE_FALLBACK + qrCodeContent")
    public ReqResult<GeneralProjectAppPayResponse> appPay(@RequestBody GeneralProjectAppPayRequest request,
                                                          HttpServletRequest httpRequest) {
        assertAppNativePayRequired(httpRequest);
        Assert.notNull(request, "request must not be null");
        Assert.hasText(request.getOrderNo(), "orderNo must not be blank");
        Assert.hasText(request.getPayChannel(), "payChannel must not be blank");

        PayChannel payChannel = PayChannel.parse(request.getPayChannel());
        String clientIp = resolveClientIp(httpRequest);

        AppTransactionRpcCreateRequest txReq = new AppTransactionRpcCreateRequest();
        txReq.setBizOrderNo(request.getOrderNo());
        txReq.setPayChannel(payChannel);
        txReq.setClientIp(clientIp);
        txReq.setPayClientScene(PayClientScene.NATIVE_APP);
        OrderAndTransactionCreateResponse txResp = paymentRpcService.createAppTransaction(txReq);

        GeneralProjectAppPayResponse resp = GeneralProjectAppPayResponse.builder()
                .orderNo(request.getOrderNo())
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

    @PostMapping("/h5/create-order")
    @Operation(summary = "H5支付-创建订单", description = "创建 H5 支付订单（不调渠道），返回订单号。App WebView 内禁止，请使用收银台或 App 原生支付")
    public ReqResult<GeneralProjectH5OrderCreateResponse> createH5Order(@RequestBody GeneralProjectH5OrderCreateRequest request,
                                                                        HttpServletRequest httpRequest) {
        assertH5PayAllowed(httpRequest);
        Assert.notNull(request, "request must not be null");
        Assert.hasText(request.getProjectId(), "projectId must not be blank");
        Assert.notNull(request.getOrderAmount(), "orderAmount must not be null");
        Assert.hasText(request.getSubject(), "subject must not be blank");

        String bizOrderNo = resolveBizOrderNo(request.getBizOrderNo(), request.getProjectId());
        String ext = buildGeneralProjectExt(request.getProjectId());

        H5OrderRpcCreateRequest h5Req = new H5OrderRpcCreateRequest();
        h5Req.setBizOrderNo(bizOrderNo);
        h5Req.setOrderAmount(request.getOrderAmount());
        h5Req.setSubject(request.getSubject());
        h5Req.setExt(ext);
        OrderCreateResponse orderResp = paymentRpcService.createOrderForH5(h5Req);

        GeneralProjectH5OrderCreateResponse resp = GeneralProjectH5OrderCreateResponse.builder()
                .orderNo(bizOrderNo)
                .gatewayOrderNo(orderResp.getGatewayPaymentOrderNo())
                .build();
        return ReqResult.success(resp);
    }

    @PostMapping("/h5/pay")
    @Operation(summary = "H5支付-调起渠道支付", description = "对已有 H5 订单调起微信/支付宝 H5 支付。App WebView 内禁止")
    public ReqResult<GeneralProjectH5PayResponse> h5Pay(@RequestBody GeneralProjectH5PayRequest request,
                                              HttpServletRequest httpRequest) {
        assertH5PayAllowed(httpRequest);
        Assert.notNull(request, "request must not be null");
        Assert.hasText(request.getOrderNo(), "orderNo must not be blank");
        Assert.hasText(request.getPayChannel(), "payChannel must not be blank");
        Assert.hasText(request.getFrontNotifyUrl(), "frontNotifyUrl must not be blank");

        PayChannel payChannel = PayChannel.parse(request.getPayChannel());
        String clientIp = resolveClientIp(httpRequest);

        String frontNotifyUrl = validateFrontNotifyUrl(request.getFrontNotifyUrl().trim());

        H5TransactionRpcCreateRequest txReq = new H5TransactionRpcCreateRequest();
        txReq.setBizOrderNo(request.getOrderNo());
        txReq.setPayChannel(payChannel);
        txReq.setClientIp(clientIp);
        txReq.setFrontNotifyUrl(buildChannelFrontNotifyUrl(frontNotifyUrl));
        OrderAndTransactionCreateResponse txResp = paymentRpcService.createH5Transaction(txReq);

        GeneralProjectH5PayResponse resp = GeneralProjectH5PayResponse.builder()
                .orderNo(request.getOrderNo())
                .gatewayOrderNo(txResp.getGatewayPaymentOrderNo())
                .formHtml(txResp.getFormHtml())
                .redirectUrl(txResp.getRedirectUrl())
                .invokeType(txResp.getInvokeType() == null ? null : txResp.getInvokeType().name())
                .status(txResp.getStatus() == null ? null : txResp.getStatus().name())
                .build();
        return ReqResult.success(resp);
    }

    @RequestMapping(value = "/h5/front-notify", method = {RequestMethod.GET, RequestMethod.POST})
    @Operation(
            summary = "H5 渠道前台回跳中转",
            description = "接收渠道前台回调（GET/POST），统一 302 到前端回跳地址")
    public void handleH5FrontNotify(@RequestParam String returnUrl, HttpServletResponse response) {
        String target = validateFrontNotifyUrl(returnUrl.trim());
        response.setStatus(HttpServletResponse.SC_FOUND);
        response.setHeader("Location", target);
    }

    @PostMapping("/status")
    @Operation(summary = "查询支付状态", description = "按网关支付订单号查询支付结果，会同步渠道")
    public ReqResult<GeneralProjectPaymentStatusResponse> queryStatus(@RequestBody GeneralProjectPaymentStatusRequest request) {
        Assert.notNull(request, "request must not be null");
        Assert.hasText(request.getGatewayOrderNo(), "gatewayOrderNo must not be blank");

        PaymentStatusQueryRequest statusReq = new PaymentStatusQueryRequest();
        statusReq.setGatewayPaymentOrderNo(request.getGatewayOrderNo());
        statusReq.setSyncFromChannel(Boolean.TRUE);
        PaymentStatusQueryResponse statusResp = paymentRpcService.queryStatus(statusReq);

        GeneralProjectPaymentStatusResponse resp = GeneralProjectPaymentStatusResponse.builder()
                .status(statusResp.getStatus() == null ? null : statusResp.getStatus().name())
                .payChannel(statusResp.getPayChannel() == null ? null : statusResp.getPayChannel().name())
                .payMode(statusResp.getPayMode() == null ? null : statusResp.getPayMode().name())
                .orderAmount(statusResp.getOrderAmount())
                .paidAt(statusResp.getPaidAt())
                .build();
        return ReqResult.success(resp);
    }

    /**
     * 构建通用项目 ext JSON，包含 generalProjectId 和 generalProjectUserId（项目创建者）。
     * 项目查不到或 creatorId 为 null 时不阻断支付（ext 中只有 projectId、无 userId）。
     */
    private String buildGeneralProjectExt(String projectId) {
        Long creatorId = null;
        try {
            CustomPageDto projectDto = iCustomPageRpcService.queryDetail(Long.parseLong(projectId.trim()));
            if (projectDto != null) {
                creatorId = projectDto.getCreatorId();
            }
        } catch (Exception e) {
            log.warn("[pay-general] queryDetail failed projectId={} msg={}", projectId, e.getMessage());
        }
        Map<String, Object> ext = new HashMap<>();
        ext.put(PayOrderExtKeys.GENERAL_PROJECT_ID, projectId);
        if (creatorId != null) {
            ext.put(PayOrderExtKeys.GENERAL_PROJECT_USER_ID, creatorId);
        }
        return JSON.toJSONString(ext);
    }

    /**
     * 解析业务订单号，projectId 必传：
     * <ul>
     *   <li>传了 bizOrderNo → GP1_{projectId}_{bizOrderNo}（幂等，同 projectId + bizOrderNo 复用）</li>
     *   <li>没传 bizOrderNo → GP2_{projectId}_{timestamp}_{random6}（自动生成，每次唯一）</li>
     * </ul>
     */
    private String resolveBizOrderNo(String bizOrderNo, String projectId) {
        String pid = projectId.trim();
        if (StringUtils.hasText(bizOrderNo)) {
            return "GP1_" + pid + "_" + bizOrderNo.trim();
        }
        long ts = System.currentTimeMillis();
        int rand = ThreadLocalRandom.current().nextInt(1_000_000);
        return "GP2_" + pid + "_" + ts + "_" + String.format("%06d", rand);
    }

    /**
     * 将前端回跳地址包装为渠道前台回调地址：支付完成后先回到平台后端，再 302 到前端目标页。
     * 与 Bill H5 一致，避免渠道直接把用户带回 SPA 并携带不可控 query 参数。
     */
    private static String buildChannelFrontNotifyUrl(String frontNotifyUrl) {
        URI frontendUri = URI.create(frontNotifyUrl);
        String origin = frontendUri.getScheme() + "://" + frontendUri.getAuthority();
        String encodedReturnUrl = URLEncoder.encode(frontNotifyUrl, StandardCharsets.UTF_8);
        return origin + "/api/pay/general/h5/front-notify?returnUrl=" + encodedReturnUrl;
    }

    private static String validateFrontNotifyUrl(String url) {
        try {
            URI uri = URI.create(url);
            String scheme = uri.getScheme();
            if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
                throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "frontNotifyUrl must start with http or https");
            }
            if (!StringUtils.hasText(uri.getHost())) {
                throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "Invalid frontNotifyUrl");
            }
            return url;
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "Invalid frontNotifyUrl");
        }
    }

    /** App WebView 内禁止 H5 支付（渠道违规），应使用 App 原生 SDK。 */
    private static void assertH5PayAllowed(HttpServletRequest httpRequest) {
        PayH5InAppGuard.assertH5PayAllowed(
                httpRequest.getHeader(PayAppWebViewDetector.HEADER_CLIENT_TYPE),
                httpRequest.getHeader("User-Agent"));
    }

    /** App 原生支付仅限 App WebView；手机浏览器请用 H5。 */
    private static void assertAppNativePayRequired(HttpServletRequest httpRequest) {
        PayAppNativeInAppGuard.assertAppNativePayRequired(
                httpRequest.getHeader(PayAppWebViewDetector.HEADER_CLIENT_TYPE),
                httpRequest.getHeader("User-Agent"));
    }

    /** 提取客户端 IP，优先 X-Forwarded-For 首段，兜底 getRemoteAddr */
    private String resolveClientIp(HttpServletRequest httpRequest) {
        String xff = httpRequest.getHeader("X-Forwarded-For");
        if (StringUtils.hasText(xff)) {
            String first = xff.split(",")[0].trim();
            if (StringUtils.hasText(first)) {
                return first;
            }
        }
        return httpRequest.getRemoteAddr();
    }
}
