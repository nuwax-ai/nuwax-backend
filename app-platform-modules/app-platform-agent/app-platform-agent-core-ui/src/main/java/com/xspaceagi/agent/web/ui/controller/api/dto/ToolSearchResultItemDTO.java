package com.xspaceagi.agent.web.ui.controller.api.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Builder
@AllArgsConstructor
@NoArgsConstructor
@Data
public class ToolSearchResultItemDTO {

    @Schema(description = "目标类型，Plugin 插件API，Workflow 工作流API，Knowledge 知识库")
    private Published.TargetType targetType;

    @Schema(description = "目标对象ID")
    private Long targetId;

    @Schema(description = "目标对象名称")
    private String name;

    @Schema(description = "工具名称")
    private String toolName;

    @Schema(description = "目标对象描述")
    private String description;

    @Schema(description = "目标对象接口定义")
    private String schema;
}
