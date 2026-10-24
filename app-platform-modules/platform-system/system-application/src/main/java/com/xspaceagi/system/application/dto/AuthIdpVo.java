package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

@Schema(description = "登录方式配置（管理端，config 已脱敏）")
@Data
@Builder
public class AuthIdpVo {

    @Schema(description = "配置ID")
    private Long id;

    @Schema(description = "类型：CAS / OAUTH2 / WECHAT")
    private String type;

    @Schema(description = "类型中文名：CAS 单点登录 / 标准 OAuth2 / 微信登录")
    private String typeDesc;

    @Schema(description = "配置名称（登录页展示名）")
    private String name;

    @Schema(description = "登录图标 URL")
    private String icon;

    @Schema(description = "状态：1-启用、0-停用")
    private Integer enabled;

    @Schema(description = "未登录自动跳转：1-是（租户内全局唯一）、0-否")
    private Integer autoRedirect;

    @Schema(description = "自动注册/绑定：1-免中间页（已注册按邮箱/手机号自动绑定、未注册自动注册并绑定）、0-进中间页由用户选择")
    private Integer autoRegisterBind;

    @Schema(description = "排序值", example = "0")
    private Integer sort;

    @Schema(description = "脱敏后的类型化配置，结构随 type 变化（secret 显示 ******）")
    private Map<String, Object> config;

    @Schema(description = "平台回调地址（只读，注册到 IdP 侧用，勿作为入参回传）")
    private String callbackUrl;
}
