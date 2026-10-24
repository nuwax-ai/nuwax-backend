package com.xspaceagi.system.spec.enums;

import lombok.Getter;

/**
 * 敏感词分类枚举
 */
@Getter
public enum SensitiveWordCategoryEnum {

    ILLEGAL("ILLEGAL", "违法违禁"),
    ADVERTISING("ADVERTISING", "广告导流"),
    PRIVACY_LEAK("PRIVACY_LEAK", "隐私泄露"),
    COMPETITOR("COMPETITOR", "竞品词");

    private final String code;
    private final String desc;

    SensitiveWordCategoryEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static SensitiveWordCategoryEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (SensitiveWordCategoryEnum categoryEnum : values()) {
            if (categoryEnum.getCode().equals(code)) {
                return categoryEnum;
            }
        }
        return null;
    }

    public static boolean isValid(String code) {
        return fromCode(code) != null;
    }

    public static boolean isInValid(String code) {
        return !isValid(code);
    }
}
