package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 绑定/注册中间页展示的外部身份信息（不含 externalId 等敏感标识）
 */
@Schema(description = "绑定/注册中间页的外部身份信息")
@Data
public class AuthIdpPendingVo {

    @Schema(description = "登录方式名称")
    private String providerName;

    @Schema(description = "登录图标 URL（管理端配置或内置品牌图标，可空）")
    private String icon;

    @Schema(description = "外部身份是否包含邮箱（信息透明展示用，不返回值）")
    private Boolean hasEmail;

    @Schema(description = "外部身份是否包含手机号（信息透明展示用，不返回值）")
    private Boolean hasPhone;

    @Schema(description = "登录方式类型：CAS / OAUTH2 / WECHAT")
    private String providerType;

    @Schema(description = "外部昵称/用户名（无昵称时为用户名）")
    private String userName;

    @Schema(description = "外部头像 URL（可空）")
    private String avatar;

    @Schema(description = "登录完成后的回跳目标（本站相对路径）")
    private String redirect;
}
