package com.xspaceagi.pay.sdk.enums;

public enum PayMode {
    scan("主扫");

    private final String name;

    PayMode(String name) {
        this.name = name;
    }

    /** 管理台/对外展示用中文名 */
    public String getName() {
        return name;
    }
}
