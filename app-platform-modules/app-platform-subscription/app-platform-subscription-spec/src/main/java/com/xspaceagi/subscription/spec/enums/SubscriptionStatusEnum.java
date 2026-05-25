package com.xspaceagi.subscription.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum SubscriptionStatusEnum {

    ACTIVE(0, "生效中"),
    EXPIRED(1, "已过期"),
    CANCELLED(2, "已取消");

    private final Integer code;
    private final String desc;

    public static SubscriptionStatusEnum getByCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (SubscriptionStatusEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
