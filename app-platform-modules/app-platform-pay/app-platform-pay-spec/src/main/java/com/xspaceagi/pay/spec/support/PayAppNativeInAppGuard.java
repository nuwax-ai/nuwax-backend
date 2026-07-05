package com.xspaceagi.pay.spec.support;

import com.xspaceagi.pay.sdk.support.PayAppWebViewDetector;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;

/**
 * App 原生支付仅允许在 App WebView 内调起；系统/手机浏览器应使用 H5 接口。
 */
public final class PayAppNativeInAppGuard {

    private PayAppNativeInAppGuard() {}

    public static void assertAppNativePayRequired(String clientTypeHeader, String userAgent) {
        if (!PayAppWebViewDetector.isAppWebView(clientTypeHeader, userAgent)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.pay_app_native_requires_app);
        }
    }
}
