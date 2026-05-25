package com.xspaceagi.credit.spec.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum CreditOperationTypeEnum {

    ADD(1, "增加"),
    DEDUCT(2, "扣减");

    private final Integer code;
    private final String desc;
}
