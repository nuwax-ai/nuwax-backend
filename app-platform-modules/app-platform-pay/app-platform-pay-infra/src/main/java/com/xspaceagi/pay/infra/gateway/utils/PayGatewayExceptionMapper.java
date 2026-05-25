package com.xspaceagi.pay.infra.gateway.utils;

import com.xspaceagi.pay.spec.PayGatewayMessages;
import com.xspaceagi.pay.spec.exception.PayGatewayBizException;
import com.xspaceagi.pay.spec.exception.PayGatewayClientException;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;

/** 将 {@link PayGatewayClientException} 转为对外 {@link BizException}。 */
public final class PayGatewayExceptionMapper {

    private PayGatewayExceptionMapper() {}

    public static BizException toBizException(PayGatewayClientException e) {
        if (e != null && e.hasGatewayApiError()) {
            return new PayGatewayBizException(e.getGatewayCode(), e.getGatewayMessage());
        }
        if (e != null) {
            String msg = e.getMessage();
            if (msg != null && !msg.isBlank() && !PayGatewayMessages.CONNECT_FAILED.equals(msg)) {
                return new BizException(ErrorCodeEnum.SYS_ERROR.getCode(), msg);
            }
        }
        return new BizException(ErrorCodeEnum.SYS_ERROR.getCode(), PayGatewayMessages.CONNECT_FAILED);
    }
}
