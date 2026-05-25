package com.xspaceagi.pay.infra.gateway.utils;

import com.xspaceagi.pay.spec.gateway.PayGatewayOutboundCacheEvictor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PayGatewayOutboundCacheEvictorImpl implements PayGatewayOutboundCacheEvictor {

    private final PayGatewayOutboundCache payGatewayOutboundCache;

    @Override
    public void evict(long tenantId) {
        payGatewayOutboundCache.invalidate(tenantId);
    }
}
