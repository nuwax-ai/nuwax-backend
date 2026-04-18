package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class TaskConversationAddOrUpdateDto {

    @Schema(description = "会话ID")
    private Long id;

    @Schema(description = "任务会话主题")
    @NotNull(message = "Task conversation subject is required")
    private String topic;

    @Schema(description = "任务会话内容")
    @NotNull(message = "Task conversation content is required")
    private String summary;

    @Schema(description = "任务会话定时配置")
    @NotNull(message = "Task conversation schedule configuration is required")
    private String taskCron;

    @Schema(description = "智能体ID")
    @NotNull(message = "Agent ID is required")
    private Long agentId;

    @Schema(description = "开发模式")
    private boolean devMode;
}
