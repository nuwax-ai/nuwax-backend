package com.xspaceagi.im.infra.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * IM 目标类型枚举
 */
@Getter
@AllArgsConstructor
public enum ImTargetTypeEnum {

    BOT("bot", "机器人"),
    APP("app", "应用");

    private final String code;
    private final String name;

    public static ImTargetTypeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ImTargetTypeEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

