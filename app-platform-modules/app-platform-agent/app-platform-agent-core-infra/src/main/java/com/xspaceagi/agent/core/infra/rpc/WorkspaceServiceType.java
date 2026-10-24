package com.xspaceagi.agent.core.infra.rpc;

import com.xspaceagi.system.spec.enums.ProjectTypeEnum;

/**
 * 工作空间所属的项目类型，file server 按此类型定位工作空间目录。
 */
public enum WorkspaceServiceType {

    USERAPP("userApp", "userapp"),

    PAGEAPP("pageApp", "web-agent-runner"),

    NORMAL_PROJECT("normalProject", "computer-normal-project"),

    TASK_AGENT("taskAgent", "computer-agent-runner");

    /** 工作空间类型值：file server 接口的 workspaceType 参数与 x-workspace-type header（工作空间定位）；git 接口兼作路由参数 */
    private final String workspaceType;

    /** 容器运行时类型；下游沙箱的 serviceType / x-service-type / service_type 参数统一取此值 */
    private final String containerType;

    WorkspaceServiceType(String workspaceType, String containerType) {
        this.workspaceType = workspaceType;
        this.containerType = containerType;
    }

    public String getWorkspaceType() {
        return workspaceType;
    }

    public String getContainerType() {
        return containerType;
    }

    public static WorkspaceServiceType fromDevTargetType(String devTargetType) {
        if (ProjectTypeEnum.UserApp.name().equals(devTargetType)) {
            return USERAPP;
        }
        if (ProjectTypeEnum.PageApp.name().equals(devTargetType)) {
            return PAGEAPP;
        }
        if (ProjectTypeEnum.NormalProject.name().equals(devTargetType)) {
            return NORMAL_PROJECT;
        }
        return TASK_AGENT;
    }
}
