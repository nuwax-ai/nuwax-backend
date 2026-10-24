package com.xspaceagi.userapp.sdk.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * 发布部署目标类型
 */
public enum UserAppDeployType {

    Platform("platform"),
    Private("private");

    /**
     * deployType=Platform 时 deploy_server_id 的占位值（云端沙箱）；
     */
    public static final long CLOUD_SERVER_ID = -1L;

    private final String value;

    UserAppDeployType(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static UserAppDeployType fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            return Platform;
        }
        for (UserAppDeployType type : values()) {
            if (type.value.equalsIgnoreCase(value)) {
                return type;
            }
        }
        return null;
    }
}
