package com.xspaceagi.pay.infra.gateway.utils;

import com.xspaceagi.eco.market.domain.model.EcoMarketClientSecretModel;
import com.xspaceagi.eco.market.domain.service.IEcoMarketClientSecretDomainService;
import com.xspaceagi.pay.spec.exception.PayGatewayClientException;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
@RequiredArgsConstructor
public class PayGatewayOutboundResolver {

    private final TenantConfigApplicationService tenantConfigApplicationService;
    private final IEcoMarketClientSecretDomainService ecoMarketClientSecretDomainService;
    private final PayGatewayOutboundCache payGatewayOutboundCache;

    public PayGatewayOutbound resolve(long tenantId) {
        return doResolve(tenantId);
    }

    /**
     * 带租户维度的短 TTL 缓存，供支付状态轮询等高频场景使用；过期与超容量条目由 Caffeine 自动清理。
     */
    public PayGatewayOutbound resolveCached(long tenantId) {
        return payGatewayOutboundCache.get(tenantId, this::doResolve);
    }

    private PayGatewayOutbound doResolve(long tenantId) {
        TenantConfigDto tenantConfig = resolveTenantConfig(tenantId);
        String baseUrl = tenantConfig != null ? tenantConfig.getPaymentGateway() : null;
        if (!StringUtils.hasText(baseUrl)) {
            throw new PayGatewayClientException("Payment gateway has not been configured");
        }
        EcoMarketClientSecretModel secret = ecoMarketClientSecretDomainService.queryByTenantId(tenantId);
        if (secret == null || !StringUtils.hasText(secret.getClientId()) || !StringUtils.hasText(secret.getClientSecret())) {
            throw new PayGatewayClientException("Client ID/Secret has not been configured");
        }
        baseUrl = baseUrl.trim();
        baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return new PayGatewayOutbound(baseUrl, secret.getClientId(), secret.getClientSecret());
    }

    private TenantConfigDto resolveTenantConfig(long tenantId) {
        RequestContext<?> ctx = RequestContext.get();
        if (ctx != null
                && Objects.equals(ctx.getTenantId(), tenantId)
                && ctx.getTenantConfig() instanceof TenantConfigDto dto) {
            return dto;
        }
        return tenantConfigApplicationService.getTenantConfig(tenantId);
    }
}
