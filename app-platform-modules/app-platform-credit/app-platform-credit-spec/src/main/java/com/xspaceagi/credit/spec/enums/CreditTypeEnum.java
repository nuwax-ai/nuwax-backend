package com.xspaceagi.credit.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CreditTypeEnum {

    SUBSCRIPTION(1, "订阅积分"),
    PURCHASE(2, "增购积分"),
    ACTIVITY(3, "活动积分"),
    MANUAL(4, "系统发放"),
    LOAN(5, "借贷"),
    MODEL_CALL(10, "模型调用"),
    AGENT_CALL(11, "智能体调用"),
    TOOL_CALL(12, "工具调用"),
    MANUAL_DEDUCT(13, "系统扣减");

    private final Integer code;
    private final String desc;

    public static CreditTypeEnum getByCode(Integer code) {
        if (code == null) {
            return null;
        }
        for (CreditTypeEnum typeEnum : values()) {
            if (typeEnum.getCode().equals(code)) {
                return typeEnum;
            }
        }
        return null;
    }
}
