package com.xspaceagi.pay.web.controller.admin;

import com.xspaceagi.pay.application.service.PaymentConfigApplicationService;
import com.xspaceagi.pay.sdk.dto.PayConfigResponse;
import com.xspaceagi.pay.spec.dto.PayGatewayConnectivityResponse;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.xspaceagi.system.spec.enums.ResourceEnum.PAY_EARNINGS_QUERY;

@Slf4j
@RestController
@RequestMapping("/api/system/pay/config")
@Tag(name = "支付-配置")
public class PayConfigAdminController {

    @Resource
    private PaymentConfigApplicationService paymentConfigAppService;

    @RequireResource(PAY_EARNINGS_QUERY)
    @PostMapping("/query")
    @Operation(summary = "查询支付配置", description = "查询当前租户在支付网关侧的费率等配置；租户取自登录上下文")
    public ReqResult<PayConfigResponse> query() {
        Long tenantId = RequestContext.get() == null ? null : RequestContext.get().getTenantId();
        Assert.notNull(tenantId, "tenantId must be non-null");
        log.info("api /pay/config/query tenantId={}", tenantId);
        return ReqResult.success(paymentConfigAppService.queryPayConfig(tenantId));
    }

    @RequireResource(PAY_EARNINGS_QUERY)
    @PostMapping("/check-connectivity")
    @Operation(
            summary = "检测支付网关联通性",
            description = "请求当前租户配置的支付网关 GET /health；成功返回网关时间戳与往返耗时")
    public ReqResult<PayGatewayConnectivityResponse> checkConnectivity() {
        Long tenantId = RequestContext.get() == null ? null : RequestContext.get().getTenantId();
        Assert.notNull(tenantId, "tenantId must be non-null");
        log.info("api /pay/config/check-connectivity tenantId={}", tenantId);
        PayGatewayConnectivityResponse resp = paymentConfigAppService.checkGatewayConnectivity(tenantId);
        return ReqResult.success(resp);
    }
}
