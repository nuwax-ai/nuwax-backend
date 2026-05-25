package com.xspaceagi.bill.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RevenueStatusEnum {
    PENDING("PENDING", "待结算"),
    WITHDRAW_APPLYING("WITHDRAW_APPLYING", "提现申请中"),
    PAYING("PAYING", "打款中"),
    SETTLED("SETTLED", "已结算");

    private final String code;
    private final String desc;

    public static RevenueStatusEnum fromCode(String code) {
        if (code == null) return null;
        for (RevenueStatusEnum e : values()) {
            if (e.getCode().equals(code)) return e;
        }
        return null;
    }
}
