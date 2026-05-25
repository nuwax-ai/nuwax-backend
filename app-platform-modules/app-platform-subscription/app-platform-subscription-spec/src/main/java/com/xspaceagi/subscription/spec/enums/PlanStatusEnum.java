package com.xspaceagi.subscription.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PlanStatusEnum {

    OFFLINE(0, "下线"),
    ONLINE(1, "上线");

    private final Integer code;
    private final String desc;

    public boolean isOnline() {
        return ONLINE.equals(this);
    }

    public static PlanStatusEnum getByCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (PlanStatusEnum e : values()) {
            if (e.getCode().equals(code)) {
                return e;
            }
        }
        return null;
    }
}
