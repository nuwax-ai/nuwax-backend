package com.xspaceagi.bill.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum WithdrawStatusEnum {
    PENDING_REVIEW("PENDING_REVIEW", "待审核"),
    APPROVED("APPROVED", "已通过"),
    REJECTED("REJECTED", "已驳回"),
    PAID("PAID", "已打款");

    private final String code;
    private final String desc;

    public static WithdrawStatusEnum fromCode(String code) {
        if (code == null) return null;
        for (WithdrawStatusEnum e : values()) {
            if (e.getCode().equals(code)) return e;
        }
        return null;
    }
}
