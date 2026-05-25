package com.xspaceagi.bill.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum OrderStatusEnum {
    PENDING("PENDING", "待支付"),
    PAID("PAID", "已支付"),
    CANCELLED("CANCELLED", "已取消");

    private final String code;
    private final String desc;

    public static OrderStatusEnum fromCode(String code) {
        if (code == null) return null;
        for (OrderStatusEnum e : values()) {
            if (e.getCode().equals(code)) return e;
        }
        return null;
    }
}
