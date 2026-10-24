package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Builder
@Schema(name = "沙盒Agent任务进度")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SandboxAgentTaskProgressDto {

    @Schema(description = "任务对应的会话ID")
    private Long conversationId;

    @Schema(description = "任务主题")
    private String topic;

    @Schema(description = "执行该任务的智能体ID")
    private Long agentId;

    @Schema(description = "执行该任务的智能体名称")
    private String agentName;

    @Schema(description = "任务状态：CREATE / EXECUTING / CANCEL / COMPLETE / FAILED")
    private String taskStatus;

    @Schema(description = "是否执行中（taskStatus == EXECUTING）")
    private Boolean running;

    @Schema(description = "最近消息列表（最多 2 条）；任务 COMPLETE 时最后一条即任务结果")
    private List<SandboxAgentTaskMessageDto> messages;
}
