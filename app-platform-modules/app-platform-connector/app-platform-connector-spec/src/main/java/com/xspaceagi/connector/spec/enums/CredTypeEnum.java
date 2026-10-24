package com.xspaceagi.connector.spec.enums;

/**
 * 凭证类型（connector_credential.cred_type）。
 */
public enum CredTypeEnum {

    /** API Key 单字段 */
    API_KEY("api_key"),

    /** OAuth2 access_token/refresh_token 组合 */
    OAUTH2_TOKEN("oauth2_token"),

    /** Bearer Token 单字段 */
    BEARER("bearer"),

    /** 自定义多字段（accessKey+secretKey+region 等） */
    CUSTOM_FIELDS("custom_fields");

    private final String code;

    CredTypeEnum(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    public static CredTypeEnum fromCode(String code) {
        for (CredTypeEnum type : values()) {
            if (type.code.equalsIgnoreCase(code)) {
                return type;
            }
        }
        throw new IllegalArgumentException("unknown cred_type: " + code);
    }
}
