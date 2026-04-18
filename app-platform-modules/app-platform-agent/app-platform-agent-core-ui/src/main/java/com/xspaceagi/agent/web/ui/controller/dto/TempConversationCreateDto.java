package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TempConversationCreateDto {

    @Schema(description = "链接Key", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "chatKey is required")
    private String chatKey;

    @Schema(description = "验证码参数")
    private String captchaVerifyParam;
}
