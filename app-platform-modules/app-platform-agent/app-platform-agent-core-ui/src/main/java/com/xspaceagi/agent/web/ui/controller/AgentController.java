package com.xspaceagi.agent.web.ui.controller;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.*;
import com.xspaceagi.agent.core.adapter.dto.config.bind.*;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.infra.rpc.CustomPageRpcService;
import com.xspaceagi.agent.core.infra.rpc.McpRpcService;
import com.xspaceagi.agent.core.infra.rpc.dto.PageDto;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.agent.web.ui.controller.util.SpaceObjectPermissionUtil;
import com.xspaceagi.agent.web.ui.dto.TriggerTimeZoneData;
import com.xspaceagi.compose.sdk.request.DorisTableDefineRequest;
import com.xspaceagi.compose.sdk.service.IComposeDbTableRpcService;
import com.xspaceagi.compose.sdk.vo.define.TableDefineVo;
import com.xspaceagi.knowledge.core.application.service.impl.KnowledgeConfigApplicationService;
import com.xspaceagi.knowledge.domain.model.KnowledgeConfigModel;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.system.application.dto.SpaceUserDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.SysUserPermissionCacheService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static com.xspaceagi.agent.core.adapter.repository.entity.Published.TargetSubType.ChatBot;
import static com.xspaceagi.agent.core.adapter.repository.entity.Published.TargetSubType.TaskAgent;
import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "智能体配置相关接口")
@RestController
@RequestMapping("/api/agent")
@Slf4j
public class AgentController {

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private McpRpcService mcpRpcService;

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private KnowledgeConfigApplicationService knowledgeConfigApplicationService;

    @Resource
    private ConfigHistoryApplicationService configHistoryApplicationService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private IComposeDbTableRpcService iComposeDbTableRpcService;

    @Resource
    private CustomPageRpcService customPageRpcService;

    @Resource
    private SkillApplicationService skillApplicationService;

    @Resource
    private SysUserPermissionCacheService sysUserPermissionCacheService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Resource
    private AgentWorkspaceApplicationService agentWorkspaceApplicationService;

    @Operation(summary = "AI生成项目信息")
    @RequestMapping(path = "/generate-info", method = RequestMethod.POST)
    public ReqResult<GenerateInfoResultDto> generateInfo(@RequestBody @Valid GenerateInfoReqDto req) {
        GenerateInfoResultDto result = agentApplicationService.generateInfo(req);
        return ReqResult.success(result);
    }

