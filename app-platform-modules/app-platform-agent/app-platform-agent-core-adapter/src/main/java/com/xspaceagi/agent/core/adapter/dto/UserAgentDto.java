package com.xspaceagi.agent.core.adapter.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class UserAgentDto extends UserTargetRelationDto implements Serializable {

    @Schema(description = "智能体ID")
    private Long agentId; // 智能体ID

    @Schema(description = "所属空间ID")
    private Long spaceId;

    @Schema(description = "统计信息", hidden = true)
    private StatisticsDto statistics;

    @Schema(description = "发布者信息", hidden = true)
    private PublishUserDto publishUser;

    @Schema(description = "智能体类型，只关注 ChatBot和PageApp")
    private String agentType;

    @Schema(description = "最后一次会话ID")
    private Long lastConversationId;

    @Schema(description = "最近会话列表")
    private List<ConversationDto> conversationList;
}
