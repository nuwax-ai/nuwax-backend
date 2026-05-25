package com.xspaceagi.bill.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum ResourceStatTypeEnum {
    CONSUMPTION("CONSUMPTION", "消费"),
    SALES("SALES", "销售");

    private final String code;
    private final String desc;

    public static ResourceStatTypeEnum fromCode(String code) {
        if (code == null) return null;
        for (ResourceStatTypeEnum e : values()) {
            if (e.getCode().equals(code)) return e;
        }
        return null;
    }
}