package com.xspaceagi.pay.application.service.impl;

import com.xspaceagi.pay.application.service.MerchantOnboardingApplicationService;
import com.xspaceagi.pay.application.support.PayGatewayOutboundExecutor;
import com.xspaceagi.pay.domain.gateway.MerchantOnboardingGateway;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingResponse;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingUpdateRequest;
import com.xspaceagi.pay.sdk.dto.MerchantOnboardingUpsertRequest;
import com.xspaceagi.pay.sdk.enums.MerchantOnboardingType;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
public class MerchantOnboardingApplicationServiceImpl implements MerchantOnboardingApplicationService {

    @Resource
    private MerchantOnboardingGateway payMerchantOnboardingGateway;

    @Resource
    private PayGatewayOutboundExecutor payGatewayOutboundExecutor;

    @Override
    public MerchantOnboardingResponse add(MerchantOnboardingUpsertRequest request) {
        rejectPlatformOnboarding(request);
        return payGatewayOutboundExecutor.invoke(resolveTenantId(), outbound -> {
            applyOpenApiClientId(outbound, request);
            return payMerchantOnboardingGateway.add(outbound, request);
        });
    }

    @Override
    public MerchantOnboardingResponse update(MerchantOnboardingUpdateRequest request) {
        rejectPlatformOnboarding(request);
        return payGatewayOutboundExecutor.invoke(resolveTenantId(), outbound -> {
            applyOpenApiClientId(outbound, request);
            return payMerchantOnboardingGateway.update(outbound, request);
        });
    }

    @Override
    public MerchantOnboardingResponse getByTenantId() {
        return payGatewayOutboundExecutor.invoke(
                resolveTenantId(), payMerchantOnboardingGateway::getByClientId);
    }

    private static void rejectPlatformOnboarding(MerchantOnboardingUpsertRequest request) {
        if (request != null && request.getOnboardingType() == MerchantOnboardingType.PLATFORM) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "agent 端不支持平台进件");
        }
    }

    private static long resolveTenantId() {
        RequestContext<?> ctx = RequestContext.get();
        if (ctx != null && ctx.getTenantId() != null) {
            return ctx.getTenantId();
        }
        throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "无法确定租户：请登录后重试");
    }

    /**
     * Open API 的 clientId 一律由当前 agent 租户凭证解析，忽略请求体中的 clientId。
     */
    private void applyOpenApiClientId(PayGatewayOutbound outbound, MerchantOnboardingUpsertRequest request) {
        String resolvedClientId = outbound.clientId();
        if (StringUtils.hasText(request.getClientId()) && !resolvedClientId.equals(request.getClientId().trim())) {
            log.warn(
                    "[merchant-onboarding] ignore clientId from request, use tenant credential requestClientId={} resolvedClientId={}",
                    request.getClientId(),
                    resolvedClientId);
        }
        request.setClientId(resolvedClientId);
    }
}
