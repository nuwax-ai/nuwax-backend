package com.xspaceagi.system.application.dto;

import lombok.Builder;
import lombok.Data;

/**
 * IdP 回跳处理结果：
 * LOGIN-已签发平台 token（controller 种 ticket cookie），
 * BIND-绑定完成，
 * PENDING-外部身份未绑定需进入中间页
 */
@Data
@Builder
public class AuthIdpCallbackResult {

    public enum ResultType {
        LOGIN, BIND, PENDING
    }

    private ResultType resultType;

    /**
     * 仅 LOGIN：平台 JWT
     */
    private String token;

    /**
     * 最终 302 目标（相对路径）
     */
    private String target;
}
