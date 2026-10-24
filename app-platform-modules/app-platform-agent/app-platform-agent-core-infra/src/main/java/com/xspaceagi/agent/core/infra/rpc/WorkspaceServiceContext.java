package com.xspaceagi.agent.core.infra.rpc;

import lombok.Getter;

/**
 * 一次工作空间 RPC 调用所属的项目上下文：项目类型 + 应用ID（appId）
 */
@Getter
public class WorkspaceServiceContext {

    private final WorkspaceServiceType serviceType;

    private final String appId;

    /** 文件访问工作目录（会话的 fileWorkspacePath；注意与 agent 工作目录 agentWorkspacePath 是两个概念） */
    private final String fileWorkspacePath;

    private WorkspaceServiceContext(WorkspaceServiceType serviceType, String appId, String fileWorkspacePath) {
        this.serviceType = serviceType;
        this.appId = appId;
        this.fileWorkspacePath = fileWorkspacePath;
    }

    public static WorkspaceServiceContext of(WorkspaceServiceType serviceType, String appId) {
        return new WorkspaceServiceContext(serviceType, appId, null);
    }

    public static WorkspaceServiceContext of(WorkspaceServiceType serviceType, String appId, String fileWorkspacePath) {
        return new WorkspaceServiceContext(serviceType, appId, fileWorkspacePath);
    }

    public static WorkspaceServiceContext general() {
        return new WorkspaceServiceContext(WorkspaceServiceType.TASK_AGENT, null, null);
    }

    public boolean isUserApp() {
        return serviceType == WorkspaceServiceType.USERAPP;
    }

    public boolean isNormalProject() {
        return serviceType == WorkspaceServiceType.NORMAL_PROJECT;
    }

    public boolean hasAppId() {
        return appId != null && !appId.isBlank();
    }

    public boolean hasFileWorkspacePath() {
        return fileWorkspacePath != null && !fileWorkspacePath.isBlank();
    }
}
