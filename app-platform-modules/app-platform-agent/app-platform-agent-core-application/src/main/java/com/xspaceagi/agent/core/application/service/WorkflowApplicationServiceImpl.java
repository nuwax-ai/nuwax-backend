package com.xspaceagi.agent.core.application.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.google.common.collect.Lists;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.*;
import com.xspaceagi.agent.core.adapter.dto.config.bind.ModelBindConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.*;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.NodeConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.*;
import com.xspaceagi.agent.core.domain.service.ConfigHistoryDomainService;
import com.xspaceagi.agent.core.domain.service.WorkflowDomainService;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowExecutor;
import com.xspaceagi.agent.core.infra.component.workflow.enums.NodeExecuteStatus;
import com.xspaceagi.agent.core.infra.component.workflow.handler.QANodeHandler;
import com.xspaceagi.agent.core.infra.converter.ArgConverter;
import com.xspaceagi.agent.core.infra.rpc.DbTableRpcService;
import com.xspaceagi.agent.core.infra.rpc.KnowledgeRpcService;
import com.xspaceagi.agent.core.infra.rpc.McpRpcService;
import com.xspaceagi.agent.core.infra.rpc.ResourcePricingRpcService;
import com.xspaceagi.agent.core.spec.enums.*;
import com.xspaceagi.compose.sdk.request.DorisTableDefineRequest;
import com.xspaceagi.compose.sdk.service.IComposeDbTableRpcService;
import com.xspaceagi.compose.sdk.vo.define.TableDefineVo;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.knowledge.sdk.request.KnowledgeCreateRequestVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeConfigVo;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.dto.McpToolDto;
import com.xspaceagi.pricing.sdk.dto.PriceEstimate;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.spec.utils.RedisUtil;
import com.xspaceagi.system.spec.utils.TimeWheel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WorkflowApplicationServiceImpl implements WorkflowApplicationService {

    @Resource
    private WorkflowDomainService workflowDomainService;

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private ConfigHistoryDomainService configHistoryDomainService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private KnowledgeRpcService KnowledgeRpcService;

    @Resource
    private IComposeDbTableRpcService iComposeDbTableRpcService;

    @Resource
    private DbTableRpcService dbTableRpcService;

    @Resource
    private McpRpcService mcpRpcService;

    @Resource
    private RedisUtil redisUtil;


    @Resource
    private QANodeHandler qaNodeHandler;

    @Resource
    private TimeWheel timeWheel;

    @Resource
    private IFileAccessService iFileAccessService;

    @Resource
    private WorkflowExecutor workflowExecutor;

    @Resource
    private ResourcePricingRpcService resourcePricingRpcService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Override
    public Long add(WorkflowConfigDto workflowConfigDto) {
        WorkflowConfig workflowConfig = WorkflowConfig.builder()
                .spaceId(workflowConfigDto.getSpaceId())
                .type(workflowConfigDto.getType())
                .agentId(workflowConfigDto.getAgentId())
                .name(workflowConfigDto.getName())
                .description(workflowConfigDto.getDescription())
                .icon(workflowConfigDto.getIcon())
                .creatorId(workflowConfigDto.getCreatorId())
                .build();
        workflowConfig.setId(workflowConfigDto.getId());
        workflowDomainService.add(workflowConfig);
        addConfigHistory(workflowConfig.getId(), ConfigHistory.Type.Add, I18nUtil.systemMessage("Workflow.ConfigHistory.Add"));
        return workflowConfig.getId();
    }

    @Override
    @Transactional
    public void save(JSONObject workflowConfigJson, WorkflowConfigDto oldWorkflowConfigDto) {
        Long workflowId = workflowConfigJson.getLong("id");
        String name = workflowConfigJson.getString("name");
        String description = workflowConfigJson.getString("description");
        String icon = workflowConfigJson.getString("icon");
        String extension = workflowConfigJson.getString("extension");
        workflowDomainService.update(WorkflowConfig.builder()
                .id(workflowId)
                .name(name)
                .description(description)
                .icon(icon)
                .ext(extension)
                .build());
        Map<Long, WorkflowNodeDto> nodeDtoMap = oldWorkflowConfigDto.getNodes().stream().collect(Collectors.toMap(WorkflowNodeDto::getId, node -> node, (old, newNode) -> newNode));
        Set<Long> nodeIdSet = new HashSet<>();
        JSONArray nodes = workflowConfigJson.getJSONArray("nodes");
        boolean hasStartNode = false;
        boolean hasEndNode = false;
        for (int i = 0; i < nodes.size(); i++) {
            JSONObject node = nodes.getJSONObject(i);
            Long id = node.getLong("id");
            if (id == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, "Node ID");
            }
            nodeIdSet.add(id);
            if (node.getString("type") == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, I18nUtil.systemMessage("Workflow.Validate.NodeType"));
            }
            WorkflowNodeConfig.NodeType type = WorkflowNodeConfig.NodeType.valueOf(node.getString("type"));
            //前端自定义的节点新增，如果主键冲突会报错
            if (!nodeDtoMap.containsKey(id)) {
                WorkflowNodeAddDto workflowNodeAddDto = new WorkflowNodeAddDto();
                workflowNodeAddDto.setWorkflowId(workflowId);
                workflowNodeAddDto.setType(type);
                workflowNodeAddDto.setTypeId(node.getLong("typeId"));
                workflowNodeAddDto.setLoopNodeId(node.getLong("loopNodeId"));
                workflowNodeAddDto.setExtension(node.getJSONObject("extension"));
                workflowNodeAddDto.setId(id);
                addWorkflowNode(workflowNodeAddDto);
            }

            WorkflowNodeUpdateDto<NodeConfigDto> workflowNodeDto = new WorkflowNodeUpdateDto<NodeConfigDto>();
            workflowNodeDto.setNodeId(id);
            workflowNodeDto.setName(node.getString("name"));
            workflowNodeDto.setDescription(node.getString("description"));
            workflowNodeDto.setInnerStartNodeId(node.getLong("innerStartNodeId"));
            workflowNodeDto.setInnerEndNodeId(node.getLong("innerEndNodeId"));
            workflowNodeDto.setLoopNodeId(node.getLong("loopNodeId"));
            if (node.getJSONArray("nextNodeIds") != null) {
                workflowNodeDto.setUpdateNextNodeIds(node.getJSONArray("nextNodeIds").toJavaList(Long.class));
            }
            NodeConfigDto nodeConfigDto = WorkflowConfigDto.convertToNodeConfigDto(type, node.getString("nodeConfig"));
            if (nodeConfigDto == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.fieldRequiredButEmpty, I18nUtil.systemMessage("Workflow.Validate.NodeConfig"));
            }
            workflowNodeDto.setNodeConfig(nodeConfigDto);
            updateWorkflowNodeConfig(workflowNodeDto);
            if (type == WorkflowNodeConfig.NodeType.Start) {
                hasStartNode = true;
            }
            if (type == WorkflowNodeConfig.NodeType.End) {
                hasEndNode = true;
            }
        }

        if (!hasStartNode) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowMissingStartNode);
        }
        if (!hasEndNode) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowMissingEndNode);
        }

        //删除多余的节点
        oldWorkflowConfigDto.getNodes().forEach(node -> {
            if (!nodeIdSet.contains(node.getId())) {
                workflowDomainService.deleteWorkflowNode(node.getId());
            }
        });
        addConfigHistory(workflowId, ConfigHistory.Type.Edit, I18nUtil.systemMessage("Workflow.ConfigHistory.UpdateConfig"));
    }

    @Override
    public void update(WorkflowConfigDto workflowConfigDto) {
        WorkflowConfig workflowConfig = new WorkflowConfig();
        BeanUtils.copyProperties(workflowConfigDto, workflowConfig);
        if (workflowConfigDto.getExtension() != null) {
            workflowConfig.setExt(JSON.toJSONString(workflowConfigDto.getExtension()));
        }
        workflowDomainService.update(workflowConfig);
        addConfigHistory(workflowConfig.getId(), ConfigHistory.Type.Edit, I18nUtil.systemMessage("Workflow.ConfigHistory.Edit"));
    }

    @Override
    public void delete(Long workflowId) {
        workflowDomainService.delete(workflowId);
    }

    @Override
    public Long copyWorkflow(Long userId, Long workflowId) {
        Long newWorkflowId = workflowDomainService.copy(userId, workflowId);
        addConfigHistory(newWorkflowId, ConfigHistory.Type.Add, I18nUtil.systemMessage("Workflow.ConfigHistory.Copy"));
        return newWorkflowId;
    }

    @Override
    public Long copyWorkflow(Long userId, WorkflowConfigDto workflowConfigDto, Long targetSpaceId) {
        WorkflowConfig workflowConfig = new WorkflowConfig();
        BeanUtils.copyProperties(workflowConfigDto, workflowConfig);
        List<WorkflowNodeConfig> workflowNodeConfigs = new ArrayList<>();
        workflowConfigDto.getNodes().forEach(workflowNodeDto -> {
            WorkflowNodeConfig workflowNodeConfig1 = new WorkflowNodeConfig();
            BeanUtils.copyProperties(workflowNodeDto, workflowNodeConfig1);
            //本空间内复制
            if (workflowConfigDto.getSpaceId().equals(targetSpaceId)) {
                workflowNodeConfig1.setConfig(JSONObject.toJSONString(workflowNodeDto.getNodeConfig()));
                workflowNodeConfigs.add(workflowNodeConfig1);
                return;
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.LLM) {
                LLMNodeConfigDto llmNodeConfigDto = (LLMNodeConfigDto) workflowNodeDto.getNodeConfig();
                Long newModelId = generateNewModelId(llmNodeConfigDto.getModelId(), targetSpaceId);
                llmNodeConfigDto.setModelId(newModelId);
                if (org.apache.commons.collections4.CollectionUtils.isNotEmpty(llmNodeConfigDto.getSkillComponentConfigs())) {
                    llmNodeConfigDto.getSkillComponentConfigs().forEach(skillComponentConfigDto -> {
                        if (skillComponentConfigDto.getType() == LLMNodeConfigDto.SkillComponentConfigDto.Type.Workflow) {
                            Long newTargetId = publishApplicationService.copyPublish(userId, Published.TargetType.Workflow, skillComponentConfigDto.getTypeId(), workflowConfigDto.getSpaceId(), targetSpaceId);
                            skillComponentConfigDto.setTypeId(newTargetId);
                        }
                        if (skillComponentConfigDto.getType() == LLMNodeConfigDto.SkillComponentConfigDto.Type.Plugin) {
                            Long newTargetId = publishApplicationService.copyPublish(userId, Published.TargetType.Plugin, skillComponentConfigDto.getTypeId(), workflowConfigDto.getSpaceId(), targetSpaceId);
                            skillComponentConfigDto.setTypeId(newTargetId);
                        }
                    });
                }
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.IntentRecognition) {
                IntentRecognitionNodeConfigDto intentRecognitionNodeConfigDto = (IntentRecognitionNodeConfigDto) workflowNodeDto.getNodeConfig();
                Long newModelId = generateNewModelId(intentRecognitionNodeConfigDto.getModelId(), targetSpaceId);
                intentRecognitionNodeConfigDto.setModelId(newModelId);
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.QA) {
                QaNodeConfigDto qaNodeConfigDto = (QaNodeConfigDto) workflowNodeDto.getNodeConfig();
                Long newModelId = generateNewModelId(qaNodeConfigDto.getModelId(), targetSpaceId);
                qaNodeConfigDto.setModelId(newModelId);
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Workflow) {
                WorkflowAsNodeConfigDto workflowAsNodeConfigDto = (WorkflowAsNodeConfigDto) workflowNodeDto.getNodeConfig();
                workflowNodeConfig1.setName(workflowNodeDto.getName());
                Long newTargetId = publishApplicationService.copyPublish(userId, Published.TargetType.Workflow, workflowAsNodeConfigDto.getWorkflowId(), workflowConfigDto.getSpaceId(), targetSpaceId);
                workflowAsNodeConfigDto.setWorkflowId(newTargetId);
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Plugin) {
                PluginNodeConfigDto pluginNodeConfigDto = (PluginNodeConfigDto) workflowNodeDto.getNodeConfig();
                Long newTargetId = publishApplicationService.copyPublish(userId, Published.TargetType.Plugin, pluginNodeConfigDto.getPluginId(), workflowConfigDto.getSpaceId(), targetSpaceId);
                pluginNodeConfigDto.setPluginId(newTargetId);
            }
            //知识库创建空库
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Knowledge && !workflowConfigDto.getSpaceId().equals(targetSpaceId)) {
                KnowledgeNodeConfigDto knowledgeNodeConfigDto = (KnowledgeNodeConfigDto) workflowNodeDto.getNodeConfig();
                if (!CollectionUtils.isEmpty(knowledgeNodeConfigDto.getKnowledgeBaseConfigs())) {
                    knowledgeNodeConfigDto.getKnowledgeBaseConfigs().forEach(knowledgeBaseConfigDto -> {
                        KnowledgeCreateRequestVo knowledgeCreateRequestVo = KnowledgeCreateRequestVo.builder()
                                .icon(knowledgeBaseConfigDto.getIcon())
                                .description(knowledgeBaseConfigDto.getDescription())
                                .name(knowledgeBaseConfigDto.getName())
                                .dataType(1)
                                .spaceId(targetSpaceId)
                                .userId(userId)
                                .build();
                        Long knowledgeConfigId = KnowledgeRpcService.createKnowledgeConfig(knowledgeCreateRequestVo, knowledgeBaseConfigDto.getKnowledgeBaseId());
                        knowledgeBaseConfigDto.setKnowledgeBaseId(knowledgeConfigId);
                    });
                }
            }

            //知识库创建空库
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.KnowledgeInsert && !workflowConfigDto.getSpaceId().equals(targetSpaceId)) {
                KnowledgeInsertNodeConfigDto knowledgeInsertNodeConfigDto = (KnowledgeInsertNodeConfigDto) workflowNodeDto.getNodeConfig();
                if (knowledgeInsertNodeConfigDto.getKnowledgeBaseId() != null) {
                    KnowledgeCreateRequestVo knowledgeCreateRequestVo = KnowledgeCreateRequestVo.builder()
                            .icon(knowledgeInsertNodeConfigDto.getIcon())
                            .description(knowledgeInsertNodeConfigDto.getDescription())
                            .name(knowledgeInsertNodeConfigDto.getName())
                            .dataType(1)
                            .spaceId(targetSpaceId)
                            .userId(userId)
                            .build();
                    Long knowledgeConfigId = KnowledgeRpcService.createKnowledgeConfig(knowledgeCreateRequestVo, knowledgeInsertNodeConfigDto.getKnowledgeBaseId());
                    knowledgeInsertNodeConfigDto.setKnowledgeBaseId(knowledgeConfigId);
                }
            }

            //数据表结构复制
            if (workflowNodeDto.getType().name().startsWith("Table")) {
                TableNodeConfigDto tableNodeConfigDto = (TableNodeConfigDto) workflowNodeDto.getNodeConfig();
                tableNodeConfigDto.setTableId(dbTableRpcService.createNewTableDefinition(userId, targetSpaceId, tableNodeConfigDto.getTableId()));
            }

            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Mcp && !workflowConfigDto.getSpaceId().equals(targetSpaceId)) {
                McpNodeConfigDto mcpNodeConfigDto = (McpNodeConfigDto) workflowNodeDto.getNodeConfig();
                McpDto mcpDto = mcpRpcService.queryMcp(mcpNodeConfigDto.getMcpId(), workflowConfigDto.getSpaceId());
                if (mcpDto == null) {
                    return;
                }
                Long newMcpId = mcpRpcService.addAndDeployMcp(userId, targetSpaceId, mcpDto);
                mcpNodeConfigDto.setMcpId(newMcpId);
            }

            workflowNodeConfig1.setConfig(JSONObject.toJSONString(workflowNodeDto.getNodeConfig()));
            workflowNodeConfigs.add(workflowNodeConfig1);
        });
        Long newWorkflowId = workflowDomainService.copy(userId, workflowConfig, workflowNodeConfigs, targetSpaceId);
        addConfigHistory(newWorkflowId, ConfigHistory.Type.Add, I18nUtil.systemMessage("Workflow.ConfigHistory.Copy"), workflowConfigDto.getId());
        return newWorkflowId;
    }

    @Override
    public void restoreWorkflow(WorkflowConfigDto workflowConfigDto) {
        WorkflowConfig workflowConfig = new WorkflowConfig();
        BeanUtils.copyProperties(workflowConfigDto, workflowConfig);
        List<WorkflowNodeConfig> workflowNodeConfigs = new ArrayList<>();
        workflowConfigDto.getNodes().forEach(workflowNodeDto -> {
            WorkflowNodeConfig workflowNodeConfig = new WorkflowNodeConfig();
            BeanUtils.copyProperties(workflowNodeDto, workflowNodeConfig);
            workflowNodeConfig.setConfig(JSONObject.toJSONString(workflowNodeDto.getNodeConfig()));
            workflowNodeConfigs.add(workflowNodeConfig);
        });
        workflowDomainService.restoreWorkflow(workflowConfig, workflowNodeConfigs);
        addConfigHistory(workflowConfig.getId(), ConfigHistory.Type.Edit, I18nUtil.systemMessage("Workflow.ConfigHistory.Restore"));
    }

    public void restoreWorkflow(String historyConfig) {
        JSONObject jsonObject = JSONObject.parseObject(historyConfig);
        WorkflowConfig workflowConfig = jsonObject.getJSONObject("workflowConfig").toJavaObject(WorkflowConfig.class);
        List<WorkflowNodeConfig> workflowNodeConfigs = jsonObject.getJSONArray("workflowNodeConfigs").toJavaList(WorkflowNodeConfig.class);
        workflowDomainService.restoreWorkflow(workflowConfig, workflowNodeConfigs);
        addConfigHistory(workflowConfig.getId(), ConfigHistory.Type.Edit, I18nUtil.systemMessage("Workflow.ConfigHistory.Restore"));
    }

    private Long generateNewModelId(Long modelId, Long targetSpaceId) {
        if (modelId == null) {
            return null;
        }
        if (targetSpaceId == -1L) {
            ModelConfigDto modelConfigDto = modelApplicationService.queryDefaultModelConfig();
            if (modelConfigDto != null) {
                return modelConfigDto.getId();
            } else {
                return null;
            }
        }
        ModelConfigDto modelConfigDto = modelApplicationService.queryModelConfigById(modelId);
        if (modelConfigDto != null) {
            if (modelConfigDto.getScope() == ModelConfig.ModelScopeEnum.Space
                    && (modelConfigDto.getSpaceId() == null || !modelConfigDto.getSpaceId().equals(targetSpaceId))) {
                ModelConfigDto modelConfigDto1 = modelApplicationService.queryDefaultModelConfig();
                if (modelConfigDto1 != null) {
                    return modelConfigDto1.getId();
                }
            }
        }
        return modelId;
    }

    @Override
    public void transfer(Long workflowId, Long targetSpaceId) {
        workflowDomainService.transfer(workflowId, targetSpaceId);
        addConfigHistory(workflowId, ConfigHistory.Type.Edit, I18nUtil.systemMessage("Workflow.ConfigHistory.SpaceTransfer"));
    }

    @Override
    public void deleteBySpaceId(Long spaceId) {
        workflowDomainService.deleteBySpaceId(spaceId);
    }

    @Override
    public List<WorkflowConfigDto> queryListBySpaceId(Long spaceId) {
        List<WorkflowConfig> workflowConfigs = workflowDomainService.queryListBySpaceId(spaceId);
        //workflowConfigs转workflowConfigDtos
        return convertWorkflowConfigs(workflowConfigs);
    }


    @Override
    public List<WorkflowConfigDto> queryListByIds(List<Long> workflowIds) {
        List<WorkflowConfig> workflowConfigs = workflowDomainService.queryListByIds(workflowIds);
        return convertWorkflowConfigs(workflowConfigs);
    }

    @Override
    public WorkflowConfigDto queryById(Long workflowId) {
        WorkflowConfig workflowConfig = workflowDomainService.queryById(workflowId);
        if (workflowConfig == null) {
            return null;
        }

        List<Arg> agentFLowArgs = null;//agentFlow补全开始节点的输出参数
        if (WorkflowConfigDto.Type.AgentFlow.name().equals(workflowConfig.getType()) && workflowConfig.getAgentId() != null) {
            List<AgentComponentConfigDto> agentComponentConfigs = agentApplicationService.queryComponentConfigListByAgentIdAndType(workflowConfig.getAgentId(), AgentComponentConfig.Type.Variable);
            if (!CollectionUtils.isEmpty(agentComponentConfigs) && agentComponentConfigs.get(0).getBindConfig() instanceof VariableConfigDto variableConfig) {
                agentFLowArgs = variableConfig.getVariables();
            }
        }

        WorkflowConfigDto workflowConfigDto = new WorkflowConfigDto();
        BeanUtils.copyProperties(workflowConfig, workflowConfigDto);
        completeCreator(List.of(workflowConfigDto));
        WorkflowNodeDto startNodeDto = queryWorkflowNode(workflowConfig.getStartNodeId());
        if (startNodeDto == null) {
            throw new BizException("Workflow configuration exception, missing start node, please delete and recreate.");
        }
        workflowConfigDto.setInputArgs(startNodeDto.getNodeConfig().getInputArgs());
        WorkflowNodeDto endNodeDto = queryWorkflowNode(workflowConfig.getEndNodeId());
        if (endNodeDto == null) {
            throw new BizException("Workflow configuration exception, missing end node, please delete and recreate.");
        }
        workflowConfigDto.setOutputArgs(endNodeDto.getNodeConfig().getOutputArgs());
        workflowConfigDto.setStartNode(startNodeDto);
        workflowConfigDto.setEndNode(endNodeDto);
        workflowConfigDto.setNodes(queryWorkflowNodeList(workflowId));
        workflowConfigDto.setExtension(JSON.parseObject(workflowConfig.getExt()));
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Workflow, workflowId);
        if (publishedDto != null) {
            workflowConfigDto.setScope(publishedDto.getScope());
            workflowConfigDto.setPublishDate(publishedDto.getModified());
            workflowConfigDto.setCategory(publishedDto.getCategory());
        }
        //更新聚合变量出参关联的参数列表
        completeVariableAggregateOutputArgConfig(workflowConfigDto.getNodes());
        workflowConfigDto.setSystemVariables(Arg.getSystemVariableArgs());
        if (agentFLowArgs != null) {
            // AgentFlow的系统变量不展示上下文
            List<Arg> systemVariables = Arg.getSystemVariableArgs().stream().filter(arg -> !GlobalVariableEnum.CHAT_CONTEXT.name().equals(arg.getName())).toList();
            workflowConfigDto.setSystemVariables(systemVariables);
            WorkflowNodeDto workflowNodeDto = workflowConfigDto.getNodes().stream().filter(node -> node.getType() == WorkflowNodeConfig.NodeType.Start).findFirst().orElse(null);
            if (workflowNodeDto != null) {
                workflowNodeDto.getNodeConfig().setInputArgs(new ArrayList<>());
                agentFLowArgs.forEach(arg -> {
                    if (!GlobalVariableEnum.isSystemVariable(arg.getName())) {
                        arg.setSystemVariable(true);
                        workflowNodeDto.getNodeConfig().getInputArgs().add(arg);
                    }
                });
            }
        }
        if (WorkflowConfigDto.Type.AgentFlow.name().equals(workflowConfig.getType()) && workflowConfig.getAgentId() != null) {
            workflowConfigDto.getNodes().stream().filter(node -> node.getType() == WorkflowNodeConfig.NodeType.End).findFirst().ifPresent(workflowNodeDto -> workflowNodeDto.getNodeConfig().setOutputArgs(new ArrayList<>()));
        }
        return workflowConfigDto;
    }

    @Override
    public WorkflowConfigDto queryByIdWithoutNodes(Long workflowId) {
        WorkflowConfig workflowConfig = workflowDomainService.queryById(workflowId);
        if (workflowConfig == null) {
            return null;
        }
        WorkflowConfigDto workflowConfigDto = new WorkflowConfigDto();
        BeanUtils.copyProperties(workflowConfig, workflowConfigDto);
        return workflowConfigDto;
    }

    @Override
    public WorkflowConfigDto queryPublishedWorkflowConfig(Long workflowId, Long spaceId, boolean forExecute) {
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Workflow, workflowId);
        if (publishedDto == null) {
            return null;
        }
        if (spaceId != null && publishedDto.getPublishedSpaceIds() != null && !publishedDto.getPublishedSpaceIds().contains(spaceId)) {
            return null;
        }
        JSONObject jsonObject = JSON.parseObject(publishedDto.getConfig());
        WorkflowConfigDto workflowConfigDto = JSON.parseObject(publishedDto.getConfig(), WorkflowConfigDto.class);
        if (jsonObject == null || workflowConfigDto == null) {
            return null;
        }

        Map<String, Arg> argMap = new HashMap<>();
        List<WorkflowNodeDto> nodes = jsonObject.getJSONArray("nodes").stream().map(node -> {
            JSONObject nodeJson = (JSONObject) node;
            WorkflowNodeDto workflowNodeDto = nodeJson.toJavaObject(WorkflowNodeDto.class);
            NodeConfigDto nodeConfigDto = WorkflowConfigDto.convertToNodeConfigDto(WorkflowNodeConfig.NodeType.valueOf(nodeJson.getString("type")), nodeJson.getString("nodeConfig"));
            workflowNodeDto.setNodeConfig(nodeConfigDto);
            workflowNodeDto.setSpaceId(workflowConfigDto.getSpaceId());
            if (forExecute) {
                completeConfigAndArgs(workflowNodeDto, true);
            }
            generateKey(workflowNodeDto.getId().toString(), nodeConfigDto.getOutputArgs(), argMap);
            return workflowNodeDto;
        }).collect(Collectors.toList());
        workflowConfigDto.setStartNode(nodes.stream().filter(node -> node.getType() == WorkflowNodeConfig.NodeType.Start).findFirst().orElse(null));
        workflowConfigDto.setEndNode(nodes.stream().filter(node -> node.getType() == WorkflowNodeConfig.NodeType.End).findFirst().orElse(null));
        List<Arg> outputArgs = workflowConfigDto.getEndNode().getNodeConfig().getOutputArgs();
        if (outputArgs != null) {
            outputArgs.forEach(outputArg -> {
                if (outputArg.getBindValueType() != null && outputArg.getBindValueType() == Arg.BindValueType.Reference) {
                    //根据 outputArg.getBindValue() 查找对应的 outputArg
                    Arg arg = argMap.get(outputArg.getBindValue());
                    if (arg != null && arg.getSubArgs() != null) {
                        outputArg.setSubArgs(arg.getSubArgs());
                    }
                }
            });
            workflowConfigDto.setOutputArgs(workflowConfigDto.getEndNode().getNodeConfig().getOutputArgs());
        }
        workflowConfigDto.setNodes(nodes);
        workflowConfigDto.setPublishedSpaceIds(publishedDto.getPublishedSpaceIds());
        workflowConfigDto.setScope(publishedDto.getScope());
        workflowConfigDto.setPublishDate(publishedDto.getModified());
        workflowConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(workflowConfigDto.getIcon(), workflowConfigDto.getName(), "workflow"));
        return workflowConfigDto;
    }

    @Override
    public WorkflowConfigDto queryPublishedWorkflowConfig(Long workflowId, Long spaceId) {
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Workflow, workflowId);
        if (publishedDto == null) {
            return null;
        }
        if (spaceId != null && publishedDto.getPublishedSpaceIds() != null && !publishedDto.getPublishedSpaceIds().contains(spaceId)) {
            return null;
        }

        return JSON.parseObject(publishedDto.getConfig(), WorkflowConfigDto.class);
    }

    @Override
    public List<WorkflowConfigDto> queryPublishedWorkflowConfigs(List<Long> workflowIds) {
        List<PublishedDto> publishedList = publishApplicationService.queryPublishedList(Published.TargetType.Workflow, workflowIds, null, true);
        return publishedList.stream().map(publishedDto -> JSON.parseObject(publishedDto.getConfig(), WorkflowConfigDto.class)).filter(Objects::nonNull).toList();
    }

    @Override
    public Long addWorkflowNode(WorkflowNodeAddDto workflowNodeAddDto) {
        String name = workflowNodeAddDto.getType().getName();
        String description = workflowNodeAddDto.getType().getDescription();
        NodeConfigDto nodeConfigDto = new NodeConfigDto();
        nodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
        nodeConfigDto.setInputArgs(List.of());
        nodeConfigDto.setOutputArgs(List.of());
        //当前空间的ID
        Long spaceId = queryById(workflowNodeAddDto.getWorkflowId()).getSpaceId();

        //nodeConfigDto 序列化的json配置,最终入库的json;初始默认一个基础的json配置
        String configJson = JSON.toJSONString(nodeConfigDto);
        switch (workflowNodeAddDto.getType()) {
            case Workflow -> {
                if (workflowNodeAddDto.getTypeId().equals(workflowNodeAddDto.getWorkflowId())) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowSelfReference);
                }
                WorkflowConfig workflowConfig = workflowDomainService.queryById(workflowNodeAddDto.getTypeId());
                if (workflowConfig == null) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowNotFound);
                }
                name = workflowConfig.getName();
                description = workflowConfig.getDescription();
                WorkflowConfigDto workflowConfigDto = queryPublishedWorkflowConfig(workflowNodeAddDto.getTypeId(), spaceId);
                if (workflowConfigDto == null) {
                    //数据存在异常
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowNotPublished);
                }
                nodeConfigDto = WorkflowAsNodeConfigDto.builder().workflowId(workflowNodeAddDto.getTypeId()).build();
                nodeConfigDto.setInputArgs(workflowConfigDto.getInputArgs());
                nodeConfigDto.setOutputArgs(workflowConfigDto.getOutputArgs());
                nodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                configJson = JSON.toJSONString(nodeConfigDto);
            }
            case LLM -> {
                ModelConfigDto modelConfigDto = modelApplicationService.queryDefaultModelConfig();
                LLMNodeConfigDto llmNodeConfigDto = new LLMNodeConfigDto();
                llmNodeConfigDto.setMaxTokens(modelConfigDto.getMaxTokens() == null ? 1024 : modelConfigDto.getMaxTokens());
                llmNodeConfigDto.setTemperature(0.7);
                llmNodeConfigDto.setTopP(0.7);
                llmNodeConfigDto.setModelId(modelConfigDto.getId());
                llmNodeConfigDto.setSkillComponentConfigs(new ArrayList<>());
                llmNodeConfigDto.setInputArgs(List.of());
                llmNodeConfigDto.setMode(ModelBindConfigDto.Mode.Balanced);
                llmNodeConfigDto.setOutputType(OutputTypeEnum.Text);
                OutputArg outputArg = new OutputArg();
                outputArg.setName("output");
                outputArg.setKey("output");
                outputArg.setDataType(DataTypeEnum.String);
                llmNodeConfigDto.setOutputArgs(List.of(outputArg));
                llmNodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                nodeConfigDto = llmNodeConfigDto;
                configJson = JSON.toJSONString(nodeConfigDto);

            }
            case Plugin -> {
                PluginDto pluginDto = pluginApplicationService.queryById(workflowNodeAddDto.getTypeId());
                if (pluginDto == null) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPluginNotFound);
                }
                PluginDto publishedPluginDto = pluginApplicationService.queryPublishedPluginConfig(workflowNodeAddDto.getTypeId(), spaceId);
                if (publishedPluginDto == null) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.permissionDenied);
                }
                name = pluginDto.getName();
                description = pluginDto.getDescription();
                PluginNodeConfigDto pluginNodeConfigDto = new PluginNodeConfigDto();
                pluginNodeConfigDto.setPluginId(workflowNodeAddDto.getTypeId());
                pluginNodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                nodeConfigDto = pluginNodeConfigDto;
                configJson = JSON.toJSONString(nodeConfigDto);
            }
            case Mcp -> {
                McpDto mcpDto = mcpRpcService.queryMcp(workflowNodeAddDto.getTypeId(), spaceId);
                if (mcpDto == null) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.mcpNotFound);
                }
                name = mcpDto.getName() + "/" + workflowNodeAddDto.getNodeConfigDto().getToolName();
                McpNodeConfigDto mcpNodeConfigDto = new McpNodeConfigDto();
                mcpNodeConfigDto.setMcpId(workflowNodeAddDto.getTypeId());
                mcpNodeConfigDto.setToolName(workflowNodeAddDto.getNodeConfigDto().getToolName());
                mcpNodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                nodeConfigDto = mcpNodeConfigDto;
                configJson = JSON.toJSONString(nodeConfigDto);
            }
            case Knowledge -> {
                var knowledgeBaseConfigs = Optional.ofNullable(workflowNodeAddDto.getNodeConfigDto())
                        .map(AddNodeConfigDto::getKnowledgeBaseConfigs)
                        .orElse(null);
                KnowledgeNodeConfigDto knowledgeNodeConfigDto = KnowledgeNodeConfigDto.addFrom(nodeConfigDto, knowledgeBaseConfigs);
                knowledgeNodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                configJson = JSON.toJSONString(knowledgeNodeConfigDto);
            }
            case KnowledgeInsert -> {
                KnowledgeInsertNodeConfigDto knowledgeInsertNodeConfigDto = KnowledgeInsertNodeConfigDto.addFrom(workflowNodeAddDto.getTypeId());
                knowledgeInsertNodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                configJson = JSON.toJSONString(knowledgeInsertNodeConfigDto);
            }
            case Agent -> {
                Assert.isTrue(workflowNodeAddDto.getTypeId() != null, "Agent ID cannot be null");
                AgentConfigDto agentConfigDto = agentApplicationService.queryPublishedConfig(workflowNodeAddDto.getTypeId(), false);
                if (agentConfigDto == null) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentNotFound);
                }
                name = agentConfigDto.getName();
                description = agentConfigDto.getDescription();
                AgentNodeConfigDto agentNodeConfigDto = new AgentNodeConfigDto();
                agentNodeConfigDto.setAgentId(workflowNodeAddDto.getTypeId());
                agentNodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                configJson = JSON.toJSONString(agentNodeConfigDto);
            }
            case LongTermMemory -> {
                List<Arg> args = new ArrayList<>();
                Arg arg = new InputArg();
                arg.setName("Query");
                arg.setDataType(DataTypeEnum.String);
                arg.setRequire(true);
                args.add(arg);
                nodeConfigDto.setInputArgs(args);

                List<Arg> outputArgs = new ArrayList<>();
                OutputArg outArg = new OutputArg();
                outArg.setName("outputList");
                outArg.setDataType(DataTypeEnum.Array_Object);
                outputArgs.add(outArg);
                List<Arg> subArgs = new ArrayList<>();
                OutputArg subArg = new OutputArg();
                subArg.setName("output");
                subArg.setDataType(DataTypeEnum.String);
                subArgs.add(subArg);
                outArg.setSubArgs(subArgs);
                nodeConfigDto.setOutputArgs(outputArgs);
                configJson = JSON.toJSONString(nodeConfigDto);
            }
            case Variable, VariableAggregation -> {
                nodeConfigDto.setOutputArgs(List.of());
                configJson = JSON.toJSONString(nodeConfigDto);
            }
            case DocumentExtraction -> {
                List<Arg> inputArgs = new ArrayList<>();
                InputArg inputArg = new InputArg();
                inputArg.setName("fileUrl");
                inputArg.setDataType(DataTypeEnum.File_Default);
                inputArgs.add(inputArg);
                nodeConfigDto.setInputArgs(inputArgs);
                List<Arg> outputArgs = new ArrayList<>();
                OutputArg outArg = new OutputArg();
                outArg.setName("output");
                outArg.setDataType(DataTypeEnum.String);
                outputArgs.add(outArg);
                nodeConfigDto.setOutputArgs(outputArgs);
                nodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                configJson = JSON.toJSONString(nodeConfigDto);
            }
            case HTTPRequest -> {
                List<Arg> outputArgs = new ArrayList<>();
                OutputArg outArg = new OutputArg();
                outArg.setName(SystemArgNameEnum.HTTP_BODY.name());
                outArg.setDataType(DataTypeEnum.String);
                outArg.setSystemVariable(true);
                outputArgs.add(outArg);
                outArg = new OutputArg();
                outArg.setName(SystemArgNameEnum.HTTP_HEADERS.name());
                outArg.setDataType(DataTypeEnum.String);
                outArg.setSystemVariable(true);
                outputArgs.add(outArg);
                outArg = new OutputArg();
                outArg.setName(SystemArgNameEnum.HTTP_STATUS_CODE.name());
                outArg.setDataType(DataTypeEnum.Integer);
                outArg.setSystemVariable(true);
                outputArgs.add(outArg);
                nodeConfigDto.setOutputArgs(outputArgs);
                nodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                configJson = JSON.toJSONString(nodeConfigDto);
            }
            case QA -> {
                QaNodeConfigDto qaNodeConfigDto = new QaNodeConfigDto();
                nodeConfigDto = qaNodeConfigDto;
                ModelConfigDto modelConfigDto = modelApplicationService.queryDefaultModelConfig();
                qaNodeConfigDto.setMaxTokens(1024);
                qaNodeConfigDto.setTemperature(0.7);
                qaNodeConfigDto.setTopP(0.7);
                qaNodeConfigDto.setModelId(modelConfigDto.getId());
                qaNodeConfigDto.setMode(ModelBindConfigDto.Mode.Balanced);
                qaNodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                List<Arg> outputArgs = new ArrayList<>();
                OutputArg outArg = new OutputArg();
                outArg.setName(SystemArgNameEnum.USER_RESPONSE.name());
                outArg.setDataType(DataTypeEnum.String);
                outArg.setSystemVariable(true);
                outputArgs.add(outArg);
                qaNodeConfigDto.setOutputArgs(outputArgs);
                configJson = JSON.toJSONString(nodeConfigDto);
            }

            case IntentRecognition -> {
                IntentRecognitionNodeConfigDto intentRecognitionNodeConfigDto = IntentRecognitionNodeConfigDto.addFrom(nodeConfigDto);
                ModelConfigDto modelConfigDto = modelApplicationService.queryDefaultModelConfig();
                intentRecognitionNodeConfigDto.setMaxTokens(1024);
                intentRecognitionNodeConfigDto.setTemperature(0.7);
                intentRecognitionNodeConfigDto.setTopP(0.7);
                intentRecognitionNodeConfigDto.setModelId(modelConfigDto.getId());
                intentRecognitionNodeConfigDto.setMode(ModelBindConfigDto.Mode.Balanced);
                configJson = JSON.toJSONString(intentRecognitionNodeConfigDto);
            }
            case Condition -> {
                ConditionNodeConfigDto conditionNodeConfigDto = ConditionNodeConfigDto.addFrom(nodeConfigDto);
                //条件分支,比nodeConfigDto 多的属性:conditionBranchConfigs,这里没法赋值,直接转json文本
                configJson = JSON.toJSONString(conditionNodeConfigDto);
            }
            case TextProcessing -> {
                TextProcessingNodeConfigDto textProcessingNodeConfigDto = TextProcessingNodeConfigDto.addFrom(nodeConfigDto);
                configJson = JSON.toJSONString(textProcessingNodeConfigDto);
            }
            case Loop -> {
                LoopNodeConfigDto loopNodeConfigDto = new LoopNodeConfigDto();
                BeanUtils.copyProperties(nodeConfigDto, loopNodeConfigDto);
                loopNodeConfigDto.setLoopType(LoopNodeConfigDto.LoopTypeEnum.ARRAY_LOOP);
                loopNodeConfigDto.setInputArgs(List.of(Arg.builder().name("input").dataType(DataTypeEnum.Array_Object).build()));
                configJson = JSON.toJSONString(loopNodeConfigDto);
            }
            case TableDataAdd -> {
                TableNodeConfigDto tableNodeConfigDto = new TableNodeConfigDto();
                tableNodeConfigDto.setTableId(workflowNodeAddDto.getTypeId());
                tableNodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                configJson = JSON.toJSONString(tableNodeConfigDto);
            }
            case TableDataUpdate -> {
                TableDataUpdateNodeConfigDto tableNodeConfigDto = new TableDataUpdateNodeConfigDto();
                tableNodeConfigDto.setTableId(workflowNodeAddDto.getTypeId());
                tableNodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                configJson = JSON.toJSONString(tableNodeConfigDto);
            }
            case TableDataDelete -> {
                TableDataDeleteNodeConfigDto tableNodeConfigDto = new TableDataDeleteNodeConfigDto();
                tableNodeConfigDto.setTableId(workflowNodeAddDto.getTypeId());
                tableNodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                configJson = JSON.toJSONString(tableNodeConfigDto);
            }
            case TableDataQuery -> {
                TableDataQueryNodeConfigDto tableNodeConfigDto = new TableDataQueryNodeConfigDto();
                tableNodeConfigDto.setTableId(workflowNodeAddDto.getTypeId());
                tableNodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                tableNodeConfigDto.setLimit(100);
                configJson = JSON.toJSONString(tableNodeConfigDto);
            }
            case TableSQL -> {
                TableCustomSqlNodeConfigDto tableNodeConfigDto = new TableCustomSqlNodeConfigDto();
                tableNodeConfigDto.setTableId(workflowNodeAddDto.getTypeId());
                tableNodeConfigDto.setExtension(workflowNodeAddDto.getExtension());
                configJson = JSON.toJSONString(tableNodeConfigDto);
            }
            default -> {
            }
        }
        List<WorkflowNodeConfig> workflowNodeConfigs = workflowDomainService.queryNodeConfigListByWorkflowId(workflowNodeAddDto.getWorkflowId());
        //使用nodeType分组
        Map<WorkflowNodeConfig.NodeType, List<WorkflowNodeConfig>> workflowNodeConfigMap = workflowNodeConfigs.stream()
                .collect(Collectors.groupingBy(WorkflowNodeConfig::getType));
        workflowNodeConfigs = workflowNodeConfigMap.get(workflowNodeAddDto.getType());
        if (workflowNodeConfigs != null && !workflowNodeConfigs.isEmpty()) {
            if (workflowNodeAddDto.getType() == WorkflowNodeConfig.NodeType.Plugin || workflowNodeAddDto.getType() == WorkflowNodeConfig.NodeType.Workflow) {
                workflowNodeConfigs = workflowNodeConfigs.stream().filter(workflowNodeConfig -> {
                    try {
                        if (workflowNodeAddDto.getType() == WorkflowNodeConfig.NodeType.Plugin) {
                            PluginNodeConfigDto pluginNodeConfigDto = JSON.parseObject(workflowNodeConfig.getConfig(), PluginNodeConfigDto.class);
                            return pluginNodeConfigDto.getPluginId().equals(workflowNodeAddDto.getTypeId());
                        }
                        if (workflowNodeAddDto.getType() == WorkflowNodeConfig.NodeType.Workflow) {
                            WorkflowAsNodeConfigDto workflowAsNodeConfigDto = JSON.parseObject(workflowNodeConfig.getConfig(), WorkflowAsNodeConfigDto.class);
                            return workflowAsNodeConfigDto.getWorkflowId().equals(workflowNodeAddDto.getTypeId());
                        }
                    } catch (Exception e) {
                        //ignore
                        return false;
                    }
                    return false;
                }).collect(Collectors.toList());
                if (!workflowNodeConfigs.isEmpty()) {
                    name = name + "_" + (workflowNodeConfigs.size() + 1);
                }
            } else {
                name = name + "_" + (workflowNodeConfigs.size() + 1);
            }
        }
        WorkflowNodeConfig workflowNodeConfig = WorkflowNodeConfig.builder()
                .workflowId(workflowNodeAddDto.getWorkflowId())
                .type(workflowNodeAddDto.getType())
                .name(name)
                .loopNodeId(workflowNodeAddDto.getLoopNodeId())
                .description(description)
                .config(configJson)
                .id(workflowNodeAddDto.getId())
                .build();
        workflowDomainService.addWorkflowNode(workflowNodeConfig);
        addConfigHistory(workflowNodeAddDto.getWorkflowId(), ConfigHistory.Type.Edit, I18nUtil.systemMessage("Workflow.ConfigHistory.AddNode", name));
        return workflowNodeConfig.getId();
    }

    @Override
    public <T> void updateWorkflowNodeConfig(WorkflowNodeUpdateDto<T> workflowNodeDto) {
        WorkflowNodeConfig workflowNodeConfig = WorkflowNodeConfig.builder()
                .id(workflowNodeDto.getNodeId())
                .name(workflowNodeDto.getName())
                .description(workflowNodeDto.getDescription())
                .innerStartNodeId(workflowNodeDto.getInnerStartNodeId())
                .innerEndNodeId(workflowNodeDto.getInnerEndNodeId())
                .loopNodeId(workflowNodeDto.getLoopNodeId())
                .nextNodeIds(workflowNodeDto.getUpdateNextNodeIds())
                .build();
        //记录意图识别下级节点关系
        if (workflowNodeDto.getNodeConfig() instanceof IntentRecognitionNodeConfigDto) {
            Set<Long> nodeIds = new HashSet<>();
            List<IntentRecognitionNodeConfigDto.IntentConfigDto> intentConfigs = ((IntentRecognitionNodeConfigDto) workflowNodeDto.getNodeConfig()).getIntentConfigs();
            if (!CollectionUtils.isEmpty(intentConfigs)) {
                List<Long> workflowNodeIds = getWorkflowNodeIds(workflowNodeDto.getNodeId());
                intentConfigs.forEach(intentConfigDto -> intentConfigDto.setNextNodeIds(filterNextNodeIds(intentConfigDto.getNextNodeIds(), workflowNodeIds, nodeIds)));
            }
            workflowNodeConfig.setNextNodeIds(new ArrayList<>(nodeIds));
        }
        //记录条件分支下级节点关系
        if (workflowNodeDto.getNodeConfig() instanceof ConditionNodeConfigDto) {
            Set<Long> nodeIds = new HashSet<>();
            List<ConditionNodeConfigDto.ConditionBranchConfigDto> conditionConfigs = ((ConditionNodeConfigDto) workflowNodeDto.getNodeConfig()).getConditionBranchConfigs();
            if (!CollectionUtils.isEmpty(conditionConfigs)) {
                List<Long> workflowNodeIds = getWorkflowNodeIds(workflowNodeDto.getNodeId());
                conditionConfigs.forEach(conditionConfigDto -> conditionConfigDto.setNextNodeIds(filterNextNodeIds(conditionConfigDto.getNextNodeIds(), workflowNodeIds, nodeIds)));
            }
            workflowNodeConfig.setNextNodeIds(new ArrayList<>(nodeIds));
        }
        //记录QA下级节点关系
        if (workflowNodeDto.getNodeConfig() instanceof QaNodeConfigDto) {
            Set<Long> nodeIds = new HashSet<>();
            List<QaNodeConfigDto.OptionConfigDto> optionConfigs = ((QaNodeConfigDto) workflowNodeDto.getNodeConfig()).getOptions();
            if (!CollectionUtils.isEmpty(optionConfigs)) {
                List<Long> workflowNodeIds = getWorkflowNodeIds(workflowNodeDto.getNodeId());
                optionConfigs.forEach(optionConfigDto -> optionConfigDto.setNextNodeIds(filterNextNodeIds(optionConfigDto.getNextNodeIds(), workflowNodeIds, nodeIds)));
            }
            if (((QaNodeConfigDto) workflowNodeDto.getNodeConfig()).getAnswerType() == QaNodeConfigDto.AnswerTypeEnum.SELECT) {
                workflowNodeConfig.setNextNodeIds(new ArrayList<>(nodeIds));
            } else if (((QaNodeConfigDto) workflowNodeDto.getNodeConfig()).getAnswerType() == QaNodeConfigDto.AnswerTypeEnum.TEXT) {
                //节点由选项回答切换到文本回答
                if (workflowNodeDto.getLastWorkflowNodeDto() != null) {
                    QaNodeConfigDto lastQaNodeConfigDto = (QaNodeConfigDto) workflowNodeDto.getLastWorkflowNodeDto().getNodeConfig();
                    if (lastQaNodeConfigDto.getAnswerType() == QaNodeConfigDto.AnswerTypeEnum.SELECT) {
                        workflowNodeConfig.setNextNodeIds(new ArrayList<>());
                    }
                }
            }
        }
        if (workflowNodeDto.getNodeConfig() instanceof VariableAggregationNodeConfigDto) {
            List<Arg> inputArgs = ((VariableAggregationNodeConfigDto) workflowNodeDto.getNodeConfig()).getInputArgs();
            if (inputArgs != null) {
                for (Arg inputArg : inputArgs) {
                    if (inputArg.getSubArgs() != null) {
                        for (int j = 0; j < inputArg.getSubArgs().size(); j++) {
                            Arg subArg = inputArg.getSubArgs().get(j);
                            subArg.setName("var_" + j);
                        }
                    }
                }
            }
        }
        if (workflowNodeDto.getNodeConfig() != null && workflowNodeDto.getNodeConfig() instanceof NodeConfigDto) {
            workflowNodeConfig.setConfig(JSON.toJSONString(workflowNodeDto.getNodeConfig()));
        }
        workflowDomainService.updateWorkflowNodeConfig(workflowNodeConfig);
        workflowNodeConfig = workflowDomainService.queryWorkflowNode(workflowNodeConfig.getId());
        if (workflowNodeConfig != null) {
            addConfigHistory(workflowNodeConfig.getWorkflowId(), ConfigHistory.Type.Edit, I18nUtil.systemMessage("Workflow.ConfigHistory.UpdateNode", workflowNodeConfig.getName()));
        }
        if (log.isDebugEnabled()) {
            log.debug("Update node config: {}", JSON.toJSONString(workflowNodeDto));
        }
    }

    private List<Long> filterNextNodeIds(List<Long> nextNodeIds, List<Long> workflowNodeIds, Set<Long> nodeIds) {
        if (nextNodeIds != null) {
            //移除nextNodeIds中不在workflowNodeIds的
            nextNodeIds = nextNodeIds.stream().filter(workflowNodeIds::contains).collect(Collectors.toList());
            nodeIds.addAll(nextNodeIds);
            return nextNodeIds;
        }
        return new ArrayList<>();
    }

    private List<Long> getWorkflowNodeIds(Long workflowNodeId) {
        WorkflowNodeConfig workflowNodeConfig = workflowDomainService.queryWorkflowNode(workflowNodeId);
        if (workflowNodeConfig == null) {
            return new ArrayList<>();
        }
        List<WorkflowNodeConfig> workflowNodeConfigs = workflowDomainService.queryNodeConfigListByWorkflowId(workflowNodeConfig.getWorkflowId());
        return workflowNodeConfigs.stream().map(WorkflowNodeConfig::getId).collect(Collectors.toList());
    }

    @Override
    public void updateLoopInnerNodes(Long loopNodeId, List<JSONObject> innerNodes) {
        //从innerNodes中得到nodeId，后续优化查询
        //List<Long> nodeIds = innerNodes.stream().map(innerNode -> innerNode.getLong("nodeId")).collect(Collectors.toList());
        innerNodes.forEach(innerNode -> {
            JSONObject nodeConfig = innerNode.getJSONObject("nodeConfig");
            WorkflowNodeConfig workflowNodeConfig = workflowDomainService.queryWorkflowNode(innerNode.getLong("id"));
            if (workflowNodeConfig == null || workflowNodeConfig.getLoopNodeId() != loopNodeId.longValue()) {
                return;
            }
            WorkflowNodeUpdateDto<NodeConfigDto> workflowNodeUpdateDto = new WorkflowNodeUpdateDto<>();
            workflowNodeUpdateDto.setNodeId(workflowNodeConfig.getId());
            NodeConfigDto nodeConfigDto = WorkflowConfigDto.convertToNodeConfigDto(workflowNodeConfig.getType(), workflowNodeConfig.getConfig());
            //只更新extension
            nodeConfigDto.setExtension(nodeConfig.getJSONObject("extension"));
            workflowNodeUpdateDto.setNodeConfig(nodeConfigDto);
            updateWorkflowNodeConfig(workflowNodeUpdateDto);
        });
    }

    @Override
    public void deleteWorkflowNode(Long id) {
        WorkflowNodeDto workflowNodeDto = queryWorkflowNode(id);
        if (workflowNodeDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowNodeIdInvalid);
        }
        if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Start || workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.End) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowCannotDeleteTerminalNodes);
        }
        if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.LoopStart || workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.LoopEnd) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowCannotDeleteLoopTerminalNodes);
        }
        workflowDomainService.deleteWorkflowNode(id);
    }

    @Override
    public List<WorkflowNodeDto> queryWorkflowNodeList(Long workflowId) {
        List<WorkflowNodeConfig> workflowNodeConfigs = workflowDomainService.queryNodeConfigListByWorkflowId(workflowId);
        //检测循环节点是否包含开始和结束（补全历史数据）
        List<WorkflowNodeConfig> loopNodes = workflowNodeConfigs.stream().filter(workflowNodeConfig -> workflowNodeConfig.getType() == WorkflowNodeConfig.NodeType.Loop).collect(Collectors.toList());
        if (!CollectionUtils.isEmpty(loopNodes)) {
            //workflowNodeConfigs转map
            Map<Long, WorkflowNodeConfig> workflowNodeConfigMap = workflowNodeConfigs.stream().collect(Collectors.toMap(WorkflowNodeConfig::getId, workflowNodeConfig -> workflowNodeConfig));
            AtomicBoolean updated = new AtomicBoolean(false);
            loopNodes.forEach(workflowNodeConfig -> updated.set(workflowDomainService.checkAndUpdateLoopStartAndEndNodes(workflowNodeConfig, workflowNodeConfigMap)));
            if (updated.get()) {
                workflowNodeConfigs = workflowDomainService.queryNodeConfigListByWorkflowId(workflowId);
            }
        }
        WorkflowConfig workflowConfig = workflowDomainService.queryById(workflowId);
        return workflowNodeConfigs.stream().map(workflowNodeConfig -> convertNodeConfig(workflowNodeConfig, workflowConfig.getSpaceId(), false)).collect(Collectors.toList());
    }

    public List<WorkflowNodeDto> queryWorkflowNodeListForTestExecute(Long workflowId) {
        List<WorkflowNodeConfig> workflowNodeConfigs = workflowDomainService.queryNodeConfigListByWorkflowId(workflowId);
        WorkflowConfig workflowConfig = workflowDomainService.queryById(workflowId);
        return workflowNodeConfigs.stream().map(workflowNodeConfig -> convertNodeConfig(workflowNodeConfig, workflowConfig.getSpaceId(), true)).collect(Collectors.toList());
    }

    @Override
    public PreviousDto queryPreviousNodes(Long nodeId) {
        WorkflowNodeConfig workflowNodeConfig = workflowDomainService.queryWorkflowNode(nodeId);
        if (workflowNodeConfig == null) {
            return PreviousDto.builder()
                    .previousNodes(new ArrayList<>())
                    .innerPreviousNodes(new ArrayList<>())
                    .argMap(new HashMap<>())
                    .build();
        }
        List<WorkflowNodeDto> workflowNodeDtos = queryWorkflowNodeList(workflowNodeConfig.getWorkflowId());
        //追加异常处理节点关系
        workflowNodeDtos.forEach(node -> {
            NodeConfigDto nodeConfigDto = node.getNodeConfig();
            if (nodeConfigDto != null && nodeConfigDto.getExceptionHandleConfig() != null) {
                if (ExceptionHandleConfigDto.ExceptionHandleTypeEnum.EXECUTE_EXCEPTION_FLOW == nodeConfigDto.getExceptionHandleConfig().getExceptionHandleType()) {
                    List<Long> exceptionHandleNodeIds = nodeConfigDto.getExceptionHandleConfig().getExceptionHandleNodeIds();
                    if (!CollectionUtils.isEmpty(exceptionHandleNodeIds) && node.getNextNodeIds() != null) {
                        node.getNextNodeIds().forEach(exceptionHandleNodeIds::remove);
                        node.getNextNodeIds().addAll(exceptionHandleNodeIds);
                    }
                }
            }
        });
        //排序
        //workflowNodeDtos转map
        Map<Long, WorkflowNodeDto> workflowNodeDtoMap = workflowNodeDtos.stream().collect(Collectors.toMap(WorkflowNodeDto::getId, workflowNodeDto -> workflowNodeDto));
        WorkflowNodeDto startNode = workflowNodeDtos.stream().filter(workflowNodeDto -> workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Start).findFirst().orElse(null);
        if (startNode == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowStartNodeNotFound);
        }
        WorkflowNodeDto nodeDto = workflowNodeDtoMap.get(nodeId);
        PreviousDto previousDto = generatePreviousNodes(workflowNodeDtos, nodeId);
        //设置previousDto.getPreviousNodes()中sort默认为0
        previousDto.getPreviousNodes().forEach(previousNodeDto -> previousNodeDto.setSort(Integer.MAX_VALUE));
        //previousDto.getPreviousNodes() 转map
        Map<Long, PreviousNodeDto> previousNodeDtoMap = previousDto.getPreviousNodes().stream().collect(Collectors.toMap(PreviousNodeDto::getId, previousNodeDto -> previousNodeDto, (previousNodeDto, previousNodeDto2) -> previousNodeDto));
        if (!CollectionUtils.isEmpty(nodeDto.getPreNodes())) {
            AtomicInteger sort = new AtomicInteger(0);
            WorkflowNodeDto workflowNodeDto = workflowNodeDtoMap.get(startNode.getId());
            PreviousNodeDto previousNodeDto = previousNodeDtoMap.get(startNode.getId());
            if (previousNodeDto != null && workflowNodeDto != null) {
                previousNodeDto.setSort(sort.getAndIncrement());
                //排过序的节点不再排序
                Set<Long> sortedIdSet = new HashSet<>();
                sortPreviousNodes(startNode.getNextNodeIds(), workflowNodeDtoMap, previousNodeDtoMap, sort, sortedIdSet);
            }
        }
        //previousDto.getPreviousNodes()中sort从大到小排序，相同的用id从小到大排序
        previousDto.getPreviousNodes().sort((o1, o2) -> {
            if (o1.getSort().equals(o2.getSort())) {
                return o1.getId().compareTo(o2.getId());
            }
            return o1.getSort().compareTo(o2.getSort());
        });
        return previousDto;
    }

    private void sortPreviousNodes(List<Long> nextNodeIds, Map<Long, WorkflowNodeDto> workflowNodeDtoMap, Map<Long, PreviousNodeDto> previousNodeDtoMap, AtomicInteger sort, Set<Long> sortedIdSet) {
        if (!CollectionUtils.isEmpty(nextNodeIds)) {
            nextNodeIds.forEach(nextId -> {
                if (sortedIdSet.contains(nextId)) {
                    return;
                }
                sortedIdSet.add(nextId);
                WorkflowNodeDto workflowNodeDto = workflowNodeDtoMap.get(nextId);
                PreviousNodeDto previousNodeDto = previousNodeDtoMap.get(nextId);
                if (workflowNodeDto != null) {
                    if (previousNodeDto != null) {
                        previousNodeDto.setSort(sort.getAndIncrement());
                    }
                    sortPreviousNodes(workflowNodeDto.getNextNodeIds(), workflowNodeDtoMap, previousNodeDtoMap, sort, sortedIdSet);
                }
            });
        }
    }

    private PreviousDto generatePreviousNodes(List<WorkflowNodeDto> workflowNodeDtos, Long nodeId) {
        List<PreviousNodeDto> previousNodes = new ArrayList<>();
        List<PreviousNodeDto> innerPreviousNodes = new ArrayList<>();
        Map<String, Arg> argMap = new HashMap<>();
        //workflowNodeDtos根据ID转map,补充完善每个节点的上级节点列表
        Map<Long, WorkflowNodeDto> workflowNodeDtoMap = workflowNodeDtos.stream().collect(Collectors.toMap(WorkflowNodeDto::getId, workflowNodeDto -> workflowNodeDto));
        workflowNodeDtos.forEach(workflowNodeDto -> {
            if (workflowNodeDto.getNextNodeIds() != null) {
                if (workflowNodeDto.getLoopNodeId() != null) {
                    workflowNodeDto.getNextNodeIds().remove(workflowNodeDto.getLoopNodeId());
                }
                workflowNodeDto.getNextNodeIds().forEach(nextNodeId -> {
                    WorkflowNodeDto next = workflowNodeDtoMap.get(nextNodeId);
                    if (next != null) {
                        if (next.getPreNodes() == null) {
                            next.setPreNodes(new ArrayList<>());
                        }
                        if (!next.getPreNodes().contains(workflowNodeDto)) {
                            next.getPreNodes().add(workflowNodeDto);
                        }
                    }
                });
            }
            //补充变量输出
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Variable) {
                VariableNodeConfigDto variableNodeConfigDto = (VariableNodeConfigDto) workflowNodeDto.getNodeConfig();
                if (variableNodeConfigDto.getConfigType() == VariableNodeConfigDto.ConfigTypeEnum.SET_VARIABLE) {
                    variableNodeConfigDto.setOutputArgs(List.of(Arg.builder().dataType(DataTypeEnum.Boolean).name("isSuccess").build()));
                }
            }
        });

        //获取指定节点的所有参数，根据入参节点id过滤workflowNodeDtos
        WorkflowNodeDto workflowNodeDto = workflowNodeDtoMap.get(nodeId);
        if (workflowNodeDto == null) {
            PreviousDto previousDto = new PreviousDto();
            previousDto.setPreviousNodes(previousNodes);
            previousDto.setInnerPreviousNodes(innerPreviousNodes);
            previousDto.setArgMap(argMap);
            return previousDto;
        }
        //移除上级节点连成死循环
        removeDeadLoopPreNodes(workflowNodeDto, workflowNodeDtoMap);

        setPreviousNodes(previousNodes, argMap, workflowNodeDto.getPreNodes(), workflowNodeDtoMap);
        //循环节点外部节点也可以作为参数
        if (CollectionUtils.isEmpty(workflowNodeDto.getPreNodes()) && workflowNodeDto.getLoopNodeId() != null && workflowNodeDto.getLoopNodeId() > 0) {
            WorkflowNodeDto loopNode = workflowNodeDtoMap.get(workflowNodeDto.getLoopNodeId());
            if (loopNode != null && loopNode.getInnerStartNodeId() != null && loopNode.getInnerStartNodeId() == workflowNodeDto.getId().longValue()) {
                setPreviousNodes(previousNodes, argMap, loopNode.getPreNodes(), workflowNodeDtoMap);
            }
        }
        if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Loop) {
            WorkflowNodeDto loopEndNode = workflowNodeDtoMap.get(workflowNodeDto.getInnerEndNodeId());
            if (loopEndNode != null) {
                loopEndNode.setNextNodeIds(null);
                setPreviousNodes(innerPreviousNodes, argMap, List.of(loopEndNode), workflowNodeDtoMap);
                innerPreviousNodes = innerPreviousNodes.stream().filter(innerNode -> innerNode.getLoopNodeId() != null && innerNode.getLoopNodeId().longValue() == nodeId.longValue()).collect(Collectors.toList());
                innerPreviousNodes.forEach(innerNode -> {
                    //类型全部改成Array
                    if (!CollectionUtils.isEmpty(innerNode.getOutputArgs())) {
                        innerNode.getOutputArgs().forEach(outputArg -> {
                            outputArg.setOriginDataType(outputArg.getDataType());
                            if (outputArg.getDataType() == null) {
                                outputArg.setDataType(DataTypeEnum.Array_Object);
                            } else {
                                if (outputArg.getDataType().name().startsWith("Array")) {
                                    outputArg.setDataType(DataTypeEnum.Array_Object);
                                } else {
                                    try {
                                        outputArg.setDataType(DataTypeEnum.valueOf("Array_" + outputArg.getDataType().name()));
                                    } catch (Exception e) {
                                        //不处理
                                    }
                                }
                            }
                        });
                    }
                });
            }
            //循环节点变量也可作为输出可选范围
            LoopNodeConfigDto loopNodeConfigDto = (LoopNodeConfigDto) workflowNodeDto.getNodeConfig();
            if (!CollectionUtils.isEmpty(loopNodeConfigDto.getVariableArgs())) {
                List<Arg> varOutputArgs = new ArrayList<>();
                loopNodeConfigDto.getVariableArgs().forEach(inputArg -> {
                    OutputArg outputArg = new OutputArg();
                    BeanUtils.copyProperties(inputArg, outputArg);
                    if (inputArg.getBindValueType() == Arg.BindValueType.Reference) {
                        Arg arg = argMap.get(inputArg.getBindValue());
                        if (arg != null) {
                            outputArg.setSubArgs(arg.getSubArgs());
                            varOutputArgs.add(outputArg);
                        }
                    } else {
                        varOutputArgs.add(outputArg);
                    }
                });
                generateKey(workflowNodeDto.getId() + "-var", varOutputArgs, argMap);
                PreviousNodeDto previousNodeDto = new PreviousNodeDto();
                previousNodeDto.setId(workflowNodeDto.getId());
                previousNodeDto.setName(workflowNodeDto.getName());
                previousNodeDto.setOutputArgs(varOutputArgs);
                previousNodeDto.setType(workflowNodeDto.getType());
                if (!innerPreviousNodes.contains(previousNodeDto)) {
                    innerPreviousNodes.add(previousNodeDto);
                }
            }
        }
        if (workflowNodeDto.getLoopNodeId() != null && workflowNodeDto.getLoopNodeId() > 0) {
            WorkflowNodeDto loopNode = workflowNodeDtoMap.get(workflowNodeDto.getLoopNodeId());
            if (loopNode != null) {
                //循环节点绑定的数组和变量也可作为参数
                List<Arg> outputArgs = new ArrayList<>();
                LoopNodeConfigDto loopNodeConfigDto = (LoopNodeConfigDto) loopNode.getNodeConfig();
                if (loopNodeConfigDto != null) {
                    if (!CollectionUtils.isEmpty(loopNodeConfigDto.getInputArgs())) {
                        loopNodeConfigDto.getInputArgs().forEach(inputArg -> {
                            if (inputArg.getBindValueType() == Arg.BindValueType.Input) {
                                return;
                            }
                            Arg arg = argMap.get(inputArg.getBindValue());
                            if (arg != null && arg.getDataType().name().startsWith("Array")) {
                                OutputArg outputArg = new OutputArg();
                                BeanUtils.copyProperties(inputArg, outputArg);
                                outputArg.setName(inputArg.getName() + "_item");
                                try {
                                    outputArg.setDataType(DataTypeEnum.valueOf(arg.getDataType().name().replace("Array_", "")));
                                } catch (Exception e) {
                                    //不处理
                                    outputArg.setDataType(DataTypeEnum.Object);
                                }
                                List<Arg> subArgs = (List<Arg>) JsonSerializeUtil.deepCopy(arg.getSubArgs());
                                outputArg.setSubArgs(subArgs);
                                outputArgs.add(outputArg);
                            }
                        });
                    }
                    OutputArg indexOutputArg = new OutputArg();
                    indexOutputArg.setName(SystemArgNameEnum.INDEX.name());
                    indexOutputArg.setDataType(DataTypeEnum.Integer);
                    indexOutputArg.setSystemVariable(true);
                    indexOutputArg.setDescription(I18nUtil.systemMessage("Workflow.Output.ArrayIndex"));
                    outputArgs.add(indexOutputArg);
                    generateKey(loopNode.getId() + "-input", outputArgs, argMap);

                    if (!CollectionUtils.isEmpty(loopNodeConfigDto.getVariableArgs())) {
                        List<Arg> varOutputArgs = new ArrayList<>();
                        loopNodeConfigDto.getVariableArgs().forEach(inputArg -> {
                            if (inputArg.getBindValueType() == null || inputArg.getBindValueType() == Arg.BindValueType.Input) {
                                varOutputArgs.add(inputArg);
                                return;
                            }
                            Arg arg = argMap.get(inputArg.getBindValue());
                            if (arg != null) {
                                OutputArg outputArg = new OutputArg();
                                BeanUtils.copyProperties(inputArg, outputArg);
                                outputArg.setSubArgs(arg.getSubArgs());
                                varOutputArgs.add(outputArg);
                            }
                        });
                        generateKey(loopNode.getId() + "-var", varOutputArgs, argMap);
                        outputArgs.addAll(varOutputArgs);
                    }
                    if (!outputArgs.isEmpty()) {
                        PreviousNodeDto previousNodeDto = new PreviousNodeDto();
                        previousNodeDto.setId(loopNode.getId());
                        previousNodeDto.setName(loopNode.getName());
                        previousNodeDto.setType(loopNode.getType());
                        previousNodeDto.setOutputArgs(outputArgs);
                        if (!previousNodes.contains(previousNodeDto)) {
                            previousNodes.add(previousNodeDto);
                        }
                    }
                }

                if (loopNode.getInnerStartNodeId() != null && loopNode.getInnerStartNodeId() == workflowNodeDto.getId().longValue()) {
                    setPreviousNodes(previousNodes, argMap, loopNode.getPreNodes(), workflowNodeDtoMap);
                }
            }
        }
        //previousNodes按照nodeId排序
        //previousNodes.sort(Comparator.comparing(PreviousNodeDto::getId));
        PreviousDto previousDto = new PreviousDto();
        previousDto.setPreviousNodes(previousNodes);
        previousDto.setInnerPreviousNodes(innerPreviousNodes);
        previousDto.setArgMap(argMap);
        return previousDto;
    }

    private void removeDeadLoopPreNodes(WorkflowNodeDto workflowNodeDto, Map<Long, WorkflowNodeDto> workflowNodeDtoMap) {
        Set<Long> ids = new HashSet<>();
        ids.add(workflowNodeDto.getId());
        removeDeadLoopPreNodes(workflowNodeDto, workflowNodeDtoMap, ids);
    }

    private void removeDeadLoopPreNodes(WorkflowNodeDto workflowNodeDto, Map<Long, WorkflowNodeDto> workflowNodeDtoMap, Set<Long> ids) {
        if (workflowNodeDto.getPreNodes() != null) {
            Iterator<WorkflowNodeDto> iterator = workflowNodeDto.getPreNodes().iterator();
            while (iterator.hasNext()) {
                WorkflowNodeDto nodeDto = iterator.next();
                if (ids.contains(nodeDto.getId())) {
                    iterator.remove();
                } else {
                    ids.add(nodeDto.getId());
                    removeDeadLoopPreNodes(nodeDto, workflowNodeDtoMap, ids);
                }
            }
        }
        if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Loop && workflowNodeDto.getInnerEndNodeId() != null) {
            WorkflowNodeDto loopEndNode = workflowNodeDtoMap.get(workflowNodeDto.getInnerEndNodeId());
            if (loopEndNode != null) {
                removeDeadLoopPreNodes(loopEndNode, workflowNodeDtoMap, ids);
            }
        }
    }

    private void setPreviousNodes(List<PreviousNodeDto> previousNodes, Map<String, Arg> argMap, List<WorkflowNodeDto> preNodes, Map<Long, WorkflowNodeDto> workflowNodeDtoMap) {
        if (preNodes != null) {
            preNodes.forEach(preNode -> {
                if (!CollectionUtils.isEmpty(preNode.getNodeConfig().getOutputArgs())) {
                    PreviousNodeDto previousNodeDto = new PreviousNodeDto();
                    previousNodeDto.setId(preNode.getId());
                    previousNodeDto.setName(preNode.getName());
                    previousNodeDto.setLoopNodeId(preNode.getLoopNodeId());
                    previousNodeDto.setType(preNode.getType());
                    previousNodeDto.setOutputArgs(preNode.getNodeConfig().getOutputArgs());
                    if (!previousNodes.contains(previousNodeDto)) {
                        previousNodes.add(previousNodeDto);
                    }
                    if (preNode.getType() == WorkflowNodeConfig.NodeType.Variable) {
                        VariableNodeConfigDto nodeConfigDto = (VariableNodeConfigDto) preNode.getNodeConfig();
                        if (nodeConfigDto.getConfigType() == VariableNodeConfigDto.ConfigTypeEnum.SET_VARIABLE) {
                            preNode.getNodeConfig().setOutputArgs(List.of(Arg.builder().name("isSuccess").dataType(DataTypeEnum.Boolean).build()));
                            previousNodeDto.setOutputArgs(preNode.getNodeConfig().getOutputArgs());
                        }
                    }
                    generateKey(preNode.getId().toString(), preNode.getNodeConfig().getOutputArgs(), argMap);
                }
                if (preNode.getType() == WorkflowNodeConfig.NodeType.Start) {
                    if (preNode.getNodeConfig().getInputArgs() == null) {
                        preNode.getNodeConfig().setInputArgs(new ArrayList<>());
                    }
                    PreviousNodeDto previousNodeDto = new PreviousNodeDto();
                    previousNodeDto.setId(preNode.getId());
                    previousNodeDto.setName(preNode.getName());
                    previousNodeDto.setType(preNode.getType());
                    //preNode.getNodeConfig().getInputArgs()转outputArgs
                    if (!previousNodes.contains(previousNodeDto)) {
                        List<Arg> outputArgs = preNode.getNodeConfig().getInputArgs().stream().map(inputArg -> {
                            OutputArg outputArg = new OutputArg();
                            BeanUtils.copyProperties(inputArg, outputArg);
                            return outputArg;
                        }).collect(Collectors.toList());
                        //追加系统变量
                        outputArgs.addAll(Arg.getSystemVariableArgs());
                        previousNodeDto.setOutputArgs(outputArgs);
                        previousNodes.add(previousNodeDto);
                        generateKey(preNode.getId().toString(), outputArgs, argMap);
                    }
                }
                if (!CollectionUtils.isEmpty(preNode.getPreNodes())) {
                    setPreviousNodes(previousNodes, argMap, preNode.getPreNodes(), workflowNodeDtoMap);
                }
                //循环节点外部节点也可以作为参数
                if (preNode.getLoopNodeId() != null && preNode.getLoopNodeId() > 0) {
                    WorkflowNodeDto loopNode = workflowNodeDtoMap.get(preNode.getLoopNodeId());
                    if (loopNode != null && loopNode.getInnerStartNodeId() != null && loopNode.getInnerStartNodeId() == preNode.getId().longValue()) {
                        if (!CollectionUtils.isEmpty(loopNode.getPreNodes())) {
                            List<WorkflowNodeDto> workflowNodeDtos = loopNode.getPreNodes().stream().filter(pre -> pre.getLoopNodeId() != null && pre.getLoopNodeId().longValue() == loopNode.getId()).collect(Collectors.toList());
                            loopNode.getPreNodes().removeAll(workflowNodeDtos);
                        }
                        setPreviousNodes(previousNodes, argMap, loopNode.getPreNodes(), workflowNodeDtoMap);
                    }
                }
            });
        }
    }

    @Override
    public WorkflowNodeDto queryWorkflowNode(Long id) {
        WorkflowNodeConfig workflowNodeConfig = workflowDomainService.queryWorkflowNode(id);
        if (workflowNodeConfig == null) {
            return null;
        }
        WorkflowConfig workflowConfig = workflowDomainService.queryById(workflowNodeConfig.getWorkflowId());
        WorkflowNodeDto workflowNodeDto = convertNodeConfig(workflowNodeConfig, workflowConfig.getSpaceId(), false);
        if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Loop) {
            if (workflowNodeDto.getInnerStartNodeId() != null) {
                workflowNodeDto.setStartNode(queryWorkflowNode(workflowNodeDto.getInnerStartNodeId()));
            }
            if (workflowNodeDto.getInnerEndNodeId() != null) {
                workflowNodeDto.setEndNode(queryWorkflowNode(workflowNodeDto.getInnerEndNodeId()));
            }
        }
        return workflowNodeDto;
    }

    @Override
    public Long copyWorkflowNode(Long id) {
        WorkflowNodeDto workflowNodeDto = queryWorkflowNode(id);
        if (workflowNodeDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowNodeIdInvalid);
        }
        if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Start || workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.End) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowCannotCopyTerminalNodes);
        }
        WorkflowNodeAddDto workflowNodeAddDto = new WorkflowNodeAddDto();
        AddNodeConfigDto nodeConfigDto = new AddNodeConfigDto();
        workflowNodeAddDto.setNodeConfigDto(nodeConfigDto);
        workflowNodeAddDto.setWorkflowId(workflowNodeDto.getWorkflowId());
        workflowNodeAddDto.setType(workflowNodeDto.getType());
        switch (workflowNodeDto.getType()) {
            case Workflow:
                WorkflowAsNodeConfigDto workflowAsNodeConfigDto = (WorkflowAsNodeConfigDto) workflowNodeDto.getNodeConfig();
                workflowNodeAddDto.setTypeId(workflowAsNodeConfigDto.getWorkflowId());
                break;
            case Plugin:
                PluginNodeConfigDto pluginNodeConfigDto = (PluginNodeConfigDto) workflowNodeDto.getNodeConfig();
                workflowNodeAddDto.setTypeId(pluginNodeConfigDto.getPluginId());
                break;
            case Mcp:
                McpNodeConfigDto mcpNodeConfigDto = (McpNodeConfigDto) workflowNodeDto.getNodeConfig();
                workflowNodeAddDto.setTypeId(mcpNodeConfigDto.getMcpId());
                nodeConfigDto.setToolName(mcpNodeConfigDto.getToolName());
                break;
            case TableDataAdd:
            case TableSQL:
            case TableDataDelete:
            case TableDataQuery:
            case TableDataUpdate:
                TableNodeConfigDto tableNodeConfigDto = (TableNodeConfigDto) workflowNodeDto.getNodeConfig();
                workflowNodeAddDto.setTypeId(tableNodeConfigDto.getTableId());
                break;
            default:
                break;
        }
        workflowNodeAddDto.setLoopNodeId(workflowNodeDto.getLoopNodeId());
        Long nodeId = addWorkflowNode(workflowNodeAddDto);
        WorkflowNodeUpdateDto<NodeConfigDto> updateWorkflowNodeConfig = new WorkflowNodeUpdateDto<NodeConfigDto>();
        updateWorkflowNodeConfig.setNodeId(nodeId);
        updateWorkflowNodeConfig.setNodeConfig(workflowNodeDto.getNodeConfig());
        updateWorkflowNodeConfig(updateWorkflowNodeConfig);
        return nodeId;
    }

    @Override
    public void updateNextIds(Long nodeId, List<Long> nextIds) {
        WorkflowNodeConfig workflowNodeConfig = WorkflowNodeConfig.builder()
                .id(nodeId)
                .nextNodeIds(nextIds)
                .build();
        workflowDomainService.updateWorkflowNodeConfig(workflowNodeConfig);
        workflowNodeConfig = workflowDomainService.queryWorkflowNode(nodeId);
        addConfigHistory(workflowNodeConfig.getWorkflowId(), ConfigHistory.Type.Edit, I18nUtil.systemMessage("Workflow.ConfigHistory.UpdateConnection", workflowNodeConfig.getName()));
    }

    @Override
    public void checkSpaceWorkflowPermission(Long spaceId, Long workflowId) {
        PublishedDto published = publishApplicationService.queryPublished(Published.TargetType.Workflow, workflowId);
        if (published == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowNotPublished);
        }
        if (published.getScope() == Published.PublishScope.Tenant || published.getScope() == Published.PublishScope.Global) {
            return;
        }
        if (published.getPublishedSpaceIds() == null) {
            return;
        }
        if (!published.getPublishedSpaceIds().contains(spaceId)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.permissionDenied);
        }
    }

    private void addConfigHistory(Long workflowId, ConfigHistory.Type type, String description) {
        addConfigHistory(workflowId, type, description, -1L);
    }

    private void addConfigHistory(Long workflowId, ConfigHistory.Type type, String description, Long copyFromWorkflowId) {
        if (redisUtil.get("config_history_workflow:" + workflowId) != null) {
            return;
        }
        WorkflowConfig workflowConfig = workflowDomainService.queryById(workflowId);
        List<WorkflowNodeConfig> workflowNodeConfigs = workflowDomainService.queryNodeConfigListByWorkflowId(workflowId);
        ConfigHistory configHistory = ConfigHistory.builder()
                .config(JSON.toJSONString(Map.of("workflowConfig", workflowConfig, "workflowNodeConfigs", workflowNodeConfigs, "copyFromWorkflowId", copyFromWorkflowId)))
                .targetId(workflowId)
                .description(description)
                .targetType(Published.TargetType.Workflow)
                .opUserId(RequestContext.get().getUserId())
                .type(type)
                .tenantId(RequestContext.get().getTenantId())
                .build();
        workflowConfig.setModified(new Date());
        workflowDomainService.update(workflowConfig);
        configHistoryDomainService.addConfigHistory(configHistory);
        redisUtil.set("config_history_workflow:" + workflowId, "1", 10);
    }

    private void completeCreator(List<WorkflowConfigDto> workflowConfigDtos) {
        //workflowConfigDtos转userMap
        Map<Long, UserDto> userMap = userApplicationService.queryUserListByIds(workflowConfigDtos.stream().map(WorkflowConfigDto::getCreatorId).collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(UserDto::getId, userDto -> userDto));
        workflowConfigDtos.forEach(workflowConfigDto -> {
            //UserDto转CreatorDto
            UserDto userDto = userMap.get(workflowConfigDto.getCreatorId());
            if (userDto != null) {
                CreatorDto creatorDto = CreatorDto.builder()
                        .userId(userDto.getId())
                        .userName(userDto.getUserName())
                        .nickName(userDto.getNickName())
                        .avatar(userDto.getAvatar())
                        .build();
                workflowConfigDto.setCreator(creatorDto);
            }
        });
    }

    private List<WorkflowConfigDto> convertWorkflowConfigs(List<WorkflowConfig> workflowConfigs) {
        List<WorkflowConfigDto> workflowConfigDtos = workflowConfigs.stream().map(workflowConfig -> {
            WorkflowConfigDto workflowConfigDto = new WorkflowConfigDto();
            BeanUtils.copyProperties(workflowConfig, workflowConfigDto);
            return workflowConfigDto;
        }).collect(Collectors.toList());
        completeCreator(workflowConfigDtos);
        return workflowConfigDtos;
    }

    //梳理出List<WorkflowNodeDto>层级关系
    public List<WorkflowNodeDto> organizeNodeHierarchicalRelationship(List<WorkflowNodeDto> workflowNodeDtos) {
        if (CollectionUtils.isEmpty(workflowNodeDtos)) {
            return List.of();
        }
        //workflowConfigDtos转map
        Map<Long, WorkflowNodeDto> workflowNodeDtoMap = workflowNodeDtos.stream().collect(Collectors.toMap(WorkflowNodeDto::getId, workflowNodeDto -> workflowNodeDto));
        workflowNodeDtos.forEach(nodeDto -> {
            //循环节点追加到上级节点下
            if (nodeDto.getLoopNodeId() != null && nodeDto.getLoopNodeId() > 0) {
                WorkflowNodeDto workflowNodeDto = workflowNodeDtoMap.get(nodeDto.getLoopNodeId());
                if (workflowNodeDto != null) {
                    if (workflowNodeDto.getInnerNodes() == null) {
                        workflowNodeDto.setInnerNodes(new ArrayList<>());
                    }
                    workflowNodeDto.getInnerNodes().add(nodeDto);
                }
            }
        });
        return workflowNodeDtos;
    }

    @Override
    public List<WorkflowNodeCheckDto> validWorkflow(Long workflowId) {
        WorkflowConfigDto workflowConfigDto = queryById(workflowId);
        if (workflowConfigDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowNotFound);
        }
        return validWorkflow(workflowConfigDto);
    }

    private List<WorkflowNodeCheckDto> validWorkflow(WorkflowConfigDto workflowConfigDto) {
        List<WorkflowNodeCheckDto> workflowNodeCheckDtos = new ArrayList<>();
        //工作流自身循环引用以及代码安全性检测
        workflowConfigDto.getNodes().forEach(node -> {
            WorkflowNodeCheckDto workflowAsNodeCheckDto = new WorkflowNodeCheckDto();
            NodeConfigDto nodeConfigDto = node.getNodeConfig();
            if (nodeConfigDto != null && nodeConfigDto.getExceptionHandleConfig() != null) {
                if (ExceptionHandleConfigDto.ExceptionHandleTypeEnum.EXECUTE_EXCEPTION_FLOW == nodeConfigDto.getExceptionHandleConfig().getExceptionHandleType()) {
                    List<Long> exceptionHandleNodeIds = nodeConfigDto.getExceptionHandleConfig().getExceptionHandleNodeIds();
                    if (!CollectionUtils.isEmpty(exceptionHandleNodeIds) && node.getNextNodeIds() != null) {
                        node.getNextNodeIds().forEach(exceptionHandleNodeIds::remove);
                        node.getNextNodeIds().addAll(exceptionHandleNodeIds);
                    } else if (CollectionUtils.isEmpty(exceptionHandleNodeIds)) {
                        workflowAsNodeCheckDto.setNodeId(node.getId());
                        workflowAsNodeCheckDto.setSuccess(false);
                        workflowAsNodeCheckDto.setMessages(List.of(I18nUtil.systemMessage("Workflow.Validate.NoExceptionHandle")));
                        workflowNodeCheckDtos.add(workflowAsNodeCheckDto);
                    }
                }
            }
            if (node.getLoopNodeId() != null && node.getNextNodeIds() != null) {
                node.getNextNodeIds().remove(node.getLoopNodeId());
            }
            if (node.getType() == WorkflowNodeConfig.NodeType.Workflow) {
                WorkflowAsNodeConfigDto workflowAsNodeConfigDto = (WorkflowAsNodeConfigDto) node.getNodeConfig();
                if (workflowAsNodeConfigDto.getWorkflowId().equals(workflowConfigDto.getId())) {
                    workflowAsNodeCheckDto.setNodeId(node.getId());
                    workflowAsNodeCheckDto.setSuccess(false);
                    workflowAsNodeCheckDto.setMessages(List.of(I18nUtil.systemMessage("Workflow.Validate.CannotReferenceSelf")));
                    workflowNodeCheckDtos.add(workflowAsNodeCheckDto);
                }
            }
            //工作流中的大模型节点引用的工作流不能包含自身
            if (node.getType() == WorkflowNodeConfig.NodeType.LLM) {
                LLMNodeConfigDto llmNodeConfigDto = (LLMNodeConfigDto) node.getNodeConfig();
                if (!CollectionUtils.isEmpty(llmNodeConfigDto.getSkillComponentConfigs())) {
                    llmNodeConfigDto.getSkillComponentConfigs().forEach(skillComponentConfig -> {
                        if (skillComponentConfig.getType() == LLMNodeConfigDto.SkillComponentConfigDto.Type.Workflow) {
                            if (skillComponentConfig.getTypeId().equals(workflowConfigDto.getId())) {
                                workflowAsNodeCheckDto.setNodeId(node.getId());
                                workflowAsNodeCheckDto.setSuccess(false);
                                workflowAsNodeCheckDto.setMessages(List.of(I18nUtil.systemMessage("Workflow.Validate.ReferencedCurrentWorkflow")));
                                workflowNodeCheckDtos.add(workflowAsNodeCheckDto);
                            }
                        }
                    });
                }
            }
            //检查代码安全性
            if (node.getType() == WorkflowNodeConfig.NodeType.Code) {
                CodeNodeConfigDto codeNodeConfigDto = (CodeNodeConfigDto) node.getNodeConfig();
                CodeCheckResultDto codeCheckResultDto;
                if (codeNodeConfigDto.getCodeLanguage() == CodeLanguageEnum.JavaScript) {
                    codeCheckResultDto = modelApplicationService.codeSaleCheck(codeNodeConfigDto.getCodeJavaScript());
                } else {
                    codeCheckResultDto = modelApplicationService.codeSaleCheck(codeNodeConfigDto.getCodePython());
                }
                if (codeCheckResultDto != null && codeCheckResultDto.getPass() != null && !codeCheckResultDto.getPass()) {
                    workflowAsNodeCheckDto.setNodeId(node.getId());
                    workflowAsNodeCheckDto.setSuccess(false);
                    workflowAsNodeCheckDto.setMessages(List.of(codeCheckResultDto.getReason()));
                    workflowNodeCheckDtos.add(workflowAsNodeCheckDto);
                }
            }
        });


        Map<Long, WorkflowNodeDto> nodeMap = workflowConfigDto.getNodes().stream().collect(Collectors.toMap(WorkflowNodeDto::getId, n -> n));
        if (!"AgentFlow".equals(workflowConfigDto.getType())) {
            //检查节点是否有循环连线
            WorkflowNodeCheckDto workflowNodeLineCheckDto = new WorkflowNodeCheckDto();
            try {
                checkIfHasLoopLine(workflowConfigDto.getStartNode(), new HashSet<>(), nodeMap, workflowNodeLineCheckDto);
            } catch (Exception e) {
                workflowNodeCheckDtos.add(workflowNodeLineCheckDto);
            }
        }
        if (!CollectionUtils.isEmpty(workflowNodeCheckDtos)) {
            return workflowNodeCheckDtos;
        }

        checkIfStartToEnd(workflowConfigDto.getStartNode(), nodeMap, workflowNodeCheckDtos, new HashSet<>());
        if (!workflowNodeCheckDtos.isEmpty()) {
            return workflowNodeCheckDtos;
        }

        workflowConfigDto.getNodes().forEach(node -> {
            WorkflowNodeCheckDto workflowNodeCheckDto = new WorkflowNodeCheckDto();
            workflowNodeCheckDtos.add(workflowNodeCheckDto);
            workflowNodeCheckDto.setNodeId(node.getId());
            workflowNodeCheckDto.setSuccess(true);
            workflowNodeCheckDto.setMessages(new ArrayList<>());

            if (node.getType() == WorkflowNodeConfig.NodeType.Start) {
                if (node.getNodeConfig().getInputArgs() != null) {
                    //  开始节点的入参最终作为出参
                    checkOutputArgs(node.getNodeConfig().getInputArgs(), workflowNodeCheckDto);
                }
                return;
            }

            if (node.getType() == WorkflowNodeConfig.NodeType.Loop) {
                if (node.getInnerStartNodeId() == null || nodeMap.get(node.getInnerStartNodeId()) == null) {
                    workflowNodeCheckDto.setSuccess(false);
                    workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoLoopStartNode"));
                }
                if (node.getInnerEndNodeId() == null || nodeMap.get(node.getInnerEndNodeId()) == null) {
                    workflowNodeCheckDto.setSuccess(false);
                    workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoLoopEndNode"));
                }
                LoopNodeConfigDto loopNodeConfigDto = (LoopNodeConfigDto) node.getNodeConfig();
                if (loopNodeConfigDto.getLoopType() != LoopNodeConfigDto.LoopTypeEnum.ARRAY_LOOP) {
                    loopNodeConfigDto.setInputArgs(new ArrayList<>());
                }
                if (loopNodeConfigDto.getLoopType() == LoopNodeConfigDto.LoopTypeEnum.INFINITE_LOOP) {
                    //检查是否有break节点
                    //过滤出循环节点内部节点且为包含Break节点
                    WorkflowNodeDto loopBreakNode = workflowConfigDto.getNodes().stream().filter(innerNode -> innerNode.getLoopNodeId() != null
                            && innerNode.getLoopNodeId().equals(node.getId()) && innerNode.getType() == WorkflowNodeConfig.NodeType.LoopBreak).findFirst().orElse(null);
                    if (loopBreakNode == null) {
                        workflowNodeCheckDto.setSuccess(false);
                        workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.LoopMissingBreakNode"));
                    }
                }
            }

            if (node.getType() == WorkflowNodeConfig.NodeType.Variable) {
                VariableNodeConfigDto nodeConfigDto = (VariableNodeConfigDto) node.getNodeConfig();
                if (nodeConfigDto.getConfigType() != VariableNodeConfigDto.ConfigTypeEnum.SET_VARIABLE) {
                    nodeConfigDto.setInputArgs(new ArrayList<>());
                } else {
                    nodeConfigDto.setOutputArgs(new ArrayList<>());
                }
            }

            List<Arg> inputArgs = node.getNodeConfig().getInputArgs();
            if (node.getNodeConfig().getInputArgs() == null) {
                node.getNodeConfig().setInputArgs(new ArrayList<>());
            }
            PreviousDto previousDto = generatePreviousNodes(workflowConfigDto.getNodes(), node.getId());
            checkArgs(node, inputArgs, previousDto.getArgMap(), workflowNodeCheckDto);
        });
        return workflowNodeCheckDtos;
    }

    private void checkIfStartToEnd(WorkflowNodeDto startNode, Map<Long, WorkflowNodeDto> nodeMap, List<WorkflowNodeCheckDto> workflowNodeCheckDtos, Set<Long> checkedNodeIds) {
        if (!CollectionUtils.isEmpty(startNode.getNextNodeIds())) {
            List<WorkflowNodeDto> nextNodes = new ArrayList<>();
            startNode.getNextNodeIds().forEach(nextNodeId -> {
                WorkflowNodeDto workflowNodeDto = nodeMap.get(nextNodeId);
                if (workflowNodeDto != null) {
                    nextNodes.add(workflowNodeDto);
                    if (checkedNodeIds.contains(nextNodeId)) {
                        return;
                    }
                    checkedNodeIds.add(nextNodeId);
                    checkIfStartToEnd(workflowNodeDto, nodeMap, workflowNodeCheckDtos, checkedNodeIds);
                }
            });
            if (nextNodes.isEmpty() && startNode.getType() != WorkflowNodeConfig.NodeType.End) {
                addErrorMessage(workflowNodeCheckDtos, startNode.getId(), I18nUtil.systemMessage("Workflow.Validate.NotConnectedToEnd", startNode.getName()));
            }
        } else if (startNode.getType() != WorkflowNodeConfig.NodeType.End) {
            addErrorMessage(workflowNodeCheckDtos, startNode.getId(), I18nUtil.systemMessage("Workflow.Validate.NotConnectedToEnd", startNode.getName()));
        }
    }

    private void addErrorMessage(List<WorkflowNodeCheckDto> workflowNodeCheckDtos, Long id, String message) {
        //workflowNodeCheckDtos转为map
        Map<Long, WorkflowNodeCheckDto> workflowNodeCheckDtoMap = workflowNodeCheckDtos.stream().collect(Collectors.toMap(WorkflowNodeCheckDto::getNodeId, workflowNodeCheckDto -> workflowNodeCheckDto));
        WorkflowNodeCheckDto workflowNodeCheckDto = workflowNodeCheckDtoMap.get(id);
        if (workflowNodeCheckDto == null) {
            workflowNodeCheckDto = new WorkflowNodeCheckDto();
            workflowNodeCheckDto.setNodeId(id);
            workflowNodeCheckDto.setSuccess(false);
            workflowNodeCheckDtos.add(workflowNodeCheckDto);
        }
        if (workflowNodeCheckDto.getMessages() == null) {
            workflowNodeCheckDto.setMessages(new ArrayList<>());
        }
        if (!workflowNodeCheckDto.getMessages().contains(message)) {
            workflowNodeCheckDto.getMessages().add(message);
        }
    }

    private void checkArgs(WorkflowNodeDto node, List<Arg> inputArgs, Map<String, Arg> argMap, WorkflowNodeCheckDto workflowNodeCheckDto) {
        checkArgs0(node, inputArgs, argMap, workflowNodeCheckDto);
        if (node.getType() == WorkflowNodeConfig.NodeType.LLM) {
            LLMNodeConfigDto nodeConfigDto = (LLMNodeConfigDto) node.getNodeConfig();
            if (nodeConfigDto.getModelId() == null) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoModelBound"));
            }
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.Plugin) {
            PluginNodeConfigDto nodeConfigDto = (PluginNodeConfigDto) node.getNodeConfig();
            if (nodeConfigDto.getPluginId() == null) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoPluginBound"));
            }
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.Mcp) {
            McpNodeConfigDto mcpNodeConfigDto = (McpNodeConfigDto) node.getNodeConfig();
            if (mcpNodeConfigDto.getMcpId() == null || mcpNodeConfigDto.getToolName() == null) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoMcpServiceBound"));
            }
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.Workflow) {
            WorkflowAsNodeConfigDto nodeConfigDto = (WorkflowAsNodeConfigDto) node.getNodeConfig();
            if (nodeConfigDto.getWorkflowId() == null) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoWorkflowBound"));
            }
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.IntentRecognition) {
            IntentRecognitionNodeConfigDto nodeConfigDto = (IntentRecognitionNodeConfigDto) node.getNodeConfig();
            if (CollectionUtils.isEmpty(nodeConfigDto.getIntentConfigs())) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoIntentBound"));
            }
            if (nodeConfigDto.getModelId() == null) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoModelBound"));
            }
            if (!CollectionUtils.isEmpty(nodeConfigDto.getIntentConfigs())) {
                nodeConfigDto.getIntentConfigs().forEach(intentConfigDto -> {
                    if (CollectionUtils.isEmpty(intentConfigDto.getNextNodeIds())) {
                        workflowNodeCheckDto.setSuccess(false);
                        workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoIntentNextNode", intentConfigDto.getIntent()));
                    }
                });
            }
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.QA) {
            QaNodeConfigDto nodeConfigDto = (QaNodeConfigDto) node.getNodeConfig();
            if (nodeConfigDto.getModelId() == null) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoModelBound"));
            }
            if (nodeConfigDto.getAnswerType() == QaNodeConfigDto.AnswerTypeEnum.SELECT && !CollectionUtils.isEmpty(nodeConfigDto.getOptions())) {
                nodeConfigDto.getOptions().forEach(optionConfigDto -> {
                    if (CollectionUtils.isEmpty(optionConfigDto.getNextNodeIds())) {
                        workflowNodeCheckDto.setSuccess(false);
                        workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoOptionNextNode", optionConfigDto.getContent()));
                    }
                });
            }
            if (StringUtils.isBlank(nodeConfigDto.getQuestion())) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.QaQuestionNotConfigured"));
            }
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.Loop) {
            LoopNodeConfigDto loopNodeConfigDto = (LoopNodeConfigDto) node.getNodeConfig();
            if (loopNodeConfigDto.getLoopType() == LoopNodeConfigDto.LoopTypeEnum.SPECIFY_TIMES_LOOP) {
                if (loopNodeConfigDto.getLoopTimes() == null || loopNodeConfigDto.getLoopTimes() < 1) {
                    workflowNodeCheckDto.setSuccess(false);
                    workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoLoopTimes"));
                }
                loopNodeConfigDto.setInputArgs(List.of());
            }
            if (loopNodeConfigDto.getLoopType() == LoopNodeConfigDto.LoopTypeEnum.INFINITE_LOOP) {
                loopNodeConfigDto.setInputArgs(List.of());
            }
            if (loopNodeConfigDto.getLoopType() == LoopNodeConfigDto.LoopTypeEnum.ARRAY_LOOP) {
                if (CollectionUtils.isEmpty(loopNodeConfigDto.getInputArgs())) {
                    workflowNodeCheckDto.setSuccess(false);
                    workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoLoopArray"));
                } else {
                    loopNodeConfigDto.getInputArgs().forEach(arg -> {
                        if (arg.getBindValueType() != Arg.BindValueType.Reference) {
                            workflowNodeCheckDto.setSuccess(false);
                            workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.LoopArrayNotBound"));
                        }
                        if (!arg.getDataType().name().startsWith("Array")) {
                            workflowNodeCheckDto.setSuccess(false);
                            workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.LoopArrayTypeError"));
                        }
                    });
                }
            }
            if (loopNodeConfigDto.getVariableArgs() != null) {
                checkArgs0(node, loopNodeConfigDto.getVariableArgs(), argMap, workflowNodeCheckDto);
            }
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.Condition) {
            ConditionNodeConfigDto conditionNodeConfigDto = (ConditionNodeConfigDto) node.getNodeConfig();
            if (!CollectionUtils.isEmpty(conditionNodeConfigDto.getConditionBranchConfigs())) {
                conditionNodeConfigDto.getConditionBranchConfigs().forEach(conditionBranchConfigDto -> {
                    if (CollectionUtils.isEmpty(conditionBranchConfigDto.getNextNodeIds())) {
                        workflowNodeCheckDto.setSuccess(false);
                        workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoConditionBranch"));
                    }
                });
            }
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.DocumentExtraction) {
            NodeConfigDto nodeConfigDto = node.getNodeConfig();
            if (CollectionUtils.isEmpty(nodeConfigDto.getInputArgs())) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoDocumentUrl"));
            } else {
                //如果为输类型入，判断是否为网络地址
                Arg arg = nodeConfigDto.getInputArgs().get(0);
                if (arg.getBindValueType() == Arg.BindValueType.Input) {
                    //判断是否为合法的网络地址
                    if (!arg.getBindValue().startsWith("http://") && !arg.getBindValue().startsWith("https://")) {
                        workflowNodeCheckDto.setSuccess(false);
                        workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.InvalidDocumentUrl"));
                    }
                }
            }
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.HTTPRequest) {
            HttpNodeConfigDto httpNodeConfigDto = (HttpNodeConfigDto) node.getNodeConfig();
            if (StringUtils.isBlank(httpNodeConfigDto.getUrl())) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoUrlBound"));
            } else {
                //判断是否为合法的URL地址
                if (!httpNodeConfigDto.getUrl().startsWith("http://") && !httpNodeConfigDto.getUrl().startsWith("https://")) {
                    workflowNodeCheckDto.setSuccess(false);
                    workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.InvalidUrl"));
                }
            }
            if (httpNodeConfigDto.getTimeout() == null || httpNodeConfigDto.getTimeout() <= 0) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoTimeout"));
            }
            checkAndUpdateInputArgs(node, httpNodeConfigDto.getHeaders(), argMap, workflowNodeCheckDto);
            checkAndUpdateInputArgs(node, httpNodeConfigDto.getQueries(), argMap, workflowNodeCheckDto);
            checkAndUpdateInputArgs(node, httpNodeConfigDto.getBody(), argMap, workflowNodeCheckDto);
        }
        if (node.getNodeConfig() instanceof TableNodeConfigDto tableNodeConfigDto) {
            if (tableNodeConfigDto.getTableId() == null) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoTableBound"));
            }
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.TableSQL && node.getNodeConfig() instanceof TableCustomSqlNodeConfigDto tableSqlNodeConfigDto) {
            if (StringUtils.isBlank(tableSqlNodeConfigDto.getSql())) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoSqlStatement"));
            }
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.TableDataQuery && node.getNodeConfig() instanceof TableDataQueryNodeConfigDto tableDataQueryNodeConfigDto) {
            if (tableDataQueryNodeConfigDto.getLimit() == null) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoTableName"));
            } else if (tableDataQueryNodeConfigDto.getLimit() > 1000) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.QueryLimitExceeded"));
            }
            checkTableConditionArgs(tableDataQueryNodeConfigDto.getConditionType(), tableDataQueryNodeConfigDto.getConditionArgs(), workflowNodeCheckDto);
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.TableDataUpdate && node.getNodeConfig() instanceof TableDataUpdateNodeConfigDto tableDataUpdateNodeConfigDto) {
            checkTableConditionArgs(tableDataUpdateNodeConfigDto.getConditionType(), tableDataUpdateNodeConfigDto.getConditionArgs(), workflowNodeCheckDto);
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.TableDataDelete && node.getNodeConfig() instanceof TableDataDeleteNodeConfigDto tableDataDeleteNodeConfigDto) {
            checkTableConditionArgs(tableDataDeleteNodeConfigDto.getConditionType(), tableDataDeleteNodeConfigDto.getConditionArgs(), workflowNodeCheckDto);
        }
    }

    private void checkTableConditionArgs(TableNodeConfigDto.ConditionTypeEnum conditionType, List<TableNodeConfigDto.ConditionArgDto> conditionArgs, WorkflowNodeCheckDto workflowNodeCheckDto) {
        if (!CollectionUtils.isEmpty(conditionArgs)) {
            if (conditionType == null && conditionArgs.size() > 1) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoConditionType"));
            }
            for (TableNodeConfigDto.ConditionArgDto conditionArgDto : conditionArgs) {
                if (conditionArgDto.getCompareType() == null) {
                    workflowNodeCheckDto.setSuccess(false);
                    workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoCompareType"));
                }
                if (conditionArgDto.getFirstArg() == null || (StringUtils.isBlank(conditionArgDto.getFirstArg().getName()) && StringUtils.isBlank(conditionArgDto.getFirstArg().getBindValue()))) {
                    workflowNodeCheckDto.setSuccess(false);
                    workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoConditionParameter"));
                }
            }
        }
    }

    @Override
    public WorkflowNodeCheckDto validWorkflowNode(WorkflowNodeDto workflowNodeDto) {
        WorkflowNodeCheckDto workflowNodeCheckDto = new WorkflowNodeCheckDto();
        workflowNodeCheckDto.setSuccess(true);
        workflowNodeCheckDto.setMessages(new ArrayList<>());
        Map<String, Arg> argMap = queryPreviousNodes(workflowNodeDto.getId()).getArgMap();
        checkArgs0(workflowNodeDto, workflowNodeDto.getNodeConfig().getInputArgs(), argMap, workflowNodeCheckDto);
        if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.HTTPRequest) {
            HttpNodeConfigDto httpNodeConfigDto = (HttpNodeConfigDto) workflowNodeDto.getNodeConfig();
            checkAndUpdateInputArgs(workflowNodeDto, httpNodeConfigDto.getHeaders(), argMap, workflowNodeCheckDto);
            checkAndUpdateInputArgs(workflowNodeDto, httpNodeConfigDto.getQueries(), argMap, workflowNodeCheckDto);
            checkAndUpdateInputArgs(workflowNodeDto, httpNodeConfigDto.getBody(), argMap, workflowNodeCheckDto);
        }
        if (workflowNodeDto.getType() != null && workflowNodeDto.getType().name().startsWith("Table")) {
            checkTableNode(workflowNodeDto, workflowNodeCheckDto);
        }
        return workflowNodeCheckDto;
    }

    private void checkTableNode(WorkflowNodeDto node, WorkflowNodeCheckDto workflowNodeCheckDto) {
        if (node.getType() == WorkflowNodeConfig.NodeType.TableSQL) {
            TableCustomSqlNodeConfigDto tableSqlNodeConfigDto = (TableCustomSqlNodeConfigDto) node.getNodeConfig();
            if (StringUtils.isBlank(tableSqlNodeConfigDto.getSql())) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoSqlStatement"));
            }
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.TableDataQuery) {
            TableDataQueryNodeConfigDto tableDataQueryNodeConfigDto = (TableDataQueryNodeConfigDto) node.getNodeConfig();
            if (tableDataQueryNodeConfigDto.getLimit() == null) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.NoTableName"));
            } else if (tableDataQueryNodeConfigDto.getLimit() > 1000) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.QueryLimitExceeded"));
            }
            checkTableConditionArgs(tableDataQueryNodeConfigDto.getConditionType(), tableDataQueryNodeConfigDto.getConditionArgs(), workflowNodeCheckDto);
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.TableDataUpdate) {
            TableDataUpdateNodeConfigDto tableDataUpdateNodeConfigDto = (TableDataUpdateNodeConfigDto) node.getNodeConfig();
            checkTableConditionArgs(tableDataUpdateNodeConfigDto.getConditionType(), tableDataUpdateNodeConfigDto.getConditionArgs(), workflowNodeCheckDto);
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.TableDataDelete) {
            TableDataDeleteNodeConfigDto tableDataDeleteNodeConfigDto = (TableDataDeleteNodeConfigDto) node.getNodeConfig();
            checkTableConditionArgs(tableDataDeleteNodeConfigDto.getConditionType(), tableDataDeleteNodeConfigDto.getConditionArgs(), workflowNodeCheckDto);
        }
    }

    private void checkArgs0(WorkflowNodeDto node, List<Arg> inputArgs, Map<String, Arg> argMap, WorkflowNodeCheckDto workflowNodeCheckDto) {
        checkAndUpdateInputArgs(node, inputArgs, argMap, workflowNodeCheckDto);
        checkDuplicateArgs(inputArgs, workflowNodeCheckDto);
        checkOutputArgs(node.getNodeConfig().getOutputArgs(), workflowNodeCheckDto);
    }

    private void checkOutputArgs(List<Arg> outputArgs, WorkflowNodeCheckDto workflowNodeCheckDto) {
        if (!CollectionUtils.isEmpty(outputArgs)) {
            outputArgs.forEach(arg -> {
                if (arg.getName() == null) {
                    arg.setName("");
                }
                //判断参数是否符合函数签名命名规则
                if (!arg.getName().matches("^[a-zA-Z_][a-zA-Z0-9_-]*$")) {
                    workflowNodeCheckDto.setSuccess(false);
                    workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.InvalidParamName", arg.getName()));
                }
            });
            checkDuplicateArgs(outputArgs, workflowNodeCheckDto);
        }
    }

    private void checkAndUpdateInputArgs(WorkflowNodeDto node, List<Arg> inputArgs, Map<String, Arg> argMap, WorkflowNodeCheckDto workflowNodeCheckDto) {
        if (inputArgs == null) {
            return;
        }
        AtomicBoolean update = new AtomicBoolean(false);
        NodeConfigDto.ContextPassingType contextPassingType = node.getNodeConfig().getContextPassingType() == null ? NodeConfigDto.ContextPassingType.Manual : node.getNodeConfig().getContextPassingType();
        inputArgs.forEach(inputArg -> {
            if (inputArg.getName() == null) {
                inputArg.setName("");
            }
            //判断参数是否符合函数签名命名规则
            if (!inputArg.getName().matches("^[a-zA-Z][a-zA-Z0-9_-]*$")) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.InvalidParamName", inputArg.getName()));
            }
            if (inputArg.isRequire() && StringUtils.isEmpty(inputArg.getBindValue()) && contextPassingType == NodeConfigDto.ContextPassingType.Manual) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.ParamNotBound", inputArg.getName()));
            }
            if (StringUtils.isNotBlank(inputArg.getBindValue()) && inputArg.getBindValueType() == Arg.BindValueType.Reference && contextPassingType == NodeConfigDto.ContextPassingType.Manual) {
                Arg arg = argMap.get(inputArg.getBindValue());
                if (arg == null) {
                    workflowNodeCheckDto.setSuccess(false);
                    workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.ParamBindValueNotExist", inputArg.getName()));
                } else if (arg.getOriginDataType() != null) {
                    if (inputArg.getDataType() != arg.getOriginDataType()) {
                        inputArg.setDataType(arg.getOriginDataType());
                        update.set(true);
                    }
                } else if (inputArg.getDataType() != arg.getDataType()) {
                    inputArg.setDataType(arg.getDataType());
                    update.set(true);
                }
            }
            if (!CollectionUtils.isEmpty(inputArg.getSubArgs())) {
                checkArgs0(node, inputArg.getSubArgs(), argMap, workflowNodeCheckDto);
            }
        });
        //引用的上级参数类型发生变更，再次补充检查更新
        if (update.get()) {
            WorkflowNodeUpdateDto<NodeConfigDto> updateWorkflowNodeConfig = new WorkflowNodeUpdateDto<NodeConfigDto>();
            updateWorkflowNodeConfig.setNodeId(node.getId());
            updateWorkflowNodeConfig.setNodeConfig(node.getNodeConfig());
            updateWorkflowNodeConfig(updateWorkflowNodeConfig);
        }
    }

    private void checkDuplicateArgs(List<Arg> args, WorkflowNodeCheckDto workflowNodeCheckDto) {
        if (args == null) {
            return;
        }
        Set<String> argNames = new HashSet<>();
        args.forEach(arg -> {
            if (argNames.contains(arg.getName())) {
                workflowNodeCheckDto.setSuccess(false);
                workflowNodeCheckDto.getMessages().add(I18nUtil.systemMessage("Workflow.Validate.ParamDuplicate", arg.getName()));
            }
            argNames.add(arg.getName());
        });
    }

    /**
     * 检测是否含有循环连线
     */
    private void checkIfHasLoopLine(WorkflowNodeDto node, Set<Long> lineNodeIds, Map<Long, WorkflowNodeDto> nodeMap, WorkflowNodeCheckDto workflowNodeCheckDto) {
        if (CollectionUtils.isEmpty(node.getNextNodeIds())) {
            return;
        }
        lineNodeIds = new HashSet<>(lineNodeIds);
        lineNodeIds.add(node.getId());
        for (Long nextNodeId : node.getNextNodeIds()) {
            WorkflowNodeDto next = nodeMap.get(nextNodeId);
            if (next != null) {
                if (lineNodeIds.contains(next.getId())) {
                    workflowNodeCheckDto.setNodeId(node.getId());
                    workflowNodeCheckDto.setSuccess(false);
                    workflowNodeCheckDto.setMessages(List.of(I18nUtil.systemMessage("Workflow.Validate.CyclicConnection", node.getName(), next.getName())));
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowNodeCycleEdge,
                            node.getName(), next.getName());
                }
                checkIfHasLoopLine(next, lineNodeIds, nodeMap, workflowNodeCheckDto);
            }
        }
        if (node.getType() == WorkflowNodeConfig.NodeType.Loop && node.getInnerStartNodeId() != null) {
            WorkflowNodeDto workflowNodeDto = nodeMap.get(node.getInnerStartNodeId());
            if (workflowNodeDto != null) {
                checkIfHasLoopLine(workflowNodeDto, new HashSet<>(), nodeMap, workflowNodeCheckDto);
            }
        }
    }

    private WorkflowNodeDto convertNodeConfig(WorkflowNodeConfig workflowNodeConfig, Long spaceId, boolean forExecute) {
        WorkflowNodeDto workflowNodeDto = new WorkflowNodeDto();
        NodeConfigDto nodeConfigDto = WorkflowConfigDto.convertToNodeConfigDto(workflowNodeConfig.getType(), workflowNodeConfig.getConfig());
        BeanUtils.copyProperties(workflowNodeConfig, workflowNodeDto);
        workflowNodeDto.setNodeConfig(nodeConfigDto);
        workflowNodeDto.setSpaceId(spaceId);
        // 补充参数信息
        completeConfigAndArgs(workflowNodeDto, forExecute);
        return workflowNodeDto;
    }

    private void completeConfigAndArgs(WorkflowNodeDto workflowNodeDto, boolean forExecute) {
        NodeConfigDto nodeConfig = workflowNodeDto.getNodeConfig();
        if (nodeConfig == null) {
            return;
        }
        if (nodeConfig.getExceptionHandleConfig() == null) {
            nodeConfig.setExceptionHandleConfig(new ExceptionHandleConfigDto());
            nodeConfig.getExceptionHandleConfig().setExceptionHandleType(ExceptionHandleConfigDto.ExceptionHandleTypeEnum.INTERRUPT);
            nodeConfig.getExceptionHandleConfig().setRetryCount(0);
            nodeConfig.getExceptionHandleConfig().setTimeout(nodeConfig instanceof AgentNodeConfigDto ? 3600 : 180);
            nodeConfig.getExceptionHandleConfig().setExceptionHandleNodeIds(new ArrayList<>());
            nodeConfig.getExceptionHandleConfig().setSpecificContent(new HashMap<>());
        }
        if (nodeConfig instanceof PluginNodeConfigDto) {
            Map<String, Arg> inputArgMap = new HashMap<>();
            generateKey(workflowNodeDto.getId().toString(), nodeConfig.getInputArgs(), inputArgMap);
            PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(((PluginNodeConfigDto) nodeConfig).getPluginId(), workflowNodeDto.getSpaceId());
            if (pluginDto != null && pluginDto.getConfig() instanceof PluginConfigDto pluginconfigdto) {
                List<Arg> inputArgs = pluginconfigdto.getInputArgs();
                List<Arg> outputArgs = pluginconfigdto.getOutputArgs();
                if (!forExecute) {
                    Arg.removeDisabledArgs(inputArgs);
                } else {
                    ((PluginNodeConfigDto) nodeConfig).setPluginConfig(pluginDto);
                }
                //使用插件最新的参数+已有参数的配置
                updateInputArgs(workflowNodeDto.getId().toString(), inputArgs, inputArgMap);
                nodeConfig.setInputArgs(inputArgs);
                nodeConfig.setOutputArgs(outputArgs);
            }
        }
        if (nodeConfig instanceof McpNodeConfigDto) {
            Map<String, Arg> inputArgMap = new HashMap<>();
            generateKey(workflowNodeDto.getId().toString(), nodeConfig.getInputArgs(), inputArgMap);
            McpNodeConfigDto mcpNodeConfig = (McpNodeConfigDto) nodeConfig;
            McpDto mcpDto = mcpRpcService.queryMcp(mcpNodeConfig.getMcpId(), workflowNodeDto.getSpaceId());
            if (mcpDto != null) {
                McpToolDto mcpToolDto = mcpDto.getDeployedConfig().getTools().stream()
                        .filter(tool -> tool.getName().equals(mcpNodeConfig.getToolName()))
                        .findFirst().orElse(null);
                List<Arg> inputArgs = ArgConverter.convertMcpArgsToArgs(mcpToolDto == null ? new ArrayList<>() : mcpToolDto.getInputArgs());
                //使用插件最新的参数+已有参数的配置
                updateInputArgs(workflowNodeDto.getId().toString(), inputArgs, inputArgMap);
                nodeConfig.setInputArgs(inputArgs);
                List<Arg> outputArgs = new ArrayList<>();
                outputArgs.add(Arg.builder().dataType(DataTypeEnum.Boolean).name("success").description(I18nUtil.systemMessage("Workflow.Output.CallSuccess")).enable(true).build());
                outputArgs.add(Arg.builder().dataType(DataTypeEnum.String).name("message").description(I18nUtil.systemMessage("Workflow.Output.FailureMessage")).enable(true).build());
                List<Arg> contentArgs = new ArrayList<>();
                contentArgs.add(Arg.builder().dataType(DataTypeEnum.String).name("type").description("Data type: text text; image image").enable(true).build());
                contentArgs.add(Arg.builder().dataType(DataTypeEnum.String).name("data").description(I18nUtil.systemMessage("Workflow.Output.ResultData")).enable(true).build());
                outputArgs.add(Arg.builder().dataType(DataTypeEnum.Array_Object).name("result").description(I18nUtil.systemMessage("Workflow.Output.ExecutionResult")).subArgs(contentArgs).enable(true).build());
                mcpNodeConfig.setOutputArgs(outputArgs);
                mcpNodeConfig.setMcp(mcpDto);
                if (!forExecute) {
                    mcpDto.getMcpConfig().setServerConfig(null);
                    mcpDto.getDeployedConfig().setServerConfig(null);
                    mcpDto.getMcpConfig().setComponents(null);
                    mcpDto.getDeployedConfig().setComponents(null);
                }
            } else {
                mcpNodeConfig.setMcp(null);
            }
        }
        if (nodeConfig instanceof WorkflowAsNodeConfigDto) {
            Map<String, Arg> inputArgMap = new HashMap<>();
            generateKey(workflowNodeDto.getId().toString(), nodeConfig.getInputArgs(), inputArgMap);
            WorkflowConfigDto workflowConfigDto = queryPublishedWorkflowConfig(((WorkflowAsNodeConfigDto) nodeConfig).getWorkflowId(), workflowNodeDto.getSpaceId(), forExecute);
            if (workflowConfigDto != null) {
                List<Arg> inputArgs = workflowConfigDto.getInputArgs();
                List<Arg> outputArgs = workflowConfigDto.getOutputArgs();
                //使用插件最新的参数+已有参数的配置
                updateInputArgs(workflowNodeDto.getId().toString(), inputArgs, inputArgMap);
                nodeConfig.setInputArgs(inputArgs);
                nodeConfig.setOutputArgs(outputArgs);
            }
            if (forExecute) {
                ((WorkflowAsNodeConfigDto) nodeConfig).setWorkflowConfig(workflowConfigDto);
            }
        }
        if (nodeConfig instanceof LLMNodeConfigDto) {
            ModelConfigDto modelConfigDto = modelApplicationService.queryModelConfigById(((LLMNodeConfigDto) nodeConfig).getModelId());
            ((LLMNodeConfigDto) nodeConfig).setModelConfig(modelConfigDto);
            List<LLMNodeConfigDto.SkillComponentConfigDto> skillComponentConfigs = ((LLMNodeConfigDto) nodeConfig).getSkillComponentConfigs();
            if (skillComponentConfigs != null) {
                skillComponentConfigs.forEach(skillComponentConfigDto -> {
                    if (skillComponentConfigDto.getType() == LLMNodeConfigDto.SkillComponentConfigDto.Type.Workflow) {
                        WorkflowConfigDto skillWorkflowConfigDto = queryPublishedWorkflowConfig(skillComponentConfigDto.getTypeId(), workflowNodeDto.getSpaceId(), forExecute);
                        if (skillWorkflowConfigDto == null) {
                            skillComponentConfigDto.setType(null);
                        } else {
                            skillComponentConfigDto.setTargetConfig(skillWorkflowConfigDto);
                            skillComponentConfigDto.setName(skillWorkflowConfigDto.getName());
                            skillComponentConfigDto.setDescription(skillWorkflowConfigDto.getDescription());
                            skillComponentConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(skillWorkflowConfigDto.getIcon(), skillWorkflowConfigDto.getName(), LLMNodeConfigDto.SkillComponentConfigDto.Type.Workflow.name()));

                            //skillWorkflowConfigDto 为空,无法进行参数绑定
                            List<Arg> argBindConfigDtos = Arg.updateBindConfigArgs(null, skillComponentConfigDto.getInputArgBindConfigs(), skillWorkflowConfigDto.getInputArgs());
                            skillComponentConfigDto.setInputArgBindConfigs(argBindConfigDtos);
                            List<Arg> argBindConfigDtos1 = Arg.updateBindConfigArgs(null, skillComponentConfigDto.getOutputArgBindConfigs(), skillWorkflowConfigDto.getOutputArgs());
                            skillComponentConfigDto.setOutputArgBindConfigs(argBindConfigDtos1);
                        }

                    }
                    if (skillComponentConfigDto.getType() == LLMNodeConfigDto.SkillComponentConfigDto.Type.Plugin) {
                        PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(skillComponentConfigDto.getTypeId(), workflowNodeDto.getSpaceId());
                        if (pluginDto == null || pluginDto.getConfig() == null) {
                            skillComponentConfigDto.setType(null);
                        } else {
                            skillComponentConfigDto.setTargetConfig(pluginDto);
                            skillComponentConfigDto.setName(pluginDto.getName());
                            skillComponentConfigDto.setDescription(pluginDto.getDescription());
                            skillComponentConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(pluginDto.getIcon(), pluginDto.getName(), LLMNodeConfigDto.SkillComponentConfigDto.Type.Plugin.name()));

                            //pluginDto 如果为空, 则无法进行绑定
                            PluginConfigDto pluginConfigDto = (PluginConfigDto) pluginDto.getConfig();
                            if (!forExecute) {
                                Arg.removeDisabledArgs(pluginConfigDto.getInputArgs());
                                pluginDto.setConfig(null);
                            }
                            List<Arg> argBindConfigDtos1 = Arg.updateBindConfigArgs(null, skillComponentConfigDto.getOutputArgBindConfigs(), pluginConfigDto.getOutputArgs());
                            skillComponentConfigDto.setOutputArgBindConfigs(argBindConfigDtos1);
                            List<Arg> args = pluginConfigDto.getInputArgs().stream().filter(arg -> arg.getEnable()).collect(Collectors.toList());
                            List<Arg> argBindConfigDtos = Arg.updateBindConfigArgs(null, skillComponentConfigDto.getInputArgBindConfigs(), args);
                            skillComponentConfigDto.setInputArgBindConfigs(argBindConfigDtos);
                        }
                    }

                    if (skillComponentConfigDto.getType() == LLMNodeConfigDto.SkillComponentConfigDto.Type.Mcp) {
                        McpDto mcpDto = mcpRpcService.queryMcp(skillComponentConfigDto.getTypeId(), workflowNodeDto.getSpaceId());
                        if (mcpDto == null) {
                            skillComponentConfigDto.setType(null);
                        } else {
                            McpToolDto mcpToolDto = mcpDto.getDeployedConfig().getTools().stream()
                                    .filter(tool -> tool.getName().equals(skillComponentConfigDto.getToolName()))
                                    .findFirst().orElse(null);
                            if (mcpToolDto != null) {
                                skillComponentConfigDto.setTargetConfig(mcpDto);
                                skillComponentConfigDto.setName(mcpDto.getName() + "/" + mcpToolDto.getName());
                                skillComponentConfigDto.setDescription(mcpDto.getDescription());
                                skillComponentConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(mcpDto.getIcon(), mcpDto.getName(), LLMNodeConfigDto.SkillComponentConfigDto.Type.Mcp.name()));
                                skillComponentConfigDto.setTargetConfig(mcpDto);
                                if (!forExecute) {
                                    mcpDto.getMcpConfig().setServerConfig(null);
                                    mcpDto.getDeployedConfig().setServerConfig(null);
                                    mcpDto.getMcpConfig().setComponents(null);
                                    mcpDto.getDeployedConfig().setComponents(null);
                                }
                                //pluginDto 如果为空, 则无法进行绑定
                                List<Arg> inputArgs = ArgConverter.convertMcpArgsToArgs(mcpToolDto.getInputArgs());
                                List<Arg> argBindConfigDtos = Arg.updateBindConfigArgs(null, skillComponentConfigDto.getInputArgBindConfigs(), inputArgs);
                                skillComponentConfigDto.setInputArgBindConfigs(argBindConfigDtos);
                            } else {
                                skillComponentConfigDto.setType(null);
                            }
                        }

                    }

                    if (skillComponentConfigDto.getType() == LLMNodeConfigDto.SkillComponentConfigDto.Type.Knowledge) {
                        KnowledgeConfigVo knowledgeConfigVo = KnowledgeRpcService.queryKnowledgeConfigById(skillComponentConfigDto.getTypeId());
                        if (knowledgeConfigVo != null) {
                            skillComponentConfigDto.setName(knowledgeConfigVo.getName());
                            skillComponentConfigDto.setDescription(knowledgeConfigVo.getDescription());
                            skillComponentConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(knowledgeConfigVo.getIcon(), knowledgeConfigVo.getName(), LLMNodeConfigDto.SkillComponentConfigDto.Type.Knowledge.name()));
                            skillComponentConfigDto.setTargetConfig(knowledgeConfigVo);
                        }
                    }
                });
                //过滤掉type为null的
                skillComponentConfigs = skillComponentConfigs.stream().filter(skillComponentConfigDto -> skillComponentConfigDto.getType() != null).collect(Collectors.toList());
                ((LLMNodeConfigDto) nodeConfig).setSkillComponentConfigs(skillComponentConfigs);
                if (!forExecute && Objects.nonNull(modelConfigDto)) {
                    modelConfigDto.setApiInfoList(null);
                }
            }
        }
        if (nodeConfig instanceof QaNodeConfigDto qaNodeConfigDto) {
            ModelConfigDto modelConfigDto = modelApplicationService.queryModelConfigById((qaNodeConfigDto).getModelId());
            (qaNodeConfigDto).setModelConfig(modelConfigDto);
            if (!forExecute && Objects.nonNull(modelConfigDto)) {
                modelConfigDto.setApiInfoList(null);
            }
        }
        if (nodeConfig instanceof IntentRecognitionNodeConfigDto intentRecognitionNodeConfigDto) {
            ModelConfigDto modelConfigDto = modelApplicationService.queryModelConfigById(intentRecognitionNodeConfigDto.getModelId());
            intentRecognitionNodeConfigDto.setModelConfig(modelConfigDto);
            if (!forExecute && Objects.nonNull(modelConfigDto)) {
                modelConfigDto.setApiInfoList(null);
            }
            intentRecognitionNodeConfigDto.getIntentConfigs().stream().filter(intentConfig -> intentConfig.getIntentType() == IntentRecognitionNodeConfigDto.IntentTypeEnum.OTHER).findFirst().ifPresent(intentConfigDto -> intentConfigDto.setIntent(I18nUtil.systemMessage("Backend.Workflow.Intent.Other")));
            I18nUtil.replaceSystemMessage("WorkflowIntent", intentRecognitionNodeConfigDto.getOutputArgs());
        }
        if (nodeConfig instanceof CodeNodeConfigDto codeNodeConfig) {
            String codePython = codeNodeConfig.getCodePython();
            String codeJavaScript = codeNodeConfig.getCodeJavaScript();
            if (StringUtils.isBlank(codePython)) {
                codeNodeConfig.setCodePython(CodeConstant.DEFAULT_CODE_PYTHON);
            }
            if (StringUtils.isBlank(codeJavaScript)) {
                codeNodeConfig.setCodeJavaScript(CodeConstant.DEFAULT_CODE_JS);
            }
            //判断code的语言,如果没有,默认为JS.场景是前端新增空白code界面,默认一个语言为js,展示js的代码示例
            if (Objects.isNull(codeNodeConfig.getCodeLanguage())) {
                codeNodeConfig.setCodeLanguage(CodeLanguageEnum.JavaScript);
            }

        }
        if (nodeConfig instanceof TextProcessingNodeConfigDto textProcessingNodeConfigDto) {
            if (!CollectionUtils.isEmpty(textProcessingNodeConfigDto.getOutputArgs())) {
                List<Arg> textOutputArgs = TextProcessingNodeConfigDto.obtainDefaultOutputArgs(textProcessingNodeConfigDto.getTextHandleType());
                textProcessingNodeConfigDto.setOutputArgs(textOutputArgs);
            }
            I18nUtil.replaceSystemMessage("WorkflowText", textProcessingNodeConfigDto.getOutputArgs());
        }
        if (nodeConfig instanceof KnowledgeNodeConfigDto knowledgeNodeConfigDto) {
            if (!CollectionUtils.isEmpty((knowledgeNodeConfigDto).getKnowledgeBaseConfigs())) {
                knowledgeNodeConfigDto.getKnowledgeBaseConfigs().forEach(knowledgeBaseConfigDto -> {
                    if (knowledgeBaseConfigDto.getKnowledgeBaseId() != null) {
                        KnowledgeConfigVo knowledgeConfigVo = KnowledgeRpcService.queryKnowledgeConfigById(knowledgeBaseConfigDto.getKnowledgeBaseId());
                        if (knowledgeConfigVo != null) {
                            knowledgeBaseConfigDto.setName(knowledgeConfigVo.getName());
                            knowledgeBaseConfigDto.setDescription(knowledgeConfigVo.getDescription());
                            knowledgeBaseConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(knowledgeConfigVo.getIcon(), knowledgeConfigVo.getName(), WorkflowNodeConfig.NodeType.Knowledge.name()));
                        }
                    }
                });
                I18nUtil.replaceSystemMessage("WorkflowKb", knowledgeNodeConfigDto.getOutputArgs());
            }
        }
        if (nodeConfig instanceof KnowledgeInsertNodeConfigDto knowledgeInsertNodeConfig) {
            KnowledgeConfigVo knowledgeConfigVo = KnowledgeRpcService.queryKnowledgeConfigById(knowledgeInsertNodeConfig.getKnowledgeBaseId());
            if (knowledgeConfigVo != null) {
                knowledgeInsertNodeConfig.setName(knowledgeConfigVo.getName());
                knowledgeInsertNodeConfig.setDescription(knowledgeConfigVo.getDescription());
                knowledgeInsertNodeConfig.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(knowledgeConfigVo.getIcon(), knowledgeConfigVo.getName(), WorkflowNodeConfig.NodeType.Knowledge.name()));
            }
            I18nUtil.replaceSystemMessage("WorkflowKb", knowledgeInsertNodeConfig.getOutputArgs());
        }
        if (nodeConfig instanceof AgentNodeConfigDto agentNodeConfigDto) {
            AgentConfigDto agentConfigDto = agentApplicationService.queryPublishedConfig(agentNodeConfigDto.getAgentId(), forExecute);
            if (agentConfigDto != null && agentConfigDto.getSpaceId().equals(workflowNodeDto.getSpaceId())) {
                agentNodeConfigDto.setName(agentConfigDto.getName());
                agentNodeConfigDto.setDescription(agentConfigDto.getDescription());
                agentNodeConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(agentConfigDto.getIcon(), agentConfigDto.getName(), WorkflowNodeConfig.NodeType.Agent.name()));
                if (forExecute) {
                    agentNodeConfigDto.setAgentConfigDto(agentConfigDto);
                }
            }
        }
        if (nodeConfig instanceof TableNodeConfigDto tableNodeConfigDto) {
            if (tableNodeConfigDto.getTableId() != null) {
                DorisTableDefineRequest request = new DorisTableDefineRequest();
                request.setTableId(tableNodeConfigDto.getTableId());
                TableDefineVo dorisTableDefinitionVo = null;
                try {
                    dorisTableDefinitionVo = iComposeDbTableRpcService.queryTableDefinition(request);
                } catch (Exception e) {
                    // 忽略
                    log.warn("Failed to query table schema definition {}", tableNodeConfigDto.getTableId());
                }
                if (dorisTableDefinitionVo != null) {
                    tableNodeConfigDto.setName(dorisTableDefinitionVo.getTableName());
                    tableNodeConfigDto.setDescription(dorisTableDefinitionVo.getTableDescription());
                    tableNodeConfigDto.setIcon(dorisTableDefinitionVo.getIcon());
                    tableNodeConfigDto.setTableFields(ArgConverter.convertTableFieldsToArgs(dorisTableDefinitionVo.getFieldList()));
                } else {
                    tableNodeConfigDto.setTableId(null);
                }
            }
            if (nodeConfig.getInputArgs() == null) {
                nodeConfig.setInputArgs(new ArrayList<>());
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.TableSQL) {
                if (nodeConfig.getOutputArgs() == null) {
                    nodeConfig.setOutputArgs(new ArrayList<>());
                }
            } else {
                nodeConfig.setOutputArgs(new ArrayList<>());
            }

            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.TableDataAdd) {
                //inputArgs转name为key的map
                Map<String, Arg> inputArgMap0 = nodeConfig.getInputArgs().stream().collect(Collectors.toMap(Arg::getName, arg -> arg, (arg1, arg2) -> arg1));
                //补充数据表字段
                if (tableNodeConfigDto.getTableFields() != null) {
                    List<Arg> newInputArgs = new ArrayList<>();
                    tableNodeConfigDto.getTableFields().forEach(tableField -> {
                        Arg arg = inputArgMap0.get(tableField.getName());
                        if (arg != null) {
                            tableField.setBindValue(arg.getBindValue());
                            tableField.setBindValueType(arg.getBindValueType());
                        }
                        newInputArgs.add(tableField);
                    });
                    //过滤系统字段
                    newInputArgs.removeIf(arg -> arg.isSystemVariable());
                    nodeConfig.setInputArgs(newInputArgs);
                }
                nodeConfig.getOutputArgs().add(Arg.builder().key("id").name("id").dataType(DataTypeEnum.Number)
                        .description("Data unique ID").systemVariable(true).require(true).build());
                nodeConfig.getOutputArgs().add(Arg.builder().key("success").name("success").dataType(DataTypeEnum.Boolean)
                        .description(I18nUtil.systemMessage("Workflow.Output.InsertSuccess")).systemVariable(true).require(true).build());
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.TableDataDelete) {
                TableDataDeleteNodeConfigDto tableDataDeleteNodeConfigDto = (TableDataDeleteNodeConfigDto) nodeConfig;
                if (tableDataDeleteNodeConfigDto.getConditionArgs() == null) {
                    tableDataDeleteNodeConfigDto.setConditionArgs(new ArrayList<>());
                }
                nodeConfig.getOutputArgs().add(Arg.builder().key("success").name("success").dataType(DataTypeEnum.Boolean)
                        .description(I18nUtil.systemMessage("Workflow.Output.DeleteSuccess")).systemVariable(true).require(true).build());
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.TableDataUpdate) {
                TableDataUpdateNodeConfigDto tableDataUpdateNodeConfigDto = (TableDataUpdateNodeConfigDto) nodeConfig;
                if (tableDataUpdateNodeConfigDto.getConditionArgs() == null) {
                    tableDataUpdateNodeConfigDto.setConditionArgs(new ArrayList<>());
                }
                nodeConfig.getOutputArgs().add(Arg.builder().key("success").name("success").dataType(DataTypeEnum.Boolean)
                        .description(I18nUtil.systemMessage("Workflow.Output.UpdateSuccess")).systemVariable(true).require(true).build());
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.TableDataQuery) {
                TableDataQueryNodeConfigDto tableDataQueryNodeConfigDto = (TableDataQueryNodeConfigDto) nodeConfig;
                if (tableDataQueryNodeConfigDto.getConditionArgs() == null) {
                    tableDataQueryNodeConfigDto.setConditionArgs(new ArrayList<>());
                }
                tableNodeConfigDto.getTableFields().forEach(tableField -> tableField.setSystemVariable(true));
                nodeConfig.getOutputArgs().add(Arg.builder().key("outputList").name("outputList").dataType(DataTypeEnum.Array_Object)
                        .description(I18nUtil.systemMessage("Workflow.Output.QueryResult")).systemVariable(true).require(true).subArgs(tableNodeConfigDto.getTableFields()).build());
                nodeConfig.getOutputArgs().add(Arg.builder().key("rowNum").name("rowNum").dataType(DataTypeEnum.Number).description(I18nUtil.systemMessage("Workflow.Output.TotalCount"))
                        .systemVariable(true).require(true).build());
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.TableSQL) {
                if (CollectionUtils.isEmpty(nodeConfig.getOutputArgs())) {
                    tableNodeConfigDto.getOutputArgs().add(Arg.builder().key("outputList").name("outputList").dataType(DataTypeEnum.Array_Object)
                            .description(I18nUtil.systemMessage("Workflow.Output.QueryResult")).systemVariable(true).require(true).build());
                    tableNodeConfigDto.getOutputArgs().add(Arg.builder().key("rowNum").name("rowNum").dataType(DataTypeEnum.Number).description(I18nUtil.systemMessage("Workflow.Output.TotalCount"))
                            .systemVariable(true).require(true).build());
                }
            }
        }
    }

    //补全变量聚合出参
    private void completeVariableAggregateOutputArgConfig(List<WorkflowNodeDto> workflowNodeDtos) {
        //过滤得到聚合节点
        List<WorkflowNodeDto> variableAggregateNodes = workflowNodeDtos.stream().filter(workflowNodeDto -> workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.VariableAggregation).toList();
        if (variableAggregateNodes.isEmpty()) {
            return;
        }
        //转map
        Map<Long, WorkflowNodeDto> workflowNodeDtoMap = workflowNodeDtos.stream().collect(Collectors.toMap(WorkflowNodeDto::getId, workflowNodeDto -> workflowNodeDto, (old, newNode) -> newNode));
        variableAggregateNodes.forEach(variableAggregateNode -> {
            NodeConfigDto nodeConfig = variableAggregateNode.getNodeConfig();
            List<Arg> output = new ArrayList<>();
            if (nodeConfig != null && nodeConfig.getInputArgs() != null) {
                nodeConfig.setOutputArgs(output);
                // 第一层为分组
                nodeConfig.getInputArgs().forEach(arg -> {
                    if (!CollectionUtils.isEmpty(arg.getSubArgs())) {
                        // 只需要关注第一个参数即可
                        Arg arg0 = arg.getSubArgs().get(0);
                        Arg outputArg = new Arg();
                        BeanUtils.copyProperties(arg0, outputArg);
                        if (arg0.getBindValueType() == Arg.BindValueType.Reference) {
                            String[] keys = arg0.getBindValue().split("\\.");
                            if (keys.length < 2) {
                                return;
                            }
                            WorkflowNodeDto workflowNodeDto;
                            try {
                                workflowNodeDto = workflowNodeDtoMap.get(Long.parseLong(keys[0]));
                                if (workflowNodeDto == null) {
                                    return;
                                }
                            } catch (NumberFormatException e) {
                                // 忽略
                                return;
                            }
                            Map<String, Arg> argMap = new HashMap<>();
                            generateKey(workflowNodeDto.getId().toString(), workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Start ? workflowNodeDto.getNodeConfig().getInputArgs() : workflowNodeDto.getNodeConfig().getOutputArgs(), argMap);
                            // 引用参数
                            Arg arg1 = argMap.get(arg0.getBindValue());
                            if (arg1 != null) {
                                BeanUtils.copyProperties(arg1, outputArg);
                            }
                        }

                        outputArg.setName(arg.getName());
                        output.add(outputArg);
                    }
                });
            }
        });
    }

    private <T> void updateInputArgs(String pKey, List<T> args, Map<String, Arg> argMap) {
        if (args != null) {
            args.forEach(arg -> {
                if (arg instanceof Arg) {
                    Arg arg1 = (Arg) arg;
                    arg1.setKey(pKey + "." + arg1.getName());
                    List<Arg> subArgs = arg1.getSubArgs();
                    Arg configArg = argMap.get(arg1.getKey());
                    if (configArg != null && arg1.getEnable() != null && arg1.getEnable()) {
                        configArg.setInputType(arg1.getInputType());
                        configArg.setDataType(arg1.getDataType());
                        configArg.setDescription(arg1.getDescription());
                        configArg.setRequire(arg1.isRequire());
                        BeanUtils.copyProperties(configArg, arg1);
                        arg1.setSubArgs(subArgs);
                    }
                    updateInputArgs(arg1.getKey(), subArgs, argMap);
                }
            });
        }
    }

    //生成参数key
    private <T> void generateKey(String pKey, List<T> args, Map<String, Arg> argMap) {
        if (args != null) {
            args.forEach(arg -> {
                if (arg instanceof Arg) {
                    Arg arg1 = (Arg) arg;
                    arg1.setKey(pKey + "." + (StringUtils.isNotBlank(arg1.getName()) ? arg1.getName() : UUID.randomUUID().toString()));
                    if (argMap != null) {
                        argMap.put(arg1.getKey(), arg1);
                    }
                    generateKey(arg1.getKey(), arg1.getSubArgs(), argMap);
                }
            });
        }
    }

    @Override
    public Flux<WorkflowExecutingDto> executeWorkflow(WorkflowExecuteRequestDto workflowExecuteRequestDto, WorkflowConfigDto workflowConfigDto) {
        UserDto userDto = ((UserDto) RequestContext.get().getUser());
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        AtomicReference<Disposable> disposableAtomicReference = new AtomicReference<>();
        Flux<WorkflowExecutingDto> flux = Flux.create(emitter -> {
            String requestId = "wf:" + workflowExecuteRequestDto.getRequestId();
            workflowExecuteRequestDto.setRequestId(requestId);
            WorkflowContext workflowContext1 = new WorkflowContext();
            QaDto qaDto = qaNodeHandler.getConversationQaInfo(redisUtil, requestId);
            if (qaDto != null) {
                if (StringUtils.isNotBlank(qaDto.getAnswer())) {
                    qaDto.setAnswer(qaDto.getAnswer() + "\n" + workflowExecuteRequestDto.getAnswer());
                } else {
                    qaDto.setAnswer(workflowExecuteRequestDto.getAnswer());
                }
                requestId = qaDto.getRequestId();
                qaNodeHandler.addOrUpdateConversationQaInfo(redisUtil, requestId, qaDto);
                workflowContext1.setUseResultCache(true);
            }

            //补充上传文件的AK
            if (workflowExecuteRequestDto.getParams() != null) {
                workflowExecuteRequestDto.getParams().forEach((key, value) -> {
                    if (value instanceof String fileUrl) {
                        if (fileUrl.startsWith("http")) {
                            fileUrl = iFileAccessService.getFileUrlWithAk(tenantConfigDto.getSiteUrl(), fileUrl);
                            workflowExecuteRequestDto.getParams().put(key, fileUrl);
                        }
                    }
                });
            }

            AgentConfigDto agentConfigDto = new AgentConfigDto();
            agentConfigDto.setId(workflowExecuteRequestDto.getAgentId() == null ? -1L : workflowExecuteRequestDto.getAgentId());
            agentConfigDto.setName("");
            AgentContext agentContext = new AgentContext();
            agentContext.setAgentConfig(agentConfigDto);
            agentContext.setUserId(RequestContext.get().getUserId());
            agentContext.setUid(((UserDto) RequestContext.get().getUser()).getUid());
            agentContext.setUser((UserDto) RequestContext.get().getUser());
            agentContext.setUserName(userDto.getNickName() != null ? userDto.getNickName() : userDto.getUserName());
            agentContext.setRequestId(requestId);
            agentContext.setConversationId(requestId);
            agentContext.setMessage(JSON.toJSONString(workflowExecuteRequestDto.getParams()));
            agentContext.getVariableParams().put(GlobalVariableEnum.AGENT_ID.name(), workflowExecuteRequestDto.getAgentId() == null ? -1L : workflowExecuteRequestDto.getAgentId());
            workflowContext1.setAgentContext(agentContext);
            workflowContext1.setRequestId(workflowExecuteRequestDto.getRequestId());
            workflowContext1.setWorkflowConfig(workflowConfigDto);
            workflowContext1.setParams(workflowExecuteRequestDto.getParams());
            workflowContext1.setFrom(workflowExecuteRequestDto.getFrom());
            workflowContext1.setTraceContext(TraceContext.builder()
                    .userId(RequestContext.get().getUserId())
                    .userName(userDto.getNickName() != null ? userDto.getNickName() : userDto.getUserName())
                    .nickName(userDto.getNickName())
                    .conversationId(RequestContext.get().getRequestId())
                    .tenantId(RequestContext.get().getTenantId())
                    .traceId(RequestContext.get().getRequestId())
                    .enableSubscription(tenantConfigDto.getEnableSubscription() != null && tenantConfigDto.getEnableSubscription() == 1)
                    .billUserId(userDto.getId())
                    .devTest(workflowExecuteRequestDto.isTest())
                    .apiKey(workflowExecuteRequestDto.getApiKey())
                    .traceTargets(Lists.newArrayList(TraceContext.TraceTarget.builder()
                            .targetType(TraceContext.TraceTargetType.Workflow)
                            .name(workflowConfigDto.getName())
                            .description(workflowConfigDto.getDescription())
                            .icon(workflowConfigDto.getIcon())
                            .targetId(workflowConfigDto.getId().toString())
                            .build()))
                    .build());
            if (workflowContext1.getTraceContext().isEnableSubscription()) {
                List<PriceEstimate.EstimateTarget> estimateTargets = new ArrayList<>();
                if (!workflowExecuteRequestDto.isTest()) {
                    estimateTargets.add(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.WORKFLOW).targetId(workflowConfigDto.getId().toString()).build());
                }
                resourcePricingRpcService.completeWorkflowEstimateTargets(estimateTargets, workflowConfigDto, new HashSet<>());
                PriceEstimate priceEstimate = resourcePricingRpcService.estimatePrice(tenantConfigDto.getTenantId(), workflowContext1.getTraceContext().getBillUserId(), estimateTargets);
                if (!priceEstimate.isPass()) {
                    WorkflowExecutingDto workflowExecutingDto = new WorkflowExecutingDto();
                    workflowExecutingDto.setSuccess(false);
                    workflowExecutingDto.setRequestId(workflowExecuteRequestDto.getRequestId());
                    workflowExecutingDto.setMessage(priceEstimate.getMessage());
                    workflowExecutingDto.setComplete(true);
                    emitter.next(workflowExecutingDto);
                    emitter.complete();
                    return;
                }
            }
            workflowContext1.setNodeExecutingConsumer(nodeExecutingDto -> {
                log.info("Node execution info: {}", nodeExecutingDto);
                WorkflowExecutingDto workflowExecutingDto = new WorkflowExecutingDto();
                workflowExecutingDto.setSuccess(nodeExecutingDto.getStatus() != NodeExecuteStatus.FAILED);
                workflowExecutingDto.setRequestId(workflowExecuteRequestDto.getRequestId());
                workflowExecutingDto.setData(nodeExecutingDto);
                workflowExecutingDto.setComplete(false);
                if (nodeExecutingDto.getResult() != null) {
                    workflowExecutingDto.setCostTime(nodeExecutingDto.getResult().getEndTime() - nodeExecutingDto.getResult().getStartTime());
                }
                emitter.next(workflowExecutingDto);
                if (nodeExecutingDto.getStatus() == NodeExecuteStatus.STOP_WAIT_ANSWER) {
                    emitter.complete();
                }
            });
            AtomicBoolean isComplete = new AtomicBoolean(false);
            nextHeartBeat(emitter, isComplete);
            Disposable disposable = workflowExecutor.execute(workflowContext1).doOnError(e -> {
                isComplete.set(true);
                log.warn("Workflow execution failed {}", workflowConfigDto.getName(), e);
                WorkflowExecutingDto workflowExecutingDto = new WorkflowExecutingDto();
                workflowExecutingDto.setSuccess(false);
                workflowExecutingDto.setRequestId(workflowExecuteRequestDto.getRequestId());
                workflowExecutingDto.setMessage(e.getMessage());
                workflowExecutingDto.setComplete(true);
                workflowExecutingDto.setNodeExecuteResultMap(workflowContext1.getNodeExecuteResultMap());
                emitter.next(workflowExecutingDto);
                emitter.complete();
            }).doOnCancel(() -> isComplete.set(true)).subscribe((result) -> {
                isComplete.set(true);
                log.info("Workflow succeeded {}", workflowConfigDto.getName());
                WorkflowExecutingDto workflowExecutingDto = new WorkflowExecutingDto();
                workflowExecutingDto.setData(result);
                workflowExecutingDto.setSuccess(true);
                workflowExecutingDto.setComplete(true);
                workflowExecutingDto.setRequestId(workflowExecuteRequestDto.getRequestId());
                workflowExecutingDto.setNodeExecuteResultMap(workflowContext1.getNodeExecuteResultMap());
                if (workflowContext1.getEndTime() == null) {
                    workflowContext1.setEndTime(System.currentTimeMillis());
                }
                workflowExecutingDto.setCostTime(workflowContext1.getEndTime() - workflowContext1.getStartTime());
                emitter.next(workflowExecutingDto);
                emitter.complete();
            });
            disposableAtomicReference.set(disposable);
        });

        return flux.doOnCancel(() -> {
            if (disposableAtomicReference.get() != null) {
                disposableAtomicReference.get().dispose();
            }
        });
    }

    private void nextHeartBeat(FluxSink<WorkflowExecutingDto> sink, AtomicBoolean isComplete) {
        timeWheel.schedule((res) -> {
            if (isComplete.get()) {
                return;
            }
            WorkflowExecutingDto workflowExecutingDto = WorkflowExecutingDto.builder()
                    .isComplete(false)
                    .data(Map.of())
                    .build();
            sink.next(workflowExecutingDto);
            nextHeartBeat(sink, isComplete);
        }, 10);
    }

    @Override
    public Long workflowEditVersion(Long workflowId, boolean inc) {
        if (inc) {
            return redisUtil.increment("workflow_version:" + workflowId, 1);
        }
        return redisUtil.increment("workflow_version:" + workflowId, 0);
    }
}
