package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Builder
@Schema(name = "沙盒Agent任务消息")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SandboxAgentTaskMessageDto {

    @Schema(description = "消息角色：USER / ASSISTANT / SYSTEM / FUNCTION")
    private String role;

    @Schema(description = "发送方类型：USER / AGENT / REMINDER")
    private String senderType;

    @Schema(description = "消息文本内容")
    private String text;

    @Schema(description = "消息时间")
    private Date time;
}
