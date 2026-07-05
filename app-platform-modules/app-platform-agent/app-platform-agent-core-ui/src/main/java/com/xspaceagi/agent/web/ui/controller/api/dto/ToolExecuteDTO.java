package com.xspaceagi.agent.web.ui.controller.api.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.Map;

@Data
public class ToolExecuteDTO {

    @Schema(description = "开发项目在平台注册的ID，从环境变量中获取 DEV_AGENT_ID")
    private Long devAgentId;

    @Schema(description = "目标类型，Plugin 插件API，Workflow 工作流API，Knowledge 知识库")
    private Published.TargetType targetType;

    @Schema(description = "目标对象ID")
    private Long targetId;

    @Schema(description = "目标对象参数")
    private Map<String, Object> params;
}
