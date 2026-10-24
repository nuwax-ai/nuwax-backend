package com.xspaceagi.im.web.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ImBindConfirmRequest {
    @Schema(description = "bind/start 返回的 bindId", requiredMode = Schema.RequiredMode.REQUIRED)
    private String bindId;

    @Schema(description = "OAuth 回跳 App 深链中的 confirmToken", requiredMode = Schema.RequiredMode.REQUIRED)
    private String confirmToken;
}
