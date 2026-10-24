package com.xspaceagi.connector.spec.enums;

/**
 * 导入 dry-run diff 条目操作类型（设计文档 §4.1 导入管线）。
 */
public enum ImportOpEnum {

    /** 新增 */
    ADD("add"),

    /** 更新（仅 managed_by=system 条目） */
    UPDATE("update"),

    /** 无变化 */
    UNCHANGED("unchanged"),

    /** 命中手工保护区（managed_by=user），跳过不覆盖 */
    SKIP_PROTECTED("skip_protected");

    private final String code;

    ImportOpEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
