package com.xspaceagi.custompage.domain.util;

import java.util.function.Supplier;

import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;

/**
 * 后台线程执行 DB 操作前绑定租户 RequestContext
 */
public final class AgentProgressContextUtil {

    private AgentProgressContextUtil() {
    }

    public static void runWithUserContext(UserContext userContext, Runnable action) {
        callWithUserContext(userContext, () -> {
            action.run();
            return null;
        });
    }

    public static <T> T callWithUserContext(UserContext userContext, Supplier<T> supplier) {
        RequestContext<?> existing = RequestContext.get();
        boolean created = false;
        try {
            if (existing == null) {
                if (userContext == null || userContext.getTenantId() == null) {
                    throw new IllegalStateException("RequestContext and tenantId are both unavailable for DB access");
                }
                RequestContext<Object> requestContext = new RequestContext<>();
                requestContext.setTenantId(userContext.getTenantId());
                requestContext.setUserId(userContext.getUserId());
                requestContext.setUserContext(userContext);
                RequestContext.set(requestContext);
                created = true;
            }
            return supplier.get();
        } finally {
            if (created) {
                RequestContext.remove();
            }
        }
    }
}
