package com.xspaceagi.eco.market.web.controller.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 客户端配置详情请求DTO
 */
@Data
@Schema(description = "客户端配置详情请求DTO")
public class ClientConfigDetailReqDTO {

    /**
     * 配置UID
     */
    @NotEmpty(message = "Configuration UID is required")
    @Schema(description = "配置UID", required = true)
    private String uid;
} 