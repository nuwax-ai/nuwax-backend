package com.xspaceagi.system.spec.enums;

import lombok.Getter;

/**
 * 分类类型枚举
 */
@Getter
public enum CategoryTypeEnum {

    AGENT("Agent", "智能体"),
    PAGE_APP("PageApp", "网页应用"),
    COMPONENT("Component", "组件"),
    CHATBOX("ChatBox", "对话框智能体"),
    CONNECTOR("Connector", "连接器"),
    SKILL("Skill", "技能");

    private final String code;
    private final String description;

    CategoryTypeEnum(String code, String description) {
        this.code = code;
        this.description = description;
    }

    public static CategoryTypeEnum fromCode(String code) {
        for (CategoryTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type;
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
