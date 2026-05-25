package com.xspaceagi.pricing.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PricingTypeEnum {

    ONE_TIME("ONE_TIME", "单次"),
    BUYOUT("BUYOUT", "买断"),
    MONTHLY("MONTHLY", "包月-属于订阅计划"),
    TIERED("TIERED", "阶梯计费");

    private final String code;
    private final String desc;

    public static PricingTypeEnum fromCode(String code) {
        if (code == null) {
            return null;
        }
        for (PricingTypeEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
