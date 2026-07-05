package com.xspaceagi.sandbox;

import jakarta.servlet.http.HttpServletRequest;

public class SandboxUtils {

    private SandboxUtils() {
    }

    /**
     * 判断当前请求是否来自沙箱环境
     */
    public static boolean isSandboxRequest(HttpServletRequest request) {
        if (request == null) {
            return false;
        }
        Object src = request.getAttribute(SandboxRequestAttributes.REQUEST_SOURCE);
        return SandboxRequestAttributes.SOURCE_SANDBOX.equals(src);
    }
}
