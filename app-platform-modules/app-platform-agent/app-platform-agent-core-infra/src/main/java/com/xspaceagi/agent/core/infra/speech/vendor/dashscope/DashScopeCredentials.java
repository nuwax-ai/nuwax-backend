package com.xspaceagi.agent.core.infra.speech.vendor.dashscope;

public final class DashScopeCredentials {

    private DashScopeCredentials() {
    }

    public static void requireApiKey(String apiKey) {
        if (apiKey != null && !apiKey.isBlank()) {
            return;
        }
        throw new IllegalStateException("DashScope requires vox.*.dashscope.api-key");
    }

    public static void requireWorkspaceId(String workspaceId) {
        if (workspaceId != null && !workspaceId.isBlank()) {
            return;
        }
        throw new IllegalStateException("DashScope requires vox.*.dashscope.workspace-id");
    }
}
