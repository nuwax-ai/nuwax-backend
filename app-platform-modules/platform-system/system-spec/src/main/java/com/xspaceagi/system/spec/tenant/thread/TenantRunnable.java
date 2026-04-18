package com.xspaceagi.system.spec.tenant.thread;

import com.xspaceagi.system.spec.common.RequestContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.Map;
import java.util.Objects;

@Slf4j
public class TenantRunnable implements Runnable {

    private Long tenantId;
    private final Runnable runnable;
    private long tid;
    private final Map<String, String> mdcContext;
    private final RequestContext<?> requestContext; // 捕获当前线程的 RequestContext

    public TenantRunnable(Runnable runnable) {
        this.runnable = runnable;
        tid = Thread.currentThread().getId();
        this.mdcContext = MDC.getCopyOfContextMap();
        this.requestContext = RequestContext.get(); // 捕获当前线程的 RequestContext

        var threadTenantId = requestContext != null ? requestContext.getTenantId() : null;
        if (Objects.nonNull(threadTenantId)) {
            this.tenantId = threadTenantId;
        }
    }

    public TenantRunnable(Long tenantId, Runnable runnable) {
        if (Objects.isNull(tenantId)) {
            throw new IllegalArgumentException("tenantId is blank; please set it");
        }

        this.tenantId = tenantId;
        this.runnable = runnable;
        tid = Thread.currentThread().getId();
        this.mdcContext = MDC.getCopyOfContextMap();
        this.requestContext = RequestContext.get(); // 捕获当前线程的 RequestContext
    }

    private void runInCurrentThread() {
        Map<String, String> oldContext = MDC.getCopyOfContextMap();
        RequestContext<?> oldRequestContext = RequestContext.get(); // 保存原有的 RequestContext
        try {
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            if (requestContext != null) {
                RequestContext.set(requestContext); // 设置捕获的 RequestContext
            } else if (Objects.nonNull(this.tenantId)) {
                RequestContext.setThreadTenantId(this.tenantId);
            }
            this.runnable.run();
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

    private void runInOtherThread() {
        Map<String, String> oldContext = MDC.getCopyOfContextMap();
        RequestContext<?> oldRequestContext = RequestContext.get(); // 保存原有的 RequestContext
        try {
            if (mdcContext != null) {
                MDC.setContextMap(mdcContext);
            }
            if (requestContext != null) {
                RequestContext.set(requestContext); // 设置捕获的 RequestContext
            } else if (Objects.nonNull(this.tenantId)) {
                RequestContext.setThreadTenantId(this.tenantId);
            }
            this.runnable.run();
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
    public void run() {
        long currentTid = Thread.currentThread().getId();
        if (tid == currentTid) {
            runInCurrentThread();
        } else {
            runInOtherThread();
        }
    }

}
