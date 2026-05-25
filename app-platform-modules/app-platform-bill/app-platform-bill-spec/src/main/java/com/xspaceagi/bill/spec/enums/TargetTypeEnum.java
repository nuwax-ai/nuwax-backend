package com.xspaceagi.bill.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum TargetTypeEnum {
    PLAN("Plan", "订阅计划"),
    CREDIT_PACKAGE("CreditPackage", "积分套餐");

    private final String code;
    private final String desc;

    public static TargetTypeEnum fromCode(String code) {
        if (code == null) return null;
        for (TargetTypeEnum e : values()) {
            if (e.getCode().equals(code)) return e;
        }
        return null;
    }
}
