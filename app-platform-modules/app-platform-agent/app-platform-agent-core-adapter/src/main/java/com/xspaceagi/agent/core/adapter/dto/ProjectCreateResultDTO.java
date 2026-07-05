package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class ProjectCreateResultDTO {

    @Schema(description = "目标项目类型")
    private Published.TargetType targetType;

    @Schema(description = "目标项目ID")
    private String targetId;

    @Schema(description = "开发智能体的关联的会话ID")
    private Long conversationId;
}
