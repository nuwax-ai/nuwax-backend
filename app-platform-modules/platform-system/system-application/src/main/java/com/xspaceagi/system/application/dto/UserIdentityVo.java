package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Date;

@Schema(description = "当前用户的外部身份绑定")
@Data
public class UserIdentityVo {

    @Schema(description = "绑定关系ID（解绑用）")
    private Long id;

    @Schema(description = "登录方式配置ID")
    private Long idpId;

    @Schema(description = "登录方式类型：CAS / OAUTH2 / WECHAT")
    private String idpType;

    @Schema(description = "登录方式名称（配置已删除时为空）")
    private String providerName;

    @Schema(description = "外部用户名/昵称")
    private String externalUserName;

    @Schema(description = "脱敏后的外部标识")
    private String externalIdMasked;

    @Schema(description = "该身份最近登录时间")
    private Date lastLoginTime;

    @Schema(description = "绑定时间")
    private Date created;
}
