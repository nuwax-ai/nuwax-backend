package com.xspaceagi.eco.market.domain.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 服务器配置详情查询请求DTO
 */
@Data
@Schema(description = "服务器配置详情查询请求DTO")
public class ServerConfigDetailReqDTO {

    /**
     * 配置UID
     */
    @NotBlank(message = "UID is required")
    @Schema(description = "配置UID", required = true)
    private String uid;

    /**
     * 客户端ID
     */
    @Schema(description = "客户端ID")
    private String clientId;

    /**
     * 客户端密钥
     */
    @Schema(description = "客户端密钥")
    private String clientSecret;
} 