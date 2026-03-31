package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.validation.constraints.NotNull;
import java.util.Date;

@Builder
@Schema(name = "更新任务信息")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class ScheduleTaskUpdateDto {

    @Schema(description = "任务ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "任务ID不能为空")
    private Long id;

    @Schema(description = "任务目标类型，Agent、Workflow", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "任务目标类型不能为空")
    private String targetType;

    @Schema(description = "任务目标ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "任务目标ID不能为空")
    private String targetId;

    @Schema(description = "任务名称", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "任务名称不能为空")
    private String taskName;

    @Schema(description = "任务执行周期", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "任务执行周期不能为空")
    private String cron;

    @Schema(description = "任务参数，智能体参数格式为 {\"message\": \"消息内容\", \"variables\":{}}，工作流参数根据实际定义参数")
    private Object params;

    @Schema(description = "智能体任务执行时是否在上次会话中继续执行，0-不保留，1-保留")
    private Integer keepConversation;

    @Schema(description = "任务最大执行次数，不传不限制，针对指定时间的固定填1")
    private Long maxExecTimes;

    @Schema(description = "任务锁定时间，传时间戳（毫秒），固定周期的可以不传")
    private Date lockTime;
}
