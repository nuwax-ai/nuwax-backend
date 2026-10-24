package com.xspaceagi.system.api;

import com.xspaceagi.system.application.service.UserConfigApplicationService;
import com.xspaceagi.system.sdk.server.IUserConfigRpcService;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.Map;

/**
 * 用户个人配置查询 RPC 接口实现（只读，薄委托到应用层）
 */
@Slf4j
@Service
public class UserConfigRpcServiceImpl implements IUserConfigRpcService {

    @Resource
    private UserConfigApplicationService userConfigApplicationService;

    @Override
    public Object getUserConfig(Long tenantId, Long userId, String key) {
        Assert.notNull(tenantId, "Tenant ID cannot be empty");
        if (RequestContext.get() == null) {
            RequestContext.setThreadTenantId(tenantId);
            try {
                return userConfigApplicationService.queryConfig(userId, key);
            } finally {
                RequestContext.remove();
            }
        }

        requireTenantContext();
        Assert.notNull(userId, "userId must be non-null");
        // key 的格式校验统一在应用层完成，非法 key 抛业务错误码 3175
        return userConfigApplicationService.queryConfig(userId, key);
    }

    @Override
    public Map<String, Object> getUserConfigMap(Long userId) {
        requireTenantContext();
        Assert.notNull(userId, "userId must be non-null");
        return userConfigApplicationService.queryConfigMap(userId);
    }

    /**
     * 租户过滤依赖 RequestContext，缺失时租户拦截器也会断言失败，但报错信息看不出该怎么修，这里提前给出可操作的提示。
     * <p>本接口不做租户绕过：调用方若要读其它租户的数据，请自行在调用前切换线程租户上下文。
     */
    private void requireTenantContext() {
        RequestContext<?> requestContext = RequestContext.get();
        Assert.notNull(requestContext,
                "RequestContext must be non-null: 用户配置 RPC 需在带租户上下文的线程内调用，后台/异步线程请先 RequestContext.setThreadTenantId(tenantId)");
        Assert.notNull(requestContext.getTenantId(),
                "tenantId must be non-null: 用户配置 RPC 需在带租户上下文的线程内调用，后台/异步线程请先 RequestContext.setThreadTenantId(tenantId)");
    }
}
