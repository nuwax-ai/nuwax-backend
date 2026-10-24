package com.xspaceagi.connector.spec.enums;

/**
 * 执行审计状态。
 */
public enum RunStatusEnum {

    SUCCESS("success"),

    FAILED("failed"),

    POLICY_DENIED("policy_denied");

    private final String code;

    RunStatusEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
