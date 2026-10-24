package com.xspaceagi.agent.web.ui.controller.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@Schema(name = "沙盒Agent任务派发请求")
@AllArgsConstructor
@NoArgsConstructor
@Data
public class SandboxAgentTaskDispatchReqDto {

    @Schema(description = "目标智能体ID：仅允许本 AK 绑定的智能体或其配置的子智能体",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private Long targetAgentId;

    @Schema(description = "任务输入：发给目标智能体的指令文本（触发真实模型执行）",
            requiredMode = Schema.RequiredMode.REQUIRED)
    private String inputMessage;

    @Schema(description = "任务主题：新建任务时作为会话 topic；缺省取 inputMessage。续聊已有任务时忽略")
    private String subject;

    @Schema(description = "已有任务（会话）ID：传入表示续聊该任务而不是新建；任务必须通过归属校验且未在执行中")
    private Long conversationId;
}
