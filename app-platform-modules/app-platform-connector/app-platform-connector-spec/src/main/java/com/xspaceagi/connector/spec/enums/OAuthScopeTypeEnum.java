package com.xspaceagi.connector.spec.enums;

/**
 * OAuth App 配置归属（设计文档 §4.3 双轨）。
 */
public enum OAuthScopeTypeEnum {

    /** 平台公共 App（owner_id=0） */
    PLATFORM("platform"),

    /** 用户自带 App（Bring Your Own，owner_id=用户id） */
    BYO("byo");

    private final String code;

    OAuthScopeTypeEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static OAuthScopeTypeEnum fromCode(String code) {
        for (OAuthScopeTypeEnum type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown oauth scope_type: " + code);
    }
}
