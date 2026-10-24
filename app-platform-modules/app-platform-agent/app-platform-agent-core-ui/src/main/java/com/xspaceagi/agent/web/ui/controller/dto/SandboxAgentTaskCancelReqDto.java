package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Schema(name = "取消任务请求")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SandboxAgentTaskCancelReqDto {

    @Schema(description = "任务对应的会话ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long conversationId;

    @Schema(description = "取消原因（可选，仅记录日志）")
    private String reason;
}
