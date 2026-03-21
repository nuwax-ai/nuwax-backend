package com.xspaceagi.im.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "IM 渠道配置统计请求")
public class ImChannelStatisticsRequest {

    @NotNull(message = "空间ID不能为空")
    @Schema(description = "空间ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long spaceId;
}

