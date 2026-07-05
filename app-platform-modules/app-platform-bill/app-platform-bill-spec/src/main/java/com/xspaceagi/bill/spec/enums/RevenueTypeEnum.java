package com.xspaceagi.bill.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RevenueTypeEnum {
    PLAN("Plan", "计划购买"),
    MODEL_CALL("ModelCall", "模型调用"),
    TOOL_CALL("ToolCall", "工具调用"),
    PROJECT_PAY("ProjectPay", "项目支付");

    private final String code;
    private final String desc;

    public static RevenueTypeEnum fromCode(String code) {
        if (code == null) return null;
        for (RevenueTypeEnum e : values()) {
            if (e.getCode().equals(code)) return e;
        }
        return null;
    }
}
