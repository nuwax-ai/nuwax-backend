package com.xspaceagi.im.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ImNotifySendRequest {
    @Schema(description = "渠道：feishu / dingtalk / wework", requiredMode = Schema.RequiredMode.REQUIRED)
    private String channel;

    @Schema(description = "平台用户 ID，可省略（默认当前用户）；若传必须与当前用户一致。Admin 代发请用请求头 nuwax-user-id")
    private Long userId;

    @Schema(description = "标题（可选）")
    private String title;

    @Schema(description = "正文", requiredMode = Schema.RequiredMode.REQUIRED)
    private String content;

    @Schema(description = "可选渠道配置 ID")
    private Long channelConfigId;
}
