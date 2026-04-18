package com.xspaceagi.system.spec.tenant.thread;

import com.xspaceagi.system.spec.common.RequestContext;
import org.slf4j.MDC;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;

public class TenantCallable<V> implements Callable<V> {

    private Long tenantId;
    private final Callable<V> callable;
    private long tid;
    private final Map<String, String> mdcContext;
    private final RequestContext<?> requestContext; // 捕获当前线程的 RequestContext

    public TenantCallable(Callable<V> callable) {
        this.callable = callable;
        tid = Thread.currentThread().getId();
        this.mdcContext = MDC.getCopyOfContextMap();
        this.requestContext = RequestContext.get(); // 捕获当前线程的 RequestContext

        var threadTenantId = requestContext != null ? requestContext.getTenantId() : null;
        if (Objects.nonNull(threadTenantId)) {
            this.tenantId = threadTenantId;
        }
    }

    public TenantCallable(Long tenantId, Callable<V> callable) {
        if (Objects.isNull(tenantId)) {
            throw new IllegalArgumentException("tenantId is null; please set it");
        }

        this.tenantId = tenantId;
        this.callable = callable;
        tid = Thread.currentThread().getId();
        this.mdcContext = MDC.getCopyOfContextMap();
        this.requestContext = RequestContext.get(); // 捕获当前线程的 RequestContext
    }

    private V callInCurrentThread() throws Exception {
        Map<String, String> oldContext = MDC.getCopyOfContextMap();
        RequestContext<?> oldRequestContext = RequestContext.get(); // 保存原有的 RequestContext
        try {
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            if (requestContext != null) {
                RequestContext.set(requestContext); // 设置捕获的 RequestContext
            }
            if (Objects.nonNull(this.tenantId)) {
                RequestContext.setThreadTenantId(this.tenantId);
            }
            return this.callable.call();
        } finally {
            if (oldContext != null) {
                MDC.setContextMap(oldContext);
            } else {
                MDC.clear();
            }
            if (oldRequestContext != null) {
                RequestContext.set(oldRequestContext); // 恢复原有的 RequestContext
            } else {
                RequestContext.remove();
            }
        }
    }

    private V callInOtherThread() throws Exception {
        Map<String, String> oldContext = MDC.getCopyOfContextMap();
        RequestContext<?> oldRequestContext = RequestContext.get(); // 保存原有的 RequestContext
        try {
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            if (requestContext != null) {
                RequestContext.set(requestContext); // 设置捕获的 RequestContext
            }
            if (Objects.nonNull(this.tenantId)) {
                RequestContext.setThreadTenantId(this.tenantId);
            }
            return callable.call();
        } finally {
            if (oldContext != null) {
                MDC.setContextMap(oldContext);
            } else {
                MDC.clear();
            }
            if (oldRequestContext != null) {
                RequestContext.set(oldRequestContext); // 恢复原有的 RequestContext
            } else {
                RequestContext.remove();
            }
        }
    }

    @Override
    public V call() throws Exception {
        long currentTid = Thread.currentThread().getId();
        if (tid == currentTid) {
            return callInCurrentThread();
        } else {
            return callInOtherThread();
        }
    }
}
