package com.xspaceagi.system.application.dto;

import lombok.Data;

/**
 * 外部身份未绑定时的待处理数据（auth:idp:pending:{token}，中间页消费，登录页往返需要较长时效）
 */
@Data
public class AuthIdpPendingData {

    private Long tenantId;

    private Long providerId;

    private String providerType;

    private String providerName;

    private String icon;

    private String externalId;

    private String unionId;

    private String userName;

    private String nickName;

    private String avatar;

    private String email;

    private String phone;

    /**
     * 登录完成后的回跳目标（已校验同站）
     */
    private String redirect;
}
