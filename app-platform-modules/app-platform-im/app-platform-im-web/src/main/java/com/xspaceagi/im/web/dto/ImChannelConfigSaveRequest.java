package com.xspaceagi.im.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * IM 渠道配置保存请求
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "IM 渠道配置保存请求")
public class ImChannelConfigSaveRequest {

    @Schema(description = "主键ID（修改时必填）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long id;

    @Schema(description = "空间主键ID（新增时必填）", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long spaceId;

    @NotBlank(message = "渠道类型不能为空")
    @Schema(description = "渠道类型：feishu/dingtalk/wework/wechat_ilink")
    private String channel;

    @NotBlank(message = "目标类型不能为空")
    @Schema(description = "目标类型：bot/app")
    private String targetType;

    @NotNull(message = "关联智能体ID不能为空")
    @Schema(description = "关联智能体ID")
    private Long agentId;

    @Schema(description = "是否启用")
    @Default
    private Boolean enabled = true;

    @Schema(description = "渠道专有配置（JSON 字符串）")
    private String configData;

    @Schema(description = "输出方式：stream(流式输出)/once(一次性输出)")
    @Default
    private String outputMode = "once";

}
