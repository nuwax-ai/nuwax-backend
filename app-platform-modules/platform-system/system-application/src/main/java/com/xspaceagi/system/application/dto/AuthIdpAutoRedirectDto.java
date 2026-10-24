package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "未登录自动跳转设置请求")
@Data
public class AuthIdpAutoRedirectDto {

    @Schema(description = "目标登录方式配置ID（必须已启用，租户内全局唯一）；传空表示取消自动跳转", example = "3")
    private Long id;
}
