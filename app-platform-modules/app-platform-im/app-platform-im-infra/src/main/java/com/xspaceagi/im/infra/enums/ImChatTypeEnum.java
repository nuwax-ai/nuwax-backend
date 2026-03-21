package com.xspaceagi.im.infra.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * IM 会话类型枚举
 */
@Getter
@AllArgsConstructor
public enum ImChatTypeEnum {

    PRIVATE("private", "私聊"),
    GROUP("group", "群聊");

    private final String code;
    private final String name;

    public static ImChatTypeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (ImChatTypeEnum value : values()) {
            if (value.code.equals(code)) {
                return value;
            }
        }
        return null;
    }
}

