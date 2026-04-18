package com.xspaceagi.agent.core.application.api;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.*;
import com.xspaceagi.agent.core.adapter.dto.config.bind.ModelBindConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.bind.PluginBindConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.bind.WorkflowBindConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.CodePluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.HttpPluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.*;
import com.xspaceagi.agent.core.adapter.repository.CopyIndexRecordRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.*;
import com.xspaceagi.agent.core.domain.service.WorkflowDomainService;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.agent.dto.AgentExecuteResult;
import com.xspaceagi.agent.core.infra.component.knowledge.KnowledgeBaseSearcher;
import com.xspaceagi.agent.core.infra.component.knowledge.SearchContext;
import com.xspaceagi.agent.core.infra.component.plugin.PluginContext;
import com.xspaceagi.agent.core.infra.component.plugin.PluginExecutor;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowExecutor;
import com.xspaceagi.agent.core.infra.component.workflow.enums.NodeExecuteStatus;
import com.xspaceagi.agent.core.infra.converter.ArgConverter;
import com.xspaceagi.agent.core.infra.rpc.CustomPageRpcService;
import com.xspaceagi.agent.core.infra.rpc.DbTableRpcService;
import com.xspaceagi.agent.core.infra.rpc.KnowledgeRpcService;
import com.xspaceagi.agent.core.infra.rpc.McpRpcService;
import com.xspaceagi.agent.core.infra.rpc.dto.PageDto;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.dto.*;
import com.xspaceagi.agent.core.sdk.dto.AgentOutputDto;
import com.xspaceagi.agent.core.sdk.dto.PluginExecuteRequestDto;
import com.xspaceagi.agent.core.sdk.dto.WorkflowExecuteRequestDto;
import com.xspaceagi.agent.core.sdk.enums.TargetTypeEnum;
import com.xspaceagi.agent.core.sdk.enums.WfExecuteResultTypeEnum;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.agent.core.spec.enums.PluginTypeEnum;
import com.xspaceagi.agent.core.spec.utils.CopyRelationCacheUtil;
import com.xspaceagi.compose.sdk.request.DorisTableDefineRequest;
import com.xspaceagi.compose.sdk.service.IComposeDbTableRpcService;
import com.xspaceagi.compose.sdk.vo.define.CreateTableDefineVo;
import com.xspaceagi.compose.sdk.vo.define.TableDefineVo;
import com.xspaceagi.custompage.sdk.dto.DataSourceDto;
import com.xspaceagi.knowledge.sdk.request.KnowledgeCreateRequestVo;
import com.xspaceagi.mcp.sdk.dto.McpConfigDto;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.utils.IPUtil;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.xspaceagi.agent.core.spec.constant.Prompts.PERSONAL_COMPUTER_ASSISTANT_PROMPT;

@Slf4j
@Service
public class AgentApiServiceImpl implements IAgentRpcService, TemplateExportOrImportService {

    // Common authentication field name keywords
    private static final List<String> AUTH_FIELD_KEYWORDS = Arrays.asList(
            "password", "passwd", "pwd", "token", "secret", "auth", "key", "credential", "session", "ak", "sk", "access"
    );

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private WorkflowDomainService workflowDomainService;

    @Resource
    private DbTableRpcService dbTableRpcService;

    @Resource
    private KnowledgeRpcService knowledgeRpcService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private McpRpcService mcpRpcService;

    @Resource
    private IComposeDbTableRpcService iComposeDbTableRpcService;

    @Resource
    private CopyIndexRecordRepository copyIndexRecordRepository;

    @Resource
    private CustomPageRpcService customPageRpcService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private SkillApplicationService skillApplicationService;

    @Resource
    private WorkflowExecutor workflowExecutor;

    @Resource
    private KnowledgeBaseSearcher knowledgeBaseSearcher;

    @Resource
    private PluginExecutor pluginExecutor;

    @Override
    public ReqResult<List<AgentInfoDto>> queryAgentInfoList(List<Long> agentIds) {
        List<AgentInfoDto> collect = agentApplicationService.queryListByIds(agentIds).stream().map(agentConfig -> {
            AgentInfoDto agentInfoDto = AgentInfoDto.builder().build();
            BeanUtils.copyProperties(agentConfig, agentInfoDto);
            return agentInfoDto;
        }).collect(Collectors.toList());
        return ReqResult.success(collect);
    }

    @Override
    public ReqResult<AgentInfoDto> queryPublishedAgentInfo(Long agentId) {
        AgentDetailDto agentDetailDto = agentApplicationService.queryAgentDetail(agentId, true);
        if (agentDetailDto == null) {
            return ReqResult.error("Agent not found or not published");
        }
        AgentInfoDto agentInfoDto = new AgentInfoDto();
        if (agentDetailDto.getVariables() != null) {
            agentInfoDto.setVariables(agentDetailDto.getVariables().stream().map(arg -> convertToArgDto(arg)).collect(Collectors.toList()));
        }
        agentInfoDto.setId(agentDetailDto.getAgentId());
        agentInfoDto.setName(agentDetailDto.getName());
        agentInfoDto.setDescription(agentDetailDto.getDescription());
        agentInfoDto.setIcon(agentDetailDto.getIcon());
        agentInfoDto.setSpaceId(agentDetailDto.getSpaceId());
        return ReqResult.success(agentInfoDto);
    }

    @Override
    public ReqResult<AgentPublishedPermissionDto> queryAgentPublishedPermission(Long agentId) {
        PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(Published.TargetType.Agent, agentId);
        AgentPublishedPermissionDto agentPublishedPermissionDto = new AgentPublishedPermissionDto();
        BeanUtils.copyProperties(publishedPermissionDto, agentPublishedPermissionDto);
        return ReqResult.success(agentPublishedPermissionDto);
    }

    @Override
    public ReqResult<String> queryPluginConfig(Long pluginId, String paramJson) {
        PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(pluginId, null);
        if (pluginDto == null) {
            return ReqResult.error("Plugin not found");
        }
        PluginConfigDto pluginConfigDto = (PluginConfigDto) pluginDto.getConfig();
        Map<String, Arg> argMap = new HashMap<>();
        Arg.generateKey(null, pluginConfigDto.getInputArgs(), argMap);
        Map<String, String> params = paramJsonToMap(paramJson);
        for (String key : params.keySet()) {
            Arg arg = argMap.get(key);
            if (arg != null) {
                arg.setBindValue(null);
            }
        }
        return ReqResult.success(JSON.toJSONString(pluginDto));
    }

    @Override
    @DSTransactional
    public ReqResult<Long> pluginEnableOrUpdate(PluginEnableOrUpdateDto pluginStartOrUpdateDto) {
        PluginDto pluginDto = JSONObject.parseObject(pluginStartOrUpdateDto.getConfig(), PluginDto.class);
        Assert.notNull(pluginDto, "Plugin configuration cannot be null");
        Assert.notNull(pluginDto.getType(), "Plugin type exception");
        if (StringUtils.isNotBlank(pluginStartOrUpdateDto.getIcon())) {
            pluginDto.setIcon(pluginStartOrUpdateDto.getIcon());
        }
        if (StringUtils.isNotBlank(pluginStartOrUpdateDto.getName())) {
            pluginDto.setName(pluginStartOrUpdateDto.getName());
        }
        PluginConfigDto pluginConfigDto;
        if (pluginDto.getType() == PluginTypeEnum.HTTP) {
            pluginConfigDto = ((JSONObject) pluginDto.getConfig()).toJavaObject(HttpPluginConfigDto.class);
        } else {
            pluginConfigDto = ((JSONObject) pluginDto.getConfig()).toJavaObject(CodePluginConfigDto.class);
        }
        pluginDto.setConfig(pluginConfigDto);
        Map<String, String> params = paramJsonToMap(pluginStartOrUpdateDto.getParamJson());
        if (params.size() > 0) {
            Map<String, Arg> argMap = new HashMap<>();
            Arg.generateKey(null, pluginConfigDto.getInputArgs(), argMap);
            for (String key : params.keySet()) {
                Arg arg = argMap.get(key);
                if (arg != null) {
                    arg.setBindValue(params.get(key));
                    arg.setBindValueType(Arg.BindValueType.Input);
                    arg.setEnable(false);
                }
            }
        }

        Long spaceId = pluginStartOrUpdateDto.getSpaceId() == null ? -1L : pluginStartOrUpdateDto.getSpaceId();
        Long pluginId = pluginStartOrUpdateDto.getPluginId();
        if (pluginStartOrUpdateDto.getPluginId() == null) {
            PluginAddDto pluginAddDto = new PluginAddDto();
            pluginAddDto.setCreatorId(pluginStartOrUpdateDto.getUserId());
            pluginAddDto.setSpaceId(spaceId);
            pluginAddDto.setName(pluginDto.getName());
            pluginAddDto.setDescription(pluginDto.getDescription());
            pluginAddDto.setType(pluginDto.getType());
            pluginAddDto.setCodeLang(pluginDto.getCodeLang());
            pluginAddDto.setIcon(generateIcon(pluginDto.getIcon()));
            pluginId = pluginApplicationService.add(pluginAddDto);
        }
        PluginUpdateDto pluginUpdateDto = new PluginUpdateDto<>();
        pluginUpdateDto.setId(pluginId);
        pluginUpdateDto.setConfig(pluginConfigDto);
        pluginUpdateDto.setDescription(pluginDto.getDescription());
        pluginUpdateDto.setIcon(generateIcon(pluginDto.getIcon()));
        pluginApplicationService.update(pluginUpdateDto);
        pluginDto.setId(pluginId);
        pluginDto.setSpaceId(spaceId);
        pluginDto.setCreatorId(pluginStartOrUpdateDto.getUserId());

        PublishApplyDto publishApply = new PublishApplyDto();
        publishApply.setSpaceId(spaceId);
        publishApply.setTargetId(pluginId);
        publishApply.setTargetType(Published.TargetType.Plugin);
        publishApply.setApplyUser(userApplicationService.queryById(pluginStartOrUpdateDto.getUserId()));
        publishApply.setName(pluginDto.getName());
        publishApply.setDescription(pluginDto.getDescription());
        publishApply.setIcon(pluginDto.getIcon());
        publishApply.setChannels(List.of(Published.PublishChannel.System));
        publishApply.setScope(spaceId == -1l ? Published.PublishScope.Tenant : Published.PublishScope.Space);
        publishApply.setCategory(pluginStartOrUpdateDto.getCategory() == null ? CategoryDto.PluginCategoryEnum.Other.getName() : pluginStartOrUpdateDto.getCategory());
        publishApply.setTargetConfig(pluginDto);
        Long applyId = publishApplicationService.publishApply(publishApply);
        publishApplicationService.publish(applyId);
        return ReqResult.success(pluginId);
    }

