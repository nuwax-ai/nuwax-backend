package com.xspaceagi.agent.core.infra.speech.vendor.dashscope;

public final class DashScopeEndpoint {

    private DashScopeEndpoint() {
    }

    public static String resolve(String workspaceId, String region, String endpointOverride) {
        if (endpointOverride != null && !endpointOverride.isBlank()) {
            return endpointOverride.trim();
        }
        DashScopeCredentials.requireWorkspaceId(workspaceId);
        String regionId = region == null || region.isBlank() ? "cn-beijing" : region.trim();
        return "wss://" + workspaceId.trim() + "." + regionId + ".maas.aliyuncs.com/api-ws/v1/inference";
    }
}
