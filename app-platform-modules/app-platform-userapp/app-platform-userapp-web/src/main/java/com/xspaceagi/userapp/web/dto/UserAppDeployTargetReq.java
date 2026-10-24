package com.xspaceagi.userapp.web.dto;

import lombok.Data;

/**
 * 应用发布部署目标设置
 */
@Data
public class UserAppDeployTargetReq {

    private Long appId;

    private String deployType;

    private Long deployServerId;
}
