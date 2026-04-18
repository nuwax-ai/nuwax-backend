package com.xspaceagi.eco.market.domain.dto.req;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 客户端注册请求DTO
 */
@Data
@Schema(description = "客户端注册请求DTO")
public class ClientRegisterReqDTO {

    /**
     * 名称
     */
    @NotBlank(message = "Name is required")
    @Schema(description = "名称", required = true)
    private String name;

    /**
     * 描述
     */
    @Schema(description = "描述")
    private String description;

    /**
     * 租户ID，非必填，如不填则使用当前用户的租户ID
     */
    @Schema(description = "租户ID，非必填，如不填则使用当前用户的租户ID")
    private Long tenantId;

    /**
     * 客户端ID
     */
    @Schema(description = "客户端ID")
    private String clientId;
}