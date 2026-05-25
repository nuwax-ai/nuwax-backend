package com.xspaceagi.bill.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum LimitModeEnum {
    ALL("ALL", "同时满足"),
    ANY("ANY", "任一满足");

    private final String code;
    private final String desc;

    public static LimitModeEnum fromCode(String code) {
        if (code == null) return null;
        for (LimitModeEnum e : values()) {
            if (e.getCode().equals(code)) return e;
        }
        return null;
    }
}
