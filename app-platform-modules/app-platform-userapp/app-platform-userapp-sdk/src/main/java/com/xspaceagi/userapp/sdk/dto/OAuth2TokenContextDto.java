package com.xspaceagi.userapp.sdk.dto;

import java.io.Serializable;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * access_token 校验通过后的上下文（供拦截器填充请求上下文）
 */
@Data
@Schema(description = "OAuth2 access_token 上下文")
public class OAuth2TokenContextDto implements Serializable {

    private static final long serialVersionUID = 1L;

    @Schema(description = "接入应用（user_project 主键）")
    private Long projectId;

    @Schema(description = "OAuth2 Client ID")
    private String clientId;

    @Schema(description = "授权用户 ID；client_credentials 下为接入应用创建者")
    private Long userId;

    @Schema(description = "租户 ID")
    private Long tenantId;

    @Schema(description = "本令牌持有的 scope（逗号分隔）")
    private String scope;

    @Schema(description = "签发来源：authorization_code / refresh_token / client_credentials")
    private String grantType;
}
