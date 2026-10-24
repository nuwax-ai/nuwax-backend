package com.xspaceagi.system.application.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Schema(description = "登录页可用的第三方登录方式列表")
@Data
@Builder
public class AuthIdpLoginListVo {

    @Schema(description = "当前租户已启用的登录方式列表")
    private List<AuthIdpLoginItemVo> items;

    @Schema(description = "未登录自动跳转的登录方式ID（未设置为 null）；登录页非 local 模式时直接跳转该项")
    private Long autoRedirectIdpId;
}
