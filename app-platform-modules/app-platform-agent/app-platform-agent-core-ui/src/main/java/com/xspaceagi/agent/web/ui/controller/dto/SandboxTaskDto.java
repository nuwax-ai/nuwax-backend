package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;
import java.util.Date;

@Builder
@Schema(name = "任务信息")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SandboxTaskDto {

    @Schema(description = "定时任务ID")
    private Long id;

    @Schema(description = "定时任务执行类型，Once 一次；Loop 循环")
    private String executeType;

    @Schema(description = "当前会话ID，从环境变量中获取，环境变量为 CONVERSATION_ID")
    private Long conversationId;

    @Schema(description = "定时任务名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "任务名称不能为空")
    private String taskName;

    @Schema(description = "定时任务执行周期，executeType为Loop时有效")
    @NotNull(message = "任务执行周期不能为空")
    private String cron;

    @Schema(description = "定时任务消息内容")
    private String message;

    @Schema(description = "定时任务执行时间，例如 2026-05-01 22:00:00，固定周期的可以不传")
    private Date lockTime;
}