    private Map<String, String> paramJsonToMap(String paramJson) {
        Map<String, String> params = new HashMap<>();
        if (paramJson != null) {
            if (JSON.isValidArray(paramJson)) {
                JSONArray jsonArray = JSON.parseArray(paramJson);
                if (jsonArray != null) {
                    jsonArray.forEach(item -> {
                        if (item instanceof JSONObject) {
                            JSONObject jsonObject = (JSONObject) item;
                            params.put(jsonObject.getString("name"), jsonObject.getString("value"));
                        }
                    });
                }
            }
        }
        return params;
    }

    @Override
    public ReqResult<Void> disablePlugin(Long pluginId) {
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Plugin, pluginId);
        if (publishedDto != null) {
            OffShelfDto offShelfDto = new OffShelfDto();
            offShelfDto.setPublishId(publishedDto.getId());
            offShelfDto.setReason("Ecosystem market disable");
            publishApplicationService.offShelf(offShelfDto);
        }
        return ReqResult.success();
    }

    @Override
    public ReqResult<String> queryTemplateConfig(TargetTypeEnum targetType, Long targetId) {
        if (targetType == TargetTypeEnum.Workflow) {
            WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(targetId, null, true);
            if (workflowConfigDto == null) {
                return ReqResult.error("Workflow not found");
            }
            resetNodesConfig(workflowConfigDto.getNodes());
            return ReqResult.success(JSONObject.toJSONString(workflowConfigDto));
        }
        if (targetType == TargetTypeEnum.Agent) {
            AgentConfigDto agentConfigDto = agentApplicationService.queryPublishedConfig(targetId, true);
            resetAgentComponents(agentConfigDto);
            return ReqResult.success(JSONObject.toJSONString(agentConfigDto));
        }
        return null;
    }

    private void resetAgentComponents(AgentConfigDto agentConfigDto) {
        agentConfigDto.getAgentComponentConfigList().forEach(agentComponentConfigDto -> {
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Model) {
                agentComponentConfigDto.setTargetId(-1L);
                agentComponentConfigDto.setTargetConfig(null);
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Workflow) {
                WorkflowConfigDto workflowConfigDto = ((WorkflowConfigDto) agentComponentConfigDto.getTargetConfig());
                resetNodesConfig(workflowConfigDto.getNodes());
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Plugin) {
                PluginConfigDto pluginConfigDto = (PluginConfigDto) ((PluginDto) agentComponentConfigDto.getTargetConfig()).getConfig();
                resetPluginArgs(pluginConfigDto.getInputArgs());
                PluginBindConfigDto pluginBindConfigDto = (PluginBindConfigDto) agentComponentConfigDto.getBindConfig();
                resetPluginArgs(pluginBindConfigDto.getInputArgBindConfigs());
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Mcp) {
                McpDto mcpDto = (McpDto) agentComponentConfigDto.getTargetConfig();
                if (mcpDto != null) {
                    resetMcpServerConfig(mcpDto.getMcpConfig());
                    resetMcpServerConfig(mcpDto.getDeployedConfig());
                }
            }
            //Knowledge base configuration does not need to be read
            //Data table reads table structure
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Table) {
                Long targetId1 = agentComponentConfigDto.getTargetId();
                if (targetId1 != null) {
                    CreateTableDefineVo createTableDefineVo = dbTableRpcService.queryCreateTableInfo(targetId1);
                    if (createTableDefineVo != null) {
                        agentComponentConfigDto.setTargetConfig(createTableDefineVo);
                    }
                }
            }
        });
    }

    private void resetMcpServerConfig(McpConfigDto mcpConfig) {
        if (mcpConfig == null) {
            return;
        }
        if (mcpConfig.getServerConfig() != null && JSON.isValid(mcpConfig.getServerConfig())) {
            JSONObject jsonObject = JSON.parseObject(mcpConfig.getServerConfig());
            //Check env field for environment variables
            checkAndRemoveEnvKeys("", jsonObject);
            mcpConfig.setServerConfig(jsonObject.toJSONString());
        }
    }

    private void checkAndRemoveEnvKeys(String pKey, JSONObject jsonObject) {
        jsonObject.keySet().forEach(key -> {
            if (!(jsonObject.get(key) instanceof JSONObject)) {
                if (pKey.toLowerCase().equals("env")) {
                    boolean isNameSuspicious = AUTH_FIELD_KEYWORDS.stream()
                            .anyMatch(keyword -> key.toLowerCase().contains(keyword));
                    if (isNameSuspicious) {
                        jsonObject.put(key, "<Authentication information>");
                    }
                }
                return;
            }
            checkAndRemoveEnvKeys(key, jsonObject.getJSONObject(key));
        });
    }

    @Override
    @DSTransactional
    public ReqResult<Long> templateEnableOrUpdate(TemplateEnableOrUpdateDto templateEnableOrUpdateDto) {
        UserDto userDto = userApplicationService.queryById(templateEnableOrUpdateDto.getUserId());
        if (userDto == null) {
            return ReqResult.error("User not found");
        }
        if (templateEnableOrUpdateDto.getTargetType() == TargetTypeEnum.Workflow) {
            if (templateEnableOrUpdateDto.getTargetId() != null && templateEnableOrUpdateDto.getTargetId() > 0) {
                WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(templateEnableOrUpdateDto.getTargetId());
                if (workflowConfigDto == null) {
                    return ReqResult.error("Workflow ID error");
                }
                workflowApplicationService.delete(workflowConfigDto.getId());
            }

            WorkflowConfigDto workflowConfigDto = WorkflowConfigDto.convertToWorkflowConfigDto(templateEnableOrUpdateDto.getConfig());
            if (workflowConfigDto == null) {
                return ReqResult.error("Template configuration exception, temporarily unavailable");
            }
            if (StringUtils.isNotBlank(templateEnableOrUpdateDto.getName())) {
                workflowConfigDto.setName(templateEnableOrUpdateDto.getName());
            }
            if (StringUtils.isNotBlank(templateEnableOrUpdateDto.getIcon())) {
                workflowConfigDto.setIcon(templateEnableOrUpdateDto.getIcon());
            }
            Long spaceId = templateEnableOrUpdateDto.getSpaceId() == null ? -1L : templateEnableOrUpdateDto.getSpaceId();
            Long newWorkflowId = copyAndPublishWorkflowConfig(userDto, workflowConfigDto, spaceId, templateEnableOrUpdateDto.getCategory(), Published.PublishScope.Tenant);
            return ReqResult.success(newWorkflowId);
        }
        if (templateEnableOrUpdateDto.getTargetType() == TargetTypeEnum.Agent) {
            if (templateEnableOrUpdateDto.getTargetId() != null && templateEnableOrUpdateDto.getTargetId() > 0) {
                AgentConfigDto agentConfigDto = agentApplicationService.queryById(templateEnableOrUpdateDto.getTargetId());
                if (agentConfigDto == null) {
                    return ReqResult.error("Agent ID error");
                }
                agentApplicationService.delete(agentConfigDto.getId());
            }
            AgentConfigDto agentConfigDto = JSON.parseObject(templateEnableOrUpdateDto.getConfig(), AgentConfigDto.class);
            if (agentConfigDto == null) {
                return ReqResult.error("Template configuration exception, temporarily unavailable");
            }
            if (StringUtils.isNotBlank(templateEnableOrUpdateDto.getName())) {
                agentConfigDto.setName(templateEnableOrUpdateDto.getName());
            }
            if (StringUtils.isNotBlank(templateEnableOrUpdateDto.getIcon())) {
                agentConfigDto.setIcon(templateEnableOrUpdateDto.getIcon());
            }
            Long agentId = newAgent(userDto, agentConfigDto, -1L);
            agentConfigDto = agentApplicationService.queryById(agentId);
            PublishApplyDto publishApply = new PublishApplyDto();
            publishApply.setSpaceId(-1L);
            publishApply.setTargetId(agentId);
            publishApply.setTargetType(Published.TargetType.Agent);
            publishApply.setApplyUser(userDto);
            publishApply.setName(agentConfigDto.getName());
            publishApply.setDescription(agentConfigDto.getDescription());
            publishApply.setIcon(generateIcon(agentConfigDto.getIcon()));
            publishApply.setChannels(List.of(Published.PublishChannel.System));
            publishApply.setScope(Published.PublishScope.Tenant);
            publishApply.setCategory(templateEnableOrUpdateDto.getCategory() == null ? CategoryDto.PluginCategoryEnum.Other.getName() : templateEnableOrUpdateDto.getCategory());
            publishApply.setTargetConfig(agentConfigDto);
            publishApply.setAllowCopy(YesOrNoEnum.Y.getKey());
            publishApply.setOnlyTemplate(YesOrNoEnum.Y.getKey());
            Long applyId = publishApplicationService.publishApply(publishApply);
            publishApplicationService.publish(applyId);
            return ReqResult.success(agentId);
        }
        return null;
    }

    private Long newAgent(UserDto userDto, AgentConfigDto agentConfigDto, long spaceId) {
        agentConfigDto.getAgentComponentConfigList().forEach(agentComponentConfigDto -> {
            AgentConfigDto.convertBindConfig(agentComponentConfigDto);
            agentComponentConfigDto.setTenantId(null);
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Model) {
                agentComponentConfigDto.setTargetId(defaultModelId());
                ModelBindConfigDto modelBindConfigDto = (ModelBindConfigDto) agentComponentConfigDto.getBindConfig();
                if (modelBindConfigDto != null) {
                    modelBindConfigDto.setReasoningModelId(null);
                }
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Workflow) {
                if (agentComponentConfigDto.getTargetConfig() != null && JSON.isValid(agentComponentConfigDto.getTargetConfig().toString())) {
                    WorkflowConfigDto workflowConfigDto = WorkflowConfigDto.convertToWorkflowConfigDto(agentComponentConfigDto.getTargetConfig().toString());
                    agentComponentConfigDto.setTargetConfig(workflowConfigDto);
                }
                WorkflowConfigDto workflowConfigDto = ((WorkflowConfigDto) agentComponentConfigDto.getTargetConfig());
                Long aLong = copyAndPublishWorkflowConfig(userDto, workflowConfigDto, spaceId, workflowConfigDto.getCategory(), Published.PublishScope.Space);
                agentComponentConfigDto.setTargetId(aLong);
                agentComponentConfigDto.setTargetConfig(null);
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Plugin) {
                if (agentComponentConfigDto.getTargetConfig() != null && (agentComponentConfigDto.getTargetConfig() instanceof JSONObject)) {
                    JSONObject pluginConfigJSONObject = (JSONObject) agentComponentConfigDto.getTargetConfig();
                    PluginDto pluginDto = pluginConfigJSONObject.toJavaObject(PluginDto.class);
                    if (pluginDto.getType() == PluginTypeEnum.HTTP) {
                        pluginDto.setConfig(pluginConfigJSONObject.getJSONObject("config").toJavaObject(HttpPluginConfigDto.class));
                    } else {
                        pluginDto.setConfig(pluginConfigJSONObject.getJSONObject("config").toJavaObject(CodePluginConfigDto.class));
                    }
                    agentComponentConfigDto.setTargetConfig(pluginDto);
                }
                PluginDto pluginDto = (PluginDto) agentComponentConfigDto.getTargetConfig();
                Long aLong = copyAndPublishPlugin(userDto, pluginDto, spaceId, pluginDto.getCategory());
                agentComponentConfigDto.setTargetId(aLong);
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Table) {
                if (agentComponentConfigDto.getTargetConfig() != null) {
                    Long tableId = dbTableRpcService.createNewTableDefinition(userDto.getId(), spaceId, agentComponentConfigDto.getTargetId(), agentComponentConfigDto.getTargetConfig().toString());
                    if (tableId != null) {
                        agentComponentConfigDto.setTargetId(tableId);
                        agentComponentConfigDto.setTargetConfig(null);
                    }
                }
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Knowledge) {
                KnowledgeCreateRequestVo knowledgeCreateRequestVo = KnowledgeCreateRequestVo.builder()
                        .dataType(1)
                        .name(agentComponentConfigDto.getName())
                        .description(agentComponentConfigDto.getDescription())
                        .icon(agentComponentConfigDto.getIcon())
                        .userId(userDto.getId())
                        .spaceId(spaceId)
                        .build();
                Long knowledgeConfigId = knowledgeRpcService.createKnowledgeConfig(knowledgeCreateRequestVo, agentComponentConfigDto.getTargetId());
                agentComponentConfigDto.setTargetId(knowledgeConfigId);
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Mcp) {
                if (agentComponentConfigDto.getTargetConfig() != null) {
                    Object mcp = agentComponentConfigDto.getTargetConfig();
                    if (mcp != null && mcp instanceof JSONObject) {
                        McpDto mcpDto = ((JSONObject) mcp).toJavaObject(McpDto.class);
                        Long aLong = mcpRpcService.addAndDeployMcp(userDto.getId(), spaceId, mcpDto);
                        agentComponentConfigDto.setTargetId(aLong);
                        agentComponentConfigDto.setTargetConfig(null);
                    }
                    if (mcp != null && mcp instanceof McpDto) {
                        McpDto mcpDto = (McpDto) mcp;
                        Long aLong = mcpRpcService.addAndDeployMcp(userDto.getId(), spaceId, mcpDto);
                        agentComponentConfigDto.setTargetId(aLong);
                        agentComponentConfigDto.setTargetConfig(null);
                    }
                }
            }
        });
        //Filter out Agent type configurations in agentConfigDto.getAgentComponentConfigList()
        agentConfigDto.getAgentComponentConfigList().removeIf(agentComponentConfigDto -> agentComponentConfigDto.getType() == AgentComponentConfig.Type.Agent);
        agentConfigDto.setTenantId(null);
        agentConfigDto.setSpaceId(spaceId);
        Long agentId = agentApplicationService.importAgent(userDto.getId(), agentConfigDto, spaceId);
        return agentId;
    }


    @Override
    public ExportTemplateDto queryTemplateConfig(Published.TargetType targetType, Long targetId) {
        ExportTemplateDto exportTemplateDto = new ExportTemplateDto();
        exportTemplateDto.setType(targetType.name());
        if (targetType == Published.TargetType.Plugin) {
            PluginDto pluginDto = pluginApplicationService.queryById(targetId);
            if (pluginDto == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPluginNotFound);
            }
            exportTemplateDto.setTemplateConfig(pluginDto);
            exportTemplateDto.setName(pluginDto.getName());
            exportTemplateDto.setSpaceId(pluginDto.getSpaceId());
        }
        if (targetType == Published.TargetType.Workflow) {
            WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryById(targetId);
            if (workflowConfigDto == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowNotFoundSimple);
            }
            List<WorkflowNodeDto> workflowNodeDtos = workflowApplicationService.queryWorkflowNodeListForTestExecute(targetId);
            resetNodesConfig(workflowNodeDtos);
            workflowConfigDto.setNodes(workflowNodeDtos);
            exportTemplateDto.setTemplateConfig(workflowConfigDto);
            exportTemplateDto.setName(workflowConfigDto.getName());
            exportTemplateDto.setSpaceId(workflowConfigDto.getSpaceId());
        }
        if (targetType == Published.TargetType.Agent) {
            AgentConfigDto agentConfigDto = agentApplicationService.queryConfigForTestExecute(targetId);
            if (agentConfigDto == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentNotFound);
            }
            resetAgentComponents(agentConfigDto);
            exportTemplateDto.setTemplateConfig(agentConfigDto);
            exportTemplateDto.setName(agentConfigDto.getName());
            exportTemplateDto.setSpaceId(agentConfigDto.getSpaceId());
        }
        if (targetType == Published.TargetType.Table) {
            DorisTableDefineRequest request = new DorisTableDefineRequest();
            request.setTableId(targetId);
            TableDefineVo dorisTableDefinitionVo;
            try {
                dorisTableDefinitionVo = iComposeDbTableRpcService.queryTableDefinition(request);
            } catch (Exception e) {
                //Ignore
                log.warn("Failed to query table schema definition {}", targetId);
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentTableSchemaQueryFailed);
            }
            exportTemplateDto.setTemplateConfig(dorisTableDefinitionVo);
            exportTemplateDto.setName(dorisTableDefinitionVo.getTableName());
            exportTemplateDto.setSpaceId(dorisTableDefinitionVo.getSpaceId());
        }
        return exportTemplateDto;
    }

    @Override
    public ReqResult<String> queryApiSchema(TargetTypeEnum targetType, Long targetId, Long projectId) {
        PageDto pageDto = customPageRpcService.queryPageDto(projectId, false);
        if (pageDto == null) {
            return ReqResult.error("Page not found");
        }
        List<DataSourceDto> dataSources = pageDto.getDataSources();
        if (dataSources == null || dataSources.isEmpty()) {
            return ReqResult.error("Page data source not found");
        }
        Optional<DataSourceDto> first = dataSources.stream().filter(dataSourceDto -> dataSourceDto.getId() != null && dataSourceDto.getId().equals(targetId)).findFirst();
        if (!first.isPresent()) {
            return ReqResult.error("Data source not found");
        }
        String key = first.get().getKey();
        Map<String, Object> apiSchema = new HashMap<>();
        apiSchema.put("servers", List.of(Map.of("url", "", "description", "Directly use the interface address given in paths, do not append prefixes like `/api` in front of the address")));
        if (targetType == TargetTypeEnum.Plugin) {
            PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(targetId, null);
            if (pluginDto == null) {
                return ReqResult.error("Plugin not found");
            }
            PluginConfigDto pluginConfigDto = (PluginConfigDto) pluginDto.getConfig();
            if (pluginConfigDto == null) {
                return ReqResult.error("Plugin configuration not found");
            }

            List<Arg> inputArgs = pluginConfigDto.getInputArgs();
            List<Arg> outputArgs = List.of(
                    Arg.builder().require(true).name("success").description("Interface call status, success=true only represents successful interface call, business execution logic please pay attention to data definition").dataType(DataTypeEnum.Boolean).build(),
                    Arg.builder().require(true).name("message").description("Error information when interface call fails, such as server internal error").dataType(DataTypeEnum.String).build(),
                    Arg.builder().require(true).name("data").description("Returned specific business data").dataType(DataTypeEnum.Object).subArgs(pluginConfigDto.getOutputArgs()).build()
            );
            Map<String, Object> outputSchema = ArgConverter.convertArgsToJsonSchema(outputArgs);
            Map<String, Object> inputSchema = ArgConverter.convertArgsToJsonSchema(inputArgs);
            apiSchema.put("paths", Map.of(
                    "/api/page/plugin/" + key + "/execute",
                    buildPath(pluginDto.getName(), pluginDto.getDescription(), "application/json", inputSchema, outputSchema)
            ));
        }
        if (targetType == TargetTypeEnum.Workflow) {
            WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(targetId, null, false);
            if (workflowConfigDto == null) {
                return ReqResult.error("Workflow not found");
            }
            String description = workflowConfigDto.getDescription() == null ? "" : workflowConfigDto.getDescription() + "(Note that this interface is a text/event-stream streaming interface, it ends when complete=true appears. When processing data, please note that SSE data starts with data:, and SSE streaming interface is not used by default unless the user explicitly requests to use streaming interface)";
            List<Arg> inputArgs = workflowConfigDto.getInputArgs();
            Map<String, Object> inputSchema = ArgConverter.convertArgsToJsonSchema(inputArgs, true);
            List<Arg> outputArgs = List.of(
                    Arg.builder().require(true).name("success").description("Interface call status, success=true only represents successful interface call, business execution logic please pay attention to data definition").dataType(DataTypeEnum.Boolean).build(),
                    Arg.builder().require(true).name("message").description("Error information when interface call fails, such as server internal error").dataType(DataTypeEnum.String).build(),
                    Arg.builder().require(true).name("data").description("Returned specific business data").dataType(DataTypeEnum.Object).subArgs(workflowConfigDto.getOutputArgs()).build(),
                    Arg.builder().require(true).name("complete").description("Whether workflow execution is completed, just need to pay attention to results when complete=true if no special requirements").dataType(DataTypeEnum.Boolean).build()
            );
            Map<String, Object> streamOutputSchema = ArgConverter.convertArgsToJsonSchema(outputArgs);

            outputArgs = List.of(
                    Arg.builder().require(true).name("success").description("Interface call status, success=true only represents successful interface call, business execution logic please pay attention to data definition").dataType(DataTypeEnum.Boolean).build(),
                    Arg.builder().require(true).name("message").description("Error information when interface call fails, such as server internal error").dataType(DataTypeEnum.String).build(),
                    Arg.builder().require(true).name("data").description("Returned specific business data").dataType(DataTypeEnum.Object).subArgs(workflowConfigDto.getOutputArgs()).build()
            );
            Map<String, Object> outputSchema = ArgConverter.convertArgsToJsonSchema(outputArgs);

            apiSchema.put("paths", Map.of(
                    "/api/page/workflow/" + key + "/streamExecute",
                    buildPath(workflowConfigDto.getName(), description, "text/event-stream", inputSchema, streamOutputSchema),
                    "/api/page/workflow/" + key + "/execute",
                    buildPath(workflowConfigDto.getName(), workflowConfigDto.getDescription() == null ? "" : workflowConfigDto.getDescription(), "application/json", inputSchema, outputSchema)
            ));
        }

        return ReqResult.success(JSON.toJSONString(apiSchema));
    }

    private Object buildPath(String name, String description, String produces, Map<String, Object> inputSchema, Map<String, Object> outputSchema) {
        return Map.of(
                "post",
                Map.of(
                        "summary", name + " interface",
                        "description", description,
                        "requestBody", Map.of(
                                "required", true,
                                "content", Map.of(
                                        "application/json",
                                        Map.of(
                                                "schema", inputSchema
                                        )
                                )
                        ),
                        "responses", Map.of(
                                "200",
                                Map.of(
                                        "description", "Success",
                                        "content", Map.of(
                                                produces,
                                                Map.of(
                                                        "schema", outputSchema
                                                )
                                        )
                                )
                        )
                )
        );
    }

    @Override
    public Long importTemplateConfig(UserDto user, Long spaceId, Published.TargetType targetType, String templateConfig) {
        if (targetType == Published.TargetType.Plugin) {
            PluginConfigDto pluginConfigDto;
            PluginDto pluginDto = JSONObject.parseObject(templateConfig, PluginDto.class);
            Assert.notNull(pluginDto, "Plugin configuration cannot be null");
            Assert.notNull(pluginDto.getType(), "Plugin type exception");
            if (pluginDto.getType() == PluginTypeEnum.HTTP) {
                pluginConfigDto = ((JSONObject) pluginDto.getConfig()).toJavaObject(HttpPluginConfigDto.class);
            } else {
                pluginConfigDto = ((JSONObject) pluginDto.getConfig()).toJavaObject(CodePluginConfigDto.class);
            }
            pluginDto.setConfig(pluginConfigDto);
            PluginAddDto pluginAddDto = new PluginAddDto();
            pluginAddDto.setCreatorId(user.getId());
            pluginAddDto.setSpaceId(spaceId);
            pluginAddDto.setName(copyIndexRecordRepository.newCopyName("Plugin", spaceId, pluginDto.getName()));
            pluginAddDto.setDescription(pluginDto.getDescription());
            pluginAddDto.setType(pluginDto.getType());
            pluginAddDto.setCodeLang(pluginDto.getCodeLang());
            pluginAddDto.setIcon(generateIcon(pluginDto.getIcon()));
            Long pluginId = pluginApplicationService.add(pluginAddDto);
            PluginUpdateDto pluginUpdateDto = new PluginUpdateDto<>();
            pluginUpdateDto.setId(pluginId);
            pluginUpdateDto.setConfig(pluginConfigDto);
            pluginApplicationService.update(pluginUpdateDto);
            return pluginId;
        }
        if (targetType == Published.TargetType.Workflow) {
            WorkflowConfigDto workflowConfigDto = WorkflowConfigDto.convertToWorkflowConfigDto(templateConfig);
            if (workflowConfigDto == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentTemplateConfigInvalid);
            }
            return newWorkflow(user, workflowConfigDto, spaceId);
        }
        if (targetType == Published.TargetType.Table) {
            return dbTableRpcService.createNewTableDefinition(user.getId(), spaceId, templateConfig.hashCode() * 1L, templateConfig);
        }
        if (targetType == Published.TargetType.Agent) {
            AgentConfigDto agentConfigDto = JSON.parseObject(templateConfig, AgentConfigDto.class);
            if (agentConfigDto == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentTemplateConfigInvalid);
            }
            return newAgent(user, agentConfigDto, spaceId);
        }
        return null;
    }

    private Long newWorkflow(UserDto user, WorkflowConfigDto workflowConfigDto, Long spaceId) {
        WorkflowConfig workflowConfig = new WorkflowConfig();
        BeanUtils.copyProperties(workflowConfigDto, workflowConfig);
        workflowConfig.setCreatorId(user.getId());
        //Complete workflow node configuration information
        completeWorkflowNodesConfig(user, workflowConfigDto, spaceId);
        List<WorkflowNodeConfig> workflowNodeConfigs = new ArrayList<>();
        workflowConfigDto.getNodes().forEach(workflowNodeDto -> {
            WorkflowNodeConfig workflowNodeConfig = new WorkflowNodeConfig();
            BeanUtils.copyProperties(workflowNodeDto, workflowNodeConfig);
            workflowNodeConfig.setConfig(JSONObject.toJSONString(workflowNodeDto.getNodeConfig()));
            workflowNodeConfigs.add(workflowNodeConfig);
        });
        return workflowDomainService.copy(user.getId(), workflowConfig, workflowNodeConfigs, spaceId);
    }

    private Long copyAndPublishWorkflowConfig(UserDto userDto, WorkflowConfigDto workflowConfigDto, Long spaceId, String category, Published.PublishScope scope) {
        if (workflowConfigDto == null) {
            return null;
        }
        Long originalWorkflowId = workflowConfigDto.getId();
        Object workflowId = CopyRelationCacheUtil.get(generateCacheKey("Workflow"), spaceId, originalWorkflowId);
        if (workflowId != null) {
            return (Long) workflowId;
        }
        Long newWorkflowId = newWorkflow(userDto, workflowConfigDto, spaceId);
        workflowConfigDto = workflowApplicationService.queryById(newWorkflowId);
        PublishApplyDto publishApply = new PublishApplyDto();
        publishApply.setSpaceId(spaceId);
        publishApply.setTargetId(newWorkflowId);
        publishApply.setTargetType(Published.TargetType.Workflow);
        publishApply.setApplyUser(userDto);
        publishApply.setName(workflowConfigDto.getName());
        publishApply.setDescription(workflowConfigDto.getDescription());
        publishApply.setIcon(generateIcon(workflowConfigDto.getIcon()));
        publishApply.setChannels(List.of(Published.PublishChannel.System));
        publishApply.setScope(scope);
        publishApply.setCategory(category == null ? CategoryDto.PluginCategoryEnum.Other.getName() : category);
        publishApply.setTargetConfig(workflowConfigDto);
        publishApply.setAllowCopy(YesOrNoEnum.Y.getKey());
        if (spaceId > 0) {
            publishApply.setScope(Published.PublishScope.Space);
            publishApply.setOnlyTemplate(YesOrNoEnum.N.getKey());
        } else {
            publishApply.setOnlyTemplate(YesOrNoEnum.Y.getKey());
        }

        Long applyId = publishApplicationService.publishApply(publishApply);
        publishApplicationService.publish(applyId);
        CopyRelationCacheUtil.put(generateCacheKey("Workflow"), spaceId, originalWorkflowId, newWorkflowId);
        return newWorkflowId;
    }

    private void completeWorkflowNodesConfig(UserDto user, WorkflowConfigDto workflowConfigDto, Long spaceId) {
        if (workflowConfigDto == null || CollectionUtils.isEmpty(workflowConfigDto.getNodes())) {
            return;
        }
        workflowConfigDto.getNodes().forEach(workflowNodeDto -> {
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Workflow) {
                WorkflowAsNodeConfigDto workflowAsNodeConfigDto = (WorkflowAsNodeConfigDto) workflowNodeDto.getNodeConfig();
                Long newWorkflowId = copyAndPublishWorkflowConfig(user, workflowAsNodeConfigDto.getWorkflowConfig(), spaceId, workflowAsNodeConfigDto.getWorkflowConfig().getCategory(), Published.PublishScope.Space);
                workflowAsNodeConfigDto.setWorkflowId(newWorkflowId);
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Plugin) {
                PluginNodeConfigDto pluginNodeConfigDto = (PluginNodeConfigDto) workflowNodeDto.getNodeConfig();
                PluginDto pluginDto = pluginNodeConfigDto.getPluginConfig();
                Long pluginId = copyAndPublishPlugin(user, pluginDto, spaceId, pluginDto.getCategory());
                pluginNodeConfigDto.setPluginId(pluginId);
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Knowledge) {
                KnowledgeNodeConfigDto knowledgeNodeConfigDto = (KnowledgeNodeConfigDto) workflowNodeDto.getNodeConfig();
                if (!CollectionUtils.isEmpty(knowledgeNodeConfigDto.getKnowledgeBaseConfigs())) {
                    knowledgeNodeConfigDto.getKnowledgeBaseConfigs().forEach(knowledgeBaseConfigDto -> {
                        KnowledgeCreateRequestVo knowledgeCreateRequestVo = KnowledgeCreateRequestVo.builder()
                                .dataType(1)
                                .name(knowledgeBaseConfigDto.getName())
                                .description(knowledgeBaseConfigDto.getDescription())
                                .icon(knowledgeBaseConfigDto.getIcon())
                                .userId(user.getId())
                                .spaceId(spaceId)
                                .build();
                        Long knowledgeConfigId = knowledgeRpcService.createKnowledgeConfig(knowledgeCreateRequestVo, knowledgeBaseConfigDto.getKnowledgeBaseId());
                        knowledgeBaseConfigDto.setKnowledgeBaseId(knowledgeConfigId);
                    });
                }
            }
            if (workflowNodeDto.getType().name().startsWith("Table")) {
                TableNodeConfigDto tableNodeConfigDto = (TableNodeConfigDto) workflowNodeDto.getNodeConfig();
                if (StringUtils.isNotBlank(tableNodeConfigDto.getOriginalTableConfig())) {
                    Long newTableId = dbTableRpcService.createNewTableDefinition(user.getId(), spaceId, tableNodeConfigDto.getTableId(), tableNodeConfigDto.getOriginalTableConfig());
                    tableNodeConfigDto.setTableId(newTableId);
                }
            }

            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.LLM) {
                LLMNodeConfigDto llmNodeConfigDto = (LLMNodeConfigDto) workflowNodeDto.getNodeConfig();
                Long newModelId = defaultModelId();
                llmNodeConfigDto.setModelId(newModelId);
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.IntentRecognition) {
                IntentRecognitionNodeConfigDto intentRecognitionNodeConfigDto = (IntentRecognitionNodeConfigDto) workflowNodeDto.getNodeConfig();
                intentRecognitionNodeConfigDto.setModelId(defaultModelId());
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.QA) {
                QaNodeConfigDto qaNodeConfigDto = (QaNodeConfigDto) workflowNodeDto.getNodeConfig();
                qaNodeConfigDto.setModelId(defaultModelId());
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Mcp) {
                McpNodeConfigDto mcpNodeConfigDto = (McpNodeConfigDto) workflowNodeDto.getNodeConfig();
                Object mcp = mcpNodeConfigDto.getMcp();
                if (mcp instanceof JSONObject) {
                    McpDto mcpDto = ((JSONObject) mcp).toJavaObject(McpDto.class);
                    Long aLong = mcpRpcService.addAndDeployMcp(user.getId(), spaceId, mcpDto);
                    mcpNodeConfigDto.setMcpId(aLong);
                    mcpNodeConfigDto.setMcp(null);
                }
                if (mcp instanceof McpDto) {
                    McpDto mcpDto = (McpDto) mcp;
                    Long aLong = mcpRpcService.addAndDeployMcp(user.getId(), spaceId, mcpDto);
                    mcpNodeConfigDto.setMcpId(aLong);
                    mcpNodeConfigDto.setMcp(null);
                }
            }
        });
    }

    private Long defaultModelId() {
        ModelConfigDto modelConfigDto = modelApplicationService.queryDefaultModelConfig();
        if (modelConfigDto != null) {
            return modelConfigDto.getId();
        } else {
            return null;
        }
    }

    private Long copyAndPublishPlugin(UserDto user, PluginDto pluginDto, Long spaceId, String category) {
        if (pluginDto == null) {
            return null;
        }
        Long originId = pluginDto.getId();
        Object value = CopyRelationCacheUtil.get(generateCacheKey("Plugin"), spaceId, originId);
        if (value != null) {
            return (Long) value;
        }
        PluginAddDto pluginAddDto = new PluginAddDto();
        pluginAddDto.setName(copyIndexRecordRepository.newCopyName("Plugin", spaceId, pluginDto.getName()));
        pluginAddDto.setDescription(pluginDto.getDescription());
        pluginAddDto.setIcon(generateIcon(pluginDto.getIcon()));
        pluginAddDto.setType(pluginDto.getType());
        pluginAddDto.setCodeLang(pluginDto.getCodeLang());
        pluginAddDto.setSpaceId(spaceId);
        pluginAddDto.setCreatorId(user.getId());
        Long pluginId = pluginApplicationService.add(pluginAddDto);
        pluginDto.setId(pluginId);
        PluginUpdateDto pluginUpdateDto = new PluginUpdateDto();
        pluginUpdateDto.setId(pluginId);
        pluginUpdateDto.setConfig(pluginDto.getConfig());
        pluginApplicationService.update(pluginUpdateDto);

        pluginDto = pluginApplicationService.queryById(pluginId);
        PublishApplyDto publishApply = new PublishApplyDto();
        publishApply.setSpaceId(spaceId);
        publishApply.setTargetId(pluginId);
        publishApply.setTargetType(Published.TargetType.Plugin);
        publishApply.setApplyUser(user);
        publishApply.setName(pluginDto.getName());
        publishApply.setDescription(pluginDto.getDescription());
        publishApply.setIcon(pluginDto.getIcon());
        publishApply.setChannels(List.of(Published.PublishChannel.System));
        publishApply.setScope(Published.PublishScope.Space);
        publishApply.setCategory(category == null ? CategoryDto.PluginCategoryEnum.Other.getName() : category);
        publishApply.setTargetConfig(pluginDto);
        Long applyId = publishApplicationService.publishApply(publishApply);
        publishApplicationService.publish(applyId);
        CopyRelationCacheUtil.put(generateCacheKey("Plugin"), spaceId, originId, pluginId);
        return pluginId;
    }

    @Override
    public ReqResult<Void> disableTemplate(TargetTypeEnum targetType, Long targetId) {
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.valueOf(targetType.name()), targetId);
        if (publishedDto != null) {
            OffShelfDto offShelfDto = new OffShelfDto();
            offShelfDto.setPublishId(publishedDto.getId());
            offShelfDto.setReason("Ecosystem market disable");
            publishApplicationService.offShelf(offShelfDto);
        }
        return ReqResult.success();
    }

    private void resetNodesConfig(List<WorkflowNodeDto> nodes) {
        if (CollectionUtils.isEmpty(nodes)) {
            return;
        }
        nodes.forEach(workflowNodeDto -> {
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.LLM) {
                LLMNodeConfigDto llmNodeConfigDto = (LLMNodeConfigDto) workflowNodeDto.getNodeConfig();
                llmNodeConfigDto.setModelConfig(null);
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.QA) {
                QaNodeConfigDto qaNodeConfigDto = (QaNodeConfigDto) workflowNodeDto.getNodeConfig();
                qaNodeConfigDto.setModelConfig(null);
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.IntentRecognition) {
                IntentRecognitionNodeConfigDto intentRecognitionNodeConfigDto = (IntentRecognitionNodeConfigDto) workflowNodeDto.getNodeConfig();
                intentRecognitionNodeConfigDto.setModelConfig(null);
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Plugin) {
                //If input parameter is authentication field, clear bindValue default value
                PluginNodeConfigDto pluginNodeConfigDto = (PluginNodeConfigDto) workflowNodeDto.getNodeConfig();
                PluginConfigDto pluginConfigDto = (PluginConfigDto) pluginNodeConfigDto.getPluginConfig().getConfig();
                resetPluginArgs(pluginConfigDto.getInputArgs());
            }
            //Knowledge base configuration remains unchanged
            //Data table reads table structure configuration
            if (workflowNodeDto.getType().name().startsWith("Table")) {
                TableNodeConfigDto tableNodeConfigDto = (TableNodeConfigDto) workflowNodeDto.getNodeConfig();
                CreateTableDefineVo createTableDefineVo = dbTableRpcService.queryCreateTableInfo(tableNodeConfigDto.getTableId());
                if (createTableDefineVo != null) {
                    tableNodeConfigDto.setOriginalTableConfig(JSON.toJSONString(createTableDefineVo));
                }
            }

            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Workflow) {
                WorkflowAsNodeConfigDto workflowAsNodeConfigDto = (WorkflowAsNodeConfigDto) workflowNodeDto.getNodeConfig();
                resetNodesConfig(workflowAsNodeConfigDto.getWorkflowConfig().getNodes());
            }
            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Mcp) {
                McpNodeConfigDto mcpNodeConfigDto = (McpNodeConfigDto) workflowNodeDto.getNodeConfig();
                if (mcpNodeConfigDto.getMcp() != null && mcpNodeConfigDto.getMcp() instanceof McpDto) {
                    McpDto mcpDto = (McpDto) mcpNodeConfigDto.getMcp();
                    resetMcpServerConfig(mcpDto.getMcpConfig());
                    resetMcpServerConfig(mcpDto.getDeployedConfig());
                }
            }
        });
    }

    private void resetPluginArgs(List<Arg> inputArgs) {
        if (CollectionUtils.isNotEmpty(inputArgs)) {
            inputArgs.forEach(arg -> {
                if (!arg.getEnable()) {
                    boolean isNameSuspicious = AUTH_FIELD_KEYWORDS.stream()
                            .anyMatch(keyword -> arg.getName().toLowerCase().contains(keyword));
                    if (isNameSuspicious) {
                        arg.setBindValue("<Authentication information>");
                        arg.setEnable(true);
                    }
                }
            });
        }
    }

    private String generateIcon(String iconUrl) {
        //Check whether pluginAddDto.getIcon() is accessible on network
        try {
            if (StringUtils.isNotBlank(iconUrl)) {
                //Check whether it is internal network URL
                if (!IPUtil.isInternalAddress(iconUrl)) {
                    return iconUrl;
                }
            }
        } catch (Exception e) {
            //Ignore
            log.warn("Plugin icon download failed {}", iconUrl);
        }
        return null;
    }

    private String generateCacheKey(String prefix) {
        String key = prefix;
        if (RequestContext.get() != null && RequestContext.get().getRequestId() != null) {
            key = key + ":" + RequestContext.get().getRequestId();
        }
        return key;
    }

    @Override
    public ReqResult<WorkflowInfoDto> getPublishedWorkflowInfo(Long workflowId, Long spaceId) {
        WorkflowConfigDto workflowConfigDto;
        try {
            workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(workflowId, spaceId, true);
        } catch (Exception e) {
            return ReqResult.error(e.getMessage());
        }
        if (workflowConfigDto == null) {
            return ReqResult.success(null);
        }
        WorkflowInfoDto workflowInfoDto = new WorkflowInfoDto();
        BeanUtils.copyProperties(workflowConfigDto, workflowInfoDto);
        if (workflowConfigDto.getOutputArgs() != null) {
            workflowInfoDto.setOutputArgs(workflowConfigDto.getOutputArgs().stream().map(arg -> convertToArgDto(arg)).collect(Collectors.toList()));
        }
        if (workflowConfigDto.getInputArgs() != null) {
            workflowInfoDto.setInputArgs(workflowConfigDto.getInputArgs().stream().map(arg -> convertToArgDto(arg)).collect(Collectors.toList()));
        }

        workflowInfoDto.setConfig(JsonSerializeUtil.toJSONStringGeneric(workflowConfigDto));
        return ReqResult.success(workflowInfoDto);
    }

    @Override
    public ReqResult<PluginInfoDto> getPublishedPluginInfo(Long pluginId, Long spaceId) {
        PluginDto pluginDto;
        try {
            pluginDto = pluginApplicationService.queryPublishedPluginConfig(pluginId, spaceId);
        } catch (Exception e) {
            return ReqResult.error(e.getMessage());
        }
        if (pluginDto == null) {
            return ReqResult.success(null);
        }
        PluginInfoDto pluginInfoDto = new PluginInfoDto();
        BeanUtils.copyProperties(pluginDto, pluginInfoDto);
        PluginConfigDto pluginConfigDto = (PluginConfigDto) pluginDto.getConfig();
        if (pluginConfigDto.getInputArgs() != null) {
            pluginInfoDto.setInputArgs(pluginConfigDto.getInputArgs().stream().map(this::convertToArgDto).collect(Collectors.toList()));
        }
        if (pluginConfigDto.getOutputArgs() != null) {
            pluginInfoDto.setOutputArgs(pluginConfigDto.getOutputArgs().stream().map(this::convertToArgDto).collect(Collectors.toList()));
        }
        pluginInfoDto.setConfig(JsonSerializeUtil.toJSONStringGeneric(pluginDto));
        return ReqResult.success(pluginInfoDto);
    }

    @Override
    public List<ArgDto> parseWorkflowPluginBindArgs(String bindConfig) {
        if (bindConfig != null) {
            try {
                WorkflowBindConfigDto workflowBindConfigDto = (WorkflowBindConfigDto) JsonSerializeUtil.parseObjectGeneric(bindConfig);
                if (workflowBindConfigDto != null) {
                    return workflowBindConfigDto.getArgBindConfigs().stream().map(this::convertToArgDto).collect(Collectors.toList());
                }
            } catch (Exception e) {
                //Ignore
            }
        }
        return List.of();
    }

    @Override
    public List<ArgDto> parseAgentPluginBindArgs(String bindConfig) {
        if (bindConfig != null) {
            try {
                PluginBindConfigDto pluginBindConfigDto = (PluginBindConfigDto) JsonSerializeUtil.parseObjectGeneric(bindConfig);
                if (pluginBindConfigDto != null) {
                    return pluginBindConfigDto.getInputArgBindConfigs().stream().map(this::convertToArgDto).collect(Collectors.toList());
                }
            } catch (Exception e) {
                //Ignore
            }
        }
        return List.of();
    }

    private ArgDto convertToArgDto(Arg arg) {
        return ArgDto.builder()
                .key(arg.getKey())
                .name(arg.getName())
                .description(arg.getDescription())
                .dataType(arg.getDataType())
                .require(arg.isRequire())
                .enable(arg.getEnable())
                .subArgs(arg.getSubArgs() == null ? null : arg.getSubArgs().stream().map(this::convertToArgDto).collect(Collectors.toList()))
                .build();
    }

    @Override
    public Mono<Object> executePlugin(PluginExecuteRequestDto pluginExecuteRequest) {
        PluginDto pluginDto = (PluginDto) JsonSerializeUtil.parseObjectGeneric(pluginExecuteRequest.getConfig());
        if (pluginDto == null) {
            return Mono.error(new IllegalArgumentException("Invalid plugin configuration"));
        }
        UserDto userDto = (UserDto) pluginExecuteRequest.getUser();
        AgentContext agentContext = new AgentContext();
        agentContext.setUser(userDto);
        agentContext.setUserId(pluginExecuteRequest.getUserId());
        agentContext.setRequestId(pluginExecuteRequest.getRequestId());
        if (userDto != null) {
            agentContext.setUid(userDto.getUid());
            agentContext.setUserName(userDto.getNickName() != null ? userDto.getNickName() : userDto.getUserName());
        }

        //For common agent, when user uses workflow, plugin, set parameter reference (temporarily only support system variables, user custom variables in agent cannot be passed)
        if (pluginExecuteRequest.getBindConfig() != null) {
            PluginBindConfigDto pluginBindConfigDto = (PluginBindConfigDto) JsonSerializeUtil.parseObjectGeneric(pluginExecuteRequest.getBindConfig());
            if (pluginBindConfigDto != null) {
                completeArgReferenceBindValue(agentContext, pluginBindConfigDto.getInputArgBindConfigs(), pluginExecuteRequest.getParams());
            }
        }
        PluginContext pluginContext = PluginContext.builder()
                .requestId(pluginExecuteRequest.getRequestId())
                .pluginConfig((PluginConfigDto) pluginDto.getConfig())
                .pluginDto(pluginDto)
                .params(pluginExecuteRequest.getParams())
                .userId(pluginExecuteRequest.getUserId())
                .agentContext(agentContext)
                .test(pluginExecuteRequest.isTest())
                .build();
        return Mono.create(emitter -> pluginExecutor.execute(pluginContext).doOnSuccess(result -> {
            if (result.isSuccess()) {
                emitter.success(result.getResult());
            } else {
                emitter.error(BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        result.getError()));
            }
        }).doOnError(emitter::error).subscribe());
    }

    private void completeArgReferenceBindValue(AgentContext agentContext, List<Arg> inputArgs, Map<String, Object> params) {
        if (inputArgs == null || params == null) {
            return;
        }
        inputArgs.forEach(argBindConfigDto -> {
            if (argBindConfigDto.getEnable() != null && !argBindConfigDto.getEnable()) {
                if (argBindConfigDto.getSubArgs() != null && !argBindConfigDto.getSubArgs().isEmpty()) {
                    Object val = params.get(argBindConfigDto.getName());
                    if (!(val instanceof Map)) {
                        params.put(argBindConfigDto.getName(), new HashMap<>());
                    }
                    completeArgReferenceBindValue(agentContext, argBindConfigDto.getSubArgs(), (Map<String, Object>) params.get(argBindConfigDto.getName()));
                } else {
                    if (argBindConfigDto.getBindValueType() == ArgBindConfigDto.BindValueType.Reference) {
                        Object val = agentContext.getVariableParams().get(argBindConfigDto.getBindValue());
                        if (val != null) {
                            params.put(argBindConfigDto.getName(), val);
                        }
                    } else {
                        Object val = argBindConfigDto.getBindValue();
                        if (val != null) {
                            params.put(argBindConfigDto.getName(), val);
                        }
                    }
                }
            }
        });
    }

    @Override
    public Flux<WorkflowExecuteResultDto> executeWorkflow(WorkflowExecuteRequestDto workflowExecuteRequest) {
        AtomicReference<Disposable> disposable = new AtomicReference<>();
        Flux<WorkflowExecuteResultDto> flux = Flux.create(emitter -> {
            WorkflowConfigDto workflowConfigDto = (WorkflowConfigDto) JsonSerializeUtil.parseObjectGeneric(workflowExecuteRequest.getConfig());
            if (workflowConfigDto == null) {
                emitter.error(BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowNotFoundSimple));
                return;
            }
            UserDto userDto = ((UserDto) workflowExecuteRequest.getUser());
            AgentContext agentContext = new AgentContext();
            agentContext.setUserId(userDto.getId());
            agentContext.setUid(userDto.getUid());
            agentContext.setUserName(userDto.getNickName() != null ? userDto.getNickName() : userDto.getUserName());
            agentContext.setUser(userDto);
            agentContext.setRequestId(workflowExecuteRequest.getRequestId());
            agentContext.setConversationId(workflowExecuteRequest.getConversationId());
            agentContext.setMessage(JSON.toJSONString(workflowExecuteRequest.getParams()));

            agentContext.setOutputConsumer(outputDto -> {
                WorkflowExecuteResultDto workflowExecutingDto = new WorkflowExecuteResultDto();
                workflowExecutingDto.setType(WfExecuteResultTypeEnum.EXECUTING_LOG);
                workflowExecutingDto.setData(outputDto.getData());
                emitter.next(workflowExecutingDto);
            });

            //For common agent, when user uses workflow, plugin, set parameter reference (temporarily only support system variables, user custom variables in agent cannot be passed)
            if (workflowExecuteRequest.getBindConfig() != null) {
                WorkflowBindConfigDto workflowBindConfigDto = (WorkflowBindConfigDto) JsonSerializeUtil.parseObjectGeneric(workflowExecuteRequest.getBindConfig());
                if (workflowBindConfigDto != null) {
                    completeArgReferenceBindValue(agentContext, workflowBindConfigDto.getInputArgBindConfigs(), workflowExecuteRequest.getParams());
                }
            }

            WorkflowContext workflowContext1 = new WorkflowContext();
            workflowContext1.setAgentContext(agentContext);
            workflowContext1.setRequestId(workflowExecuteRequest.getRequestId());
            workflowContext1.setWorkflowConfig(workflowConfigDto);
            workflowContext1.setParams(workflowExecuteRequest.getParams());
            workflowContext1.setNodeExecutingConsumer(nodeExecutingDto -> {
                log.info("Node execution info: {}", nodeExecutingDto);
                if (nodeExecutingDto.getStatus() == NodeExecuteStatus.FINISHED) {
                    WfNodeExecuteResultDto wfNodeExecuteResultDto = new WfNodeExecuteResultDto();
                    wfNodeExecuteResultDto.setSuccess(nodeExecutingDto.getStatus() != NodeExecuteStatus.FAILED);
                    wfNodeExecuteResultDto.setInput(nodeExecutingDto.getResult().getInput());
                    wfNodeExecuteResultDto.setData(nodeExecutingDto.getResult().getData());
                    wfNodeExecuteResultDto.setNodeId(nodeExecutingDto.getResult().getNodeId());
                    wfNodeExecuteResultDto.setNodeName(nodeExecutingDto.getResult().getNodeName());
                    wfNodeExecuteResultDto.setStartTime(nodeExecutingDto.getResult().getStartTime());
                    wfNodeExecuteResultDto.setEndTime(nodeExecutingDto.getResult().getEndTime());
                    wfNodeExecuteResultDto.setError(nodeExecutingDto.getResult().getError());
                    WorkflowExecuteResultDto workflowExecutingDto = new WorkflowExecuteResultDto();
                    workflowExecutingDto.setType(WfExecuteResultTypeEnum.EXECUTING_LOG);
                    workflowExecutingDto.setData(wfNodeExecuteResultDto);
                    emitter.next(workflowExecutingDto);
                }
                if (nodeExecutingDto.getStatus() == NodeExecuteStatus.STOP_WAIT_ANSWER) {
                    emitter.complete();
                }
            });
            disposable.set(workflowExecutor.execute(workflowContext1).doOnError(emitter::error).subscribe((result) -> {
                log.info("Workflow succeeded {}", workflowConfigDto.getName());
                WorkflowExecuteResultDto workflowExecutingDto = new WorkflowExecuteResultDto();
                workflowExecutingDto.setData(result);
                workflowExecutingDto.setOutputContent(workflowContext1.getEndNodeContent());
                workflowExecutingDto.setType(WfExecuteResultTypeEnum.EXECUTE_RESULT);
                emitter.next(workflowExecutingDto);
                emitter.complete();
            }));
        });
        return flux.doOnCancel(() -> {
            if (disposable.get() != null) {
                disposable.get().dispose();
            }
        });
    }

    @Override
    public Flux<AgentOutputDto> executeAgent(AgentExecuteRequestDto agentExecuteRequestDto) {
        Assert.notNull(agentExecuteRequestDto.getSessionId(), "Session ID cannot be null");
        Assert.notNull(agentExecuteRequestDto.getAgentId(), "Session ID cannot be null");
        Assert.notNull(agentExecuteRequestDto.getUser(), "User information cannot be null");
        UserDto userDto = (UserDto) agentExecuteRequestDto.getUser();
        TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(userDto.getTenantId());
        try {
            RequestContext<Object> requestContext = RequestContext.builder()
                    .userId(userDto.getId())
                    .tenantId(userDto.getTenantId())
                    .user(userDto)
                    .tenantConfig(tenantConfig)
                    .build();
            RequestContext.set(requestContext);
            Long conversationId;
            Object val = redisUtil.get("session_" + agentExecuteRequestDto.getSessionId());
            if (val == null) {
                ConversationDto conversation = conversationApplicationService.createConversation(userDto.getId(), agentExecuteRequestDto.getAgentId(), false);
                redisUtil.set("session_" + agentExecuteRequestDto.getSessionId(), conversation.getId().toString(), 1800);
                conversationId = conversation.getId();
            } else {
                conversationId = Long.parseLong(val.toString());
                redisUtil.expire("session_" + agentExecuteRequestDto.getSessionId(), 1800);
            }
            TryReqDto tryReqDto = new TryReqDto();
            tryReqDto.setFrom("mcp");
            tryReqDto.setConversationId(conversationId);
            tryReqDto.setMessage(agentExecuteRequestDto.getMessage());
            tryReqDto.setVariableParams(agentExecuteRequestDto.getVariables());
            return conversationApplicationService.chat(tryReqDto, null, false).map(agentOutputDto -> {
                AgentOutputDto agentOutputDto0 = new AgentOutputDto();
                agentOutputDto0.setCompleted(agentOutputDto.isCompleted());
                if (agentOutputDto.getData() instanceof ChatMessageDto) {
                    agentOutputDto0.setData(((ChatMessageDto) agentOutputDto.getData()).getText());
                } else if (agentOutputDto.getData() instanceof AgentExecuteResult) {
                    agentOutputDto0.setData(((AgentExecuteResult) agentOutputDto.getData()).getOutputText());
                    agentOutputDto0.setError(((AgentExecuteResult) agentOutputDto.getData()).getError());
                }
                agentOutputDto0.setEventType(agentOutputDto.getEventType().name());
                agentOutputDto0.setRequestId(agentOutputDto.getRequestId());
                agentOutputDto0.setError(agentOutputDto.getError());
                return agentOutputDto0;
            });
        } finally {
            RequestContext.remove();
        }
    }

    @Override
    public ReqResult<Long> createPageAppAgent(PageAppAgentCreateDto pageAppAgentCreateDto) {
        Assert.notNull(pageAppAgentCreateDto.getCreatorId(), "Creator ID cannot be null");
        Assert.notNull(pageAppAgentCreateDto.getSpaceId(), "Space ID cannot be null");
        Assert.hasText(pageAppAgentCreateDto.getName(), "Name cannot be empty");
        Assert.notNull(pageAppAgentCreateDto.getProjectId(), "projectId cannot be null");
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setCreatorId(pageAppAgentCreateDto.getCreatorId());
        agentConfigDto.setSpaceId(pageAppAgentCreateDto.getSpaceId());
        agentConfigDto.setName(pageAppAgentCreateDto.getName());
        agentConfigDto.setDescription(pageAppAgentCreateDto.getDescription());
        agentConfigDto.setIcon(pageAppAgentCreateDto.getIcon());
        agentConfigDto.setType("PageApp");
        Long agentId = agentApplicationService.add(agentConfigDto);
        AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
        agentComponentConfigDto.setAgentId(agentId);
        agentComponentConfigDto.setType(AgentComponentConfig.Type.Page);
        agentComponentConfigDto.setTargetId(pageAppAgentCreateDto.getProjectId());
        agentComponentConfigDto.setName("Web App");
        agentApplicationService.addComponentConfig(agentComponentConfigDto);
        AgentConfigDto agentConfigUpdate = new AgentConfigDto();
        agentConfigUpdate.setId(agentId);
        agentConfigUpdate.setHideChatArea(1);
        agentConfigUpdate.setExpandPageArea(1);
        agentApplicationService.update(agentConfigUpdate);
        return ReqResult.success(agentId);
    }

    @Override
    public ReqResult<Long> createUserSandboxAgent(Long userId, Long sandboxId, String name) {
        List<SpaceDto> spaceDtos = spaceApplicationService.queryListByUserId(userId);
        //Get personal space
        SpaceDto spaceDto = spaceDtos.stream().filter(spaceDto1 -> spaceDto1.getType().equals(Space.Type.Personal)).findFirst().orElse(null);
        if (spaceDto == null) {
            return ReqResult.error("User does not have personal space");
        }
        AgentConfigDto agentConfigDto = buildPrivateAgentConfigDto(userId, sandboxId, spaceDto, name);
        agentConfigDto.setHideDesktop(StringUtils.isNotBlank(name) ? YesOrNoEnum.N.getKey() : YesOrNoEnum.Y.getKey());
        Long agentId = agentApplicationService.add(agentConfigDto);

        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (StringUtils.isNotBlank(tenantConfigDto.getUserComputerDefaultSkillIds())) {
            String[] split = tenantConfigDto.getUserComputerDefaultSkillIds().split(",");
            for (String skillId : split) {
                long id;
                try {
                    id = Long.parseLong(skillId);
                } catch (NumberFormatException e) {
                    continue;
                }
                SkillConfigDto skillConfigDto = skillApplicationService.queryById(id);
                if (skillConfigDto != null) {
                    AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
                    agentComponentConfigDto.setAgentId(agentId);
                    agentComponentConfigDto.setType(AgentComponentConfig.Type.Skill);
                    agentComponentConfigDto.setTargetId(id);
                    agentComponentConfigDto.setName(skillConfigDto.getName());
                    agentApplicationService.addComponentConfig(agentComponentConfigDto);
                }
            }
        }

        agentConfigDto = agentApplicationService.queryById(agentId);
        PublishApplyDto publishApply = new PublishApplyDto();
        publishApply.setSpaceId(spaceDto.getId());
        publishApply.setTargetId(agentId);
        publishApply.setTargetType(Published.TargetType.Agent);
        UserDto userDto = userApplicationService.queryById(userId);
        publishApply.setApplyUser(userDto);
        publishApply.setName(agentConfigDto.getName());
        publishApply.setDescription(agentConfigDto.getDescription());
        publishApply.setIcon(generateIcon(agentConfigDto.getIcon()));
        publishApply.setChannels(List.of(Published.PublishChannel.System));
        publishApply.setScope(Published.PublishScope.Space);
        publishApply.setCategory(CategoryDto.PluginCategoryEnum.Other.getName());
        publishApply.setTargetConfig(agentConfigDto);
        publishApply.setAllowCopy(YesOrNoEnum.N.getKey());
        publishApply.setOnlyTemplate(YesOrNoEnum.N.getKey());
        Long applyId = publishApplicationService.publishApply(publishApply);
        publishApplicationService.publish(applyId);

        //Add recent use
        agentApplicationService.addOrUpdateRecentUsed(userId, agentId);
        return ReqResult.success(agentId);
    }

    @Override
    public ReqResult<Void> updateUserSandboxAgentName(Long agentId, String oldName, String newName) {
        AgentConfigDto agentConfigDto = agentApplicationService.queryById(agentId);
        //If user has changed agent name, do not modify again
        if (agentConfigDto != null && agentConfigDto.getName().contains(oldName)) {
            newName = "Agent@" + newName;
            AgentConfigDto agentConfigUpdate = new AgentConfigDto();
            agentConfigUpdate.setId(agentId);
            agentConfigUpdate.setName(newName);
            agentApplicationService.update(agentConfigUpdate);
            publishApplicationService.updatePublishName(Published.TargetType.Agent, agentId, newName);
        }
        return ReqResult.success();
    }

    private @NotNull AgentConfigDto buildPrivateAgentConfigDto(Long userId, Long sandboxId, SpaceDto spaceDto, String name) {
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        agentConfigDto.setSystemPrompt(PERSONAL_COMPUTER_ASSISTANT_PROMPT);
        agentConfigDto.setCreatorId(userId);
        agentConfigDto.setSpaceId(spaceDto.getId());
        agentConfigDto.setName(StringUtils.isNotBlank(name) ? "Agent@" + name : I18nUtil.systemMessage("Backend.Agent.PrivateAgent.DefaultName", sandboxId.toString()));
        agentConfigDto.setDescription(I18nUtil.systemMessage("Backend.Agent.PrivateAgent.Description"));
        agentConfigDto.setType("TaskAgent");
        agentConfigDto.setOpenLongMemory(AgentConfig.OpenStatus.Open);
        agentConfigDto.setAllowOtherModel(YesOrNoEnum.Y.getKey());
        Map<String, Object> extra = new HashMap<>();
        extra.put("sandboxId", sandboxId);
        extra.put("private", true);//Personal computer Agent
        agentConfigDto.setExtra(extra);
        return agentConfigDto;
    }

    @Override
    public ReqResult<Void> updatePageAppAgent(PageAppAgentUpdateDto pageAppAgentUpdateDto) {
        Assert.notNull(pageAppAgentUpdateDto.getAgentId(), "agentId cannot be null");
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        BeanUtils.copyProperties(pageAppAgentUpdateDto, agentConfigDto);
        agentConfigDto.setId(pageAppAgentUpdateDto.getAgentId());
        agentApplicationService.update(agentConfigDto);
        return ReqResult.success();
    }

    @Override
    public ReqResult<Void> deletePageAppAgent(Long pageAppAgentId) {
        AgentConfigDto agentConfigDto = agentApplicationService.queryById(pageAppAgentId);
        if (agentConfigDto == null) {
            return ReqResult.error("Web application agent not found");
        }
        if (!agentConfigDto.getType().equals("PageApp")) {
            return ReqResult.error("Not a web application agent");
        }
        agentApplicationService.delete(pageAppAgentId);
        return ReqResult.success();
    }

    @Override
    public ReqResult<Void> deleteUserSandboxAgent(Long agentId, Long sandboxId) {
        AgentConfigDto agentConfigDto = agentApplicationService.queryById(agentId);
        if (agentConfigDto == null) {
            return ReqResult.error("Web application agent not found");
        }
        Map<String, Object> extra = agentConfigDto.getExtra();
        if (extra == null || extra.get("sandboxId") == null || !sandboxId.toString().equals(extra.get("sandboxId").toString())) {
            return ReqResult.error("Agent computer associated agent information error");
        }
        agentApplicationService.delete(agentId);
        return ReqResult.success();
    }

    @Override
    public Mono<List<KnowledgeQaDto>> searchKnowledge(KnowledgeSearchRequest knowledgeSearchRequest) {
        SearchContext searchContext = new SearchContext();
        searchContext.setQuery(knowledgeSearchRequest.getQuery());
        searchContext.setSearchStrategy(knowledgeSearchRequest.getSearchStrategy());
        searchContext.setMaxRecallCount(knowledgeSearchRequest.getMaxRecallCount());
        searchContext.setMatchingDegree(knowledgeSearchRequest.getMatchingDegree());
        searchContext.setKnowledgeBaseIds(knowledgeSearchRequest.getKnowledgeBaseIds());
        AgentContext agentContext = new AgentContext();
        agentContext.setUser((UserDto) knowledgeSearchRequest.getUser());
        searchContext.setAgentContext(agentContext);
        return knowledgeBaseSearcher.search(searchContext).map(knowledgeQaVos -> knowledgeQaVos.stream().map(knowledgeQaVo -> {
            KnowledgeQaDto knowledgeQaDto = new KnowledgeQaDto();
            BeanUtils.copyProperties(knowledgeQaVo, knowledgeQaDto);
            return knowledgeQaDto;
        }).collect(Collectors.toList()));
    }
}
