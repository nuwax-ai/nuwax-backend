package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Schema(name = "沙盒Agent任务派发结果")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SandboxAgentTaskDispatchResultDto {

    @Schema(description = "任务对应的会话ID：后续用 progress / cancel 都拿它当句柄")
    private Long conversationId;

    @Schema(description = "true=本次新建了任务会话；false=续聊了已有任务")
    private Boolean created;

    @Schema(description = "任务主题")
    private String topic;

    @Schema(description = "执行任务的智能体ID")
    private Long agentId;

    @Schema(description = "执行任务的智能体名称")
    private String agentName;

    @Schema(description = "派发后的任务状态：EXECUTING=已开始执行；CREATE=启动被拒绝（原因见 error）；FAILED=启动后立即失败")
    private String taskStatus;

    @Schema(description = "启动阶段的拒绝原因（如无执行权限、超出配额）；null 表示启动未被拒绝")
    private String error;
}
