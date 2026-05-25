package com.xspaceagi.pay.application.support;

import com.xspaceagi.pay.infra.gateway.utils.PayGatewayExceptionMapper;
import com.xspaceagi.pay.infra.gateway.utils.PayGatewayOutboundResolver;
import com.xspaceagi.pay.spec.exception.PayGatewayClientException;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutbound;
import com.xspaceagi.pay.spec.gateway.PayGatewayOutboundCacheEvictor;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 应用层统一解析 {@link PayGatewayOutbound} 并调用支付网关；{@link #invokeCached} 供轮询等高频场景减少配置查询。
 */
@Component
@RequiredArgsConstructor
public class PayGatewayOutboundExecutor {

    private final PayGatewayOutboundResolver payGatewayOutboundResolver;
    private final PayGatewayOutboundCacheEvictor payGatewayOutboundCacheEvictor;

    public PayGatewayOutbound resolve(long tenantId) {
        return payGatewayOutboundResolver.resolve(tenantId);
    }

    public PayGatewayOutbound resolveCached(long tenantId) {
        return payGatewayOutboundResolver.resolveCached(tenantId);
    }

    public void evictCached(long tenantId) {
        payGatewayOutboundCacheEvictor.evict(tenantId);
    }

    /** 解析凭证后执行网关调用；{@link PayGatewayClientException} 转为 {@link com.xspaceagi.system.spec.exception.BizException}。 */
    public <T> T invoke(long tenantId, Function<PayGatewayOutbound, T> action) {
        try {
            return action.apply(resolve(tenantId));
        } catch (PayGatewayClientException e) {
            throw PayGatewayExceptionMapper.toBizException(e);
        }
    }

    /** 使用短 TTL 缓存解析凭证，其余同 {@link #invoke(long, Function)}。 */
    public <T> T invokeCached(long tenantId, Function<PayGatewayOutbound, T> action) {
        try {
            return action.apply(resolveCached(tenantId));
        } catch (PayGatewayClientException e) {
            throw PayGatewayExceptionMapper.toBizException(e);
        }
    }
}
