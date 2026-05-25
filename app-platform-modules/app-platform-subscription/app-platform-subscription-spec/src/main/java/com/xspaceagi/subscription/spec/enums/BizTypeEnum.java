package com.xspaceagi.subscription.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum BizTypeEnum {

    SYSTEM("SYSTEM", "系统全局"),
    AGENT("AGENT", "智能体"),
    SKILL("SKILL", "技能");

    private final String code;
    private final String desc;

    public static BizTypeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (BizTypeEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
