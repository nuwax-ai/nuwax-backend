package com.xspaceagi.im.infra.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * IM 输出方式枚举
 */
@Getter
@AllArgsConstructor
public enum ImOutputModeEnum {

    STREAM("stream", "流式输出"),
    ONCE("once", "一次性输出");

    private final String code;
    private final String name;

    public static ImOutputModeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ImOutputModeEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }

    public static boolean isValid(String code) {
        return fromCode(code) != null;
    }
}

