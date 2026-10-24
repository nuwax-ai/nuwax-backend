package com.xspaceagi.im.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ImUnbindRequest {
    @Schema(description = "渠道：feishu / dingtalk / wework", requiredMode = Schema.RequiredMode.REQUIRED)
    private String channel;
}
