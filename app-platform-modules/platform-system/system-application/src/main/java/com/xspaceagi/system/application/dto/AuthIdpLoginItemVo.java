package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "登录页单个第三方登录方式")
@Data
public class AuthIdpLoginItemVo {

    @Schema(description = "登录方式配置ID（发起授权时作 provider 参数）")
    private Long id;

    @Schema(description = "类型：CAS / OAUTH2 / WECHAT")
    private String type;

    @Schema(description = "登录按钮展示名")
    private String name;

    @Schema(description = "登录图标 URL（可空）")
    private String icon;

    @Schema(description = "仅 type=WECHAT 有值：QRCODE-网站扫码（PC 与手机浏览器展示，移动端自动唤起微信）、OA-公众号网页授权（仅微信内浏览器展示）")
    private String wechatMode;
}
