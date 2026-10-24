package com.xspaceagi.connector.spec.enums;

/**
 * action 执行类型（设计文档 §4.4）。
 */
public enum ExecTypeEnum {

    /** 声明式 HTTP 执行（路径 A，http_spec 驱动） */
    DECLARATIVE("DECLARATIVE"),

    /** 自定义执行器（路径 B，exec_ref 指向 ConnectorRuntime 实现类，仅官方层可建） */
    CUSTOM("CUSTOM"),

    /** 绑定插件执行（exec_ref 指向已发布插件 id） */
    PLUGIN("PLUGIN"),

    /** 绑定工作流执行（exec_ref 指向已发布工作流 id） */
    WORKFLOW("WORKFLOW");

    private final String code;

    ExecTypeEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static ExecTypeEnum fromCode(String code) {
        for (ExecTypeEnum type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown exec_type: " + code);
    }
}
