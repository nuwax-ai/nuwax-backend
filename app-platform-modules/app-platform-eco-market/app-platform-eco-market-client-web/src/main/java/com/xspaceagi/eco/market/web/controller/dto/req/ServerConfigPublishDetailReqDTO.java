package com.xspaceagi.eco.market.web.controller.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
@Schema(description = "发布配置详情请求DTO")
public class ServerConfigPublishDetailReqDTO {

    @Schema(description = "配置唯一标识")
    @NotBlank(message = "uid is required")
    private String uid;

    @Schema(description = "客户端ID")
    @NotBlank(message = "clientId is required")
    private String clientId;

    @Schema(description = "客户端密钥")
    @NotBlank(message = "clientSecret is required")
    private String clientSecret;
} 