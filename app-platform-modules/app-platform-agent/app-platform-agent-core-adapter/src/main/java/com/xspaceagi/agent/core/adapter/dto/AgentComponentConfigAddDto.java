package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class AgentComponentConfigAddDto {

    @Schema(description = "关联的AgentID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "agentId is required")
    private Long agentId; // AgentID

    @Schema(description = "组件类型", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "type is required")
    private AgentComponentConfig.Type type;

    @Schema(description = "关联的组件ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "targetId is required")
    private Long targetId; // 关联的组件ID

    @Schema(description = "工具名称，选择MCP时有用")
    private String toolName;
}
