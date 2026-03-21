package com.xspaceagi.agent.core.application.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.*;
import com.xspaceagi.agent.core.adapter.dto.config.bind.*;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.CodePluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.HttpPluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.CopyIndexRecordRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.*;
import com.xspaceagi.agent.core.domain.service.AgentDomainService;
import com.xspaceagi.agent.core.domain.service.ConfigHistoryDomainService;
import com.xspaceagi.agent.core.domain.service.PublishDomainService;
import com.xspaceagi.agent.core.domain.service.UserTargetRelationDomainService;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.plugin.PluginContext;
import com.xspaceagi.agent.core.infra.component.plugin.PluginExecutor;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowExecutor;
import com.xspaceagi.agent.core.infra.converter.ArgConverter;
import com.xspaceagi.agent.core.infra.rpc.CustomPageRpcService;
import com.xspaceagi.agent.core.infra.rpc.DbTableRpcService;
import com.xspaceagi.agent.core.infra.rpc.KnowledgeRpcService;
import com.xspaceagi.agent.core.infra.rpc.McpRpcService;
import com.xspaceagi.agent.core.infra.rpc.dto.PageDto;
import com.xspaceagi.agent.core.spec.enums.*;
import com.xspaceagi.agent.core.spec.utils.PlaceholderParser;
import com.xspaceagi.compose.sdk.request.DorisTableDefineRequest;
import com.xspaceagi.compose.sdk.service.IComposeDbTableRpcService;
import com.xspaceagi.compose.sdk.vo.define.TableDefineVo;
import com.xspaceagi.knowledge.sdk.request.KnowledgeCreateRequestVo;
import com.xspaceagi.knowledge.sdk.response.KnowledgeConfigVo;
import com.xspaceagi.mcp.sdk.dto.McpComponentDto;
import com.xspaceagi.mcp.sdk.dto.McpConfigDto;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.dto.McpToolDto;
import com.xspaceagi.mcp.sdk.enums.InstallTypeEnum;
import com.xspaceagi.mcp.sdk.enums.McpComponentTypeEnum;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.dto.permission.BindRestrictionTargetsDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.SysSubjectPermissionApplicationService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.sdk.server.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.PermissionSubjectTypeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AgentApplicationServiceImpl implements AgentApplicationService {

    private static final Long DEFAULT_MODEL_ID = 1L;
    private static final Integer MAX_QUERY_SIZE = 1000;
    @Resource
    private AgentDomainService agentDomainService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private ConfigHistoryDomainService configHistoryDomainService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private PublishDomainService publishDomainService;

    @Resource
    private UserTargetRelationDomainService userTargetRelationDomainService;

    @Resource
    private KnowledgeRpcService KnowledgeRpcService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private IComposeDbTableRpcService iComposeDbTableRpcService;

    @Resource
    private DbTableRpcService dbTableRpcService;

    @Resource
    private McpRpcService mcpRpcService;

    @Resource
    private CustomPageRpcService customPageRpcService;

    @Resource
    private CopyIndexRecordRepository copyIndexRecordRepository;

    @Resource
    private SysSubjectPermissionApplicationService sysSubjectPermissionApplicationService;

    @Resource
    private SkillApplicationService skillApplicationService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Resource
    private WorkflowExecutor workflowExecutor;

    @Resource
    private PluginExecutor pluginExecutor;
    @Autowired
    private ConversationApplicationServiceImpl conversationApplicationService;

    @Override
    @DSTransactional
    public Long add(AgentConfigDto agent) {
        AgentConfig agentConfig = new AgentConfig();
        BeanUtils.copyProperties(agent, agentConfig);
        agentConfig.setUid(UUID.randomUUID().toString().replace("-", ""));
        agentConfig.setOpeningGuidQuestion("[]");
        agentConfig.setOpenLongMemory(agent.getOpenLongMemory() != null ? agent.getOpenLongMemory() : AgentConfig.OpenStatus.Close);
        agentConfig.setOpenSuggest(AgentConfig.OpenStatus.Close);
        agentConfig.setExtra(agent.getExtra() != null ? JSON.toJSONString(agent.getExtra()) : null);
        agentConfig.setHideDesktop(agent.getHideDesktop() == null ? YesOrNoEnum.N.getKey() : agent.getHideDesktop());
        agentDomainService.add(agentConfig);
        agent.setId(agentConfig.getId());

        // 设置默认模型组件
        ModelConfigDto modelConfigDto = null;
        if ("TaskAgent".equals(agent.getType())) {
            TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
            if (tenantConfig.getDefaultCodingModelId() == null || tenantConfig.getDefaultCodingModelId() < 1) {
                ModelQueryDto modelQueryDto = new ModelQueryDto();
                modelQueryDto.setModelType(ModelTypeEnum.Chat);
                modelQueryDto.setApiProtocol(ModelApiProtocolEnum.Anthropic);
                modelQueryDto.setSpaceId(agent.getSpaceId());
                modelQueryDto.setEnabled(1);
                List<ModelConfigDto> modelConfigDtos = modelApplicationService.queryModelConfigList(modelQueryDto);
                if (!modelConfigDtos.isEmpty()) {
                    modelConfigDto = modelConfigDtos.get(0);
                }
            } else {
                modelConfigDto = modelApplicationService.queryModelConfigById(tenantConfig.getDefaultCodingModelId());
            }
        } else {
            modelConfigDto = modelApplicationService.queryDefaultModelConfig();
        }
        Assert.notNull(modelConfigDto, "请在系统模型配置中设置默认模型");
        ModelBindConfigDto modelBindConfigDto = ModelBindConfigDto.builder()
                .mode(ModelBindConfigDto.Mode.Balanced)
                .contextRounds(3)
                .temperature(1.0)
                .topP(0.7)
                .maxTokens(modelConfigDto.getMaxTokens())
                .build();
        AgentComponentConfig agentComponentConfig = AgentComponentConfig.builder().agentId(agentConfig.getId())
                .type(AgentComponentConfig.Type.Model).targetId(modelConfigDto.getId())
                .name(modelConfigDto.getName()).description(modelConfigDto.getModel())
                .bindConfig(modelBindConfigDto).build();
        agentDomainService.addAgentComponentConfig(agentComponentConfig);

        // 添加智能体新增历史记录
        addConfigHistory(agentConfig.getId(), ConfigHistory.Type.Add, "智能体创建");

        return agentConfig.getId();
    }

    private void addConfigHistory(Long agentId, ConfigHistory.Type type, String description) {
        AgentConfigDto agentConfigDto = queryById(agentId);
        ConfigHistory configHistory = ConfigHistory.builder()
                .config(JSON.toJSONString(agentConfigDto))
                .targetId(agentId)
                .description(description)
                .targetType(Published.TargetType.Agent)
                .opUserId(RequestContext.get().getUserId())
                .type(type)
                .build();
        configHistoryDomainService.addConfigHistory(configHistory);
        // 添加或更新用户最近编辑
        if (type == ConfigHistory.Type.Add || type == ConfigHistory.Type.Edit
                || type == ConfigHistory.Type.AddComponent || type == ConfigHistory.Type.EditComponent
                || type == ConfigHistory.Type.DeleteComponent) {
            UserTargetRelation userTargetRelation = UserTargetRelation.builder()
                    .userId(RequestContext.get().getUserId())
                    .targetType(Published.TargetType.Agent)
                    .targetId(agentId)
                    .type(UserTargetRelation.OpType.Edit)
                    .build();
            userTargetRelationDomainService.addOrUpdateRecentEdit(userTargetRelation);
        }
    }

    @Override
    public void update(AgentConfigDto agentConfigDto) {
        AgentConfig agentConfig = new AgentConfig();
        BeanUtils.copyProperties(agentConfigDto, agentConfig);
        if (agentConfigDto.getOpeningGuidQuestions() != null) {
            agentConfig.setOpeningGuidQuestion(JSON.toJSONString(agentConfigDto.getOpeningGuidQuestions()));
        }
        if (agentConfigDto.getExtra() != null) {
            agentConfig.setExtra(JSON.toJSONString(agentConfigDto.getExtra()));
        }
        agentDomainService.update(agentConfig);
        if (agentConfigDto.getSpaceId() != null && agentConfigDto.getSpaceId() != -1) {
            addConfigHistory(agentConfig.getId(), ConfigHistory.Type.Edit, "编辑基础配置");
        }
    }

    @Override
    @DSTransactional
    public void delete(Long agentId) {
        // 删除智能体，含配置以及发布信息、统计信息、用户收藏点赞信息
        // 删除智能体配置
        agentDomainService.delete(agentId);
        publishApplicationService.deletePublishedApply(Published.TargetType.Agent, agentId);
    }

    @Override
    public Long copyAgent(Long userId, Long agentId) {
        // 拷贝agent配置
        Long id = agentDomainService.copyAgent(userId, agentId);
        addConfigHistory(id, ConfigHistory.Type.Add, "智能体复制创建");
        return id;
    }

    @Override
    public Long copyAgent(Long userId, AgentConfigDto agentConfigDto, Long targetSpaceId) {
        return copyAgent(userId, agentConfigDto, targetSpaceId, false);
    }

    private Long copyAgent(Long userId, AgentConfigDto agentConfigDto, Long targetSpaceId, boolean importAgent) {
        Assert.notNull(userId, "userId must be non-null");
        Assert.notNull(agentConfigDto, "agentConfigDto must be non-null");
        Assert.notNull(targetSpaceId, "targetSpaceId must be non-null");
        AgentConfig newAgentConfig = new AgentConfig();
        BeanUtils.copyProperties(agentConfigDto, newAgentConfig);
        newAgentConfig.setId(null);
        newAgentConfig.setCreated(null);
        newAgentConfig.setModified(null);
        newAgentConfig.setDevConversationId(null);
        newAgentConfig.setCreatorId(userId);
        newAgentConfig.setUid(UUID.randomUUID().toString().replace("-", ""));
        newAgentConfig.setSpaceId(targetSpaceId);
        if (agentConfigDto.getOpeningGuidQuestions() != null) {
            newAgentConfig.setOpeningGuidQuestion(JSON.toJSONString(agentConfigDto.getOpeningGuidQuestions()));
        }
        // 目标空间ID和当前空间ID相同，则名称不能重复
        if (!importAgent && !agentConfigDto.getSpaceId().equals(-1L) && agentConfigDto.getSpaceId().equals(targetSpaceId) && newAgentConfig.getName() != null && !newAgentConfig.getName().contains("（副本）")) {
            newAgentConfig.setName(newAgentConfig.getName() + "（副本）");
        } else {
            if (agentConfigDto.getSpaceId().equals(-1L) || importAgent) {
                newAgentConfig.setName(newAgentConfig.getName());
            } else {
                newAgentConfig.setName(copyIndexRecordRepository.newCopyName("agent", agentConfigDto.getSpaceId(), newAgentConfig.getName()));
            }
        }
        newAgentConfig.setPublishStatus(Published.PublishStatus.Developing);
        agentDomainService.add(newAgentConfig);

        //  复制组件
        agentConfigDto.getAgentComponentConfigList().forEach(agentComponentConfig -> {
            AgentComponentConfig newAgentComponentConfig = new AgentComponentConfig();
            BeanUtils.copyProperties(agentComponentConfig, newAgentComponentConfig);
            newAgentComponentConfig.setId(null);
            newAgentComponentConfig.setAgentId(newAgentConfig.getId());
            //相同空间下直接复制，targetSpaceId为-1时，为生态市场启用
            if (agentConfigDto.getSpaceId().equals(targetSpaceId) || importAgent) {
                agentDomainService.addAgentComponentConfig(newAgentComponentConfig);
                return;
            }
            //非相同空间下
            //大模型：判断是否为全局模型，若不是全局则修改为全局通用模型
            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Model) {
                if (targetSpaceId == -1L) {
                    ModelConfigDto modelConfigDto1 = modelApplicationService.queryDefaultModelConfig();
                    if (modelConfigDto1 != null) {
                        newAgentComponentConfig.setTargetId(modelConfigDto1.getId());
                        ModelBindConfigDto modelBindConfigDto = (ModelBindConfigDto) agentComponentConfig.getBindConfig();
                        if (modelBindConfigDto != null) {
                            modelBindConfigDto.setReasoningModelId(null);
                        }
                    }
                } else {
                    ModelConfigDto modelConfigDto = modelApplicationService.queryModelConfigById(agentComponentConfig.getTargetId());
                    newAgentComponentConfig.setTargetId(null);
                    if (modelConfigDto != null) {
                        if (modelConfigDto.getScope() == ModelConfig.ModelScopeEnum.Space
                                && (modelConfigDto.getSpaceId() == null || !modelConfigDto.getSpaceId().equals(targetSpaceId))) {
                            ModelConfigDto modelConfigDto1 = modelApplicationService.queryDefaultModelConfig();
                            if (modelConfigDto1 != null) {
                                newAgentComponentConfig.setTargetId(modelConfigDto1.getId());
                            } else {
                                newAgentComponentConfig.setTargetId(-1L);
                            }
                        } else {
                            newAgentComponentConfig.setTargetId(modelConfigDto.getId());
                        }
                    } else {
                        ModelConfigDto modelConfigDto1 = modelApplicationService.queryDefaultModelConfig();
                        if (modelConfigDto1 != null) {
                            newAgentComponentConfig.setTargetId(modelConfigDto1.getId());
                        } else {
                            newAgentComponentConfig.setTargetId(-1L);
                        }
                    }
                    ModelBindConfigDto modelBindConfigDto = (ModelBindConfigDto) agentComponentConfig.getBindConfig();
                    if (modelBindConfigDto.getReasoningModelId() != null) {
                        modelConfigDto = modelApplicationService.queryModelConfigById(modelBindConfigDto.getReasoningModelId());
                        if (modelConfigDto == null || (modelConfigDto.getScope() == ModelConfig.ModelScopeEnum.Space
                                && !modelConfigDto.getSpaceId().equals(targetSpaceId))) {
                            modelBindConfigDto.setReasoningModelId(null);
                        }
                    }
                }
            }
            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Plugin) {
                Long newTargetId = publishApplicationService.copyPublish(userId, Published.TargetType.Plugin, agentComponentConfig.getTargetId(), agentConfigDto.getSpaceId(), targetSpaceId);
                newAgentComponentConfig.setTargetId(newTargetId);
            }

            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Skill) {
                Long newTargetId = publishApplicationService.copyPublish(userId, Published.TargetType.Skill, agentComponentConfig.getTargetId(), agentConfigDto.getSpaceId(), targetSpaceId);
                newAgentComponentConfig.setTargetId(newTargetId);
            }

            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Workflow) {
                Long newTargetId = publishApplicationService.copyPublish(userId, Published.TargetType.Workflow, agentComponentConfig.getTargetId(), agentConfigDto.getSpaceId(), targetSpaceId);
                newAgentComponentConfig.setTargetId(newTargetId);
            }
            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Agent) {
                Long newTargetId = publishApplicationService.copyPublish(userId, Published.TargetType.Agent, agentComponentConfig.getTargetId(), agentConfigDto.getSpaceId(), targetSpaceId);
                newAgentComponentConfig.setTargetId(newTargetId);
            }
            //知识库在同个空间下复用
            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Knowledge && !agentConfigDto.getSpaceId().equals(targetSpaceId)) {
                KnowledgeCreateRequestVo knowledgeCreateRequestVo = KnowledgeCreateRequestVo.builder()
                        .dataType(1)
                        .name(agentComponentConfig.getName())
                        .description(agentComponentConfig.getDescription())
                        .icon(agentComponentConfig.getIcon())
                        .userId(userId)
                        .spaceId(targetSpaceId)
                        .build();
                Long knowledgeConfigId = KnowledgeRpcService.createKnowledgeConfig(knowledgeCreateRequestVo, agentComponentConfig.getTargetId());
                newAgentComponentConfig.setTargetId(knowledgeConfigId);
            }
            //如果是复制到外部空间，则复制数据表结构；
            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Table) {
                Long newTableId = dbTableRpcService.createNewTableDefinition(userId, targetSpaceId, agentComponentConfig.getTargetId());
                newAgentComponentConfig.setTargetId(newTableId);
            }
            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Mcp) {
                McpDto mcpDto = mcpRpcService.queryMcp(agentComponentConfig.getTargetId(), agentConfigDto.getSpaceId());
                if (mcpDto == null) {
                    return;
                }
                Long newMcpId = mcpRpcService.addAndDeployMcp(userId, targetSpaceId, mcpDto);
                newAgentComponentConfig.setTargetId(newMcpId);
            }

            agentDomainService.addAgentComponentConfig(newAgentComponentConfig);
        });
        return newAgentConfig.getId();
    }

    @Override
    public Long importAgent(Long userId, AgentConfigDto agentConfigDto, Long targetSpaceId) {
        return copyAgent(userId, agentConfigDto, targetSpaceId, true);
    }

    @Override
    @DSTransactional
    public void transfer(Long userId, Long agentId, Long targetSpaceId) {
        AgentConfigDto agentConfigDto = queryById(agentId);
        Assert.notNull(agentConfigDto, "agentId错误");
        if (agentConfigDto.getSpaceId().equals(targetSpaceId)) {
            return;
        }
        Long tempAgentId = copyAgent(userId, agentConfigDto, targetSpaceId);
        agentDomainService.transferUpdate(agentId, tempAgentId);
        addConfigHistory(agentId, ConfigHistory.Type.Edit, "智能体空间转移");
    }

    @Override
    public void deleteBySpaceId(Long spaceId) {
        agentDomainService.deleteBySpaceId(spaceId);
    }

    @Override
    public List<AgentConfigDto> queryListBySpaceId(Long spaceId) {
        List<AgentConfig> agentConfigs = agentDomainService.queryListBySpaceId(spaceId);
        List<Long> agentIds = agentConfigs.stream().map(AgentConfig::getId).collect(Collectors.toList());
        Map<Long, StatisticsDto> agentStatisticsMap = getAgentStatisticsMapByAgentIds(agentIds);
        Map<Long, UserTargetRelation> userTargetRelationMap = getUserTargetRelationMap(agentIds);
        List<AgentConfigDto> agentConfigDtos = agentConfigs.stream().map(agentConfig -> {
            AgentConfigDto agentConfigDto = convertToDto(agentConfig);
            agentConfigDto.setAgentStatistics(agentStatisticsMap.get(agentConfig.getId()));
            // 当前登录用户是否开发收藏
            if (userTargetRelationMap.get(agentConfig.getId()) != null) {
                agentConfigDto.setDevCollected(true);
            }
            agentConfigDto.setSystemPrompt(null);
            agentConfigDto.setUserPrompt(null);
            agentConfigDto.setOpeningChatMsg(null);
            agentConfigDto.setOpeningGuidQuestions(null);
            agentConfigDto.setSuggestPrompt(null);
            return agentConfigDto;
        }).collect(Collectors.toList());
        completeCreatorAndSpaceInfo(agentConfigDtos);
        return agentConfigDtos;
    }

    private void completeCreatorAndSpaceInfo(List<AgentConfigDto> agentConfigDtos) {
        // agentConfigDtos转userMap
        List<UserDto> userDtos = userApplicationService.queryUserListByIds(
                agentConfigDtos.stream().map(AgentConfigDto::getCreatorId).collect(Collectors.toList()));
        Map<Long, UserDto> userMap = userDtos.stream().collect(Collectors.toMap(UserDto::getId, userDto -> userDto));
        Map<Long, SpaceDto> spaceMap = spaceApplicationService.queryByIds(
                        agentConfigDtos.stream().map(AgentConfigDto::getSpaceId).collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(SpaceDto::getId, spaceDto -> spaceDto));
        agentConfigDtos.forEach(agentConfigDto -> {
            // UserDto转CreatorDto
            UserDto userDto = userMap.get(agentConfigDto.getCreatorId());
            if (userDto != null) {
                CreatorDto creatorDto = CreatorDto.builder()
                        .userId(userDto.getId())
                        .userName(userDto.getUserName())
                        .nickName(userDto.getNickName())
                        .avatar(userDto.getAvatar())
                        .build();
                agentConfigDto.setCreator(creatorDto);
            }
            SpaceDto spaceDto = spaceMap.get(agentConfigDto.getSpaceId());
            if (spaceDto != null) {
                agentConfigDto.setSpace(spaceDto);
            }
        });
    }

    @Override
    public List<AgentConfigDto> queryListByIds(List<Long> agentIds) {
        Map<Long, UserTargetRelation> userTargetRelationMap = getUserTargetRelationMap(agentIds);
        return agentDomainService.queryListByIds(agentIds).stream().map(agentConfig -> {
            AgentConfigDto agentConfigDto = convertToDto(agentConfig);
            if (userTargetRelationMap.get(agentConfig.getId()) != null) {
                agentConfigDto.setDevCollected(true);
            }
            return agentConfigDto;
        }).collect(Collectors.toList());
    }

    private AgentConfigDto convertToDto(AgentConfig agentConfig) {
        AgentConfigDto agentConfigDto = AgentConfigDto.builder().build();
        BeanUtils.copyProperties(agentConfig, agentConfigDto);
        if (agentConfig.getOpeningGuidQuestion() != null) {
            agentConfigDto.setOpeningGuidQuestions(JSON.parseArray(agentConfig.getOpeningGuidQuestion(), String.class));
        }
        if (agentConfig.getExtra() != null && JSON.isValidObject(agentConfig.getExtra())) {
            agentConfigDto.setExtra(JSON.parseObject(agentConfig.getExtra(), Map.class));
        }
        if (agentConfig.getExtra() == null) {
            agentConfigDto.setExtra(new HashMap<>());
        }
        return agentConfigDto;
    }

    private Map<Long, UserTargetRelation> getUserTargetRelationMap(List<Long> agentIds) {
        Map<Long, UserTargetRelation> userTargetRelationMap;
        if (RequestContext.get() != null && RequestContext.get().getUserId() != null) {
            List<UserTargetRelation> userTargetRelationList = userTargetRelationDomainService
                    .queryUserTargetRelationByTargetIds(RequestContext.get().getUserId(),
                            Published.TargetType.Agent, UserTargetRelation.OpType.DevCollect, agentIds);
            userTargetRelationMap = userTargetRelationList.stream().collect(
                    Collectors.toMap(UserTargetRelation::getTargetId, userTargetRelation -> userTargetRelation));
        } else {
            userTargetRelationMap = Map.of();
        }
        return userTargetRelationMap;
    }

    @Override
    public AgentConfigDto queryById(Long agentId) {
        return queryById(agentId, false);
    }

    @Override
    public AgentConfigDto queryAgentByIdWithStatics(Long agentId) {
        AgentConfigDto agentConfigDto = queryById(agentId, false);
        if (agentConfigDto != null) {
            Map<Long, StatisticsDto> agentStatisticsMapByAgentIds = getAgentStatisticsMapByAgentIds(List.of(agentId));
            agentConfigDto.setAgentStatistics(agentStatisticsMapByAgentIds.get(agentId));
        }
        return agentConfigDto;
    }

    private AgentConfigDto queryById(Long agentId, boolean forExecute) {
        AgentConfig agentConfig = agentDomainService.queryById(agentId);
        if (agentConfig != null) {
            AgentConfigDto agentConfigDto = convertToDto(agentConfig);
            agentConfigDto.setAgentComponentConfigList(queryComponentConfigList(agentId, forExecute));
            // 从agentConfigDto.getAgentComponentConfigList()中获取类型为Model的组件列表
            List<AgentComponentConfigDto> modelComponentConfigList = agentConfigDto.getAgentComponentConfigList()
                    .stream()
                    .filter(agentComponentConfigDto -> agentComponentConfigDto.getType() == AgentComponentConfig.Type.Model)
                    .toList();
            if (!modelComponentConfigList.isEmpty()) {
                agentConfigDto.setModelComponentConfig(modelComponentConfigList.get(0));
            }
            completeCreatorAndSpaceInfo(List.of(agentConfigDto));
            PublishedDto published = publishApplicationService.queryPublished(Published.TargetType.Agent, agentId);
            if (published != null) {
                agentConfigDto.setPublishDate(published.getModified());
                agentConfigDto.setCategory(published.getCategory());
            }
            return agentConfigDto;
        }

        return null;
    }

    @Override
    public void addComponentConfig(AgentComponentConfigDto agentComponentConfigDto) {
        AgentComponentConfig agentComponentConfig = new AgentComponentConfig();
        BeanUtils.copyProperties(agentComponentConfigDto, agentComponentConfig);
        //补充数据
        if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Model) {
            throw new BizException("模型组件配置不能手动添加");
        }
        if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Plugin) {
            PublishedDto published = publishApplicationService.queryPublished(Published.TargetType.Plugin, agentComponentConfigDto.getTargetId());
            if (published == null) {
                throw new BizException("插件不存在或未发布");
            }
            agentComponentConfig.setName(published.getName());
            agentComponentConfig.setDescription(published.getDescription());
            agentComponentConfig.setIcon(published.getIcon());
            PluginBindConfigDto pluginBindConfigDto = new PluginBindConfigDto();
            pluginBindConfigDto.setInputArgBindConfigs(new ArrayList<>());
            pluginBindConfigDto.setOutputArgBindConfigs(new ArrayList<>());
            pluginBindConfigDto.setInvokeType(PluginBindConfigDto.PluginInvokeTypeEnum.ON_DEMAND);
            pluginBindConfigDto.setDefaultSelected(0);
            agentComponentConfig.setBindConfig(pluginBindConfigDto);
        }
        if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Workflow) {
            PublishedDto published = publishApplicationService.queryPublished(Published.TargetType.Workflow, agentComponentConfigDto.getTargetId());
            if (published == null) {
                throw new BizException("工作流不存在或未发布");
            }
            agentComponentConfig.setName(published.getName());
            agentComponentConfig.setDescription(published.getDescription());
            agentComponentConfig.setIcon(published.getIcon());
            WorkflowBindConfigDto workflowBindConfigDto = new WorkflowBindConfigDto();
            workflowBindConfigDto.setArgBindConfigs(new ArrayList<>());
            workflowBindConfigDto.setOutputArgBindConfigs(new ArrayList<>());
            workflowBindConfigDto.setInvokeType(WorkflowBindConfigDto.WorkflowInvokeTypeEnum.ON_DEMAND);
            workflowBindConfigDto.setDefaultSelected(0);
            agentComponentConfig.setBindConfig(workflowBindConfigDto);
        }
        if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Knowledge) {
            KnowledgeConfigVo knowledgeConfigModel = KnowledgeRpcService.queryKnowledgeConfigById(agentComponentConfigDto.getTargetId());
            if (knowledgeConfigModel == null) {
                throw new BizException("知识库不存在");
            }
            agentComponentConfig.setName(knowledgeConfigModel.getName());
            agentComponentConfig.setDescription(knowledgeConfigModel.getDescription());
            KnowledgeBaseBindConfigDto knowledgeBaseBindConfigDto = new KnowledgeBaseBindConfigDto();
            knowledgeBaseBindConfigDto.setInvokeType(KnowledgeBaseBindConfigDto.InvokeTypeEnum.ON_DEMAND);
            knowledgeBaseBindConfigDto.setDefaultSelected(0);
            knowledgeBaseBindConfigDto.setMatchingDegree(0.5);
            knowledgeBaseBindConfigDto.setMaxRecallCount(5);
            knowledgeBaseBindConfigDto.setNoneRecallReplyType(KnowledgeBaseBindConfigDto.NoneRecallReplyTypeEnum.DEFAULT);
            knowledgeBaseBindConfigDto.setSearchStrategy(SearchStrategyEnum.MIXED);
            agentComponentConfig.setBindConfig(knowledgeBaseBindConfigDto);
        }
        if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Table) {
            // 数据表组件配置
            DorisTableDefineRequest request = new DorisTableDefineRequest();
            request.setTableId(agentComponentConfigDto.getTargetId());
            TableDefineVo dorisTableDefinitionVo = iComposeDbTableRpcService.queryTableDefinition(request);
            agentComponentConfig.setName(dorisTableDefinitionVo.getTableName());
            agentComponentConfig.setDescription(dorisTableDefinitionVo.getTableDescription());
            agentComponentConfig.setIcon(dorisTableDefinitionVo.getIcon());
            TableBindConfigDto tableBindConfigDto = new TableBindConfigDto();
            tableBindConfigDto.setInputArgBindConfigs(new ArrayList<>());
            tableBindConfigDto.setOutputArgBindConfigs(new ArrayList<>());
            agentComponentConfig.setBindConfig(tableBindConfigDto);
        }
        if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Mcp) {
            McpBindConfigDto mcpBindConfigDto = (McpBindConfigDto) agentComponentConfig.getBindConfig();
            if (mcpBindConfigDto == null) {
                throw new BizException("MCP缺少必要配置参数");
            }
            mcpBindConfigDto.setInputArgBindConfigs(new ArrayList<>());
            mcpBindConfigDto.setOutputArgBindConfigs(new ArrayList<>());
            mcpBindConfigDto.setInvokeType(McpBindConfigDto.McpInvokeTypeEnum.ON_DEMAND);
            mcpBindConfigDto.setDefaultSelected(0);
        }

        if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Page) {
            PageBindConfigDto pageBindConfigDto = new PageBindConfigDto();
            pageBindConfigDto.setHomeIndex(0);
            pageBindConfigDto.setVisibleToLLM(1);
            agentComponentConfig.setBindConfig(pageBindConfigDto);
        }

        // 初始化Skill
        if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Skill) {
            PublishedDto published = publishApplicationService.queryPublished(Published.TargetType.Skill, agentComponentConfigDto.getTargetId());
            if (published == null) {
                throw new BizException("技能不存在或未发布");
            }
            agentComponentConfig.setName(published.getName());
            agentComponentConfig.setDescription(published.getDescription());
            agentComponentConfig.setIcon(published.getIcon());
            SkillBindConfigDto skillBindConfigDto = new SkillBindConfigDto();
            skillBindConfigDto.setInvokeType(SkillBindConfigDto.SkillInvokeTypeEnum.ON_DEMAND);
            skillBindConfigDto.setDefaultSelected(0);
            agentComponentConfig.setBindConfig(skillBindConfigDto);
        }

        agentDomainService.addAgentComponentConfig(agentComponentConfig);
        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setId(agentComponentConfigDto.getAgentId());
        agentConfig.setModified(new Date());
        agentDomainService.update(agentConfig);
        if (StringUtils.isNotBlank(agentComponentConfig.getName())) {
            addConfigHistory(agentComponentConfigDto.getAgentId(), ConfigHistory.Type.AddComponent,
                    "添加配置-" + agentComponentConfig.getName());
        }
        agentComponentConfigDto.setId(agentComponentConfig.getId());
    }

    @Override
    public void updateComponentConfig(AgentComponentConfigDto agentComponentConfigDto) {
        AgentComponentConfig agentComponentConfig = new AgentComponentConfig();
        BeanUtils.copyProperties(agentComponentConfigDto, agentComponentConfig);
        agentDomainService.updateAgentComponentConfig(agentComponentConfig);
        agentComponentConfig = agentDomainService.queryComponentConfig(agentComponentConfigDto.getId());
        if (agentComponentConfig == null) {
            return;
        }
        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setId(agentComponentConfig.getAgentId());
        agentConfig.setModified(new Date());
        agentDomainService.update(agentConfig);
        addConfigHistory(agentConfig.getId(), ConfigHistory.Type.EditComponent,
                "编辑配置-" + agentComponentConfig.getName());
    }

    @Override
    public void deleteComponentConfig(Long id) {
        AgentComponentConfig agentComponentConfig = agentDomainService.queryComponentConfig(id);
        if (agentComponentConfig == null) {
            throw new BizException("配置不存在");
        }
        if (agentComponentConfig.getType() == AgentComponentConfig.Type.Model) {
            throw new BizException("模型组件配置不能删除");
        }
        agentDomainService.deleteAgentComponentConfigById(id);
        AgentConfig agentConfig = new AgentConfig();
        agentConfig.setId(agentComponentConfig.getAgentId());
        agentConfig.setModified(new Date());
        agentDomainService.update(agentConfig);
        addConfigHistory(agentComponentConfig.getAgentId(), ConfigHistory.Type.DeleteComponent,
                "删除配置-" + agentComponentConfig.getName());
    }

    @Override
    public AgentComponentConfigDto queryComponentConfig(Long id) {
        AgentComponentConfig agentComponentConfig = agentDomainService.queryComponentConfig(id);
        if (agentComponentConfig == null) {
            return null;
        }
        AgentComponentConfigDto agentComponentConfigDto = AgentComponentConfigDto.builder().build();
        BeanUtils.copyProperties(agentComponentConfig, agentComponentConfigDto);
        AgentConfig agentConfig = agentDomainService.queryById(agentComponentConfig.getAgentId());
        if (agentConfig != null) {
            agentComponentConfigDto.setSpaceId(agentConfig.getSpaceId());
        }
        List<AgentComponentConfigDto> agentComponentConfigDtos = completeAgentComponentConfig(List.of(agentComponentConfigDto), false);
        if (CollectionUtils.isEmpty(agentComponentConfigDtos)) {
            return null;
        }
        return agentComponentConfigDtos.get(0);
    }

    @Override
    public List<AgentComponentConfigDto> queryComponentConfigList(Long agentId) {
        return queryComponentConfigList(agentId, false);
    }

    private List<AgentComponentConfigDto> queryComponentConfigList(Long agentId, boolean forExecute) {
        List<AgentComponentConfig> agentComponentConfigList = agentDomainService.queryAgentComponentConfigList(agentId);
        AgentComponentConfig varConfig = agentComponentConfigList.stream().filter(agentComponentConfig -> agentComponentConfig.getType() == AgentComponentConfig.Type.Variable).findFirst().orElse(null);
        if (varConfig == null) {
            varConfig = AgentComponentConfig.builder().agentId(agentId)
                    .type(AgentComponentConfig.Type.Variable).targetId(0L).name("变量").description("智能体变量")
                    .build();
            VariableConfigDto variableBindConfigDto = new VariableConfigDto();
            variableBindConfigDto.setVariables(new ArrayList<>());
            varConfig.setBindConfig(variableBindConfigDto);
            agentDomainService.addAgentComponentConfig(varConfig);
            agentComponentConfigList.add(varConfig);
        }

        AgentComponentConfig eventConfig = agentComponentConfigList.stream().filter(agentComponentConfig -> agentComponentConfig.getType() == AgentComponentConfig.Type.Event).findFirst().orElse(null);
        if (eventConfig == null) {
            //初始化事件配置
            EventBindConfigDto eventBindConfigDto = new EventBindConfigDto();
            eventBindConfigDto.setEventConfigs(new ArrayList<>());
            AgentComponentConfig eventComponentConfig = AgentComponentConfig.builder().agentId(agentId)
                    .type(AgentComponentConfig.Type.Event)
                    .targetId(-1L)
                    .name("事件绑定").description("事件绑定")
                    .bindConfig(eventBindConfigDto).build();
            agentDomainService.addAgentComponentConfig(eventComponentConfig);
        }

        AgentComponentConfig subAgentConfig = agentComponentConfigList.stream().filter(agentComponentConfig -> agentComponentConfig.getType() == AgentComponentConfig.Type.SubAgent).findFirst().orElse(null);
        if (subAgentConfig == null) {
            //初始化subagent配置
            SubAgentBindConfigDto subAgentBindConfigDto = new SubAgentBindConfigDto();
            subAgentBindConfigDto.setSubAgents(new ArrayList<>());
            subAgentConfig = AgentComponentConfig.builder().agentId(agentId)
                    .type(AgentComponentConfig.Type.SubAgent)
                    .targetId(-1L)
                    .name("SubAgent").description("SubAgent")
                    .bindConfig(subAgentBindConfigDto).build();
            agentDomainService.addAgentComponentConfig(subAgentConfig);
        }

        AgentConfig agentConfig = agentDomainService.queryById(agentId);
        List<AgentComponentConfigDto> agentComponentConfigDtoList = agentComponentConfigList.stream().map(agentComponentConfig -> {
            AgentComponentConfigDto agentComponentConfigDto = AgentComponentConfigDto.builder().build();
            BeanUtils.copyProperties(agentComponentConfig, agentComponentConfigDto);
            if (agentConfig != null) {
                agentComponentConfigDto.setSpaceId(agentConfig.getSpaceId());
            }
            return agentComponentConfigDto;
        }).collect(Collectors.toList());
        agentComponentConfigDtoList = completeAgentComponentConfig(agentComponentConfigDtoList, forExecute);
        return agentComponentConfigDtoList;
    }

    @Override
    public AgentConfigDto queryPublishedConfigForExecute(Long agentId) {
        return queryPublishedConfig(agentId, true);
    }

    public AgentConfigDto queryPublishedConfig(Long agentId, boolean execute) {
        PublishedDto agentPublished = publishApplicationService.queryPublished(Published.TargetType.Agent, agentId);
        if (agentPublished == null) {
            return null;
        }
        AgentConfigDto agentConfigDto = JSON.parseObject(agentPublished.getConfig(), AgentConfigDto.class);
        convertComponentConfig(agentConfigDto.getModelComponentConfig());
        agentConfigDto.getAgentComponentConfigList().forEach(componentConfigDto -> componentConfigDto.setSpaceId(agentConfigDto.getSpaceId()));
        agentConfigDto.setAgentComponentConfigList(completeAgentComponentConfig(agentConfigDto.getAgentComponentConfigList(), execute));
        AgentComponentConfigDto modelComponentConfig = agentConfigDto.getAgentComponentConfigList()
                .stream()
                .filter(agentComponentConfigDto -> agentComponentConfigDto.getType() == AgentComponentConfig.Type.Model)
                .findFirst().orElse(null);
        if (modelComponentConfig == null) {
            throw new BizException("智能体关联大模型已下线，请重新配置");
        }
        agentConfigDto.setModelComponentConfig(modelComponentConfig);
        TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(RequestContext.get().getTenantId());
        if (tenantConfig.getDefaultAgentId() != null && agentId.longValue() == tenantConfig.getDefaultAgentId() && CollectionUtils.isNotEmpty(tenantConfig.getDefaultAgentIds())) {
            //排除自身
            tenantConfig.getDefaultAgentIds().remove(agentId);
            List<PublishedDto> publishedList = publishApplicationService.queryPublishedList(Published.TargetType.Agent, tenantConfig.getDefaultAgentIds());
            publishedList.forEach(publishedDto -> {
                AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
                agentComponentConfigDto.setAgentId(agentId);
                agentComponentConfigDto.setTargetId(publishedDto.getTargetId());
                agentComponentConfigDto.setName(publishedDto.getName());
                agentComponentConfigDto.setDescription(publishedDto.getDescription());
                agentComponentConfigDto.setType(AgentComponentConfig.Type.Agent);
                agentConfigDto.getAgentComponentConfigList().add(agentComponentConfigDto);
            });
        }
        agentConfigDto.setPublishDate(agentPublished.getModified());
        agentConfigDto.setAccessControl(agentPublished.getAccessControl());
        return agentConfigDto;
    }

    private AgentConfigDto queryPublishedConfig(Long agentId) {
        PublishedDto agentPublished = publishApplicationService.queryPublished(Published.TargetType.Agent, agentId);
        if (agentPublished == null) {
            return null;
        }
        AgentConfigDto agentConfigDto = JSON.parseObject(agentPublished.getConfig(), AgentConfigDto.class);
        agentConfigDto.setCollected(agentPublished.isCollect());
        agentConfigDto.setAgentStatistics(agentPublished.getStatistics());
        agentConfigDto.setPublishUser(agentPublished.getPublishUser());
        agentConfigDto.setPublishDate(agentPublished.getModified());
        agentConfigDto.setPublishRemark(agentPublished.getRemark());
        agentConfigDto.setCategory(agentPublished.getCategory());
        agentConfigDto.setAccessControl(agentPublished.getAccessControl());
        PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(Published.TargetType.Agent, agentId);
        agentConfigDto.setAllowCopy(publishedPermissionDto.isCopy() ? YesOrNoEnum.Y.getKey() : YesOrNoEnum.N.getKey());
        convertComponentConfig(agentConfigDto.getModelComponentConfig());
        return agentConfigDto;
    }

    private void convertComponentConfig(AgentComponentConfigDto componentConfig) {
        AgentConfigDto.convertBindConfig(componentConfig);
    }

    @Override
    public AgentConfigDto queryConfigForTestExecute(Long agentId) {
        return queryById(agentId, true);
    }

    @Override
    public AgentDetailDto queryAgentDetail(Long agentId, boolean isPublished) {
        AgentDetailDto agentDetailDto = new AgentDetailDto();
        AgentConfigDto agentConfigDto;
        if (isPublished) {
            agentConfigDto = queryPublishedConfig(agentId);
            if (agentConfigDto != null) {
                agentDetailDto.setStatistics(agentConfigDto.getAgentStatistics());
                agentDetailDto.setPublishUser(agentConfigDto.getPublishUser());
                agentDetailDto.setRemark(agentConfigDto.getPublishRemark());
                agentDetailDto.setPublishDate(agentConfigDto.getPublishDate());
                agentDetailDto.setCategory(agentConfigDto.getCategory());
                agentDetailDto.setAllowCopy(agentConfigDto.getAllowCopy());
            }
        } else {
            agentConfigDto = queryById(agentId);
        }
        if (agentConfigDto == null) {
            return null;
        }
        List<Arg> variables = new ArrayList<>();
        List<AgentManualComponentDto> agentManualComponentDtos = agentConfigDto.getAgentComponentConfigList().stream().filter(agentComponentConfigDto ->
        {
            convertComponentConfig(agentComponentConfigDto);
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Variable) {
                VariableConfigDto bindConfig = (VariableConfigDto) agentComponentConfigDto.getBindConfig();
                if (bindConfig != null && CollectionUtils.isNotEmpty(bindConfig.getVariables())) {
                    variables.addAll(bindConfig.getVariables().stream().filter(var -> !var.isSystemVariable() && var.getInputType() != null && var.getInputType() != Arg.InputTypeEnum.AutoRecognition).collect(Collectors.toList()));
                }
            }
            if (agentComponentConfigDto.getBindConfig() != null) {
                if (agentComponentConfigDto.getBindConfig() instanceof PluginBindConfigDto pluginBindConfigDto) {
                    return pluginBindConfigDto.getInvokeType() == PluginBindConfigDto.PluginInvokeTypeEnum.MANUAL ||
                            pluginBindConfigDto.getInvokeType() == PluginBindConfigDto.PluginInvokeTypeEnum.MANUAL_ON_DEMAND;
                }
                if (agentComponentConfigDto.getBindConfig() instanceof WorkflowBindConfigDto workflowBindConfigDto) {
                    return workflowBindConfigDto.getInvokeType() == WorkflowBindConfigDto.WorkflowInvokeTypeEnum.MANUAL ||
                            workflowBindConfigDto.getInvokeType() == WorkflowBindConfigDto.WorkflowInvokeTypeEnum.MANUAL_ON_DEMAND;
                }
                if (agentComponentConfigDto.getBindConfig() instanceof KnowledgeBaseBindConfigDto knowledgeBaseBindConfigDto) {
                    return knowledgeBaseBindConfigDto.getInvokeType() == KnowledgeBaseBindConfigDto.InvokeTypeEnum.MANUAL ||
                            knowledgeBaseBindConfigDto.getInvokeType() == KnowledgeBaseBindConfigDto.InvokeTypeEnum.MANUAL_ON_DEMAND;
                }
                if (agentComponentConfigDto.getBindConfig() instanceof SkillBindConfigDto skillBindConfigDto) {
                    return skillBindConfigDto.getInvokeType() == SkillBindConfigDto.SkillInvokeTypeEnum.MANUAL_ON_DEMAND;
                }
            }
            return false;
        }).map(agentComponentConfigDto -> {
            AgentManualComponentDto agentManualComponentDto = new AgentManualComponentDto();
            BeanUtils.copyProperties(agentComponentConfigDto, agentManualComponentDto);
            if (agentComponentConfigDto.getBindConfig() instanceof PluginBindConfigDto pluginBindConfigDto) {
                agentManualComponentDto.setDefaultSelected(pluginBindConfigDto.getDefaultSelected());
            }
            if (agentComponentConfigDto.getBindConfig() instanceof WorkflowBindConfigDto workflowBindConfigDto) {
                agentManualComponentDto.setDefaultSelected(workflowBindConfigDto.getDefaultSelected());
            }
            if (agentComponentConfigDto.getBindConfig() instanceof KnowledgeBaseBindConfigDto knowledgeBaseBindConfigDto) {
                agentManualComponentDto.setDefaultSelected(knowledgeBaseBindConfigDto.getDefaultSelected());
            }
            if (agentComponentConfigDto.getBindConfig() instanceof SkillBindConfigDto skillBindConfigDto) {
                agentManualComponentDto.setDefaultSelected(skillBindConfigDto.getDefaultSelected());
                if (StringUtils.isNotBlank(skillBindConfigDto.getAlias())) {
                    agentManualComponentDto.setName(skillBindConfigDto.getAlias());
                }
            }
            return agentManualComponentDto;
        }).collect(Collectors.toList());

        if (agentConfigDto.getModelComponentConfig() != null) {
            ModelBindConfigDto bindConfig = (ModelBindConfigDto) agentConfigDto.getModelComponentConfig().getBindConfig();
            if (bindConfig.getReasoningModelId() != null) {
                ModelConfigDto modelConfigDto = modelApplicationService.queryModelConfigById(bindConfig.getReasoningModelId());
                if (modelConfigDto != null) {
                    AgentManualComponentDto agentManualComponentDto = new AgentManualComponentDto();
                    agentManualComponentDto.setId(modelConfigDto.getId());
                    agentManualComponentDto.setName(modelConfigDto.getName());
                    agentManualComponentDto.setDescription(modelConfigDto.getDescription());
                    agentManualComponentDto.setType(AgentComponentConfig.Type.Model);
                    agentManualComponentDtos.add(agentManualComponentDto);
                }
            }
        }

        if (RequestContext.get().getUserId() != null) {
            List<UserTargetRelation> userTargetRelationList = userTargetRelationDomainService
                    .queryUserTargetRelationByTargetIds(RequestContext.get().getUserId(), Published.TargetType.Agent,
                            UserTargetRelation.OpType.Collect, List.of(agentId));
            if (userTargetRelationList != null && !userTargetRelationList.isEmpty()) {
                agentDetailDto.setCollect(true);
            }
        }
        agentDetailDto.setCreatorId(agentConfigDto.getCreatorId());
        agentDetailDto.setAgentId(agentId);
        agentDetailDto.setName(agentConfigDto.getName());
        agentDetailDto.setDescription(agentConfigDto.getDescription());
        agentDetailDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(agentConfigDto.getIcon(), agentConfigDto.getName(), "agent"));
        agentDetailDto.setSpaceId(agentConfigDto.getSpaceId());
        agentDetailDto.setOpeningGuidQuestions(agentConfigDto.getOpeningGuidQuestions());
        agentDetailDto.setExpandPageArea(agentConfigDto.getExpandPageArea());
        agentDetailDto.setHideChatArea(agentConfigDto.getHideChatArea());
        agentDetailDto.setHideDesktop(agentConfigDto.getHideDesktop() == null ? YesOrNoEnum.N.getKey() : agentConfigDto.getHideDesktop());
        if ("PageApp".equals(agentConfigDto.getType())) {
            agentDetailDto.setExpandPageArea(1);
            agentDetailDto.setHideChatArea(1);
        }
        agentDetailDto.setPageHomeIndex(agentConfigDto.getPageHomeIndex());
        agentDetailDto.setType(agentConfigDto.getType());
        Map<String, Object> variablesMap = new HashMap<>();
        if (RequestContext.get() != null && RequestContext.get().getUser() != null) {
            UserDto userDto = (UserDto) RequestContext.get().getUser();
            variablesMap.put(GlobalVariableEnum.SYS_USER_ID.name(), userDto.getId());
            variablesMap.put(GlobalVariableEnum.USER_UID.name(), userDto.getUid());
            variablesMap.put(GlobalVariableEnum.USER_NAME.name(), userDto.getNickName() == null ? userDto.getUserName() : userDto.getNickName());
        }
        variablesMap.put(GlobalVariableEnum.AGENT_ID.name(), agentId);
        variablesMap.put("AGENT_NAME", agentDetailDto.getName());
        if (StringUtils.isNotBlank(agentConfigDto.getOpeningChatMsg())) {
            String openingChatMsg = PlaceholderParser.resoleAndReplacePlaceholder(variablesMap, agentConfigDto.getOpeningChatMsg());
            agentDetailDto.setOpeningChatMsg(openingChatMsg);
        }
        // 处理引导问题
        Map<Long, AgentComponentConfigDto> pageComponentConfigMap = agentConfigDto.getAgentComponentConfigList().stream().filter(agentComponentConfigDto -> agentComponentConfigDto.getType() == AgentComponentConfig.Type.Page)
                .collect(Collectors.toMap(AgentComponentConfigDto::getTargetId, componentConfigDto -> componentConfigDto, (old, newConfig) -> newConfig));
        if (CollectionUtils.isNotEmpty(agentConfigDto.getOpeningGuidQuestions())) {
            agentDetailDto.setOpeningGuidQuestions(agentConfigDto.getOpeningGuidQuestions().stream().map(question -> {
                String questionText = PlaceholderParser.resoleAndReplacePlaceholder(variablesMap, question);
                return questionText;
            }).collect(Collectors.toList()));
            List<String> newOpeningGuidQuestions = new ArrayList<>();
            agentDetailDto.setGuidQuestionDtos(agentDetailDto.getOpeningGuidQuestions().stream().map(question -> {
                GuidQuestionDto guidQuestionDto;
                try {
                    if (!JSON.isValid(question)) {
                        guidQuestionDto = new GuidQuestionDto();
                        guidQuestionDto.setType(GuidQuestionDto.GuidQuestionType.Question);
                        guidQuestionDto.setInfo(question);
                        return guidQuestionDto;
                    }
                    guidQuestionDto = JSON.parseObject(question, GuidQuestionDto.class);
                } catch (Exception e) {
                    newOpeningGuidQuestions.add(question);
                    log.warn("Invalid JSON question: {}", question);
                    return null;
                }
                if (guidQuestionDto == null) {
                    newOpeningGuidQuestions.add(question);
                    return null;
                }
                if (guidQuestionDto.getType() == GuidQuestionDto.GuidQuestionType.Page) {
                    if (guidQuestionDto.getPageId() == null || !pageComponentConfigMap.containsKey(guidQuestionDto.getPageId())) {
                        return null;
                    }
                    PageDto pageDto = customPageRpcService.queryPageDto(guidQuestionDto.getPageId());
                    if (pageDto != null) {
                        guidQuestionDto.setBasePath(pageDto.getBasePath());
                        Optional<PageArgConfig> first = pageDto.getPageArgConfigs().stream().filter(pageArgConfig -> pageArgConfig.getPageUri().equals(guidQuestionDto.getPageUri())).findFirst();
                        if (!first.isPresent()) {
                            return null;
                        }
                        PageArgConfig pageArgConfig = first.get();
                        guidQuestionDto.setPageUrl(pageArgConfig.getPageUrl(agentId));
                        guidQuestionDto.setParams(new HashMap<>());
                        if (guidQuestionDto.getArgs() != null && pageArgConfig.getArgs() != null) {
                            //guidQuestionDto.getArgs()转name为key的map
                            Map<String, Arg> argConfigMap = guidQuestionDto.getArgs().stream().collect(Collectors.toMap(Arg::getName, arg -> arg, (old, newArg) -> newArg));
                            pageArgConfig.getArgs().forEach(arg -> {
                                if (arg.getEnable() != null && !arg.getEnable()) {
                                    guidQuestionDto.getParams().put(arg.getName(), arg.getBindValue());
                                } else {
                                    if (argConfigMap.containsKey(arg.getName())) {
                                        Arg argConfig = argConfigMap.get(arg.getName());
                                        if (StringUtils.isNotBlank(argConfig.getBindValue())) {
                                            guidQuestionDto.getParams().put(arg.getName(), argConfig.getBindValue());
                                        } else {
                                            guidQuestionDto.getParams().put(arg.getName(), arg.getBindValue());
                                        }
                                    } else {
                                        guidQuestionDto.getParams().put(arg.getName(), arg.getBindValue());
                                    }
                                }
                            });
                        }
                        newOpeningGuidQuestions.add(guidQuestionDto.getInfo());
                        return guidQuestionDto;
                    }
                    return null;
                }
                return guidQuestionDto;
            }).collect(Collectors.toList()));
            agentDetailDto.getGuidQuestionDtos().removeIf(Objects::isNull);

            //兼容旧版本
            agentDetailDto.setOpeningGuidQuestions(newOpeningGuidQuestions);
        }
        Optional<AgentComponentConfigDto> first = agentConfigDto.getAgentComponentConfigList().stream().filter(agentComponentConfigDto -> agentComponentConfigDto.getType().equals(AgentComponentConfig.Type.Event)).findFirst();
        first.ifPresent(agentComponentConfigDto -> agentDetailDto.setEventBindConfig((EventBindConfigDto) agentComponentConfigDto.getBindConfig()));
        agentDetailDto.setOpenSuggest(agentConfigDto.getOpenSuggest());
        agentDetailDto.setOpenScheduledTask(agentConfigDto.getOpenScheduledTask());
        agentDetailDto.setManualComponents(agentManualComponentDtos);
        agentDetailDto.setVariables(variables);
        Long sandboxId = null;
        try {
            sandboxId = agentConfigDto.getExtra() == null || agentConfigDto.getExtra().get("sandboxId") == null ? null : Long.parseLong(agentConfigDto.getExtra().get("sandboxId").toString());
        } catch (NumberFormatException e) {
            //do nothing
        }
        agentDetailDto.setSandboxId(sandboxId);

        // 是否有权限
        agentDetailDto.setHasPermission(true);
        if (agentConfigDto.getAccessControl() != null && agentConfigDto.getAccessControl().equals(YesOrNoEnum.Y.getKey())
                && RequestContext.get().getUserId() != null && !agentConfigDto.getCreatorId().equals(RequestContext.get().getUserId())) {
            UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());
            if (userDataPermission.getAgentIds() == null || !userDataPermission.getAgentIds().contains(agentId)) {
                agentDetailDto.setHasPermission(false);
            }
        }

        //补充变量下拉选项数据绑定值
        try {
            completeSelectConfig(variables, agentDetailDto.getSpaceId());
        } catch (Exception e) {
            log.warn("补充变量下拉选项数据绑定值失败", e);
            //忽略
        }
        TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfig != null && tenantConfig.getSiteUrl() != null) {
            String siteUrl = tenantConfig.getSiteUrl().trim();
            siteUrl = siteUrl.endsWith("/") ? siteUrl.substring(0, siteUrl.length() - 1) : siteUrl;
            agentDetailDto.setShareLink(siteUrl + "/agent/" + agentId);
        }
        return agentDetailDto;
    }

    public List<Arg> getAgentNoneSystemVariables(Long agentId, Long spaceId) {
        List<AgentComponentConfig> agentComponentConfigList = agentDomainService.queryAgentComponentConfigList(agentId);
        AgentComponentConfig agentComponentConfig = agentComponentConfigList.stream().filter(agentComponentConfig0 -> agentComponentConfig0.getType() == AgentComponentConfig.Type.Variable).findFirst().orElse(null);
        if (agentComponentConfig == null) {
            return new ArrayList<>();
        }
        AgentComponentConfigDto agentComponentConfigDto = AgentComponentConfigDto.builder().build();
        BeanUtils.copyProperties(agentComponentConfig, agentComponentConfigDto);
        convertComponentConfig(agentComponentConfigDto);
        VariableConfigDto bindConfig = (VariableConfigDto) agentComponentConfigDto.getBindConfig();
        List<Arg> variables = new ArrayList<>();
        if (bindConfig != null && CollectionUtils.isNotEmpty(bindConfig.getVariables())) {
            variables.addAll(bindConfig.getVariables().stream().filter(var -> !var.isSystemVariable() && var.getInputType() != null && var.getInputType() != Arg.InputTypeEnum.AutoRecognition).collect(Collectors.toList()));
        }
        completeSelectConfig(variables, spaceId);
        return variables;
    }

    private void completeSelectConfig(List<Arg> variables, Long spaceId) {
        variables.forEach(variable -> {
            if (Arg.InputTypeEnum.Select == variable.getInputType() || Arg.InputTypeEnum.MultipleSelect == variable.getInputType()) {
                if (variable.getSelectConfig() == null || variable.getSelectConfig().getDataSourceType() != SelectConfig.DataSourceTypeEnum.BINDING) {
                    return;
                }
                if (variable.getSelectConfig().getTargetType() == Published.TargetType.Workflow) {
                    //执行工作流获取数据
                    WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(variable.getSelectConfig().getTargetId(), spaceId, true);
                    if (workflowConfigDto == null) {
                        //引用的工作流不存在
                        return;
                    }
                    UserDto userDto = ((UserDto) RequestContext.get().getUser());
                    WorkflowContext workflowContext = new WorkflowContext();
                    workflowContext.setWorkflowConfig(workflowConfigDto);
                    AgentContext agentContext = new AgentContext();
                    agentContext.setUser((UserDto) RequestContext.get().getUser());
                    agentContext.setUserId(RequestContext.get().getUserId());
                    agentContext.setUid(((UserDto) RequestContext.get().getUser()).getUid());
                    agentContext.setUserName(userDto.getNickName() != null ? userDto.getNickName() : userDto.getUserName());
                    agentContext.setRequestId(RequestContext.get().getRequestId());
                    agentContext.setConversationId(RequestContext.get().getRequestId());
                    agentContext.setMessage("");

                    workflowContext.setAgentContext(agentContext);
                    workflowContext.setRequestId(RequestContext.get().getRequestId());
                    workflowContext.setWorkflowConfig(workflowConfigDto);
                    workflowContext.setParams(new HashMap<>());
                    Object value = workflowExecutor.execute(workflowContext).timeout(Duration.ofSeconds(10)).block();
                    if (value != null) {
                        JSONObject jsonObject = JSONObject.parseObject(JSONObject.toJSONString(value));
                        if (jsonObject.isArray("options")) {
                            variable.getSelectConfig().setOptions(parseToOptions(jsonObject.getJSONArray("options")));
                        }
                    }
                }
                if (variable.getSelectConfig().getTargetType() == Published.TargetType.Plugin) {
                    PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(variable.getSelectConfig().getTargetId(), spaceId);
                    if (pluginDto == null) {
                        return;
                    }
                    AgentContext agentContext = new AgentContext();
                    agentContext.setUser((UserDto) RequestContext.get().getUser());
                    PluginContext pluginContext = PluginContext.builder()
                            .requestId(RequestContext.get().getRequestId())
                            .pluginConfig((PluginConfigDto) pluginDto.getConfig())
                            .pluginDto(pluginDto)
                            .params(new HashMap<>())
                            .userId(RequestContext.get().getUserId())
                            .test(false)
                            .agentContext(agentContext)
                            .build();
                    Object value = pluginExecutor.execute(pluginContext).block().getResult();
                    if (value != null) {
                        JSONObject jsonObject = JSONObject.parseObject(JSONObject.toJSONString(value));
                        if (jsonObject.isArray("options")) {
                            variable.getSelectConfig().setOptions(parseToOptions(jsonObject.getJSONArray("options")));
                        }
                    }
                }
            }
        });
    }

    private List<SelectConfig.SelectOption> parseToOptions(JSONArray jsonOptions) {
        List<SelectConfig.SelectOption> options = new ArrayList<>();
        for (int i = 0; i < jsonOptions.size(); i++) {
            JSONObject jsonOption = jsonOptions.getJSONObject(i);
            SelectConfig.SelectOption option = new SelectConfig.SelectOption();
            option.setLabel(jsonOption.getString("label"));
            option.setValue(jsonOption.getString("value"));
            if (jsonOption.isArray("children")) {
                option.setChildren(parseToOptions(jsonOption.getJSONArray("children")));
            }
            options.add(option);
        }
        return options;
    }

    // 补全智能体组件详细配置信息
    private List<AgentComponentConfigDto> completeAgentComponentConfig(List<AgentComponentConfigDto> agentComponentConfigList, boolean forExecute) {
        if (agentComponentConfigList == null || agentComponentConfigList.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, AgentComponentConfigDto> pageComponentConfigMap = agentComponentConfigList.stream().filter(agentComponentConfigDto -> agentComponentConfigDto.getType() == AgentComponentConfig.Type.Page)
                .collect(Collectors.toMap(AgentComponentConfigDto::getTargetId, componentConfigDto -> componentConfigDto, (old, newConfig) -> newConfig));
        agentComponentConfigList.forEach(agentComponentConfigDto -> {
            if (agentComponentConfigDto.getType() == null) {
                agentComponentConfigDto.setDeleted(true);
                return;
            }
            //从发布数据得到的需要转换
            convertComponentConfig(agentComponentConfigDto);
            // 获取各个组件原始配置
            // 获取模型配置
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Model) {
                ModelConfigDto modelConfigDto = modelApplicationService.queryModelConfigById(agentComponentConfigDto.getTargetId());
                //agentComponentConfigDto.setDeleted(modelConfigDto == null);
                if (modelConfigDto != null && !forExecute) {
                    modelConfigDto.setApiInfoList(null);
                }
                if (modelConfigDto != null) {
                    agentComponentConfigDto.setName(modelConfigDto.getName());
                }
                agentComponentConfigDto.setTargetConfig(modelConfigDto);
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Plugin) {
                PublishedDto publishedDto = publishApplicationService.queryPublishedWithSpaceId(Published.TargetType.Plugin, agentComponentConfigDto.getTargetId(), agentComponentConfigDto.getSpaceId());
                agentComponentConfigDto.setDeleted(publishedDto == null);
                if (!agentComponentConfigDto.isDeleted()) {
                    agentComponentConfigDto.setDeleted(publishedDto == null || publishedDto.getConfig() == null || !JSON.isValid(publishedDto.getConfig()));
                }
                if (publishedDto != null && !agentComponentConfigDto.isDeleted()) {
                    PluginDto pluginDto = JSON.parseObject(publishedDto.getConfig(), PluginDto.class);
                    if (pluginDto == null || pluginDto.getConfig() == null) {
                        agentComponentConfigDto.setDeleted(true);
                        return;
                    }
                    agentComponentConfigDto.setName(pluginDto.getName());
                    agentComponentConfigDto.setDescription(pluginDto.getDescription());
                    agentComponentConfigDto.setIcon(pluginDto.getIcon());
                    agentComponentConfigDto.setTargetConfig(pluginDto);
                    PluginConfigDto pluginConfigDto;
                    if (pluginDto.getType() == PluginTypeEnum.HTTP) {
                        pluginConfigDto = JSON.parseObject(pluginDto.getConfig().toString(), HttpPluginConfigDto.class);
                    } else {
                        pluginConfigDto = JSON.parseObject(pluginDto.getConfig().toString(), CodePluginConfigDto.class);
                    }
                    pluginDto.setConfig(pluginConfigDto);
                    if (!forExecute) {
                        if (pluginConfigDto.getInputArgs() != null) {
                            Arg.removeDisabledArgs(pluginConfigDto.getInputArgs());
                        }
                        if (pluginConfigDto instanceof HttpPluginConfigDto) {
                            ((HttpPluginConfigDto) pluginConfigDto).setUrl(null);
                        }
                        if (pluginConfigDto instanceof CodePluginConfigDto) {
                            ((CodePluginConfigDto) pluginConfigDto).setCode(null);
                        }
                    }
                    PluginBindConfigDto pluginBindConfigDto = (PluginBindConfigDto) agentComponentConfigDto.getBindConfig();
                    if (pluginBindConfigDto == null) {
                        pluginBindConfigDto = new PluginBindConfigDto();
                        agentComponentConfigDto.setBindConfig(pluginBindConfigDto);
                    }
                    List<Arg> inputArgBindConfigs = Arg.updateBindConfigArgs(null, pluginBindConfigDto.getInputArgBindConfigs(), pluginConfigDto.getInputArgs());
                    pluginBindConfigDto.setInputArgBindConfigs(inputArgBindConfigs);
                    List<Arg> outputArgBindConfigs = Arg.updateBindConfigArgs(null, pluginBindConfigDto.getOutputArgBindConfigs(), pluginConfigDto.getOutputArgs());
                    pluginBindConfigDto.setOutputArgBindConfigs(outputArgBindConfigs);
                    if (pluginBindConfigDto.getInvokeType() == null) {
                        pluginBindConfigDto.setInvokeType(PluginBindConfigDto.PluginInvokeTypeEnum.ON_DEMAND);
                    }
                    if (pluginBindConfigDto.getDefaultSelected() == null) {
                        pluginBindConfigDto.setDefaultSelected(0);
                    }
                    if (pluginBindConfigDto.getAsync() == null) {
                        pluginBindConfigDto.setAsync(0);
                        pluginBindConfigDto.setAsyncReplyContent("已经开始为您处理，请耐心等待运行结果");
                    }
                }
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Mcp) {
                McpDto mcpDto = mcpRpcService.queryMcp(agentComponentConfigDto.getTargetId(), agentComponentConfigDto.getSpaceId());
                agentComponentConfigDto.setDeleted(mcpDto == null || agentComponentConfigDto.getBindConfig() == null);
                if (!agentComponentConfigDto.isDeleted()) {
                    agentComponentConfigDto.setDeleted(mcpDto == null || mcpDto.getDeployedConfig() == null);
                    if (agentComponentConfigDto.isDeleted()) {
                        return;
                    }
                } else {
                    return;
                }
                if (mcpDto.getDeployedConfig() == null) {
                    return;
                }
                McpBindConfigDto mcpBindConfigDto = (McpBindConfigDto) agentComponentConfigDto.getBindConfig();
                McpToolDto mcpToolDto = mcpDto.getDeployedConfig().getTools().stream()
                        .filter(tool -> tool.getName().equals(mcpBindConfigDto.getToolName()))
                        .findFirst().orElse(null);
                if (!agentComponentConfigDto.isDeleted()) {
                    agentComponentConfigDto.setDeleted(mcpToolDto == null);
                }
                if (!agentComponentConfigDto.isDeleted()) {
                    mcpDto.getDeployedConfig().getTools().removeIf(tool -> !tool.getName().equals(mcpToolDto.getName()));
                    agentComponentConfigDto.setGroupName(mcpDto.getName());
                    agentComponentConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(mcpDto.getIcon(), mcpDto.getName(), "mcp"));
                    agentComponentConfigDto.setGroupDescription(mcpDto.getDescription());
                    agentComponentConfigDto.setName(mcpToolDto.getName());
                    agentComponentConfigDto.setDescription(mcpToolDto.getDescription());
                    agentComponentConfigDto.setTargetConfig(mcpDto);
                    List<Arg> args = ArgConverter.convertMcpArgsToArgs(mcpToolDto.getInputArgs());
                    List<Arg> inputArgBindConfigs = Arg.updateBindConfigArgs(null, mcpBindConfigDto.getInputArgBindConfigs(), args);
                    mcpBindConfigDto.setInputArgBindConfigs(inputArgBindConfigs);
                    if (mcpBindConfigDto.getInvokeType() == null) {
                        mcpBindConfigDto.setInvokeType(McpBindConfigDto.McpInvokeTypeEnum.ON_DEMAND);
                    }
                    if (mcpBindConfigDto.getDefaultSelected() == null) {
                        mcpBindConfigDto.setDefaultSelected(0);
                    }
                    if (mcpBindConfigDto.getAsync() == null) {
                        mcpBindConfigDto.setAsync(0);
                        mcpBindConfigDto.setAsyncReplyContent("已经开始为您处理，请耐心等待运行结果");
                    }
                    if (!forExecute) {
                        mcpDto.setMcpConfig(null);
                        mcpDto.getDeployedConfig().setServerConfig(null);
                    }
                }
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Page) {
                PageDto pageDto = customPageRpcService.queryPageDto(agentComponentConfigDto.getTargetId());
                agentComponentConfigDto.setDeleted(pageDto == null);
                if (pageDto != null) {
                    if (agentComponentConfigDto.getTargetConfig() == null) {
                        agentComponentConfigDto.setName(pageDto.getName());
                        agentComponentConfigDto.setDescription(pageDto.getDescription());
                        agentComponentConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(pageDto.getIcon(), pageDto.getName(), "page"));
                        agentComponentConfigDto.setTargetConfig(pageDto);
                        PageBindConfigDto pageBindConfigDto = (PageBindConfigDto) agentComponentConfigDto.getBindConfig();
                        if (pageBindConfigDto == null) {
                            pageBindConfigDto = new PageBindConfigDto();
                            pageBindConfigDto.setVisibleToLLM(1);
                            pageBindConfigDto.setHomeIndex(0);
                            agentComponentConfigDto.setBindConfig(pageBindConfigDto);
                        }
                        pageBindConfigDto.setPageArgConfigs(pageDto.getPageArgConfigs());
                        pageBindConfigDto.setBasePath(pageDto.getBasePath());
                    }
                }
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Event) {
                EventBindConfigDto eventBindConfigDto = (EventBindConfigDto) agentComponentConfigDto.getBindConfig();
                if (eventBindConfigDto == null) {
                    eventBindConfigDto = new EventBindConfigDto();
                    eventBindConfigDto.setEventConfigs(new ArrayList<>());
                }
                Map<Long, PageDto> pageDtoMap = new HashMap<>();
                eventBindConfigDto.getEventConfigs().stream().map(eventConfig -> {
                    // 事件提示词中的参数定义
                    List<String> required = new ArrayList<>();
                    Map<String, Object> properties = new HashMap<>();
                    Map<String, Object> inputSchema = Map.of("type", "object", "properties", properties, "required", required);
                    if (eventConfig.getType() == EventConfigDto.EventType.Page) {
                        if (eventConfig.getPageId() == null || !pageComponentConfigMap.containsKey(eventConfig.getPageId())) {
                            return null;
                        }
                        PageDto pageDto = pageDtoMap.get(eventConfig.getPageId());
                        if (pageDto == null) {
                            pageDto = customPageRpcService.queryPageDto(eventConfig.getPageId());
                        }
                        if (pageDto == null) {
                            return null;
                        }
                        Optional<PageArgConfig> first = pageDto.getPageArgConfigs().stream().filter(pageArgConfig -> pageArgConfig.getPageUri().equals(eventConfig.getPageUri())).findFirst();
                        if (first.isEmpty()) {
                            return null;
                        }
                        PageArgConfig pageArgConfig = first.get();
                        if (eventConfig.getArgs() != null && pageArgConfig.getArgs() != null) {
                            Map<String, Arg> argMap = eventConfig.getArgs().stream().collect(Collectors.toMap(Arg::getName, arg -> arg, (arg1, arg2) -> arg1));
                            pageArgConfig.getArgs().forEach(arg -> {
                                Arg arg0 = argMap.get(arg.getName());
                                if (arg0 != null && StringUtils.isNotBlank(arg0.getBindValue())) {
                                    arg.setBindValue(arg0.getBindValue());
                                }
                                if (StringUtils.isBlank(arg.getBindValue())) {
                                    properties.put(arg.getName(), Map.of("type", "string", "description", arg.getDescription()));
                                    if (arg.isRequire()) {
                                        required.add(arg.getName());
                                    }
                                }
                            });
                            eventConfig.setArgs(pageArgConfig.getArgs());
                        } else {
                            eventConfig.setArgs(new ArrayList<>());
                        }
                        eventConfig.setPageName(pageArgConfig.getName());
                        eventConfig.setPageUrl(pageArgConfig.getPageUrl(agentComponentConfigDto.getAgentId()));
                    }
                    eventConfig.setArgJsonSchema(JSON.toJSONString(inputSchema));
                    return eventConfig;
                }).collect(Collectors.toList());
                eventBindConfigDto.getEventConfigs().removeIf(Objects::isNull);
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Workflow) {
                WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(agentComponentConfigDto.getTargetId(), agentComponentConfigDto.getSpaceId(), forExecute);
                agentComponentConfigDto.setDeleted(workflowConfigDto == null);
                if (workflowConfigDto != null) {
                    agentComponentConfigDto.setName(workflowConfigDto.getName());
                    agentComponentConfigDto.setDescription(workflowConfigDto.getDescription());
                    agentComponentConfigDto.setIcon(workflowConfigDto.getIcon());
                    agentComponentConfigDto.setTargetConfig(workflowConfigDto);
                    WorkflowBindConfigDto workflowBindConfigDto = (WorkflowBindConfigDto) agentComponentConfigDto.getBindConfig();
                    if (workflowBindConfigDto == null) {
                        workflowBindConfigDto = new WorkflowBindConfigDto();
                        agentComponentConfigDto.setBindConfig(workflowBindConfigDto);
                    }
                    List<Arg> argBindConfigs = Arg.updateBindConfigArgs(null, workflowBindConfigDto.getArgBindConfigs(), workflowConfigDto.getInputArgs());
                    workflowBindConfigDto.setArgBindConfigs(argBindConfigs);
                    List<Arg> outputArgBindConfigs = Arg.updateBindConfigArgs(null, workflowBindConfigDto.getOutputArgBindConfigs(), workflowConfigDto.getOutputArgs());
                    workflowBindConfigDto.setOutputArgBindConfigs(outputArgBindConfigs);
                    if (!forExecute) {
                        workflowConfigDto.setNodes(null);
                    }
                    if (workflowBindConfigDto.getInvokeType() == null) {
                        workflowBindConfigDto.setInvokeType(WorkflowBindConfigDto.WorkflowInvokeTypeEnum.ON_DEMAND);
                    }
                    if (workflowBindConfigDto.getDefaultSelected() == null) {
                        workflowBindConfigDto.setDefaultSelected(0);
                    }
                    if (workflowBindConfigDto.getAsync() == null) {
                        workflowBindConfigDto.setAsync(0);
                        workflowBindConfigDto.setAsyncReplyContent("已经开始为您处理，请耐心等待运行结果");
                    }
                }
            }

            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Table) {
                DorisTableDefineRequest request = new DorisTableDefineRequest();
                request.setTableId(agentComponentConfigDto.getTargetId());
                TableDefineVo dorisTableDefinitionVo = null;
                try {
                    dorisTableDefinitionVo = iComposeDbTableRpcService.queryTableDefinition(request);
                } catch (Exception e) {
                    //  忽略
                    log.warn("查询表结构定义失败 {}", agentComponentConfigDto.getTargetId());
                }
                agentComponentConfigDto.setDeleted(dorisTableDefinitionVo == null);
                if (dorisTableDefinitionVo != null) {
                    agentComponentConfigDto.setName(dorisTableDefinitionVo.getTableName());
                    agentComponentConfigDto.setDescription(dorisTableDefinitionVo.getTableDescription());
                    agentComponentConfigDto.setIcon(dorisTableDefinitionVo.getIcon());
                    agentComponentConfigDto.setTargetConfig(dorisTableDefinitionVo);
                    TableBindConfigDto tableBindConfigDto = (TableBindConfigDto) agentComponentConfigDto.getBindConfig();
                    if (tableBindConfigDto == null) {
                        tableBindConfigDto = new TableBindConfigDto();
                        agentComponentConfigDto.setBindConfig(tableBindConfigDto);
                    }
                    List<Arg> args = ArgConverter.convertTableFieldsToArgs(dorisTableDefinitionVo.getFieldList());
                    // 去掉无需用户设置的系统变量
                    List<Arg> inputArgs = args.stream().filter(arg -> !arg.getName().equals("user_name")
                                    && !arg.getName().equals("nick_name")
                                    && !arg.getName().equals("agent_name")
                                    && !arg.getName().equals("created")
                                    && !arg.getName().equals("modified")
                                    && !arg.getName().equals("id")
                            )
                            .collect(Collectors.toList());
                    List<Arg> argBindConfigs = Arg.updateBindConfigArgs(null, tableBindConfigDto.getInputArgBindConfigs(), inputArgs);
                    tableBindConfigDto.setInputArgBindConfigs(argBindConfigs);
                    List<Arg> outputArgBindConfigs = new ArrayList<>();
                    outputArgBindConfigs.add(Arg.builder().name("outputList").description("数据列表").dataType(DataTypeEnum.Array_Object).enable(true).subArgs(args).build());
                    outputArgBindConfigs.add(Arg.builder().name("rowNum").description("数据行数").dataType(DataTypeEnum.Integer).enable(true).build());
                    Arg.generateKey(null, outputArgBindConfigs, new HashMap<>());
                    tableBindConfigDto.setOutputArgBindConfigs(outputArgBindConfigs);
                }
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Knowledge) {
                KnowledgeConfigVo knowledgeConfigModel = KnowledgeRpcService.queryKnowledgeConfigById(agentComponentConfigDto.getTargetId());
                agentComponentConfigDto.setDeleted(knowledgeConfigModel == null);
                if (knowledgeConfigModel != null) {
                    agentComponentConfigDto.setName(knowledgeConfigModel.getName());
                    agentComponentConfigDto.setDescription(knowledgeConfigModel.getDescription());
                    agentComponentConfigDto.setIcon(knowledgeConfigModel.getIcon());
                    agentComponentConfigDto.setTargetConfig(knowledgeConfigModel);
                }
                KnowledgeBaseBindConfigDto knowledgeBaseBindConfigDto = (KnowledgeBaseBindConfigDto) agentComponentConfigDto.getBindConfig();
                if (knowledgeBaseBindConfigDto == null) {
                    knowledgeBaseBindConfigDto = new KnowledgeBaseBindConfigDto();
                    agentComponentConfigDto.setBindConfig(knowledgeBaseBindConfigDto);
                }
                if (knowledgeBaseBindConfigDto.getInvokeType() == null) {
                    knowledgeBaseBindConfigDto.setInvokeType(KnowledgeBaseBindConfigDto.InvokeTypeEnum.ON_DEMAND);
                }
                if (knowledgeBaseBindConfigDto.getDefaultSelected() == null) {
                    knowledgeBaseBindConfigDto.setDefaultSelected(0);
                }
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Skill) {
                SkillConfigDto skillConfigDto = skillApplicationService.queryPublishedSkillConfig(agentComponentConfigDto.getTargetId(), agentComponentConfigDto.getSpaceId(), false);
                agentComponentConfigDto.setDeleted(skillConfigDto == null);
                if (skillConfigDto != null) {
                    agentComponentConfigDto.setName(skillConfigDto.getName());
                    agentComponentConfigDto.setDescription(skillConfigDto.getDescription());
                    agentComponentConfigDto.setIcon(skillConfigDto.getIcon());
                    agentComponentConfigDto.setTargetConfig(skillConfigDto);
                    SkillBindConfigDto skillBindConfigDto = (SkillBindConfigDto) agentComponentConfigDto.getBindConfig();
                    if (skillBindConfigDto == null) {
                        skillBindConfigDto = new SkillBindConfigDto();
                        agentComponentConfigDto.setBindConfig(skillBindConfigDto);
                    }
                    if (skillBindConfigDto.getInvokeType() == null) {
                        skillBindConfigDto.setInvokeType(SkillBindConfigDto.SkillInvokeTypeEnum.ON_DEMAND);
                    }
                    if (skillBindConfigDto.getDefaultSelected() == null) {
                        skillBindConfigDto.setDefaultSelected(0);
                    }
                }
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Trigger) {
                TriggerConfigDto triggerConfigDto = (TriggerConfigDto) agentComponentConfigDto.getBindConfig();
                if (triggerConfigDto.getComponentType() == TriggerConfigDto.TriggerComponentTypeEnum.PLUGIN) {
                    PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Plugin, agentComponentConfigDto.getTargetId());
                    if (publishedDto != null) {
                        PluginDto pluginDto = JSON.parseObject(publishedDto.getConfig(), PluginDto.class);
                        PluginConfigDto pluginConfigDto = JSON.parseObject(pluginDto.getConfig().toString(), PluginConfigDto.class);
                        triggerConfigDto.setName(pluginDto.getName());
                        List<Arg> argBindConfigDtos = Arg.updateBindConfigArgs(null, triggerConfigDto.getArgBindConfigs(), pluginConfigDto.getInputArgs());
                        triggerConfigDto.setArgBindConfigs(argBindConfigDtos);
                    }
                }

                if (triggerConfigDto.getComponentType() == TriggerConfigDto.TriggerComponentTypeEnum.WORKFLOW) {
                    PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Workflow, agentComponentConfigDto.getTargetId());
                    if (publishedDto != null) {
                        WorkflowConfigDto workflowConfigDto = JSON.parseObject(publishedDto.getConfig(), WorkflowConfigDto.class);
                        triggerConfigDto.setName(workflowConfigDto.getName());
                        List<Arg> argBindConfigDtos = Arg.updateBindConfigArgs(null, triggerConfigDto.getArgBindConfigs(), workflowConfigDto.getInputArgs());
                        triggerConfigDto.setArgBindConfigs(argBindConfigDtos);
                    }
                }
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Variable) {
                VariableConfigDto variableConfigDto = (VariableConfigDto) agentComponentConfigDto.getBindConfig();
                if (variableConfigDto != null && variableConfigDto.getVariables() != null) {
                    //variableConfigDto.getVariables()转name为key的map
                    Map<String, Arg> variableMap = variableConfigDto.getVariables().stream().collect(Collectors.toMap(Arg::getName, variableDto -> variableDto, (c1, c2) -> c1));
                    Arg.getSystemVariableArgs().forEach(arg -> {
                        if (variableMap.get(arg.getName()) == null) {
                            variableConfigDto.getVariables().add(arg);
                        }
                    });
                    //排序 variableConfigDto.getVariables() 系统变量排前面
                    variableConfigDto.getVariables().sort((o1, o2) -> {
                        if (o1.isSystemVariable() && !o2.isSystemVariable()) {
                            return -1;
                        }
                        if (!o1.isSystemVariable() && o2.isSystemVariable()) {
                            return 1;
                        }
                        return 0;
                    });
                }
            }
            if (agentComponentConfigDto.getType() != AgentComponentConfig.Type.Model && agentComponentConfigDto.getType() != AgentComponentConfig.Type.Variable) {
                agentComponentConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(agentComponentConfigDto.getIcon(), agentComponentConfigDto.getName(), agentComponentConfigDto.getType().name()));
            }
        });
        return agentComponentConfigList.stream().filter(agentComponentConfigDto -> !agentComponentConfigDto.isDeleted()).collect(Collectors.toList());
    }

    @Override
    public List<CardDto> queryCardList() {
        return agentDomainService.queryCardList().stream().map(card -> {
            CardDto cardDto = new CardDto();
            BeanUtils.copyProperties(card, cardDto);
            JSONArray args = JSON.parseArray(card.getArgs());
            // args转换成List<ArgDto>
            cardDto.setArgList(args.stream().map(arg -> {
                JSONObject argObj = (JSONObject) arg;
                CardDto.CardArgsDto cardArgsDto = new CardDto.CardArgsDto();
                cardArgsDto.setKey(argObj.getString("key"));
                cardArgsDto.setPlaceholder(argObj.getString("placeholder"));
                return cardArgsDto;
            }).collect(Collectors.toList()));
            return cardDto;
        }).collect(Collectors.toList());
    }

    @Override
    public List<UserAgentDto> queryRecentEditList(Long userId, Integer size) {
        List<UserAgentDto> userAgentList = convertUserAgentList(
                userTargetRelationDomainService.queryRecentEditList(userId, Published.TargetType.Agent, size));
        return filterNoPermissionOrDeletedAgent(userId, userAgentList);
    }

    @Override
    public List<UserAgentDto> queryRecentUseList(Long userId, String kw, Integer size, Integer pageIndex) {
        pageIndex = pageIndex == null || pageIndex <= 0 ? 1 : pageIndex;
        List<UserTargetRelation> userTargetRelations;
        if (StringUtils.isNotBlank(kw)) {
            userTargetRelations = userTargetRelationDomainService.queryRecentUseList(userId, Published.TargetType.Agent, MAX_QUERY_SIZE, null);
        } else {
            userTargetRelations = userTargetRelationDomainService.queryRecentUseList(userId, Published.TargetType.Agent, size, pageIndex);
        }
        List<UserAgentDto> userAgentList = userTargetRelations.stream().map(userTargetRelation -> {
            UserAgentDto userAgentDto = new UserAgentDto();
            userAgentDto.setId(userTargetRelation.getId());
            userAgentDto.setUserId(userTargetRelation.getUserId());
            userAgentDto.setAgentId(userTargetRelation.getTargetId());
            userAgentDto.setCreated(userTargetRelation.getCreated());
            userAgentDto.setModified(userTargetRelation.getModified());
            try {
                if (userTargetRelation.getExtra() != null && JSON.isValidObject(userTargetRelation.getExtra())) {
                    JSONObject extra = JSON.parseObject(userTargetRelation.getExtra());
                    userAgentDto.setLastConversationId(extra.getLong("conversationId"));
                    // 判断会话是否已删除
                    if (conversationApplicationService.getConversationByCid(userAgentDto.getLastConversationId()) == null) {
                        userAgentDto.setLastConversationId(null);
                    }
                }
            } catch (Exception e) {
                // 忽略
            }
            return userAgentDto;
        }).collect(Collectors.toList());
        userAgentList = filterOfflineAgent(userAgentList, kw);
        if (StringUtils.isBlank(kw)) {
            return userAgentList;
        }
        //根据pageIndex, size返回
        return userAgentList.stream().skip((long) (pageIndex - 1) * size).limit(size).collect(Collectors.toList());
    }

    @Override
    public List<UserAgentDto> queryRecentUseList(Long userId, Integer size) {
        List<UserTargetRelation> userTargetRelations = userTargetRelationDomainService.queryRecentUseList(userId, Published.TargetType.Agent, size, 1);
        return userTargetRelations.stream().map(this::converToUserAgentDto).collect(Collectors.toList());
    }

    private UserAgentDto converToUserAgentDto(UserTargetRelation userTargetRelation) {
        UserAgentDto userAgentDto = new UserAgentDto();
        userAgentDto.setId(userTargetRelation.getId());
        userAgentDto.setUserId(userTargetRelation.getUserId());
        userAgentDto.setAgentId(userTargetRelation.getTargetId());
        userAgentDto.setCreated(userTargetRelation.getCreated());
        userAgentDto.setModified(userTargetRelation.getModified());
        try {
            if (userTargetRelation.getExtra() != null && JSON.isValidObject(userTargetRelation.getExtra())) {
                JSONObject extra = JSON.parseObject(userTargetRelation.getExtra());
                userAgentDto.setLastConversationId(extra.getLong("conversationId"));
            }
        } catch (Exception e) {
            // 忽略
        }
        return userAgentDto;
    }

    @Override
    public UserAgentDto queryUserAgentRecentUse(Long userId, Long agentId) {
        UserTargetRelation userTargetRelation = userTargetRelationDomainService.queryRecentUse(userId, Published.TargetType.Agent, agentId);
        if (userTargetRelation == null) {
            return null;
        }
        return converToUserAgentDto(userTargetRelation);
    }

    // 过滤已下线的智能体
    private List<UserAgentDto> filterOfflineAgent(List<UserAgentDto> userAgentList, String kw) {
        // 根据userAgentList中的agentId查询已发布的智能体列表
        List<PublishedDto> publishedList = publishApplicationService.queryPublishedList(Published.TargetType.Agent,
                userAgentList.stream().map(UserAgentDto::getAgentId).collect(Collectors.toList()), kw);
        // agentPublishedList转换成Map，key为agentId
        Map<Long, PublishedDto> agentPublishedMap = publishedList.stream()
                .collect(Collectors.toMap(PublishedDto::getTargetId, publishedDto -> publishedDto, (v1, v2) -> v1));

        return userAgentList.stream().filter(userAgentDto -> {
            // 过滤掉已下线的智能体
            PublishedDto publishedDto = agentPublishedMap.get(userAgentDto.getAgentId());
            if (publishedDto == null) {
                if (StringUtils.isBlank(kw)) {
                    userTargetRelationDomainService.delete(userAgentDto.getId());
                }
                return false;
            }
            userAgentDto.setName(publishedDto.getName());
            userAgentDto.setDescription(publishedDto.getDescription());
            userAgentDto.setIcon(publishedDto.getIcon());
            userAgentDto.setAgentType(publishedDto.getAgentType());
            userAgentDto.setPublishUser(publishedDto.getPublishUser());
            userAgentDto.setStatistics(publishedDto.getStatistics());
            return true;
        }).collect(Collectors.toList());
    }

    @Override
    public List<UserAgentDto> queryCollectionList(Long userId, Integer page, Integer size) {
        List<UserAgentDto> userAgentList = convertUserAgentList(
                userTargetRelationDomainService.queryCollectionList(userId, Published.TargetType.Agent, page, size));
        return filterOfflineAgent(userAgentList, null);
    }

    @Override
    public List<UserAgentDto> queryDevCollectionList(Long userId, Integer page, Integer size) {
        List<UserAgentDto> userAgentList = convertUserAgentList(
                userTargetRelationDomainService.queryDevCollectionList(userId, Published.TargetType.Agent, page, size));
        return filterNoPermissionOrDeletedAgent(userId, userAgentList);
    }

    private List<UserAgentDto> filterNoPermissionOrDeletedAgent(Long userId, List<UserAgentDto> userAgentList) {
        List<Long> userSpaceIds = spaceApplicationService.queryListByUserId(userId).stream().map(SpaceDto::getId).collect(Collectors.toList());
        userAgentList = userAgentList.stream().filter(userAgentDto -> {
            // 过滤掉没有权限编辑的智能体
            if (!userSpaceIds.contains(userAgentDto.getSpaceId())) {
                return false;
            }
            return true;
        }).collect(Collectors.toList());
        Map<Long, AgentConfigDto> agentMap = queryListByIds(userAgentList.stream().map(UserAgentDto::getAgentId).collect(Collectors.toList())).stream()
                .collect(Collectors.toMap(AgentConfigDto::getId, agentConfigDto -> agentConfigDto));
        return userAgentList.stream().filter(userAgentDto -> {
            // 过滤掉已删除的智能体
            AgentConfigDto agentConfigDto = agentMap.get(userAgentDto.getAgentId());
            if (agentConfigDto == null || "PageApp".equals(agentConfigDto.getType())) {
                userTargetRelationDomainService.delete(userAgentDto.getId());
                return false;
            }
            userAgentDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(agentConfigDto.getIcon(), agentConfigDto.getName()));
            return true;
        }).collect(Collectors.toList());
    }

    private List<UserAgentDto> convertUserAgentList(List<UserTargetRelation> userAgentList) {
        // 通过userAgentList的agentId集合查询智能体列表，然后转成以智能体id为key的map
        // 忽略tenantId条件
        RequestContext.addTenantIgnoreEntity(AgentConfig.class);
        Map<Long, AgentConfig> agentConfigMap = agentDomainService.queryListByIds(userAgentList.stream()
                        .map(UserTargetRelation::getTargetId).collect(Collectors.toList())).stream()
                .collect(Collectors.toMap(AgentConfig::getId, agentConfig -> agentConfig));
        RequestContext.removeTenantIgnoreEntity(AgentConfig.class);
        return userAgentList.stream().map(userAgent -> {
            UserAgentDto userAgentDto = new UserAgentDto();
            BeanUtils.copyProperties(userAgent, userAgentDto);
            AgentConfig agentConfig = agentConfigMap.get(userAgent.getTargetId());
            if (agentConfig != null) {
                userAgentDto.setName(agentConfig.getName());
                userAgentDto.setIcon(agentConfig.getIcon());
                userAgentDto.setDescription(agentConfig.getDescription());
                userAgentDto.setSpaceId(agentConfig.getSpaceId());
                userAgentDto.setAgentId(agentConfig.getId());
                try {
                    userAgentDto.setAgentType(agentConfig.getType() == null ? Published.TargetSubType.ChatBot.toString() : agentConfig.getType());
                } catch (Exception e) {
                    // ignore
                }
            }
            return userAgentDto;
        }).collect(Collectors.toList());
    }

    @Override
    public void collect(Long userId, Long agentId) {
        if (publishApplicationService.queryPublishedList(Published.TargetType.Agent, List.of(agentId)).isEmpty()) {
            throw new BizException("该智能体未发布或已下架");
        }
        boolean res = userTargetRelationDomainService.record(userId, Published.TargetType.Agent,
                UserTargetRelation.OpType.Collect, agentId);
        if (res) {
            publishDomainService.incStatisticsCount(Published.TargetType.Agent, agentId,
                    PublishedStatistics.Key.COLLECT_COUNT.getKey(), 1L);
        }
    }

    @Override
    public void unCollect(Long userId, Long agentId) {
        boolean res = userTargetRelationDomainService.unRecord(userId, Published.TargetType.Agent,
                UserTargetRelation.OpType.Collect, agentId);
        if (res) {
            publishDomainService.incStatisticsCount(Published.TargetType.Agent, agentId,
                    PublishedStatistics.Key.COLLECT_COUNT.getKey(), -1L);
        }
    }

    @Override
    public void devCollect(Long userId, Long agentId) {
        userTargetRelationDomainService.record(userId, Published.TargetType.Agent, UserTargetRelation.OpType.DevCollect,
                agentId);
    }

    @Override
    public void unDevCollect(Long userId, Long agentId) {
        userTargetRelationDomainService.unRecord(userId, Published.TargetType.Agent,
                UserTargetRelation.OpType.DevCollect, agentId);
    }

    @Override
    public void like(Long userId, Long agentId) {
        if (publishApplicationService.queryPublishedList(Published.TargetType.Agent, List.of(agentId)).isEmpty()) {
            throw new BizException("该智能体未发布或已下架");
        }
        userTargetRelationDomainService.like(userId, Published.TargetType.Agent, agentId);
    }

    @Override
    public void unLike(Long userId, Long agentId) {
        userTargetRelationDomainService.unLike(userId, Published.TargetType.Agent, agentId);
    }

    @Override
    public void addOrUpdateRecentUsed(Long userId, Long agentId) {
        userTargetRelationDomainService.addOrUpdateRecentUsed(UserTargetRelation.builder().targetType(Published.TargetType.Agent)
                .targetId(agentId).userId(userId).type(UserTargetRelation.OpType.Conversation).build());
    }

    @Override
    public void addOrUpdateRecentUsed(Long userId, Long agentId, Long conversationId) {
        Assert.notNull(conversationId, "conversationId must be non-null");
        userTargetRelationDomainService.addOrUpdateRecentUsed(UserTargetRelation.builder().targetType(Published.TargetType.Agent)
                .targetId(agentId).userId(userId).type(UserTargetRelation.OpType.Conversation)
                .extra(JSON.toJSONString(Map.of("conversationId", conversationId))).build());
    }

    public void buildProxyMcp(AgentConfigDto agentConfigDto, boolean isDev) {
        List<AgentComponentConfigDto> agentComponentConfigList = agentConfigDto.getAgentComponentConfigList();
        List<AgentComponentConfigDto> mcpToolComponentList = agentComponentConfigList.stream().filter(agentComponentConfig -> {
            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Mcp && agentComponentConfig.getTargetConfig() instanceof McpDto mcpDto) {
                return mcpDto.getInstallType() == InstallTypeEnum.COMPONENT;
            }
            return false;
        }).toList();
        McpDto proxyMcp = new McpDto();
        proxyMcp.setName("proxy");
        proxyMcp.setServerName("proxy");
        proxyMcp.setDescription("智能体绑定的所有插件工作流等组件统一代理MCP");
        proxyMcp.setCategory("Proxy");
        proxyMcp.setInstallType(InstallTypeEnum.COMPONENT);
        proxyMcp.setCreatorId(agentConfigDto.getCreatorId());
        proxyMcp.setSpaceId(agentConfigDto.getSpaceId());
        McpConfigDto mcpConfig = new McpConfigDto();
        proxyMcp.setMcpConfig(mcpConfig);
        List<McpComponentDto> components = new ArrayList<>();
        mcpConfig.setComponents(components);
        mcpToolComponentList.forEach(mcpToolComponent -> {
            McpBindConfigDto mcpBindConfigDto = (McpBindConfigDto) mcpToolComponent.getBindConfig();
            try {
                McpDto mcpDto = (McpDto) mcpToolComponent.getTargetConfig();
                McpComponentDto mcpComponentDto = mcpDto.getDeployedConfig().getComponents().stream().filter(component0 -> component0.getToolName().equals(mcpBindConfigDto.getToolName())).findFirst().orElse(null);
                components.add(mcpComponentDto);
            } catch (Exception e) {
                log.error("解析MCP配置错误", e);
            }
        });

        //工作流、插件、知识库、数据表转换
        for (AgentComponentConfigDto componentConfigDto : agentComponentConfigList) {
            if (componentConfigDto.getType() == AgentComponentConfig.Type.Workflow || componentConfigDto.getType() == AgentComponentConfig.Type.Plugin || componentConfigDto.getType() == AgentComponentConfig.Type.Knowledge || componentConfigDto.getType() == AgentComponentConfig.Type.Table) {
                McpComponentDto mcpComponentDto = new McpComponentDto();
                mcpComponentDto.setName(componentConfigDto.getName());
                mcpComponentDto.setDescription(componentConfigDto.getDescription());
                mcpComponentDto.setIcon(componentConfigDto.getIcon());
                mcpComponentDto.setType(McpComponentTypeEnum.valueOf(componentConfigDto.getType().name()));
                mcpComponentDto.setTargetId(componentConfigDto.getTargetId());
                if (componentConfigDto.getType() == AgentComponentConfig.Type.Plugin) {
                    PluginDto pluginDto = (PluginDto) componentConfigDto.getTargetConfig();
                    PluginBindConfigDto bindConfig = (PluginBindConfigDto) componentConfigDto.getBindConfig();
                    mcpComponentDto.setToolName(pluginDto.getFunctionName());
                    mcpComponentDto.setTargetBindConfig(JsonSerializeUtil.toJSONStringGeneric(bindConfig));
                }
                if (componentConfigDto.getType() == AgentComponentConfig.Type.Workflow) {
                    WorkflowConfigDto workflowConfigDto = (WorkflowConfigDto) componentConfigDto.getTargetConfig();
                    WorkflowBindConfigDto bindConfig = (WorkflowBindConfigDto) componentConfigDto.getBindConfig();
                    mcpComponentDto.setToolName(workflowConfigDto.getFunctionName());
                    mcpComponentDto.setTargetBindConfig(JsonSerializeUtil.toJSONStringGeneric(bindConfig));
                }
                components.add(mcpComponentDto);
            }
        }
        if (components.isEmpty()) {
            log.info("没有需要代理的组件, agentName {}", agentConfigDto.getName());
            return;
        }

        Long proxyMcpId = null;
        Map<String, Object> extra = agentConfigDto.getExtra();
        if (extra == null) {
            extra = new HashMap<>();
        }
        if (isDev) {
            Object devProxyMcpId = extra.get("devProxyMcpId");
            if (devProxyMcpId != null) {
                try {
                    proxyMcpId = Long.parseLong(devProxyMcpId.toString());
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        } else {
            Object prodProxyMcpId = extra.get("prodProxyMcpId");
            if (prodProxyMcpId != null) {
                try {
                    proxyMcpId = Long.parseLong(prodProxyMcpId.toString());
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }
        if (proxyMcpId != null) {
            proxyMcp.setId(proxyMcpId);
        }
        Long id = mcpRpcService.deployProxyMcp(proxyMcp);
        if (proxyMcpId == null) {
            extra.put(isDev ? "devProxyMcpId" : "prodProxyMcpId", id);
            agentConfigDto.setExtra(extra);
            AgentConfigDto updatedAgentConfigDto = new AgentConfigDto();
            updatedAgentConfigDto.setId(agentConfigDto.getId());
            updatedAgentConfigDto.setExtra(extra);
            update(agentConfigDto);
        }

    }

    @DSTransactional
    @Override
    public void updateAccessControlStatus(Long agentId, Integer status) {
        // 先查询原始状态，用于判断是否从「受限」变为「不受限」
        AgentConfig originConfig = agentDomainService.queryById(agentId);
        if (originConfig == null) {
            throw new BizException("数据不存在,id=" + agentId);
        }

        int oldStatus = originConfig.getAccessControl() != null ? originConfig.getAccessControl() : 0;
        int newStatus = status == null ? 0 : status;

        AgentConfig agentConfigUpdate = new AgentConfig();
        agentConfigUpdate.setId(agentId);
        agentConfigUpdate.setAccessControl(newStatus);
        agentDomainService.update(agentConfigUpdate);
        publishApplicationService.updateAccessControlStatus(Published.TargetType.Agent, agentId, status);

        // 根据 AgentConfig 类型区分主体类型：通用智能体 / 网页应用
        PermissionSubjectTypeEnum subjectTypeEnum = PermissionSubjectTypeEnum.AGENT;
        if (Published.TargetSubType.PageApp.name().equals(originConfig.getType())) {
            subjectTypeEnum = PermissionSubjectTypeEnum.PAGE;
        }

        // 如果从受限(1)切换为不受限(0)，需要删除原有主体访问权限绑定并清除缓存
        if (oldStatus == 1 && newStatus == 0) {
            UserContext userContext = null;
            if (RequestContext.get() != null && RequestContext.get().getUser() instanceof UserContext) {
                userContext = (UserContext) RequestContext.get().getUser();
            }
            // 不设置 roleIds 和 groupIds，内部会按空集合处理，表示清空所有绑定
            sysSubjectPermissionApplicationService.bindRestrictionTargets(
                    subjectTypeEnum,
                    agentId,
                    new BindRestrictionTargetsDto(),
                    userContext
            );
        }
    }

    @Override
    public Long countUserCreatedAgent(Long userId) {
        return agentDomainService.countUserCreatedAgent(userId);
    }

    @Override
    public Long countUserCreatedPageApp(Long userId) {
        return agentDomainService.countUserCreatedPageApp(userId);
    }

    private Map<Long, StatisticsDto> getAgentStatisticsMapByAgentIds(List<Long> agentIds) {
        List<StatisticsDto> agentStatisticsList = publishDomainService
                .queryStatisticsCountList(Published.TargetType.Agent, agentIds);
        return agentStatisticsList.stream().map(agentStatistics -> {
            StatisticsDto agentStatisticsDto = new StatisticsDto();
            BeanUtils.copyProperties(agentStatistics, agentStatisticsDto);
            return agentStatisticsDto;
        }).collect(Collectors.toMap(StatisticsDto::getTargetId, agentStatistics -> agentStatistics));
    }
}