    // 方法内部做了资源鉴权，此处不用@RequireResource鉴权
    @Operation(summary = "新增智能体接口")
    @RequestMapping(path = "/add", method = RequestMethod.POST)
    public ReqResult<Long> add(@RequestBody @Valid AgentAddDto agentAddDto) {
        spacePermissionService.checkSpaceUserPermission(agentAddDto.getSpaceId());
        // 根据智能体类型校验创建权限（ChatBot / TaskAgent）
        String resourceCode = null;
        if (ChatBot.name().equals(agentAddDto.getType())) {
            resourceCode = AGENT_CREATE_CHAT_BOT.getCode();
        } else if (TaskAgent.name().equals(agentAddDto.getType())) {
            resourceCode = AGENT_CREATE_TASK_AGENT.getCode();
        }
        if (resourceCode != null) {
            sysUserPermissionCacheService.checkResourcePermissionAny(
                    RequestContext.get().getUserId(),
                    List.of(resourceCode)
            );
        }
        UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());
        userDataPermission.checkMaxAgentCount(agentApplicationService.countUserCreatedAgent(RequestContext.get().getUserId()).intValue());
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        BeanUtils.copyProperties(agentAddDto, agentConfigDto);
        agentConfigDto.setCreatorId(RequestContext.get().getUserId());
        Long agentId = agentApplicationService.add(agentConfigDto);
        return ReqResult.success(agentId);
    }

    @RequireResource(COMPONENT_LIB_COPY)
    @Operation(summary = "创建副本接口")
    @RequestMapping(path = "/copy/{agentId}", method = RequestMethod.POST)
    public ReqResult<Long> copy(@PathVariable Long agentId) {
        AgentConfigDto agentConfigDto = agentApplicationService.queryById(agentId);
        if (agentConfigDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentIdInvalid);
        }
        // 检查权限
        spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId());
        Long id = agentApplicationService.copyAgent(RequestContext.get().getUserId(), agentId);
        return ReqResult.success(id);
    }

    @RequireResource(AGENT_COPY_TO_SPACE)
    @Operation(summary = "复制到空间接口")
    @RequestMapping(path = "/copy/{agentId}/{targetSpaceId}", method = RequestMethod.POST)
    public ReqResult<Long> copyToSpace(@PathVariable Long agentId, @PathVariable Long targetSpaceId) {

        UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());
        userDataPermission.checkMaxAgentCount(agentApplicationService.countUserCreatedAgent(RequestContext.get().getUserId()).intValue());

        AgentConfigDto agentConfigDto = agentApplicationService.queryById(agentId);
        if (agentConfigDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentIdInvalid);
        }
        if (targetSpaceId.equals(agentConfigDto.getSpaceId())) {
            // 复制到本空间，只需检查普通用户权限
            spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId());
        } else {
            // 复制到其他空间，只有管理员可复制
            spacePermissionService.checkSpaceAdminPermission(agentConfigDto.getSpaceId());
            // 复制到其他空间，判断目标空间权限
            spacePermissionService.checkSpaceUserPermission(targetSpaceId);
        }

        Long id = agentApplicationService.copyAgent(RequestContext.get().getUserId(), agentConfigDto, targetSpaceId);
        return ReqResult.success(id);
    }

    @RequireResource(AGENT_MIGRATE)
    @Operation(summary = "智能体迁移接口")
    @RequestMapping(path = "/transfer/{agentId}/space/{targetSpaceId}", method = RequestMethod.POST)
    public ReqResult<Void> transfer(@PathVariable Long agentId, @PathVariable Long targetSpaceId) {
        // 检查权限，只有管理员和创建者可以迁移
        checkAgentAdminPermission(agentId);
        spacePermissionService.checkSpaceUserPermission(targetSpaceId);
        agentApplicationService.transfer(RequestContext.get().getUserId(), agentId, targetSpaceId);
        return ReqResult.success();
    }

    @RequireResource(AGENT_QUERY_LIST)
    @Operation(summary = "查询空间智能体列表接口")
    @RequestMapping(path = "/list/{spaceId}", method = RequestMethod.GET)
    public ReqResult<List<AgentConfigDto>> list(@PathVariable Long spaceId) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(spaceId, RequestContext.get().getUserId());
        List<AgentConfigDto> agents = agentApplicationService.queryListBySpaceId(spaceId);
        agents.forEach(agent -> {
            agent.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(agent.getIcon(), agent.getName(), "agent"));
            List<String> collect = SpaceObjectPermissionUtil.generatePermissionList(spaceUserDto, agent.getCreatorId()).stream().map(permission -> permission.name()).collect(Collectors.toList());
            agent.setPermissions(collect);
        });
        return ReqResult.success(agents);
    }

    @RequireResource(AGENT_QUERY_DETAIL)
    @Operation(summary = "查询智能体配置信息")
    @RequestMapping(path = "/{agentId}", method = RequestMethod.GET)
    public ReqResult<AgentConfigDto> getAgentConfig(@PathVariable Long agentId) {
        AgentConfigDto agentConfigDto = agentApplicationService.queryAgentByIdWithStatics(agentId);
        if (agentConfigDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentIdInvalid);
        }
        spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId());
        if (agentConfigDto.getDevConversationId() == null) {
            ConversationDto conversationDto = conversationApplicationService.createConversation(RequestContext.get().getUserId(), agentId, true);
            AgentConfigDto agentConfigDto1 = new AgentConfigDto();
            agentConfigDto1.setId(agentId);
            agentConfigDto1.setDevConversationId(conversationDto.getId());
            agentApplicationService.update(agentConfigDto1);
            agentConfigDto.setDevConversationId(conversationDto.getId());
        }
        agentConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(agentConfigDto.getIcon(), agentConfigDto.getName()));
        SpaceUserDto spaceUserDto = spaceApplicationService.querySpaceUser(agentConfigDto.getSpaceId(), RequestContext.get().getUserId());
        List<String> collect = SpaceObjectPermissionUtil.generatePermissionList(spaceUserDto, agentConfigDto.getCreatorId()).stream().map(permission -> permission.name()).collect(Collectors.toList());
        agentConfigDto.setPermissions(collect);
        Map<Long, AgentComponentConfigDto> pageComponentConfigMap = agentConfigDto.getAgentComponentConfigList().stream().filter(agentComponentConfigDto -> agentComponentConfigDto.getType() == AgentComponentConfig.Type.Page)
                .collect(Collectors.toMap(AgentComponentConfigDto::getTargetId, componentConfigDto -> componentConfigDto, (old, newConfig) -> newConfig));
        var guidQuestionDtos = Optional.ofNullable(agentConfigDto.getOpeningGuidQuestions()).map(item -> item.stream().map(question -> {
                    GuidQuestionDto guidQuestionDto;
                    try {
                        if (!JSON.isValid(question)) {
                            guidQuestionDto = new GuidQuestionDto();
                            guidQuestionDto.setType(GuidQuestionDto.GuidQuestionType.Question);
                            guidQuestionDto.setInfo(question);
                        } else {
                            guidQuestionDto = JSON.parseObject(question, GuidQuestionDto.class);
                        }
                        if (guidQuestionDto == null) {
                            return null;
                        }
                    } catch (Exception e) {
                        log.warn("Invalid JSON question: {}", question);
                        return null;
                    }
                    if (guidQuestionDto.getType() == GuidQuestionDto.GuidQuestionType.Page) {
                        if (guidQuestionDto.getPageId() == null || pageComponentConfigMap.get(guidQuestionDto.getPageId()) == null) {
                            return null;
                        }
                        PageDto pageDto = customPageRpcService.queryPageDto(guidQuestionDto.getPageId());
                        if (pageDto != null) {
                            Optional<PageArgConfig> first = pageDto.getPageArgConfigs().stream().filter(pageArgConfig -> pageArgConfig.getPageUri().equals(guidQuestionDto.getPageUri())).findFirst();
                            if (first.isEmpty()) {
                                return null;
                            }
                            return guidQuestionDto;
                        }
                        return null;
                    }
                    return guidQuestionDto;
                }).collect(Collectors.toList()))
                .orElse(Collections.emptyList());
        agentConfigDto.setGuidQuestionDtos(guidQuestionDtos);
        if (CollectionUtils.isNotEmpty(guidQuestionDtos)) {
            guidQuestionDtos.removeIf(Objects::isNull);
        }

        return ReqResult.success(agentConfigDto);
    }

    @RequireResource(AGENT_QUERY_DETAIL)
    @Operation(summary = "查询智能体历史配置信息接口")
    @RequestMapping(path = "/config/history/list/{agentId}", method = RequestMethod.GET)
    public ReqResult<List<ConfigHistoryDto>> historyList(@PathVariable Long agentId) {
        checkAgentPermission(agentId);
        List<ConfigHistoryDto> historyList = configHistoryApplicationService.queryConfigHistoryList(Published.TargetType.Agent, agentId);
        return ReqResult.success(historyList);
    }

    @RequireResource(AGENT_DELETE)
    @Operation(summary = "删除智能体接口")
    @RequestMapping(path = "/delete/{agentId}", method = RequestMethod.POST)
    public ReqResult<Void> delete(@PathVariable Long agentId) {
        AgentConfigDto agentConfigDto = checkAgentPermission(agentId);
        if (agentConfigDto.getExtra().containsKey("private")) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPrivateComputerDeleteForbidden);
        }
        agentApplicationService.delete(agentId);

        if (agentConfigDto.getDevAgentConversationId() != null) {
            try {
                agentWorkspaceApplicationService.deleteWorkspace(agentConfigDto.getCreatorId(), agentConfigDto.getDevAgentConversationId());
            } catch (Exception e) {
                log.warn("Failed to delete sandbox workspace for agent, agentId={}, cId={}", agentId, agentConfigDto.getDevAgentConversationId(), e);
            }
        }
        return ReqResult.success();
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "更新智能体基础配置信息")
    @RequestMapping(path = "/config/update", method = RequestMethod.POST)
    public ReqResult<Void> updateAgentConfig(@RequestBody AgentConfigUpdateDto agentConfigUpdateDto) {
        AgentConfigDto agentConfigDto1 = checkAgentPermission(agentConfigUpdateDto.getId());
        AgentConfigDto agentConfigDto = new AgentConfigDto();
        BeanUtils.copyProperties(agentConfigUpdateDto, agentConfigDto);
        if (agentConfigUpdateDto.getGuidQuestionDtos() != null) {
            agentConfigDto.setOpeningGuidQuestions(agentConfigUpdateDto.getGuidQuestionDtos().stream().map(guidQuestionDto -> JSON.toJSONString(guidQuestionDto)).collect(Collectors.toList()));
        }
        agentConfigDto.setSpaceId(agentConfigDto1.getSpaceId());
        agentApplicationService.update(agentConfigDto);
        return ReqResult.success();
    }

    @RequireResource(AGENT_PUBLISH)
    @Operation(summary = "智能体发布申请")
    @RequestMapping(path = "/publish/apply/{agentId}", method = RequestMethod.POST)
    public ReqResult<String> publishApply(@PathVariable Long agentId, @RequestBody PublishApplySubmitDto publishApplySubmitDto) {
        checkAgentPermission(agentId);
        AgentConfigDto agentConfigDto = agentApplicationService.queryById(agentId);

        PublishApplyDto publishApplyDto = new PublishApplyDto();
        publishApplyDto.setApplyUser((UserDto) RequestContext.get().getUser());
        publishApplyDto.setTargetType(Published.TargetType.Agent);
        publishApplyDto.setTargetSubType(Published.TargetSubType.valueOf(agentConfigDto.getType()));
        publishApplyDto.setTargetId(agentId);
        publishApplyDto.setChannels(List.of(Published.PublishChannel.Square));
        publishApplyDto.setRemark(publishApplySubmitDto.getRemark());
        publishApplyDto.setName(agentConfigDto.getName());
        publishApplyDto.setDescription(agentConfigDto.getDescription());
        publishApplyDto.setIcon(agentConfigDto.getIcon());
        publishApplyDto.setTargetConfig(agentConfigDto);
        publishApplyDto.setSpaceId(agentConfigDto.getSpaceId());
        Long applyId = publishApplicationService.publishApply(publishApplyDto);
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfigDto.getAgentPublishAudit() == 0) {
            publishApplicationService.publish(applyId);
            return ReqResult.create(ReqResult.SUCCESS, I18nUtil.systemMessage("Backend.Agent.Publish.Success"), I18nUtil.systemMessage("Backend.Agent.Publish.Success"));
        }
        return ReqResult.create(ReqResult.SUCCESS, I18nUtil.systemMessage("Backend.Agent.Publish.SubmitPending"), I18nUtil.systemMessage("Backend.Agent.Publish.SubmitPending"));
    }

    @RequireResource(AGENT_QUERY_DETAIL)
    @Operation(summary = "查询卡片列表")
    @RequestMapping(path = "/card/list", method = RequestMethod.GET)
    public ReqResult<List<CardDto>> getCardList() {
        return ReqResult.success(agentApplicationService.queryCardList());
    }

    @RequireResource(AGENT_QUERY_DETAIL)
    @Operation(summary = "查询智能体配置组件列表")
    @RequestMapping(path = "/component/list/{agentId}", method = RequestMethod.GET)
    public ReqResult<List<AgentComponentConfigDto>> getAgentNodeConfigList(@PathVariable Long agentId) {
        checkAgentPermission(agentId);
        List<AgentComponentConfigDto> agentComponentConfigDtos = agentApplicationService.queryComponentConfigList(agentId);
        return ReqResult.success(agentComponentConfigDtos);
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "新增智能体插件、工作流、知识库组件配置")
    @RequestMapping(path = "/component/add", method = RequestMethod.POST)
    public ReqResult<Long> addComponentModelConfig(@RequestBody @Valid AgentComponentConfigAddDto agentComponentConfigAddDto) {
        AgentConfigDto agentConfigDto = checkAgentPermission(agentComponentConfigAddDto.getAgentId());
        AgentComponentConfigDto componentConfigDto = new AgentComponentConfigDto();
        if (agentComponentConfigAddDto.getType() == AgentComponentConfig.Type.Plugin) {
            //校验选择的插件有没有使用权限
            pluginApplicationService.checkSpacePluginPermission(agentConfigDto.getSpaceId(), agentComponentConfigAddDto.getTargetId());
        }
        if (agentComponentConfigAddDto.getType() == AgentComponentConfig.Type.Workflow) {
            //校验选择的工作流有没有使用权限
            workflowApplicationService.checkSpaceWorkflowPermission(agentConfigDto.getSpaceId(), agentComponentConfigAddDto.getTargetId());
        }
        if (agentComponentConfigAddDto.getType() == AgentComponentConfig.Type.Knowledge) {
            //校验选择的知识有没有使用权限
            KnowledgeConfigModel knowledgeConfigModel = knowledgeConfigApplicationService.queryOneInfoById(agentComponentConfigAddDto.getTargetId());
            if (knowledgeConfigModel == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentKnowledgeIdInvalid);
            }

            //新增特殊授权
            UserDto userDto = (UserDto) RequestContext.get().getUser();
            UserDataPermissionDto userDataPermissionDto = userDataPermissionRpcService.getUserDataPermission(userDto.getId());
            List<Long> knowledgeIds = userDataPermissionDto.getKnowledgeIds();
            boolean needCheckPermissionState = true;
            if (knowledgeIds != null && !knowledgeIds.isEmpty()) {
                for (Long knowledgeId : knowledgeIds) {
                    if (knowledgeId.equals(knowledgeConfigModel.getId())) {
                        needCheckPermissionState = false;
                        break;
                    }
                }
            }
            //新增特殊授权
            if (needCheckPermissionState) {
                spacePermissionService.checkSpaceUserPermission(knowledgeConfigModel.getSpaceId(), RequestContext.get().getUserId());
            }

        }
        //校验数据表权限
        if (agentComponentConfigAddDto.getType() == AgentComponentConfig.Type.Table) {
            checkTablePermission(agentComponentConfigAddDto.getTargetId());
        }
        if (agentComponentConfigAddDto.getType() == AgentComponentConfig.Type.Mcp) {
            McpDto deployedMcp = mcpRpcService.queryMcp(agentComponentConfigAddDto.getTargetId(), agentConfigDto.getSpaceId());
            mcpRpcService.checkMcpPermission(deployedMcp, agentComponentConfigAddDto.getToolName());
            componentConfigDto.setName(deployedMcp.getName() + "/" + agentComponentConfigAddDto.getToolName());
            componentConfigDto.setIcon(deployedMcp.getIcon());
            componentConfigDto.setDescription(deployedMcp.getDescription());
            McpBindConfigDto mcpBindConfigDto = new McpBindConfigDto();
            mcpBindConfigDto.setToolName(agentComponentConfigAddDto.getToolName());
            componentConfigDto.setBindConfig(mcpBindConfigDto);
        }
        //添加页面权限校验
        if (agentComponentConfigAddDto.getType() == AgentComponentConfig.Type.Page) {
            PageDto pageDto = customPageRpcService.queryPageDto(agentComponentConfigAddDto.getTargetId());
            if (pageDto == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPageIdInvalid);
            }
            spacePermissionService.checkSpaceUserPermission(pageDto.getSpaceId(), RequestContext.get().getUserId());
        }
        //校验Skill权限
        if (agentComponentConfigAddDto.getType() == AgentComponentConfig.Type.Skill) {
            skillApplicationService.checkSpaceSkillPermission(agentConfigDto.getSpaceId(), agentComponentConfigAddDto.getTargetId());
        }

        if (agentComponentConfigAddDto.getType() == AgentComponentConfig.Type.Agent) {
            checkAgentPermission(agentComponentConfigAddDto.getTargetId());
        }

        BeanUtils.copyProperties(agentComponentConfigAddDto, componentConfigDto);
        agentApplicationService.addComponentConfig(componentConfigDto);
        return ReqResult.success(componentConfigDto.getId());
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "更新智能体页面配置")
    @RequestMapping(path = "/component/page/update", method = RequestMethod.POST)
    public ReqResult<Void> updateComponentPageConfig(@RequestBody @Valid AgentComponentConfigUpdateDto<PageBindConfigDto> componentConfigUpdateDto) {
        checkComponentPermission(componentConfigUpdateDto.getId(), AgentComponentConfig.Type.Page);
        AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
        BeanUtils.copyProperties(componentConfigUpdateDto, agentComponentConfigDto);
        agentApplicationService.updateComponentConfig(agentComponentConfigDto);
        return ReqResult.success();
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "新增智能体触发器配置")
    @RequestMapping(path = "/component/trigger/add", method = RequestMethod.POST)
    public ReqResult<Void> addComponentTriggerConfig(@RequestBody @Valid TriggerConfigAddDto triggerConfigDto) {
        checkAgentPermission(triggerConfigDto.getAgentId());
        AgentComponentConfigDto componentConfigDto = new AgentComponentConfigDto();
        componentConfigDto.setName(triggerConfigDto.getName());
        componentConfigDto.setAgentId(triggerConfigDto.getAgentId());
        componentConfigDto.setType(AgentComponentConfig.Type.Trigger);
        componentConfigDto.setBindConfig(triggerConfigDto);
        componentConfigDto.setTargetId(-1L);
        agentApplicationService.addComponentConfig(componentConfigDto);
        return ReqResult.success();
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "删除智能体组件配置")
    @RequestMapping(path = "/component/delete/{id}", method = RequestMethod.POST)
    public ReqResult<Void> deleteComponentModelConfig(@PathVariable Long id) {
        //权限验证
        try {
            checkComponentPermission(id, null);
        } catch (Exception e) {
            if (e instanceof BizException bizException && "0003".equals(bizException.getCode())) {
                //已经不存在了
                return ReqResult.success();
            }
        }
        agentApplicationService.deleteComponentConfig(id);
        return ReqResult.success();
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "更新模型组件配置")
    @RequestMapping(path = "/component/model/update", method = RequestMethod.POST)
    public ReqResult<Void> updateComponentModelConfig(@RequestBody AgentComponentConfigUpdateDto<ModelBindConfigDto> componentConfigUpdateDto) {
        //权限验证
        checkComponentPermission(componentConfigUpdateDto.getId(), AgentComponentConfig.Type.Model);
        if (componentConfigUpdateDto.getTargetId() != null) {
            //校验选择的模型有没有使用权限
            modelApplicationService.checkModelUsePermission(componentConfigUpdateDto.getTargetId());
        }

        AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
        BeanUtils.copyProperties(componentConfigUpdateDto, agentComponentConfigDto);
        agentApplicationService.updateComponentConfig(agentComponentConfigDto);
        return ReqResult.success();
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "更新插件组件配置")
    @RequestMapping(path = "/component/plugin/update", method = RequestMethod.POST)
    public ReqResult<Void> updateComponentPluginConfig(@RequestBody AgentComponentConfigUpdateDto<PluginBindConfigDto> componentConfigUpdateDto) {
        //权限验证
        AgentConfigDto agentConfigDto = checkComponentPermission(componentConfigUpdateDto.getId(), AgentComponentConfig.Type.Plugin);
        if (componentConfigUpdateDto.getTargetId() != null) {
            //校验选择的插件有没有使用权限
            pluginApplicationService.checkSpacePluginPermission(agentConfigDto.getSpaceId(), componentConfigUpdateDto.getTargetId());
        }
        checkArgDefaultValue(componentConfigUpdateDto.getBindConfig() != null ? componentConfigUpdateDto.getBindConfig().getInputArgBindConfigs() : null);
        AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
        BeanUtils.copyProperties(componentConfigUpdateDto, agentComponentConfigDto);
        agentApplicationService.updateComponentConfig(agentComponentConfigDto);
        return ReqResult.success();
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "更新MCP组件配置")
    @RequestMapping(path = "/component/mcp/update", method = RequestMethod.POST)
    public ReqResult<Void> updateComponentMcpConfig(@RequestBody AgentComponentConfigUpdateDto<McpBindConfigDto> componentConfigUpdateDto) {
        //权限验证
        AgentConfigDto agentConfigDto = checkComponentPermission(componentConfigUpdateDto.getId(), AgentComponentConfig.Type.Mcp);
        if (componentConfigUpdateDto.getTargetId() != null) {
            //校验选择的插件有没有使用权限
            McpDto deployedMcp = mcpRpcService.queryMcp(componentConfigUpdateDto.getTargetId(), agentConfigDto.getSpaceId());
            mcpRpcService.checkMcpPermission(deployedMcp, componentConfigUpdateDto.getBindConfig().getToolName());
        }
        checkArgDefaultValue(componentConfigUpdateDto.getBindConfig() != null ? componentConfigUpdateDto.getBindConfig().getInputArgBindConfigs() : null);
        AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
        BeanUtils.copyProperties(componentConfigUpdateDto, agentComponentConfigDto);
        agentApplicationService.updateComponentConfig(agentComponentConfigDto);
        return ReqResult.success();
    }

    private void checkArgDefaultValue(List<Arg> args) {
        if (args != null) {
            args.forEach(arg -> {
                if (arg.getBindValueType() == Arg.BindValueType.Input && StringUtils.isNotBlank(arg.getBindValue())) {
                    if (arg.getDataType() == DataTypeEnum.Boolean && !arg.getBindValue().toLowerCase().equals("true") && !arg.getBindValue().toLowerCase().equals("false")) {
                        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentArgBooleanDefaultInvalid, arg.getName());
                    }
                    //Integer只能为整数
                    if (arg.getDataType() == DataTypeEnum.Integer) {
                        try {
                            Integer.parseInt(arg.getBindValue());
                        } catch (NumberFormatException e) {
                            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentArgIntegerDefaultInvalid, arg.getName());
                        }
                    }
                    //Number
                    if (arg.getDataType() == DataTypeEnum.Number) {
                        try {
                            Double.parseDouble(arg.getBindValue());
                        } catch (NumberFormatException e) {
                            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentArgNumberDefaultInvalid, arg.getName());
                        }
                    }
                }
            });
        }
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "更新工作流组件配置")
    @RequestMapping(path = "/component/workflow/update", method = RequestMethod.POST)
    public ReqResult<Void> updateComponentWorkflowConfig(@RequestBody AgentComponentConfigUpdateDto<WorkflowBindConfigDto> componentConfigUpdateDto) {
        //权限验证
        AgentConfigDto agentConfigDto = checkComponentPermission(componentConfigUpdateDto.getId(), AgentComponentConfig.Type.Workflow);
        if (componentConfigUpdateDto.getTargetId() != null) {
            //校验选择的插件有没有使用权限
            workflowApplicationService.checkSpaceWorkflowPermission(agentConfigDto.getSpaceId(), componentConfigUpdateDto.getTargetId());
        }
        checkArgDefaultValue(componentConfigUpdateDto.getBindConfig() != null ? componentConfigUpdateDto.getBindConfig().getInputArgBindConfigs() : null);
        AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
        BeanUtils.copyProperties(componentConfigUpdateDto, agentComponentConfigDto);
        agentApplicationService.updateComponentConfig(agentComponentConfigDto);
        return ReqResult.success();
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "更新技能Skill绑定配置")
    @RequestMapping(path = "/component/skill/update", method = RequestMethod.POST)
    public ReqResult<Void> updateComponentSkillConfig(@RequestBody AgentComponentConfigUpdateDto<SkillBindConfigDto> componentConfigUpdateDto) {
        //权限验证
        AgentConfigDto agentConfigDto = checkComponentPermission(componentConfigUpdateDto.getId(), AgentComponentConfig.Type.Skill);
        if (componentConfigUpdateDto.getTargetId() != null) {
            //校验选择的技能有没有使用权限
            skillApplicationService.checkSpaceSkillPermission(agentConfigDto.getSpaceId(), componentConfigUpdateDto.getTargetId());
        }

        AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
        BeanUtils.copyProperties(componentConfigUpdateDto, agentComponentConfigDto);
        agentApplicationService.updateComponentConfig(agentComponentConfigDto);
        return ReqResult.success();
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "更新数据表组件配置")
    @RequestMapping(path = "/component/table/update", method = RequestMethod.POST)
    public ReqResult<Void> updateComponentTableConfig(@RequestBody AgentComponentConfigUpdateDto<TableBindConfigDto> componentConfigUpdateDto) {
        //权限验证
        checkComponentPermission(componentConfigUpdateDto.getId(), AgentComponentConfig.Type.Table);
        if (componentConfigUpdateDto.getTargetId() != null) {
            //校验选择的数据表有没有使用权限
            checkTablePermission(componentConfigUpdateDto.getTargetId());
        }
        checkArgDefaultValue(componentConfigUpdateDto.getBindConfig() != null ? componentConfigUpdateDto.getBindConfig().getInputArgBindConfigs() : null);
        AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
        BeanUtils.copyProperties(componentConfigUpdateDto, agentComponentConfigDto);
        agentApplicationService.updateComponentConfig(agentComponentConfigDto);
        return ReqResult.success();
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "更新知识库组件配置")
    @RequestMapping(path = "/component/knowledge/update", method = RequestMethod.POST)
    public ReqResult<Void> updateComponentKnowledgeConfig(@RequestBody AgentComponentConfigUpdateDto<KnowledgeBaseBindConfigDto> componentConfigUpdateDto) {
        //权限验证
        checkComponentPermission(componentConfigUpdateDto.getId(), AgentComponentConfig.Type.Knowledge);
        if (componentConfigUpdateDto.getTargetId() != null) {
            //校验选择的知识有没有使用权限
            KnowledgeConfigModel knowledgeConfigModel = knowledgeConfigApplicationService.queryOneInfoById(componentConfigUpdateDto.getTargetId());
            if (knowledgeConfigModel == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentKnowledgeIdInvalid);
            }
        }
        AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
        BeanUtils.copyProperties(componentConfigUpdateDto, agentComponentConfigDto);
        agentApplicationService.updateComponentConfig(agentComponentConfigDto);
        return ReqResult.success();
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "更新触发器组件配置")
    @RequestMapping(path = "/component/trigger/update", method = RequestMethod.POST)
    public ReqResult<Void> updateComponentTriggerConfig(@RequestBody AgentComponentConfigUpdateDto<TriggerConfigDto> componentConfigUpdateDto) {
        //权限验证
        checkComponentPermission(componentConfigUpdateDto.getId(), AgentComponentConfig.Type.Trigger);
        AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
        BeanUtils.copyProperties(componentConfigUpdateDto, agentComponentConfigDto);
        agentApplicationService.updateComponentConfig(agentComponentConfigDto);
        return ReqResult.success();
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "更新变量配置")
    @RequestMapping(path = "/component/variable/update", method = RequestMethod.POST)
    public ReqResult<Void> updateComponentVariableConfig(@RequestBody AgentComponentConfigUpdateDto<VariableConfigDto> componentConfigUpdateDto) {
        //权限验证
        AgentConfigDto agentConfigDto = checkComponentPermission(componentConfigUpdateDto.getId(), AgentComponentConfig.Type.Variable);
        AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
        VariableConfigDto variableConfigDto = componentConfigUpdateDto.getBindConfig();
        //检查是否有重复的变量名
        Set<String> variableNames = new HashSet<>();
        variableConfigDto.getVariables().forEach(arg -> {
            if (variableNames.contains(arg.getName())) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentVariableNameDuplicated, arg.getName());
            }
            variableNames.add(arg.getName());
            if (arg.getInputType() == Arg.InputTypeEnum.Select || arg.getInputType() == Arg.InputTypeEnum.MultipleSelect) {
                if (arg.getSelectConfig() == null) {
                    throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentVariableSelectOptionsRequired, arg.getName());
                }
                if (arg.getSelectConfig().getDataSourceType() == SelectConfig.DataSourceTypeEnum.BINDING) {
                    if (arg.getSelectConfig().getTargetId() == null) {
                        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentVariableSelectOptionsRequired, arg.getName());
                    }
                    if (arg.getSelectConfig().getTargetType() == Published.TargetType.Plugin) {
                        PluginDto pluginDto = pluginApplicationService.queryPublishedPluginConfig(arg.getSelectConfig().getTargetId(), agentConfigDto.getSpaceId());
                        if (pluginDto == null) {
                            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentVariablePluginOffline, arg.getName());
                        }
                        PluginConfigDto pluginConfigDto = (PluginConfigDto) pluginDto.getConfig();
                        if (pluginConfigDto == null) {
                            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentVariablePluginDataInvalid, arg.getName());
                        }
                        if (pluginConfigDto.getOutputArgs() == null) {
                            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentVariablePluginOutputEmpty,
                                    arg.getName(), pluginDto.getName());
                        }
                        AtomicBoolean hasOptions = new AtomicBoolean(false);
                        pluginConfigDto.getOutputArgs().forEach(outputArg -> {
                            if ("options".equals(outputArg.getName())) {
                                hasOptions.set(true);
                            }
                        });
                        if (!hasOptions.get()) {
                            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentVariablePluginOptionsMissing, arg.getName());
                        }
                        arg.getSelectConfig().setTargetName(pluginDto.getName());
                        arg.getSelectConfig().setTargetDescription(pluginDto.getDescription());
                        arg.getSelectConfig().setTargetIcon(pluginDto.getIcon());
                    }
                    if (arg.getSelectConfig().getTargetType() == Published.TargetType.Workflow) {
                        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(arg.getSelectConfig().getTargetId(), agentConfigDto.getSpaceId(), false);
                        if (workflowConfigDto == null) {
                            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentVariableWorkflowNotFound, arg.getName());
                        }
                        if (workflowConfigDto.getOutputArgs() == null) {
                            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentVariableWorkflowNoOutput, arg.getName());
                        }
                        AtomicBoolean hasOptions = new AtomicBoolean(false);
                        workflowConfigDto.getOutputArgs().forEach(outputArg -> {
                            if ("options".equals(outputArg.getName())) {
                                hasOptions.set(true);
                            }
                        });
                        if (!hasOptions.get()) {
                            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentVariableWorkflowOptionsMissing, arg.getName());
                        }
                        arg.getSelectConfig().setTargetName(workflowConfigDto.getName());
                        arg.getSelectConfig().setTargetDescription(workflowConfigDto.getDescription());
                        arg.getSelectConfig().setTargetIcon(workflowConfigDto.getIcon());
                    }
                } else {
                    if (arg.getSelectConfig().getOptions() == null || CollectionUtils.isEmpty(arg.getSelectConfig().getOptions())) {
                        throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentVariableOptionsMissing, arg.getName());
                    }
                }
            }
        });
        BeanUtils.copyProperties(componentConfigUpdateDto, agentComponentConfigDto);
        agentApplicationService.updateComponentConfig(agentComponentConfigDto);
        return ReqResult.success();
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "更新事件绑定配置")
    @RequestMapping(path = "/component/event/update", method = RequestMethod.POST)
    public ReqResult<Void> updateComponentEventConfig(@RequestBody AgentComponentConfigUpdateDto<EventBindConfigDto> componentConfigUpdateDto) {
        //权限验证
        checkComponentPermission(componentConfigUpdateDto.getId(), AgentComponentConfig.Type.Event);
        componentConfigUpdateDto.getBindConfig().getEventConfigs().forEach(eventConfigDto -> {
            if (eventConfigDto.getType() == EventConfigDto.EventType.Page) {
                Assert.notNull(eventConfigDto.getPageId(), "页面ID不能为空");
                Assert.notNull(eventConfigDto.getPageUri(), "页面URI不能为空");
                PageDto pageDto = customPageRpcService.queryPageDto(eventConfigDto.getPageId());
                Assert.notNull(pageDto, "页面不存在");
            } else {
                Assert.notNull(eventConfigDto.getUrl(), "URL不能为空");
            }
        });

        AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
        BeanUtils.copyProperties(componentConfigUpdateDto, agentComponentConfigDto);
        agentApplicationService.updateComponentConfig(agentComponentConfigDto);
        return ReqResult.success();
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "更新subagent配置")
    @RequestMapping(path = "/component/subagent/update", method = RequestMethod.POST)
    public ReqResult<Void> updateComponentSubAgentConfig(@RequestBody AgentComponentConfigUpdateDto<SubAgentBindConfigDto> componentConfigUpdateDto) {
        //权限验证
        checkComponentPermission(componentConfigUpdateDto.getId(), AgentComponentConfig.Type.SubAgent);
        componentConfigUpdateDto.getBindConfig().getSubAgents().forEach(subAgent -> {
            Assert.notNull(subAgent.getName(), "子智能体名称不能为空");
            Assert.notNull(subAgent.getPrompt(), "子智能体提示词不能为空");
        });

        AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
        BeanUtils.copyProperties(componentConfigUpdateDto, agentComponentConfigDto);
        agentApplicationService.updateComponentConfig(agentComponentConfigDto);
        return ReqResult.success();
    }

    @RequireResource(AGENT_MODIFY)
    @Operation(summary = "更新subagent配置")
    @RequestMapping(path = "/component/hook/update", method = RequestMethod.POST)
    public ReqResult<Void> updateComponentHookConfig(@RequestBody AgentComponentConfigUpdateDto<HookConfigDto> componentConfigUpdateDto) {
        //权限验证
        checkComponentPermission(componentConfigUpdateDto.getId(), AgentComponentConfig.Type.Hook);
        componentConfigUpdateDto.getBindConfig().getHooks().forEach(hook -> {
            Assert.notNull(hook.getName(), "hook name can not be null");
            Assert.notNull(hook.getConfig(), "hook config can not be null");
            Assert.notNull(hook.getType(), "hook type can not be null");
            Assert.notNull(hook.getEvent(), "hook event can not be null");
        });

        AgentComponentConfigDto agentComponentConfigDto = new AgentComponentConfigDto();
        BeanUtils.copyProperties(componentConfigUpdateDto, agentComponentConfigDto);
        agentApplicationService.updateComponentConfig(agentComponentConfigDto);
        return ReqResult.success();
    }

    @Operation(summary = "触发器定时任务时区数据")
    @RequestMapping(path = "/trigger/timeZone/data", method = RequestMethod.GET)
    public ReqResult<TriggerTimeZoneData> getTriggerTimeZoneData() {
        //权限验证
        InputStream inputStream = TriggerTimeZoneData.class.getClassLoader().getResourceAsStream("timeZone.json");
        //inputStream转文本
        String timeZoneStr = convertInputStreamToString(inputStream);
        TriggerTimeZoneData triggerTimeZoneData = new TriggerTimeZoneData();
        triggerTimeZoneData.setUtcTimeZones(JSON.parseArray(timeZoneStr, TriggerTimeZoneData.UTCTimeZone.class));
        inputStream = TriggerTimeZoneData.class.getClassLoader().getResourceAsStream("cronExps.json");
        //inputStream转文本
        String cronExpStr = convertInputStreamToString(inputStream);
        triggerTimeZoneData.setCronExpScopes(JSON.parseArray(cronExpStr, TriggerTimeZoneData.CronExpScope.class));
        return ReqResult.success(triggerTimeZoneData);
    }

    @Operation(summary = "智能体跳转")
    @RequestMapping(path = "/redirect/{agentId}", method = RequestMethod.GET)
    public void agentRedirect(@PathVariable Long agentId, HttpServletResponse response) throws IOException {
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Agent, agentId);
        if (publishedDto == null) {
            response.sendRedirect(tenantConfigDto.getCasClientHostUrl());
            return;
        }
        ConversationDto conversation = conversationApplicationService.createConversation(RequestContext.get().getUserId(), agentId, false);
        agentApplicationService.addOrUpdateRecentUsed(RequestContext.get().getUserId(), agentId);
        response.sendRedirect(tenantConfigDto.getCasClientHostUrl().endsWith("/") ? tenantConfigDto.getCasClientHostUrl() + "home/chat/" + conversation.getId() : tenantConfigDto.getCasClientHostUrl() + "/home/chat/" + conversation.getId());
    }

    //variable
    @RequireResource(AGENT_QUERY_DETAIL)
    @Operation(summary = "查询智能体参数列表(不含系统参数)")
    @RequestMapping(path = "/variable/list/{agentId}", method = RequestMethod.GET)
    public ReqResult<List<Arg>> variableList(@PathVariable Long agentId) {
        AgentConfigDto agentConfigDto = checkAgentPermission(agentId);
        List<Arg> agentNoneSystemVariables = agentApplicationService.getAgentNoneSystemVariables(agentId, agentConfigDto.getSpaceId());
        return ReqResult.success(agentNoneSystemVariables);
    }

    private String convertInputStreamToString(InputStream inputStream) {
        StringBuilder stringBuilder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            String line;
            while ((line = reader.readLine()) != null) {
                stringBuilder.append(line).append("\n");
            }
            inputStream.close();
        } catch (IOException e) {
            log.error("Error converting InputStream to String", e);
        }
        return stringBuilder.toString();
    }

    private AgentConfigDto checkComponentPermission(Long id, AgentComponentConfig.Type type) {
        AgentComponentConfigDto agentComponentConfigDto = agentApplicationService.queryComponentConfig(id);
        if (agentComponentConfigDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentComponentNotFoundOrRemoved);
        }
        if (type != null && type != agentComponentConfigDto.getType()) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentComponentTypeInvalid);
        }
        return checkAgentPermission(agentComponentConfigDto.getAgentId());
    }

    private AgentConfigDto checkAgentPermission(Long agentId) {
        List<AgentConfigDto> agentDtos = agentApplicationService.queryListByIds(List.of(agentId));
        if (CollectionUtils.isEmpty(agentDtos)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentNotFoundAlt);
        }
        AgentConfigDto agentDto = agentDtos.get(0);
        spacePermissionService.checkSpaceUserPermission(agentDto.getSpaceId());
        return agentDto;
    }

    private void checkAgentAdminPermission(Long agentId) {
        AgentConfigDto agentDto = agentApplicationService.queryById(agentId);
        if (agentDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentNotFoundAlt);
        }
        if (RequestContext.get().getUserId().equals(agentDto.getCreatorId())) {
            //除了智能体是用户创建的，还需是空间用户
            spacePermissionService.checkSpaceUserPermission(agentDto.getSpaceId());
            return;
        }
        spacePermissionService.checkSpaceAdminPermission(agentDto.getSpaceId());
    }

    private void checkTablePermission(Long tableId) {
        DorisTableDefineRequest dorisTableDefineRequest = new DorisTableDefineRequest();
        dorisTableDefineRequest.setTableId(tableId);
        TableDefineVo tableDefineVo = iComposeDbTableRpcService.queryTableDefinition(dorisTableDefineRequest);
        if (tableDefineVo == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentTableIdInvalid);
        }
        spacePermissionService.checkSpaceUserPermission(tableDefineVo.getSpaceId());
    }
}
