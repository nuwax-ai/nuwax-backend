package com.xspaceagi.pay.spec.gateway;

/**
 * 租户支付网关出站凭证缓存失效（如 {@code paymentGateway}、client 密钥变更后调用）。
 */
public interface PayGatewayOutboundCacheEvictor {

    void evict(long tenantId);
}
