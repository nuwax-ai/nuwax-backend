package com.xspaceagi.userapp.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "平台支持的 OAuth2 scope 元数据")
public class ThirdAppOAuthScopeVo {

    @Schema(description = "scope 值（授权请求 scope 参数使用）")
    private String scope;

    @Schema(description = "能力说明")
    private String description;

    @Schema(description = "是否敏感能力")
    private Boolean sensitive;
}
