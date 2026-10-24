package com.xspaceagi.userapp.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/**
 * 重新生成后的接入凭证：明文密钥仅此一次返回
 */
@Data
@Schema(description = "三方应用 OAuth2 凭证（密钥明文仅返回一次）")
public class ThirdAppOAuthCredentialVo {

    @Schema(description = "OAuth2 Client ID")
    private String clientId;

    @Schema(description = "OAuth2 Client Secret 明文；请立即保存，平台不再重复展示（可另调 /secret 查看）")
    private String clientSecret;
}
