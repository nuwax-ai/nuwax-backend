package com.xspaceagi.bill.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum PayStatusEnum {
    PENDING("PENDING", "待支付"),
    PROCESSING("PROCESSING", "处理中"),
    SUCCESS("SUCCESS", "支付成功"),
    FAILED("FAILED", "支付失败"),
    CLOSED("CLOSED", "已关闭");

    private final String code;
    private final String desc;

    public static PayStatusEnum fromCode(String code) {
        if (code == null) return null;
        for (PayStatusEnum e : values()) {
            if (e.getCode().equals(code)) return e;
        }
        return null;
    }
}
