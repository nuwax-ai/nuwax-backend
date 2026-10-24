package com.xspaceagi.system.spec.enums;

import lombok.Getter;

/**
 * 敏感词匹配方式枚举
 */
@Getter
public enum SensitiveWordMatchTypeEnum {

    CONTAIN("CONTAIN", "包含"),
    REGEX("REGEX", "正则");

    private final String code;
    private final String desc;

    SensitiveWordMatchTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static SensitiveWordMatchTypeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (SensitiveWordMatchTypeEnum matchTypeEnum : values()) {
            if (matchTypeEnum.getCode().equals(code)) {
                return matchTypeEnum;
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
