package com.xspaceagi.pay.spec.gateway;

/** 可选注入 {@link PayGatewayOutboundCacheEvictor} 时的空安全调用。 */
public final class PayGatewayOutboundCacheEvictSupport {

    private PayGatewayOutboundCacheEvictSupport() {}

    public static void evictIfPresent(PayGatewayOutboundCacheEvictor evictor, Long tenantId) {
        if (evictor != null && tenantId != null) {
            evictor.evict(tenantId);
        }
    }
}
