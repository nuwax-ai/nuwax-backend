package com.xspaceagi.userapp.sdk.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * 应用任务状态
 */
public enum UserAppTaskStatus {

    /** 已受理未开始 */
    PENDING("pending"),

    /** 编译/启停执行中 */
    RUNNING("running"),

    /** 成功（终态） */
    COMPLETED("completed"),

    /** 失败（终态），看 error 字段 */
    FAILED("failed"),

    /** 被取消（终态） */
    CANCELLED("cancelled");

    /** 落库/传输值 */
    private final String value;

    UserAppTaskStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static UserAppTaskStatus fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        for (UserAppTaskStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        return null;
    }

    /** 终态：completed / failed / cancelled */
    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == CANCELLED;
    }

    /** 活跃态：pending / running */
    public boolean isActive() {
        return this == PENDING || this == RUNNING;
    }
}
