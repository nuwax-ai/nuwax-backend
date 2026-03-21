package com.xspaceagi.im.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * IM 渠道配置启用/禁用请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "IM 渠道配置启用/禁用请求")
public class ImChannelConfigEnabledRequest {

    @NotNull(message = "配置ID不能为空")
    @Schema(description = "配置ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @NotNull(message = "启用状态不能为空")
    @Schema(description = "是否启用", requiredMode = Schema.RequiredMode.REQUIRED)
    private Boolean enabled;
}
