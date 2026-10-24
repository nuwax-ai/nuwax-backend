package com.xspaceagi.system.application.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 中间页「注册并登录」完成结果
 */
@Data
@Builder
public class AuthIdpLoginResult {

    private String token;

    private String redirect;
}
