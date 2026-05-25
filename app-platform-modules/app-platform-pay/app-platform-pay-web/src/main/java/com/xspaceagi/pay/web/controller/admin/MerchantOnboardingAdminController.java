package com.xspaceagi.pay.web.controller.admin;

import com.xspaceagi.pay.application.service.MerchantOnboardingApplicationService;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingResponse;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingUpdateRequest;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingUpsertRequest;
import com.xspaceagi.pay.sdk.enums.MerchantOnboardingType;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.xspaceagi.system.spec.enums.ResourceEnum.PAY_EARNINGS_MODIFY;
import static com.xspaceagi.system.spec.enums.ResourceEnum.PAY_EARNINGS_QUERY;

@Slf4j
@RestController
@RequestMapping("/api/system/pay/merchant-onboarding")
@Tag(name = "支付-商户进件")
public class MerchantOnboardingAdminController {

    @Resource
    private MerchantOnboardingApplicationService merchantOnboardingAppService;

    @RequireResource(PAY_EARNINGS_MODIFY)
    @PostMapping("/add")
    @Operation(summary = "新增进件", description = "仅支持租户进件、用户进件；不支持平台进件。clientId 由服务端按登录租户凭证写入，请求体勿传")
    public ReqResult<MerchantOnboardingResponse> add(@Valid @RequestBody MerchantOnboardingUpsertRequest request) {
        Assert.notNull(request.getOnboardingType(), "onboardingType must be non-null");
        Assert.isTrue(request.getOnboardingType() != MerchantOnboardingType.PLATFORM, "agent 端不支持平台进件");
        Long tenantId = RequestContext.get() == null ? null : RequestContext.get().getTenantId();
        Assert.notNull(tenantId, "tenantId must be non-null");
        log.info("api /pay/merchant-onboarding/add type={}, tenantId={}", request.getOnboardingType(), tenantId);
        return ReqResult.success(merchantOnboardingAppService.add(request));
    }

    @RequireResource(PAY_EARNINGS_MODIFY)
    @PostMapping("/update")
    @Operation(summary = "更新进件", description = "仅支持租户进件、用户进件；不支持平台进件。clientId 由服务端按登录租户凭证写入，请求体勿传")
    public ReqResult<MerchantOnboardingResponse> update(@Valid @RequestBody MerchantOnboardingUpdateRequest request) {
        Assert.notNull(request.getId(), "id must be non-null");
        Assert.isTrue(request.getOnboardingType() != MerchantOnboardingType.PLATFORM, "agent 端不支持平台进件");
        Long tenantId = RequestContext.get() == null ? null : RequestContext.get().getTenantId();
        Assert.notNull(tenantId, "tenantId must be non-null");
        log.info("api /pay/merchant-onboarding/update id={}, tenantId={}", request.getId(), tenantId);
        return ReqResult.success(merchantOnboardingAppService.update(request));
    }

    @RequireResource(PAY_EARNINGS_QUERY)
    @PostMapping("/get-by-tenant-id")
    @Operation(summary = "按租户查询进件")
    public ReqResult<MerchantOnboardingResponse> getByTenantId() {
        Assert.notNull(RequestContext.get() != null ? RequestContext.get().getTenantId() : null, "tenantId must be non-null");
        return ReqResult.success(merchantOnboardingAppService.getByTenantId());
    }
}
