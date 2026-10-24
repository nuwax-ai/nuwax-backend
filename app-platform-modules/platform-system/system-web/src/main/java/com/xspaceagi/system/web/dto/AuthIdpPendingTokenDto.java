package com.xspaceagi.system.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Schema(description = "绑定/注册中间页操作请求")
@Data
public class AuthIdpPendingTokenDto {

    @Schema(description = "中间页 pending token", required = true)
    private String token;
}
