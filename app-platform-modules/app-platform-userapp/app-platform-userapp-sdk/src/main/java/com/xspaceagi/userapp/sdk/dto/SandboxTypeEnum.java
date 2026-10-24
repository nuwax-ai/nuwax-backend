package com.xspaceagi.userapp.sdk.dto;

public enum SandboxTypeEnum {

    Cloud,
    Personal;

    /** 由 sandbox_config 的 scope 编码（global/user）转换；未知返回 null（调用方按非法处理） */
    public static SandboxTypeEnum fromScopeCode(String scopeCode) {
        if ("global".equalsIgnoreCase(scopeCode)) {
            return Cloud;
        }
        if ("user".equalsIgnoreCase(scopeCode)) {
            return Personal;
        }
        return null;
    }
}
