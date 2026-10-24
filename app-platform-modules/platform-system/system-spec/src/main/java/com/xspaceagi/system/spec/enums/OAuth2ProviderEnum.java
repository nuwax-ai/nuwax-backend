package com.xspaceagi.system.spec.enums;

import lombok.Getter;

/**
 * 标准 OAuth2 登录的提供方预设：端点与用户信息格式由平台适配层内置，自定义则要求填写完整端点
 */
@Getter
public enum OAuth2ProviderEnum {

    FEISHU("FEISHU", "飞书"),
    WECOM("WECOM", "企业微信"),
    DINGTALK("DINGTALK", "钉钉"),
    GITHUB("GITHUB", "GitHub"),
    CUSTOM("CUSTOM", "自定义");

    private final String code;
    private final String desc;

    OAuth2ProviderEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static OAuth2ProviderEnum of(String code) {
        if (code == null) {
            return null;
        }
        for (OAuth2ProviderEnum provider : values()) {
            if (provider.code.equalsIgnoreCase(code)) {
                return provider;
            }
        }
        return null;
    }
}
