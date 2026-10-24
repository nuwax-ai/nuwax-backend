package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Schema(name = "沙盒Agent任务取消结果")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SandboxAgentTaskCancelResultDto {

    @Schema(description = "任务对应的会话ID")
    private Long conversationId;

    @Schema(description = "本次是否真的执行了取消（true=取消前任务在执行中；false=任务本就未在执行，未做变更）")
    private Boolean cancelled;

    @Schema(description = "取消后的任务状态：CANCEL / COMPLETE / FAILED / CREATE")
    private String taskStatus;
}
