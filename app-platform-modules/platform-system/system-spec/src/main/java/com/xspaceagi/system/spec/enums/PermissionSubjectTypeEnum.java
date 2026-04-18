package com.xspaceagi.system.spec.enums;

import lombok.Getter;

/**
 * 权限主体类型枚举
 */
@Getter
public enum PermissionSubjectTypeEnum {

    MODEL(1, "模型"),
    AGENT(2, "通用智能体"),
    PAGE(3, "应用页面"),
    OPEN_API(4, "开放API"),
    KNOWLEDGE(5, "知识库");

    private final Integer code;
    private final String desc;

    PermissionSubjectTypeEnum(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static PermissionSubjectTypeEnum getByCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (PermissionSubjectTypeEnum typeEnum : values()) {
            if (typeEnum.getCode().equals(code)) {
                return typeEnum;
            }
        }
        return null;
    }

    public static boolean isValid(Integer code) {
        return getByCode(code) != null;
    }

    public static boolean isInValid(Integer code) {
        return !isValid(code);
    }
}

