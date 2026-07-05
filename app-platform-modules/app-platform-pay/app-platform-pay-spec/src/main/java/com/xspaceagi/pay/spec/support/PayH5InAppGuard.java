package com.xspaceagi.pay.spec.support;

import com.xspaceagi.pay.sdk.support.PayAppWebViewDetector;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

/**
 * App WebView 内禁止调起 H5 支付（渠道违规）；应使用 App 原生 SDK 接口。
 */
public final class PayH5InAppGuard {

    private PayH5InAppGuard() {}

    public static void assertH5PayAllowed(String clientTypeHeader, String userAgent) {
        if (PayAppWebViewDetector.isAppWebView(clientTypeHeader, userAgent)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_h5_not_allowed_in_app);
        }
    }
}
