package com.xspaceagi.eco.market.domain.dto.req;

import java.util.List;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

/**
 * 服务器配置批量详情查询请求DTO
 */
@Data
@Schema(description = "服务器配置批量详情查询请求DTO")
public class ServerConfigBatchDetailReqDTO {

    /**
     * 配置UID列表
     */
    @NotEmpty(message = "UID list cannot be empty")
    @Schema(description = "配置UID列表", required = true)
    private List<String> uids;

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