package com.xspaceagi.userapp.sdk.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * 私有部署服务器状态
 */
public enum UserPrivateServerStatus {

    // 未验证
    Unverified("unverified"),
    // 在线
    Online("online"),
    // 离线
    Offline("offline");

    private final String value;

    UserPrivateServerStatus(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static UserPrivateServerStatus fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        for (UserPrivateServerStatus status : values()) {
            if (status.value.equalsIgnoreCase(value)) {
                return status;
            }
        }
        return null;
    }
}
