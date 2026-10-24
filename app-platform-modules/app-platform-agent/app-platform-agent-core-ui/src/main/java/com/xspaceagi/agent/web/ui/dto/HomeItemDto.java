package com.xspaceagi.agent.web.ui.dto;

import com.xspaceagi.agent.core.adapter.dto.PublishUserDto;
import com.xspaceagi.agent.core.adapter.dto.StatisticsDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class HomeItemDto {

    @Schema(description = "目标对象（智能体、工作流、插件）ID")
    private Published.TargetType targetType;

    @Schema(description = "目标对象（智能体、工作流、插件）ID")
    private Long targetId;

    @Schema(description = "名称")
    private String name;

    @Schema(description = "描述")
    private String description;

    @Schema(description = "图标")
    private String icon;

    @Schema(description = "统计信息")
    private StatisticsDto statistics;

    @Schema(description = "发布者信息")
    private PublishUserDto publishUser;

    @Schema(description = "当前登录用户是否收藏")
    private boolean isCollect;

    private Integer sort;

    @Schema(description = "智能体类型，只关注 ChatBot和PageApp")
    private String agentType;

    @Schema(description = "最后一次会话ID")
    private Long lastConversationId;

    private Object extra;
}
