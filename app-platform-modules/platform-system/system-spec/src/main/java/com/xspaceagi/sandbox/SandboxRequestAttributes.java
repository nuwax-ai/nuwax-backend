package com.xspaceagi.sandbox;

/**
 * Sandbox 相关的attribute key
 */
public class SandboxRequestAttributes {

    private SandboxRequestAttributes() {
    }

    /**
     * 原始请求 URI（形如：/api/v1/4sandbox/{module}/...）
     * 鉴权拦截器需要基于这个值做白名单/AKTargetType 判断。
     */
    public static final String ORIGINAL_REQUEST_URI = "ORIGINAL_REQUEST_URI";

    /**
     * 请求来源（用于 controller 做沙箱/前端差异处理）
     */
    public static final String REQUEST_SOURCE = "REQUEST_SOURCE";

    /**
     * sandbox 来源标记值
     */
    public static final String SOURCE_SANDBOX = "sandbox";
}

