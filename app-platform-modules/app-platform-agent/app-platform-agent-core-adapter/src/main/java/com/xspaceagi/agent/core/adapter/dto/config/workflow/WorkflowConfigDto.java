package com.xspaceagi.agent.core.adapter.dto.config.workflow;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.dto.CreatorDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.CodePluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.HttpPluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.repository.entity.WorkflowNodeConfig;
import com.xspaceagi.agent.core.spec.enums.PluginTypeEnum;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Data
public class WorkflowConfigDto {

    @Schema(description = "工作流ID")
    private Long id;

    @Schema(description = "空间ID")
    private Long spaceId;

    @Schema(description = "创建者ID")
    private Long creatorId;

    @Schema(description = "工作流名称")
    private String name;

    @Schema(description = "工作流函数名", hidden = true)
    private String functionName;

    @Schema(description = "工作流描述")
    private String description;

    @Schema(description = "图标地址")
    private String icon;

    @Schema(description = "工作流类型: Workflow、AgentFlow")
    private String type;

    @Schema(description = "AgentFlow时所属AgentID")
    private Long agentId;

    @Schema(description = "开始节点", hidden = true)
    private WorkflowNodeDto startNode;

    @Schema(description = "结束节点", hidden = true)
    private WorkflowNodeDto endNode;

    @Schema(description = "工作流入参，也就是起始节点配置的入参")
    private List<Arg> inputArgs;

    @Schema(description = "工作流出参，也就是结束节点配置的出参")
    private List<Arg> outputArgs;

    @Schema(description = "系统变量")
    private List<Arg> systemVariables;

    @Schema(description = "工作流节点列表")
    private List<WorkflowNodeDto> nodes;

    @Schema(description = "发布状态")
    private Published.PublishStatus publishStatus;

