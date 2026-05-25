package com.xspaceagi.subscription.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PlanPeriodEnum {

    MONTH(1, "月"),
    QUARTER(3, "季度"),
    YEAR(12, "年"),
    FOREVER(0, "永久");

    private final Integer code;
    private final String desc;

    public static PlanPeriodEnum getByCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (PlanPeriodEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
