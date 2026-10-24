package com.xspaceagi.im.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ImBindStartRequest {
    @Schema(description = "渠道：feishu / dingtalk / wework", requiredMode = Schema.RequiredMode.REQUIRED)
    private String channel;

    @Schema(description = "可选，多条渠道配置时指定 im_channel_config.id")
    private Long channelConfigId;

    @Schema(description = "回跳地址（可选）。默认租户 siteUrl+/api/v1/im-notify/bind/return；若传须为该地址或命中额外白名单")
    private String appReturnUrl;
}
