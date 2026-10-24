package com.xspaceagi.system.spec.enums;

import lombok.Getter;

/**
 * 外部登录方式
 */
@Getter
public enum AuthIdpTypeEnum {

    CAS("CAS", "CAS 单点登录"),
    OAUTH2("OAUTH2", "标准 OAuth2"),
    WECHAT("WECHAT", "微信登录");

    private final String code;
    private final String desc;

    AuthIdpTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static AuthIdpTypeEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (AuthIdpTypeEnum type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        return null;
    }
}
