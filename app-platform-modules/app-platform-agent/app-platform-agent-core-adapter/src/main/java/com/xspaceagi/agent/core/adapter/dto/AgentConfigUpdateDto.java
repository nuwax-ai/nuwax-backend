package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentConfigUpdateDto implements Serializable {

    @Schema(description = "智能体ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "id不能为空")
    private Long id; // 智能体ID

    @Schema(description = "Agent名称")
    private String name; // Agent名称

    @Schema(description = "Agent描述")
    private String description; // Agent描述

    @Schema(description = "图标地址")
    private String icon; // 图标地址

    @Schema(description = "系统提示词")
    private String systemPrompt; // 系统提示词

    @Schema(description = "用户消息提示词")
    private String userPrompt; // 用户消息提示词，{{AGENT_USER_MSG}}引用用户消息

    @Schema(description = "是否开启问题建议")
    private AgentConfig.OpenStatus openSuggest; // 是否开启问题建议

    @Schema(description = "问题建议提示词")
    private String suggestPrompt; // 用户问题建议

    @Schema(description = "首次打开聊天框自动回复消息")
    private String openingChatMsg; // 首次打开聊天框自动回复消息

    @Schema(description = "首次打开引导问题（弃用）")
    private List<String> openingGuidQuestions; // 开场引导问题

    @Schema(description = "引导问题")
    private List<GuidQuestionDto> guidQuestionDtos;

    @Schema(description = "是否开启长期记忆")
    private AgentConfig.OpenStatus openLongMemory; // 是否开启长期记忆

    @Schema(description = "是否开启定时任务")
    private AgentConfig.OpenStatus openScheduledTask;

    @Schema(description = "是否隐藏远程桌面，1 隐藏；0 不隐藏")
    private Integer hideDesktop;

    @Schema(description = "是否允许用户在对话框中选择其他模型, 1 允许，其他不允许")
    private Integer allowOtherModel;

    @Schema(description = "是否允许用户在对话框中@技能， 1 允许，其他不允许")
    private Integer allowAtSkill;

    @Schema(description = "是否允许用户在对话框中选择自己的电脑， 1 允许，其他不允许")
    private Integer allowPrivateSandbox;

    @Schema(description = "是否默认展开扩展页面区域, 1 展开；0 不展开")
    private Integer expandPageArea;

    @Schema(description = "是否隐藏聊天区域，1 隐藏；0 不隐藏")
    private Integer hideChatArea;
}
