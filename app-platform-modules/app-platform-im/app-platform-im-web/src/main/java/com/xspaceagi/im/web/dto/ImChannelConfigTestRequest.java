package com.xspaceagi.im.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * IM 渠道配置连通性测试请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "IM 渠道配置连通性测试请求")
public class ImChannelConfigTestRequest {

    @NotBlank(message = "渠道类型不能为空")
    @Schema(description = "渠道类型：feishu/dingtalk/wework")
    private String channel;

    @NotBlank(message = "目标类型不能为空")
    @Schema(description = "目标类型：bot/app")
    private String targetType;

    @Schema(description = "渠道专有配置（JSON 字符串）")
    private String configData;
}
