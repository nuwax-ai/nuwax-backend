package com.xspaceagi.pay.infra.gateway.utils;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** 租户 {@link PayGatewayOutbound} 的 Caffeine 缓存（与 {@link PayGatewayOutboundResolver} 解耦，供失效 SPI 单独注入）。 */
@Component
public class PayGatewayOutboundCache {

    private static final Duration CACHE_TTL = Duration.ofMinutes(30);
    private static final int CACHE_MAXIMUM_SIZE = 10_000;

    private final Cache<Long, PayGatewayOutbound> cache =
            Caffeine.newBuilder().maximumSize(CACHE_MAXIMUM_SIZE).expireAfterWrite(CACHE_TTL).build();

    public PayGatewayOutbound get(long tenantId, Function<Long, PayGatewayOutbound> loader) {
        return cache.get(tenantId, loader);
    }

    public void invalidate(long tenantId) {
        cache.invalidate(tenantId);
    }
}
