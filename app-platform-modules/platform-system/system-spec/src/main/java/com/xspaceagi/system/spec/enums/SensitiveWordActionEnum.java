package com.xspaceagi.system.spec.enums;

import lombok.Getter;

/**
 * 敏感词触发策略枚举
 */
@Getter
public enum SensitiveWordActionEnum {

    DISCONNECT("DISCONNECT", "断开连接"),
    REPLACE("REPLACE", "替换");

    private final String code;
    private final String desc;

    SensitiveWordActionEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static SensitiveWordActionEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (SensitiveWordActionEnum actionEnum : values()) {
            if (actionEnum.getCode().equals(code)) {
                return actionEnum;
            }
        }
        return null;
    }

    public static boolean isValid(String code) {
        return fromCode(code) != null;
    }

    public static boolean isInValid(String code) {
        return !isValid(code);
    }
}
