package com.xspaceagi.connector.spec.enums;

/**
 * 目录条目归属：system=导入生成（随版本 diff 更新），user=手工创建/修改（重导入保护区）。
 */
public enum ManagedByEnum {

    SYSTEM("system"),

    USER("user");

    private final String code;

    ManagedByEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
