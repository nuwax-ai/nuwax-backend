package com.xspaceagi.eco.market.domain.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 客户端注册请求
 */
@Data
@Schema(description = "客户端注册请求")
public class ClientRegisterRequest {

    /**
     * 名称
     */
    @NotBlank(message = "Name is required")
    @Schema(description = "名称", requiredMode = Schema.RequiredMode.REQUIRED)
    private String name;

    /**
     * 描述
     */
    @Schema(description = "描述")
    private String description;

    /**
     * 租户ID
     */
    @NotNull(message = "Tenant ID is required")
    @Schema(description = "租户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long tenantId;
}