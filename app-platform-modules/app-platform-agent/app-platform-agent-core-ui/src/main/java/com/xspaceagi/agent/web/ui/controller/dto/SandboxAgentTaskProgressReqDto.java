package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Schema(name = "查询任务进度请求")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SandboxAgentTaskProgressReqDto {

    @Schema(description = "任务对应的会话ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long conversationId;
}