    @Schema(description = "最后编辑时间")
    private Date modified;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "创建者信息")
    private CreatorDto creator;

    @Schema(description = "扩展字段，用于前端存储画布位置等相关配置")
    private Map<String, Object> extension;

    @Schema(description = "已发布的范围，用于发布时做默认选中")
    private Published.PublishScope scope;

    private String category;

    @Schema(description = "发布时间，如果不为空，与当前modified时间做对比，如果发布时间小于modified，则前端显示：有更新未发布")
    private Date publishDate;

    @Schema(description = "权限列表")
    private List<String> permissions;

    @Schema(description = "已发布的空间ID", hidden = true)
    private List<Long> publishedSpaceIds;

    @Schema(description = "编辑版本")
    private Long editVersion;

    public List<Arg> getOutputArgs() {
        return outputArgs == null ? new ArrayList<>() : outputArgs;
    }

    public List<Arg> getInputArgs() {
        return inputArgs == null ? new ArrayList<>() : inputArgs;
    }

    public static NodeConfigDto convertToNodeConfigDto(WorkflowNodeConfig.NodeType type, String config) {
        NodeConfigDto nodeConfigDto;
        switch (type) {
            case Code:
                nodeConfigDto = JSON.parseObject(config, CodeNodeConfigDto.class);
                break;
            case Condition:
                nodeConfigDto = JSON.parseObject(config, ConditionNodeConfigDto.class);
                break;
            case End:
                nodeConfigDto = JSON.parseObject(config, EndNodeConfigDto.class);
                break;
            case IntentRecognition:
                nodeConfigDto = JSON.parseObject(config, IntentRecognitionNodeConfigDto.class);
                break;
            case Knowledge:
                nodeConfigDto = JSON.parseObject(config, KnowledgeNodeConfigDto.class);
                break;
            case KnowledgeInsert:
                nodeConfigDto = JSON.parseObject(config, KnowledgeInsertNodeConfigDto.class);
                break;
            case Agent:
                nodeConfigDto = JSON.parseObject(config, AgentNodeConfigDto.class);
                break;
            case Loop:
                nodeConfigDto = JSON.parseObject(config, LoopNodeConfigDto.class);
                break;
            case Output:
                nodeConfigDto = JSON.parseObject(config, ProcessOutputNodeConfigDto.class);
                break;
            case QA:
                nodeConfigDto = JSON.parseObject(config, QaNodeConfigDto.class);
                break;
            case TextProcessing:
                nodeConfigDto = JSON.parseObject(config, TextProcessingNodeConfigDto.class);
                break;
            case Workflow:
                JSONObject jsonObject = JSON.parseObject(config);
                WorkflowAsNodeConfigDto workflowAsNodeConfigDto = jsonObject.toJavaObject(WorkflowAsNodeConfigDto.class);
                WorkflowConfigDto workflowConfigDto = convertToWorkflowConfigDto(jsonObject.getString("workflowConfig"));
                workflowAsNodeConfigDto.setWorkflowConfig(workflowConfigDto);
                nodeConfigDto = workflowAsNodeConfigDto;
                break;
            case LLM:
                nodeConfigDto = JSON.parseObject(config, LLMNodeConfigDto.class);
                break;
            case Mcp:
                nodeConfigDto = JSON.parseObject(config, McpNodeConfigDto.class);
                McpNodeConfigDto mcpNodeConfigDto = (McpNodeConfigDto) nodeConfigDto;
                Object mcp = mcpNodeConfigDto.getMcp();
                if (mcp != null && mcp instanceof JSONObject) {
                    McpDto mcpDto = ((JSONObject) mcp).toJavaObject(McpDto.class);
                    mcpNodeConfigDto.setMcp(mcpDto);
                }
                break;
            case Plugin:
                PluginNodeConfigDto pluginNodeConfigDto = JSON.parseObject(config, PluginNodeConfigDto.class);
                PluginDto pluginConfig = pluginNodeConfigDto.getPluginConfig();
                if (pluginConfig != null && pluginConfig.getConfig() != null && (pluginConfig.getConfig() instanceof JSONObject)) {
                    JSONObject pluginConfigJSONObject = (JSONObject) pluginConfig.getConfig();
                    if (pluginConfig.getType() == PluginTypeEnum.HTTP) {
                        pluginConfig.setConfig(pluginConfigJSONObject.toJavaObject(HttpPluginConfigDto.class));
                    } else {
                        pluginConfig.setConfig(pluginConfigJSONObject.toJavaObject(CodePluginConfigDto.class));
                    }
                }
                nodeConfigDto = pluginNodeConfigDto;
                break;
            case Variable:
                nodeConfigDto = JSON.parseObject(config, VariableNodeConfigDto.class);
                break;
            case HTTPRequest:
                nodeConfigDto = JSON.parseObject(config, HttpNodeConfigDto.class);
                break;
            case TableDataAdd:
                nodeConfigDto = JSON.parseObject(config, TableNodeConfigDto.class);
                break;
            case TableDataDelete:
                nodeConfigDto = JSON.parseObject(config, TableDataDeleteNodeConfigDto.class);
                break;
            case TableDataUpdate:
                nodeConfigDto = JSON.parseObject(config, TableDataUpdateNodeConfigDto.class);
                break;
            case TableDataQuery:
                nodeConfigDto = JSON.parseObject(config, TableDataQueryNodeConfigDto.class);
                break;
            case TableSQL:
                nodeConfigDto = JSON.parseObject(config, TableCustomSqlNodeConfigDto.class);
                break;
            case VariableAggregation:
                nodeConfigDto = JSON.parseObject(config, VariableAggregationNodeConfigDto.class);
                break;
            default:
                nodeConfigDto = JSON.parseObject(config, NodeConfigDto.class);
                break;
        }
        return nodeConfigDto;
    }

    public static WorkflowConfigDto convertToWorkflowConfigDto(String workflowConfigJson) {
        if (workflowConfigJson == null || !JSON.isValid(workflowConfigJson)) {
            return null;
        }
        JSONObject workflowConfigJsonObject = JSON.parseObject(workflowConfigJson);
        if (workflowConfigJsonObject != null) {
            WorkflowConfigDto workflowConfigDto = workflowConfigJsonObject.toJavaObject(WorkflowConfigDto.class);
            workflowConfigDto.getEndNode().setNodeConfig(workflowConfigJsonObject.getJSONObject("endNode").getObject("nodeConfig", EndNodeConfigDto.class));
            List<WorkflowNodeDto> nodes = workflowConfigJsonObject.getJSONArray("nodes").stream().map(node -> {
                JSONObject nodeJson = (JSONObject) node;
                WorkflowNodeDto workflowNodeDto = nodeJson.toJavaObject(WorkflowNodeDto.class);
                NodeConfigDto nodeConfigDto = WorkflowConfigDto.convertToNodeConfigDto(WorkflowNodeConfig.NodeType.valueOf(nodeJson.getString("type")), nodeJson.getString("nodeConfig"));
                workflowNodeDto.setNodeConfig(nodeConfigDto);
                workflowNodeDto.setSpaceId(workflowConfigDto.getSpaceId());
                return workflowNodeDto;
            }).collect(Collectors.toList());
            workflowConfigDto.setNodes(nodes);
            return workflowConfigDto;
        }
        return null;
    }

    public enum Type {
        Workflow,
        AgentFlow
    }
}
