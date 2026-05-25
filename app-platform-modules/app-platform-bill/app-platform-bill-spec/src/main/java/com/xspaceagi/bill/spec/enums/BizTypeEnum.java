package com.xspaceagi.bill.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum BizTypeEnum {
    CREDIT_PURCHASE("CreditPurchase", "积分购买"),
    SUBSCRIPTION("Subscription", "订阅");

    private final String code;
    private final String desc;

    public static BizTypeEnum fromCode(String code) {
        if (code == null) return null;
        for (BizTypeEnum e : values()) {
            if (e.getCode().equals(code)) return e;
        }
        return null;
    }
}
