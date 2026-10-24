package com.xspaceagi.userapp.sdk.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * 容器任务类型
 */
public enum UserAppTaskType {

    DEV_START("dev_start", "dev"),
    DEV_RESTART("dev_restart", "dev"),
    BUILD("build", "build");

    private final String value;

    /** 活跃任务守卫组（dev=开发容器，build=构建）：同组互斥、跨组并行 */
    private final String guardGroup;

    UserAppTaskType(String value, String guardGroup) {
        this.value = value;
        this.guardGroup = guardGroup;
    }

    public String getValue() {
        return value;
    }

    public String getGuardGroup() {
        return guardGroup;
    }

    /** 活跃槽位键：appId + 守卫组（写入 userapp_task.active_guard，依赖唯一键防同组并发） */
    public String guardKey(Long appId) {
        return appId + ":" + guardGroup;
    }

    public static UserAppTaskType fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        for (UserAppTaskType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null;
    }
}
