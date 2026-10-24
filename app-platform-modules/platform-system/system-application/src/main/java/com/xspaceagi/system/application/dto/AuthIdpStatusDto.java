package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "登录方式启用/停用请求")
@Data
public class AuthIdpStatusDto {

    @Schema(description = "配置ID", required = true)
    private Long id;

    @Schema(description = "状态：1-启用、0-停用（停用联动取消未登录自动跳转）")
    private Integer enabled;
}
