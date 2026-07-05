package com.xspaceagi.system.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "微信公众号 OAuth 授权页 URL")
public class WeChatOaOAuthUrlResponse {

    @Schema(description = "微信 OAuth 授权页完整 URL（微信内浏览器 location.href 跳转）")
    private String authorizeUrl;
}
