package com.xspaceagi.agent.core.adapter.dto;

import com.xspaceagi.agent.core.adapter.dto.config.workflow.AddNodeConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.WorkflowNodeConfig;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.util.Map;

@Data
public class WorkflowNodeAddDto implements Serializable {

    @Schema(description = "工作流ID", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "Workflow ID is required")
    private Long workflowId;

    @Schema(description = "可选，循环节点ID，如果在循环体里添加节点，需要传该参数")
    private Long loopNodeId;

    @Schema(description = "节点类型")
    private WorkflowNodeConfig.NodeType type;

    @Schema(description = "可选，节点类型对应的目标配置ID（工作流、插件为必传字段）")
    private Long typeId;

    @Schema(description = "扩展字段，用于前端存储画布位置等相关配置")
    private Map<String, Object> extension;

    @Schema(description = "可选，节点ID，如果为空则新增节点，不为空则更新节点", requiredMode = Schema.RequiredMode.NOT_REQUIRED, hidden = true)
    private Long id;

    @Schema(description = "节点配置信息,新增时,前端传的节点配置")
    private AddNodeConfigDto nodeConfigDto;
}
