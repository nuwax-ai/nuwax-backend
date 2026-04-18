package com.xspaceagi.agent.core.adapter.dto.config.workflow;

import com.xspaceagi.agent.core.adapter.repository.entity.WorkflowNodeConfig;
import com.xspaceagi.system.spec.annotation.I18n;
import com.xspaceagi.system.spec.annotation.I18nField;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.*;

@I18n(module = "WorkflowNode")
@Data
public class WorkflowNodeDto {
    @Schema(description = "节点ID")
    private Long id;

    @Schema(description = "节点名称")
    private String name;

    @Schema(description = "节点描述")
    private String description;

    @Schema(description = "工作流ID")
    private Long workflowId;

    @I18nField(keyPrefix = true)
    @Schema(description = "节点类型")
    private WorkflowNodeConfig.NodeType type;

    @Schema(description = "上级节点", hidden = true)
    private List<WorkflowNodeDto> preNodes;

    @Schema(description = "节点详细配置信息")
    private NodeConfigDto nodeConfig;

    @Schema(description = "下级节点列表", hidden = true)
    private List<WorkflowNodeDto> nextNodes;

    @Schema(description = "无法再执行到的下级节点ID集合", hidden = true)
    private Set<Long> unreachableNextNodeIds;

    @Schema(description = "下级节点ID列表")
    private List<Long> nextNodeIds; // 使用 String 类型来存储 JSON 数据

    @Schema(description = "正常流程的原始下级节点ID列表", hidden = true)
    private List<Long> originalNextNodeIds;

    @Schema(description = "上级循环节点ID")
    private Long loopNodeId;

    @Schema(description = "循环内部节点列表")
    private List<WorkflowNodeDto> innerNodes;

    @Schema(description = "循环内部节点开始节点ID")
    private Long innerStartNodeId;

    @Schema(description = "循环内部节点结束节点ID")
    private Long innerEndNodeId;

    @Schema(description = "节点执行状态", hidden = true)
    private boolean virtualExecute = false;

    @Schema(description = "循环结束节点", hidden = true)
    private boolean isInnerEndNode = false;

    @Schema(description = "起始节点")
    private WorkflowNodeDto startNode;

    @Schema(description = "结束节点")
    private WorkflowNodeDto endNode;

    private Long spaceId;
    private Date modified;

    private Date created;

    public Set<Long> getUnreachableNextNodeIds() {
        return unreachableNextNodeIds == null ? unreachableNextNodeIds = new HashSet<>() : unreachableNextNodeIds;
    }

    public List<WorkflowNodeDto> getNextNodes() {
        return nextNodes == null ? nextNodes = new ArrayList<>() : nextNodes;
    }

    public List<WorkflowNodeDto> getPreNodes() {
        return preNodes == null ? preNodes = new ArrayList<>() : preNodes;
    }

    @Override
    public String toString() {
        return "id=" + id + ",name=" + name + ",description=" + description + ",nextNodes=" + nextNodes + ",type=" + type;
    }
}
