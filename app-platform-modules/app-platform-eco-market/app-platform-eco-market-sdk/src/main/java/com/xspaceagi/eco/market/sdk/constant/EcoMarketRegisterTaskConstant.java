package com.xspaceagi.eco.market.sdk.constant;

/**
 * 生态市场客户端注册调度任务常量
 */
public final class EcoMarketRegisterTaskConstant {

    public static final String BEAN_ID = "ecoMarketClientRegisterTaskService";

    private EcoMarketRegisterTaskConstant() {
    }

    public static String taskIdForTenant(Long tenantId) {
        return "eco-market:register:" + tenantId;
    }
}
