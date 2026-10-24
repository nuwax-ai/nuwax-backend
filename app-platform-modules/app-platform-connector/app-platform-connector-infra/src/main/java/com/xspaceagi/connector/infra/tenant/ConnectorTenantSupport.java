package com.xspaceagi.connector.infra.tenant;

import com.xspaceagi.system.spec.tenant.thread.TenantSqlContext;

import java.util.function.Supplier;

/**
 * 连接器模块租户拦截豁免工具。
 * <p>
 * 连接器 7 张表中：credential/oauth_config/oauth_state 无租户列（全局保险库，仅经
 * connection 间接访问），action 目录为跨租户共享，provider/connection/run_log 的
 * 可见性规则（官方 space_id=0 + 空间私有）无法用单一租户等值条件表达。
 * 因此模块内所有 SQL 统一豁免租户拦截器，改由 repository 层显式条件过滤——
 * 每个 repository 方法必须自带租户/用户/空间谓词（设计文档 §4.8 隔离三维度）。
 */
public final class ConnectorTenantSupport {

    private ConnectorTenantSupport() {
    }

    /**
     * 在豁免租户过滤的上下文中执行查询/写入（支持嵌套，恢复原状态）。
     */
    public static <T> T withoutTenantFilter(Supplier<T> supplier) {
        TenantSqlContext context = TenantSqlContext.get();
        boolean previous = context.isIgnoreCheck();
        context.setIgnoreCheck(true);
        try {
            return supplier.get();
        } finally {
            context.setIgnoreCheck(previous);
        }
    }

    public static void withoutTenantFilter(Runnable runnable) {
        withoutTenantFilter(() -> {
            runnable.run();
            return null;
        });
    }
}
