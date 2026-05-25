package com.xspaceagi.pay.spec.exception;

import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;

/**
 * 支付网关业务失败：对外 {@code code} 仍为平台 {@link ErrorCodeEnum#SYS_ERROR}，{@code displayCode} 为网关 code。
 */
public class PayGatewayBizException extends BizException {

    private final String displayCode;

    public PayGatewayBizException(String gatewayCode, String gatewayMessage) {
        super(ErrorCodeEnum.SYS_ERROR.getCode(), gatewayMessage);
        this.displayCode = gatewayCode;
    }

    public String getDisplayCode() {
        return displayCode;
    }
}
