package com.xspaceagi.userapp.sdk.dto;

import org.apache.commons.lang3.StringUtils;

/**
 * 应用环境（开发/生产）
 */
public enum UserAppStage {

    DEV("dev"),
    PROD("prod");

    private final String value;

    UserAppStage(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public static UserAppStage fromValue(String value) {
        if (StringUtils.isBlank(value)) {
            return null;
        }
        for (UserAppStage stage : values()) {
            if (stage.value.equalsIgnoreCase(value)) {
                return stage;
            }
        }
        return null;
    }
}
