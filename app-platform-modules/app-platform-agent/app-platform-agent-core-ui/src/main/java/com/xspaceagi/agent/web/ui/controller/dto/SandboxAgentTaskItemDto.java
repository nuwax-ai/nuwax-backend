package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Builder
@Schema(name = "沙盒Agent任务条目")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SandboxAgentTaskItemDto {

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

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "最近更新时间")
    private Date modified;
}
