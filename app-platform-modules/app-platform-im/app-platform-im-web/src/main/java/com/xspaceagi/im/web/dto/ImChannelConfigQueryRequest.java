package com.xspaceagi.im.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * IM 渠道配置查询请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "IM 渠道配置查询请求")
public class ImChannelConfigQueryRequest {

    @Schema(description = "渠道类型：feishu/dingtalk/wework", requiredMode = Schema.RequiredMode.REQUIRED)
    private String channel;

    @Schema(description = "目标类型：bot/app")
    private String targetType;

    @Schema(description = "目标唯一标识")
    private String targetId;

    @Schema(description = "关联智能体ID")
    private Long agentId;

    @Schema(description = "是否启用")
    private Boolean enabled;

    @Schema(description = "空间ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long spaceId;
}
