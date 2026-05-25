package com.xspaceagi.pricing.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PricingConfigStatusEnum {

    DISABLED(0, "禁用"),
    ENABLED(1, "启用");

    private final Integer code;
    private final String desc;

    public boolean isEnabled() {
        return ENABLED.equals(this);
    }

    public static PricingConfigStatusEnum getByCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (PricingConfigStatusEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
