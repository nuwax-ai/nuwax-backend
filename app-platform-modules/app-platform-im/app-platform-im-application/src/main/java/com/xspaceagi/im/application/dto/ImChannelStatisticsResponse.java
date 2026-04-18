package com.xspaceagi.im.application.dto;

import com.xspaceagi.system.spec.annotation.I18n;
import com.xspaceagi.system.spec.annotation.I18nField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@I18n(module = "ImChannel")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "IM 渠道统计响应")
public class ImChannelStatisticsResponse {

    @I18nField(keyPrefix = true)
    @Schema(description = "渠道类型：feishu/dingtalk/wework")
    private String channel;

    @I18nField(field = "name")
    @Schema(description = "渠道名称")
    private String channelName;

    @Schema(description = "配置条数")
    private Long count;
}

