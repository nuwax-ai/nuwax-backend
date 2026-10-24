package com.xspaceagi.agent.core.application.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.repository.entity.*;
import com.xspaceagi.agent.core.domain.service.AgentDomainService;
import com.xspaceagi.agent.core.domain.service.ConversationDomainService;
import com.xspaceagi.agent.core.domain.service.UserTargetRelationDomainService;
import com.xspaceagi.agent.core.infra.component.agent.SandboxAgentClient;
import com.xspaceagi.agent.core.infra.rpc.SandboxServerConfigService;
import com.xspaceagi.agent.core.infra.rpc.dto.SandboxServerConfig;
import com.xspaceagi.agent.core.spec.constant.Prompts;
import com.xspaceagi.agent.core.spec.enums.GlobalVariableEnum;
import com.xspaceagi.agent.core.spec.enums.MessageTypeEnum;
import com.xspaceagi.agent.core.spec.enums.TaskCron;
import com.xspaceagi.connector.sdk.IConnectorApiService;
import com.xspaceagi.connector.sdk.dto.ConnectorProviderInfo;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.custompage.sdk.dto.CustomPageDto;
import com.xspaceagi.memory.sdk.dto.MemoryMetaData;
import com.xspaceagi.memory.sdk.service.IMemoryRpcService;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.ProjectTypeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.JsonTruncateUtil;
import com.xspaceagi.system.spec.utils.RedisUtil;
import com.xspaceagi.userapp.sdk.IUserAppRpcService;
import com.xspaceagi.userapp.sdk.IUserProjectRpcService;
import com.xspaceagi.userapp.sdk.dto.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.xspaceagi.agent.core.adapter.dto.ChatMessageDto.removeSystemTagContent;

@Slf4j
@Service("conversationApplicationService")
public class ConversationApplicationServiceImpl extends AbstractTaskExecuteService implements ConversationApplicationService {

    private static final String DEFAULT_TOPIC_EN = "Unnamed conversation";

    private static final String DEFAULT_TOPIC_CN = "未命名会话";

    /**
     * 会话默认名按用户语言取：en-US 用英文，其余中文
     */
    private static String defaultTopic() {
        return "en-US".equalsIgnoreCase(RequestContext.get().getLang()) ? DEFAULT_TOPIC_EN : DEFAULT_TOPIC_CN;
    }

    private static final long CLOUD_SANDBOX_ID = -1L;
    private static final int MAX_USER_AGENT_TASK_SIZE = 10;

    /**
     * 随项目删除的项目会话类型（Agent/Plugin/Skill/PageApp）：
     * 项目存续期间不允许从会话入口单独删除，随项目删除联动清理
     */
    private static final Set<String> PROJECT_BOUND_DEV_TARGET_TYPES = Set.of(
            ProjectTypeEnum.Agent.name(), ProjectTypeEnum.Plugin.name(),
            ProjectTypeEnum.Skill.name(), ProjectTypeEnum.PageApp.name());

    /**
     * 工作区为项目共享目录的会话类型（UserApp/NormalProject）：
     * 会话可直接删除但不清理工作区文件，工作区随项目删除统一清理
     */
    private static final Set<String> SHARED_WORKSPACE_DEV_TARGET_TYPES = Set.of(
            ProjectTypeEnum.UserApp.name(), ProjectTypeEnum.NormalProject.name());

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private ConversationDomainService conversationDomainService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private AgentDomainService agentDomainService;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private ISandboxConfigRpcService iSandboxConfigRpcService;

    @Resource
    private SandboxServerConfigService sandboxServerConfigService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private IMemoryRpcService iMemoryRpcService;

    @Resource
    private SandboxAgentClient sandboxAgentClient;

    @Resource
    private ICustomPageRpcService iCustomPageRpcService;

    @Resource
    private IUserProjectRpcService iUserProjectRpcService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private UserTargetRelationDomainService userTargetRelationDomainService;

    @Resource
    private IUserAppRpcService iUserAppRpcService;

    @Resource
    private IConnectorApiService iConnectorApiService;

    @Resource
    private SkillApplicationService skillApplicationService;

    @Resource
    private PluginApplicationService pluginApplicationService;

    @Resource
    private AgentWorkspaceApplicationService agentWorkspaceApplicationService;

    @Resource
    private ChatApplicationService chatApplicationService;

    @PostConstruct
    private void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("longMemeryGenerator")
                .beanId("conversationApplicationService")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERY_5_SECOND.getCron())
                .params(Map.of())
                .build());
    }

    public ConversationDto createConversation(Long userId, Long agentId, boolean devMode) {
        return createConversation(userId, agentId, devMode, false);
    }

    @Override
    public ConversationDto createConversation(Long userId, Long agentId, boolean devMode, Map<String, Object> variables) {
        return createConversation(userId, agentId, devMode, false, variables);
    }

    @Override
    public ConversationDto createConversation(Long userId, Long agentId, boolean devMode, boolean tempChat) {
        return createConversation(userId, agentId, devMode, tempChat, null);
    }

    @Override
    public Long createRepoDocConversation(Long userId, Long agentId, String slugId, String docName) {
        Conversation conversation = new Conversation();
        conversation.setAgentId(agentId);
        conversation.setUserId(userId);
        conversation.setUid(UUID.randomUUID().toString().replace("-", ""));
        conversation.setTopic(docName);
        conversation.setTopicUpdated(0);
        conversation.setDevMode(0);
        Map<String, Object> variables = new HashMap<>();
        variables.put("SLUG_ID", slugId);
        conversation.setVariables(variables);
        conversation.setType(Conversation.ConversationType.Repo);
        conversation.setDevTargetType("Repo");
        conversation.setDevTargetId(slugId);
        conversationDomainService.createConversation(conversation);
        return conversation.getId();
    }

    @Override
    public void createConversationForPageApp(Long userId, Long agentId) {
        if (conversationDomainService.agentUserCount(userId, agentId) == 0) {
            //使用过的用户数量+1
            publishApplicationService.incStatisticsCount(Published.TargetType.Agent, agentId, PublishedStatistics.Key.USER_COUNT.getKey(), 1L);
            Conversation conversation = new Conversation();
            conversation.setAgentId(agentId);
            conversation.setUserId(userId);
            conversation.setUid(UUID.randomUUID().toString().replace("-", ""));
            conversation.setTopic(defaultTopic());
            conversation.setDevMode(0);
            conversation.setTenantId(RequestContext.get().getTenantId());
            conversation.setTopicUpdated(0);
            conversationDomainService.createConversation(conversation);
        }
        // 增加智能体统计会话数量
        publishApplicationService.incStatisticsCount(Published.TargetType.Agent, agentId, PublishedStatistics.Key.CONV_COUNT.getKey(), 1L);
    }

    @Override
    public ConversationDto createConversationForTaskCenter(Long tenantId, Long userId, Long agentId) {
        Conversation conversation = new Conversation();
        conversation.setTenantId(tenantId);
        conversation.setAgentId(agentId);
        conversation.setUserId(userId);
        conversation.setUid(UUID.randomUUID().toString().replace("-", ""));
        conversation.setTopic("");
        conversation.setDevMode(0);
        conversation.setTenantId(RequestContext.get().getTenantId());
        conversation.setType(Conversation.ConversationType.TaskCenter);
        return TenantFunctions.callWithIgnoreCheck(() -> {
            conversationDomainService.createConversation(conversation);
            return convertConversationWithAgent(conversation);
        });
    }

    @Override
    public ConversationDto createConversationForProjectDevelopment(Long tenantId, Long userId, Long devSpaceId, Long devAgentId, String targetType, Long targetId, Long sandboxId, String workspacePath) {
        Conversation conversation = new Conversation();
        conversation.setTenantId(tenantId);
        conversation.setAgentId(devAgentId);
        conversation.setDevSpaceId(devSpaceId);
        conversation.setDevTargetType(targetType);
        conversation.setDevTargetId(targetId.toString());
        // 同一项目可建多个会话：会话一律继承项目目录
        conversation.setUserId(userId);
        conversation.setUid(UUID.randomUUID().toString().replace("-", ""));
        conversation.setTopic("");
        conversation.setDevMode(0);
        conversation.setTenantId(RequestContext.get().getTenantId());
        conversation.setType(Conversation.ConversationType.Development);
        conversation.setTopicUpdated(1);
        ProjectTypeEnum type;
        try {
            type = ProjectTypeEnum.valueOf(targetType.trim());
        } catch (IllegalArgumentException e) {
            type = null;
        }

        // 为用户记录智能体沙箱
        if (sandboxId != null) {
            iSandboxConfigRpcService.saveUserSelectedSandboxId(userId, devAgentId, sandboxId);
        }

        Long resolvedSandboxId;
        if (type == ProjectTypeEnum.NormalProject || type == ProjectTypeEnum.UserApp) {
            // 项目会话（常规/全栈），继承自项目，忽略传入的沙箱
            UserProjectWorkspaceDto binding = iUserProjectRpcService.getUserWorkspace(userId, type, targetId);
            if (binding == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectNotFound);
            }
            resolvedSandboxId = binding.getSandboxId();
            conversation.setFileWorkspacePath(binding.getFileWorkspacePath());
            conversation.setAgentWorkspacePath(binding.getAgentWorkspacePath());
        } else {
            resolvedSandboxId = sandboxId != null ? sandboxId : iSandboxConfigRpcService.queryUserSelectedSandboxId(userId, devAgentId);
            // 会话级自定义目录：仅个人沙箱支持（RPC 内拦截云端/他人沙箱），目录必须锚定沙箱定位工作机
            if (StringUtils.isNotBlank(workspacePath)) {
                if (resolvedSandboxId == null || resolvedSandboxId == CLOUD_SANDBOX_ID) {
                    throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "workspacePath requires a personal sandbox");
                }
                String conversationWorkspacePath = iUserProjectRpcService.validateConversationWorkspace(userId, targetId, resolvedSandboxId, workspacePath);
                conversation.setFileWorkspacePath(conversationWorkspacePath);
                conversation.setAgentWorkspacePath(conversationWorkspacePath);
            } else if (resolvedSandboxId != null && resolvedSandboxId == CLOUD_SANDBOX_ID) {
                resolvedSandboxId = allocateCloudSandbox(userId);
            }
        }
        if (resolvedSandboxId != null && resolvedSandboxId > 0) {
            conversation.setSandboxServerId(resolvedSandboxId.toString());
        }
        return TenantFunctions.callWithIgnoreCheck(() -> {
            conversationDomainService.createConversation(conversation);
            return convertConversationWithAgent(conversation);
        });
    }


    @Override
    public ConversationDto createConversation(Long userId, Long agentId, boolean devMode, boolean tempChat, Map<String, Object> variables) {
        return doCreateConversation(userId, agentId, devMode, tempChat, variables, null, null, null, null);
    }

    @Override
    public ConversationDto createConversation(Long userId, Long agentId, boolean devMode, Map<String, Object> variables, Long sandboxId, String workspacePath, Long projectId, String projectType) {
        return doCreateConversation(userId, agentId, devMode, false, variables, sandboxId, workspacePath, projectId, projectType);
    }

    /**
     * 分配真实云端沙箱 id
     */
    private Long allocateCloudSandbox(Long userId) {
        TenantConfigDto tenantConfig = tenantConfigApplicationService.getTenantConfig(RequestContext.get().getTenantId());
        SandboxServerConfig.SandboxServer server = sandboxServerConfigService.selectServer(tenantConfig, userId, String.valueOf(CLOUD_SANDBOX_ID));
        if (server == null || server.getServerId() == null) {
            return null;
        }
        try {
            return Long.parseLong(server.getServerId());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 沙箱归属前置校验（对前端传入的真实沙箱 id）
     */
    private void validateSandboxOwnership(Long userId, Long sandboxId) {
        SandboxConfigRpcDto sandboxConfig = iSandboxConfigRpcService.queryByIdInTenant(sandboxId);
        if (sandboxConfig == null) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "sandboxId not found");
        }
        if (sandboxConfig.getScope() == SandboxScopeEnum.USER
                && (!userId.equals(sandboxConfig.getUserId()) || Boolean.FALSE.equals(sandboxConfig.getIsActive()))) {
            throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(),
                    "sandboxId must be an active personal sandbox of the current user");
        }
    }

    /**
     * 判断项目绑定沙箱是否云端
     */
    private boolean isProjectCloudSandbox(UserProjectDto project) {
        if (project.getSandboxType() != null) {
            return project.getSandboxType() == SandboxTypeEnum.Cloud;
        }
        if (project.getSandboxId() == null || project.getSandboxId() <= 0) {
            return false;
        }
        SandboxConfigRpcDto sandboxConfig = iSandboxConfigRpcService.queryByIdInTenant(project.getSandboxId());
        return sandboxConfig == null || sandboxConfig.getScope() != SandboxScopeEnum.USER;
    }

    /**
     * 会话沙箱解析模式
     */
    private enum SandboxMode {
        /** 独立会话（无项目） */
        Standalone,
        /** 继承项目：项目沙箱 + 项目目录绑定 */
        Inherit,
        /** 会话级覆盖（目录+沙箱成对）：目录已归一化，沙箱用传入值 */
        OverrideWithDir,
        /** 会话级只换沙箱：沙箱用传入值（-1 时分配真实云端）；目录不落项目绑定，由 file-server 默认规则处理 */
        SandboxOnly
    }

    /**
     * 会话执行环境决策结果
     *
     * @param project                    关联项目（包含隐式创建），null=无项目
     * @param resolvedSandboxId          决策产物沙箱 id（非原始传参）；-1=云端哨兵（后续按场景分配真实云端沙箱或保持不落库）
     * @param sandboxMode                沙箱解析模式，见 {@link SandboxMode}
     * @param conversationWorkspacePath  会话级自定义目录（已归一化），null=不走会话级目录
     * @param workspaceBinding           项目级目录绑定行（用户级：同用户同项目多会话共享目录，不同用户隔离）
     */
    private record ConversationEnv(
            UserProjectDto project,
            Long resolvedSandboxId,
            SandboxMode sandboxMode,
            String conversationWorkspacePath,
            UserProjectWorkspaceDto workspaceBinding) {

        /** 独立会话：直接用传入沙箱（可能为 null 或 -1） */
        static ConversationEnv standalone(Long sandboxId) {
            return new ConversationEnv(null, sandboxId, SandboxMode.Standalone, null, null);
        }
    }

    private ConversationDto doCreateConversation(Long userId, Long agentId, boolean devMode, boolean tempChat, Map<String, Object> variables, Long sandboxId, String workspacePath, Long projectId, String projectType) {
        AgentConfigDto agentConfigDto = agentApplicationService.queryById(agentId);
        if (agentConfigDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSelectedNotFound);
        }
        if (conversationDomainService.agentUserCount(userId, agentId) == 0 && !devMode) {
            //使用过的用户数量+1
            publishApplicationService.incStatisticsCount(Published.TargetType.Agent, agentId, PublishedStatistics.Key.USER_COUNT.getKey(), 1L);
        }
        Conversation conversation = buildBaseConversation(userId, agentId, devMode, tempChat, variables);
        // DevDebug 沙箱继承须先于环境决策执行
        applyDevDebugInheritance(conversation, agentConfigDto, devMode);

        // 沙箱与工作目录决策，产出后不再变更
        ConversationEnv env = resolveConversationEnv(userId, sandboxId, workspacePath, projectId, projectType);
        Long finalSandboxId;
        if (env.project() != null) {
            applyProjectTarget(conversation, env.project());
            finalSandboxId = resolveProjectSandboxId(env, userId);
        } else {
            // 未关联项目时用传入值（-1=云端哨兵，分配真实云端沙箱落库）
            finalSandboxId = resolveStandaloneSandboxId(env.resolvedSandboxId(), userId, agentId, agentConfigDto);
        }
        if (finalSandboxId != null && finalSandboxId > 0) {
            conversation.setSandboxServerId(finalSandboxId.toString());
        }
        applyWorkspacePaths(conversation, env);
        conversationDomainService.createConversation(conversation);
        // 增加智能体统计会话数量
        publishApplicationService.incStatisticsCount(Published.TargetType.Agent, agentId, PublishedStatistics.Key.CONV_COUNT.getKey(), 1L);

        if (devMode && !tempChat) {
            bindDevConversation(agentId, conversation.getId(), agentConfigDto);
        }

        return convertConversationWithAgent(conversation);
    }

    private Conversation buildBaseConversation(Long userId, Long agentId, boolean devMode, boolean tempChat, Map<String, Object> variables) {
        Conversation conversation = new Conversation();
        conversation.setAgentId(agentId);
        conversation.setUserId(userId);
        conversation.setUid(UUID.randomUUID().toString().replace("-", ""));
        conversation.setTopic(defaultTopic());
        conversation.setDevMode(devMode ? 1 : 0);
        conversation.setVariables(variables);
        conversation.setTenantId(RequestContext.get().getTenantId());
        if (tempChat) {
            conversation.setType(Conversation.ConversationType.TempChat);
        }
        return conversation;
    }

    /**
     * devMode 且 agent 已有调试会话（或由 VibeCoding 创建）→ 会话标记为 DevDebug，
     * 并继承 agent 调试会话的沙箱（为空时为选择了云端电脑）
     */
    private void applyDevDebugInheritance(Conversation conversation, AgentConfigDto agentConfigDto, boolean devMode) {
        boolean isCreatedFromVibeCoding = agentConfigDto.getExtra() != null && agentConfigDto.getExtra().get("createdFrom") != null && "VibeCoding".equals(agentConfigDto.getExtra().get("createdFrom"));
        if (!devMode || (agentConfigDto.getDevAgentConversationId() == null && !isCreatedFromVibeCoding)) {
            return;
        }
        conversation.setType(Conversation.ConversationType.DevDebug);
        ConversationDto conversationByCid = getConversationByCid(agentConfigDto.getDevAgentConversationId());
        conversation.setSandboxServerId(conversationByCid.getSandboxServerId() == null ? "-1" : conversationByCid.getSandboxServerId());
    }

    /**
     * 沙箱与工作目录决策，分三路：
     * 项目会话 / 隐式建项目 / 独立会话
     */
    private ConversationEnv resolveConversationEnv(Long userId, Long sandboxId, String workspacePath, Long projectId, String projectType) {
        if (sandboxId != null && sandboxId > 0) {
            validateSandboxOwnership(userId, sandboxId);
        }
        if (projectId != null) {
            return resolveProjectEnv(userId, sandboxId, workspacePath, projectId, projectType);
        }
        if (sandboxId != null && StringUtils.isNotBlank(workspacePath)) {
            return resolveImplicitProjectEnv(userId, sandboxId, workspacePath);
        }
        return ConversationEnv.standalone(sandboxId);
    }

    /**
     * 项目会话
     */
    private ConversationEnv resolveProjectEnv(Long userId, Long sandboxId, String workspacePath, Long projectId, String projectType) {
        ProjectTypeEnum type = parseProjectType(projectType);
        UserProjectDto project = iUserProjectRpcService.queryByProjectId(type, projectId);
        if (project == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectNotFound);
        }
        // 空间权限：项目会话须为项目所属空间成员（或平台管理员）
        spacePermissionService.checkSpaceUserPermission(project.getSpaceId(), userId);

        if (type == ProjectTypeEnum.NormalProject) {
            return normalProjectEnv(userId, sandboxId, workspacePath, project);
        }
        if (type == ProjectTypeEnum.UserApp) {
            return userAppEnv(userId, project);
        }
        // 其他类型
        throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "unsupported project type for conversation: " + type);
    }

    /**
     * 常规项目
     */
    private ConversationEnv normalProjectEnv(Long userId, Long sandboxId, String workspacePath, UserProjectDto project) {
        ResolvedConversationDir dir = resolveConversationDir(userId, sandboxId, workspacePath, project);
        SandboxMode sandboxMode = resolveSandboxMode(project, dir.sandboxId(), dir.path());
        // Inherit 查/建项目目录绑定行
        UserProjectWorkspaceDto workspaceBinding = sandboxMode == SandboxMode.Inherit
                ? iUserProjectRpcService.ensureUserWorkspace(userId, ProjectTypeEnum.NormalProject, project.getProjectId())
                : null;
        return new ConversationEnv(project, dir.sandboxId(), sandboxMode, dir.path(), workspaceBinding);
    }

    /**
     * 全栈应用
     */
    private ConversationEnv userAppEnv(Long userId, UserProjectDto project) {
        //不用关心前端传的什么：目录忽略，沙箱恒继承项目
        return new ConversationEnv(project, CLOUD_SANDBOX_ID, SandboxMode.Inherit, null,
                iUserProjectRpcService.ensureUserWorkspace(userId, ProjectTypeEnum.UserApp, project.getProjectId()));
    }

    /** 会话级目录解析结果：锚定后的沙箱 id + 归一化目录 */
    private record ResolvedConversationDir(Long sandboxId, String path) {
    }

    /**
     * 会话级自定义目录解析
     */
    private ResolvedConversationDir resolveConversationDir(Long userId, Long sandboxId, String workspacePath, UserProjectDto project) {
        if (StringUtils.isBlank(workspacePath)) {
            return new ResolvedConversationDir(sandboxId, null);
        }
        if (sandboxId == null) {
            sandboxId = project.getSandboxId() != null ? project.getSandboxId() : CLOUD_SANDBOX_ID;
        }
        return new ResolvedConversationDir(sandboxId, iUserProjectRpcService.validateConversationWorkspace(userId, project.getProjectId(), sandboxId, workspacePath));
    }

    /**
     * 沙箱解析模式判定（仅常规项目）
     */
    private SandboxMode resolveSandboxMode(UserProjectDto project,
            Long resolvedSandboxId, String conversationWorkspacePath) {
        // 传了目录+沙箱
        if (conversationWorkspacePath != null) {
            return SandboxMode.OverrideWithDir;
        }
        // 未传沙箱和目录
        if (resolvedSandboxId == null) {
            return SandboxMode.Inherit;
        }
        // 只传云端沙箱，未传目录：如果项目是云端沙箱，则继承，否则会话覆盖
        if (resolvedSandboxId == CLOUD_SANDBOX_ID || !isPersonalSandbox(resolvedSandboxId)) {
            return isProjectCloudSandbox(project) ? SandboxMode.Inherit : SandboxMode.SandboxOnly;
        }
        // 只传个人沙箱，未传目录
        return SandboxMode.SandboxOnly;
    }

    /** 是否个人沙箱 id：-1 哨兵与云端/租户/未知 id 均不是 */
    private boolean isPersonalSandbox(Long sandboxId) {
        if (sandboxId == null || sandboxId <= 0) {
            return false;
        }
        SandboxConfigRpcDto sandboxConfig = iSandboxConfigRpcService.queryByIdInTenant(sandboxId);
        return sandboxConfig != null && sandboxConfig.getScope() == SandboxScopeEnum.USER;
    }

    /** 项目定位必须 id+类型 */
    private ProjectTypeEnum parseProjectType(String projectType) {
        if (StringUtils.isBlank(projectType)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectTypeRequired);
        }
        try {
            return ProjectTypeEnum.valueOf(projectType.trim());
        } catch (IllegalArgumentException e) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectTypeInvalid);
        }
    }

    /**
     * 项目外 个人沙箱+自定义目录 → 隐式创建常规项目并绑定该目录（占用一律报错）；
     * 非个人沙箱则不建项目，直接用传入沙箱
     */
    private ConversationEnv resolveImplicitProjectEnv(Long userId, Long sandboxId, String workspacePath) {
        SandboxConfigRpcDto sandboxConfig = iSandboxConfigRpcService.queryByIdInTenant(sandboxId);
        if (sandboxConfig == null || sandboxConfig.getScope() != SandboxScopeEnum.USER) {
            return ConversationEnv.standalone(sandboxId);
        }
        // createNormal 内部已登记目录（含自定义），纯查询取回绑定行用于写会话双路径
        UserProjectCreateDto implicitDto = buildImplicitNormalCreateDto(userId, sandboxId);
        implicitDto.setWorkspacePath(workspacePath);
        UserProjectDto project = iUserProjectRpcService.createNormal(implicitDto);
        UserProjectWorkspaceDto workspaceBinding = iUserProjectRpcService.getUserWorkspace(userId, ProjectTypeEnum.NormalProject, project.getProjectId());
        if (workspaceBinding == null) {
            // createNormal 事务内已登记，缺失即数据不一致
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userProjectNotFound);
        }
        log.info("Normal project implicitly created for conversation, projectId={}, sandboxId={}, workspacePath={}",
                project.getProjectId(), sandboxId, workspaceBinding.getFileWorkspacePath());
        return new ConversationEnv(project, sandboxId, SandboxMode.Inherit, null, workspaceBinding);
    }

    /**
     * 项目上会话沙箱ID的获取
     */
    private Long resolveProjectSandboxId(ConversationEnv env, Long userId) {
        // 继承项目沙箱
        if (env.sandboxMode() == SandboxMode.Inherit) {
            return env.project().getSandboxId();
        }
        // 用传入沙箱
        if (env.sandboxMode() == SandboxMode.SandboxOnly && env.resolvedSandboxId() == CLOUD_SANDBOX_ID) {
            Long allocated = allocateCloudSandbox(userId);
            if (allocated == null) {
                throw new BizException(ErrorCodeEnum.INVALID_PARAM.getCode(), "No cloud sandbox available for this conversation");
            }
            return allocated;
        }
        return env.resolvedSandboxId();
    }

    /**
     * 独立会话沙箱ID的获取
     */
    private Long resolveStandaloneSandboxId(Long sandboxId, Long userId, Long agentId, AgentConfigDto agentConfigDto) {
        if (sandboxId == null || sandboxId != CLOUD_SANDBOX_ID || "ChatBot".equals(agentConfigDto.getType())) {
            return sandboxId;
        }
        Long allocated = allocateCloudSandbox(userId);
        if (allocated != null) {
            iSandboxConfigRpcService.saveUserSelectedSandboxId(userId, agentId, allocated);
            return allocated;
        }
        return sandboxId;
    }

    private void applyProjectTarget(Conversation conversation, UserProjectDto project) {
        if (project.getProjectType() != null) {
            conversation.setDevTargetType(project.getProjectType().name());
        }
        conversation.setDevTargetId(String.valueOf(project.getProjectId()));
        conversation.setDevSpaceId(project.getSpaceId());
        conversation.setType(Conversation.ConversationType.Development);
    }

    /**
     * 写入文件访问目录
     */
    private void applyWorkspacePaths(Conversation conversation, ConversationEnv env) {
        if (env.conversationWorkspacePath() != null) {
            conversation.setFileWorkspacePath(env.conversationWorkspacePath());
            conversation.setAgentWorkspacePath(env.conversationWorkspacePath());
        } else if (env.workspaceBinding() != null) {
            conversation.setFileWorkspacePath(env.workspaceBinding().getFileWorkspacePath());
            conversation.setAgentWorkspacePath(env.workspaceBinding().getAgentWorkspacePath());
        }
    }

    /** devMode 非临时会话 → 回写 agent 的调试会话 id（modified 乐观校验） */
    private void bindDevConversation(Long agentId, Long conversationId, AgentConfigDto source) {
        AgentConfigDto updateDto = new AgentConfigDto();
        updateDto.setId(agentId);
        updateDto.setDevConversationId(conversationId);
        updateDto.setModified(source.getModified());
        agentApplicationService.update(updateDto);
    }

    /**
     * 隐式创建常规项目：空间取用户个人空间（无个人空间取第一个）；目录由调用方另行绑定
     */
    private UserProjectCreateDto buildImplicitNormalCreateDto(Long userId, Long sandboxId) {
        List<SpaceDto> spaces = spaceApplicationService.queryListByUserId(userId);
        if (spaces == null || spaces.isEmpty()) {
            throw new BizException("Invalid user data");
        }
        Long spaceId = spaces.stream()
                .filter(s -> s.getType() == Space.Type.Personal)
                .findFirst().orElse(spaces.get(0)).getId();
        UserProjectCreateDto createDto = new UserProjectCreateDto();
        createDto.setCreatorId(userId);
        createDto.setSpaceId(spaceId);
        createDto.setSandboxId(sandboxId);
        return createDto;
    }

    @Override
    public ConversationDto createConversationForMasterAgentLoop(Long tenantId, Long userId, Long agentId, String topic, Map<String, Object> variables) {
        Conversation conversation = new Conversation();
        conversation.setAgentId(agentId);
        conversation.setUserId(userId);
        conversation.setUid(UUID.randomUUID().toString().replace("-", ""));
        conversation.setTopic(topic);
        conversation.setTopicUpdated(1);
        conversation.setDevMode(0);
        conversation.setVariables(variables);
        conversation.setTenantId(tenantId);
        conversation.setType(Conversation.ConversationType.Chat);
        TenantFunctions.runWithIgnoreCheck(() -> conversationDomainService.createConversation(conversation));
        return convertConversation(conversation);
    }

    @Override
    public ConversationDto createTaskConversation(Long userId, TaskConversationAddOrUpdateDto taskConversationAddOrUpdateDto) {
        List<ConversationDto> conversationDtoList = queryTaskConversationList(userId, taskConversationAddOrUpdateDto.getAgentId(), Conversation.ConversationTaskStatus.EXECUTING);
        if (conversationDtoList.size() >= MAX_USER_AGENT_TASK_SIZE) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentScheduledTaskLimitExceeded,
                    String.valueOf(MAX_USER_AGENT_TASK_SIZE));
        }
        //检查taskCron是否符合规范
        if (!ScheduleTaskDto.Cron.isValid(taskConversationAddOrUpdateDto.getTaskCron())) {
            throw new IllegalArgumentException("Invalid cron expression");
        }
        ConversationDto conversationDto = createConversation(userId, taskConversationAddOrUpdateDto.getAgentId(), taskConversationAddOrUpdateDto.isDevMode());
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId(conversationDto.getUid())
                .beanId("conversationTaskService")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(taskConversationAddOrUpdateDto.getTaskCron())
                .params(Map.of("id", conversationDto.getId(), "tenantId", conversationDto.getTenantId(), "agentId", conversationDto.getAgentId(), "userId", userId))
                .build());
        conversationDto.setType(Conversation.ConversationType.TASK);
        Conversation conversation = new Conversation();
        conversation.setId(conversationDto.getId());
        conversation.setTaskId(conversationDto.getUid());
        conversation.setTopic(taskConversationAddOrUpdateDto.getTopic());
        conversation.setSummary(taskConversationAddOrUpdateDto.getSummary());
        conversation.setTaskCron(taskConversationAddOrUpdateDto.getTaskCron());
        conversation.setTaskStatus(Conversation.ConversationTaskStatus.EXECUTING);
        conversation.setType(Conversation.ConversationType.TASK);
        conversationDomainService.updateConversation(userId, conversationDto.getId(), conversation);
        return getConversation(userId, conversationDto.getId());
    }

    @Override
    public void cancelTaskConversation(Long userId, Long conversationId) {
        Conversation conversation = new Conversation();
        conversation.setTaskStatus(Conversation.ConversationTaskStatus.CANCEL);
        conversationDomainService.updateConversation(userId, conversationId, conversation);
        Conversation conversation1 = conversationDomainService.getConversation(conversationId);
        if (conversation1 != null) {
            scheduleTaskApiService.cancel(conversation1.getTaskId());
        }
    }

    @Override
    public void updateTaskConversation(Long userId, TaskConversationAddOrUpdateDto taskConversationAddOrUpdateDto) {
        //检查taskCron是否符合规范
        if (taskConversationAddOrUpdateDto.getTaskCron() != null && !ScheduleTaskDto.Cron.isValid(taskConversationAddOrUpdateDto.getTaskCron())) {
            throw new IllegalArgumentException("Invalid cron expression");
        }
        Assert.notNull(taskConversationAddOrUpdateDto.getId(), "Conversation ID cannot be null");
        Conversation conversation = new Conversation();
        conversation.setId(taskConversationAddOrUpdateDto.getId());
        conversation.setTaskCron(taskConversationAddOrUpdateDto.getTaskCron());
        conversation.setTopic(taskConversationAddOrUpdateDto.getTopic());
        conversation.setSummary(taskConversationAddOrUpdateDto.getSummary());
        conversationDomainService.updateConversation(userId, taskConversationAddOrUpdateDto.getId(), conversation);
        Conversation conversation1 = conversationDomainService.getConversation(taskConversationAddOrUpdateDto.getId());
        if (conversation1 != null) {
            scheduleTaskApiService.update(ScheduleTaskDto.builder()
                    .taskId(conversation1.getUid())
                    .cron(taskConversationAddOrUpdateDto.getTaskCron())
                    .build());
        }
    }

    @Override
    public void updateConversationSandboxServerId(Long cid, String sandboxServerId) {
        TenantFunctions.runWithIgnoreCheck(() -> conversationDomainService.updateConversation(cid, Conversation.builder().sandboxServerId(sandboxServerId).build()));
    }

    @Override
    public void updateConversationStatus(Long cid, Conversation.ConversationTaskStatus status) {
        conversationDomainService.updateConversation(cid, Conversation.builder().taskStatus(status).build());
    }

    @Override
    public void updateConversationVariables(Long id, Map<String, Object> variables) {
        Map<String, Object> variables0 = new HashMap<>();
        if (variables != null) {
            //去除GlobalVariableEnum.isSystemVariable
            variables0.putAll(variables);
            variables0.entrySet().removeIf(entry -> GlobalVariableEnum.isSystemVariable(entry.getKey()) || "SANDBOX_ACCESS_KEY".equals(entry.getKey()));
        }
        TenantFunctions.runWithIgnoreCheck(() -> conversationDomainService.updateConversation(id, Conversation.builder().variables(variables0).build()));
    }

    //Conversation转ConversationDto
    private ConversationDto convertConversation(Conversation conversation) {
        if (conversation == null) {
            return null;
        }
        ConversationDto conversationDto = new ConversationDto();
        conversationDto.setId(conversation.getId());
        conversationDto.setUid(conversation.getUid());
        conversationDto.setAgentId(conversation.getAgentId());
        conversationDto.setUserId(conversation.getUserId());
        conversationDto.setTopic(conversation.getTopic());
        conversationDto.setSummary(conversation.getSummary());
        conversationDto.setCreated(conversation.getCreated());
        conversationDto.setModified(conversation.getModified());
        conversationDto.setDevMode(conversation.getDevMode());
        conversationDto.setTaskCron(conversation.getTaskCron());
        conversationDto.setTaskCronDesc(TaskCron.getCronDesc(conversation.getTaskCron()));
        conversationDto.setTaskStatus(conversation.getTaskStatus());
        conversationDto.setTenantId(conversation.getTenantId());
        conversationDto.setType(conversation.getType());
        conversationDto.setVariables(conversation.getVariables());
        conversationDto.setTopicUpdated(conversation.getTopicUpdated());
        conversationDto.setSandboxSessionId(conversation.getSandboxSessionId());
        conversationDto.setSandboxServerId(conversation.getSandboxServerId());
        conversationDto.setDevTargetType(conversation.getDevTargetType());
        conversationDto.setDevTargetId(conversation.getDevTargetId());
        conversationDto.setFileWorkspacePath(conversation.getFileWorkspacePath());
        conversationDto.setAgentWorkspacePath(conversation.getAgentWorkspacePath());
        conversationDto.setPinned(Boolean.TRUE.equals(conversation.getPinned()));
        conversationDto.setArchived(Boolean.TRUE.equals(conversation.getArchived()));
        conversationDto.setDevSpaceId(conversation.getDevSpaceId());
        conversationDto.setIcon(conversation.getIcon());
        Map<String, Object> vars = conversation.getVariables() != null ? new HashMap<>(conversation.getVariables()) : new HashMap<>();
        conversationDto.setVariables(vars);
        if (RequestContext.get() == null) {
            return conversationDto;
        }
        try {
            if (ProjectTypeEnum.NormalProject.name().equals(conversation.getDevTargetType())) {
                UserProjectDto userProjectDto = iUserProjectRpcService.queryById(Long.parseLong(conversation.getDevTargetId()));
                if (userProjectDto != null && userProjectDto.getRepoSlugId() != null) {
                    vars.put("PROJECT_FOLDER_SLUG_ID", userProjectDto.getRepoSlugId());
                }
                if (userProjectDto != null && userProjectDto.getPlanSlugId() != null) {
                    vars.put("PROJECT_PLAN_SLUG_ID", userProjectDto.getPlanSlugId());
                }
            }
            if (ProjectTypeEnum.UserApp.name().equals(conversation.getDevTargetType())) {
                UserAppDto userAppDto = iUserAppRpcService.queryById(Long.parseLong(conversation.getDevTargetId()));
                if (userAppDto != null && userAppDto.getRepoSlugId() != null) {
                    vars.put("PROJECT_FOLDER_SLUG_ID", userAppDto.getRepoSlugId());
                }
                if (userAppDto != null && userAppDto.getPlanSlugId() != null) {
                    vars.put("PROJECT_PLAN_SLUG_ID", userAppDto.getPlanSlugId());
                }
            }
        } catch (Exception e) {
            //ignore
            log.warn("查询项目异常 {} {}", conversation.getDevTargetType(), conversation.getDevTargetId(), e);
        }
        //用户已启用的连接器整理成 md 表格放进会话变量，供提示词以 {{ENABLED_CONNECTORS}} 引用
        try {
            String enabledConnectorTable = buildEnabledConnectorTable(conversation);
            if (enabledConnectorTable != null) {
                vars.put("ENABLED_CONNECTORS", enabledConnectorTable);
            }
        } catch (Exception e) {
            //ignore
            log.warn("查询连接器异常 {} {}", conversation.getUserId(), conversation.getTenantId(), e);
        }
        return conversationDto;
    }

    /**
     * 用户已启用的连接器 → markdown 表格，列固定 {@code |name|service|}。
     * <p>
     * 口径是「用户开关打开」（{@code connector_connection.enabled = 1}），**不看凭证健康度**——
     * 「启用了但 token 已过期」也算已启用，与连接器目录侧 {@code connectionEnabled=true} 同口径。
     * <p>
     * 可见范围是「官方 + 用户全部相关空间」，由连接器侧解析（与目录页不传 spaceId 时同一算法）。
     * 这里**不传会话的 {@code devSpaceId}**：普通会话该字段为空，传了会退化成只剩官方目录，
     * 把用户已启用的空间自建连接器漏掉。
     * <p>
     * 无已启用连接器时返回 {@code null}（不塞只有表头的空表）；名称里的 {@code |} 与换行会撑坏表格，做转义。
     */
    private String buildEnabledConnectorTable(Conversation conversation) {
        if (conversation.getUserId() == null) {
            return null;
        }
        List<ConnectorProviderInfo> providers = iConnectorApiService.listEnabledProviders(
                conversation.getTenantId(), conversation.getUserId());
        if (CollectionUtils.isEmpty(providers)) {
            return null;
        }
        StringBuilder table = new StringBuilder("|name|service|\n|---|---|\n");
        for (ConnectorProviderInfo provider : providers) {
            table.append('|').append(escapeTableCell(provider.getDisplayName()))
                    .append('|').append(escapeTableCell(provider.getService())).append("|\n");
        }
        return table.toString();
    }

    /**
     * markdown 表格单元格转义：竖线会拆列、换行会断行
     */
    private static String escapeTableCell(String value) {
        return value == null ? "" : value.replace("|", "\\|").replaceAll("[\r\n]+", " ");
    }

    private ConversationDto convertConversationWithAgent(Conversation conversation) {
        if (conversation == null) {
            return null;
        }
        ConversationDto conversationDto = convertConversation(conversation);
        AgentDetailDto agentDetailDto = agentApplicationService.queryAgentDetail(conversation.getAgentId(), conversation.getDevMode() != 1);
        if (agentDetailDto != null) {
            conversationDto.setAgent(agentDetailDto);
            List<Arg> variables = agentDetailDto.getVariables();
            Set<String> nameSet;
            if (variables != null) {
                nameSet = agentDetailDto.getVariables().stream().map(Arg::getName).collect(Collectors.toSet());
            } else {
                nameSet = Collections.emptySet();
            }
            if (conversationDto.getVariables() != null) {
                conversationDto.getVariables().entrySet().removeIf(entry -> !nameSet.contains(entry.getKey()));
            }
        } else {
            conversationDto.setVariables(null);
        }

        return conversationDto;
    }

    @Override
    public void deleteConversation(Long userId, Long id) {
        Conversation conversation = conversationDomainService.getConversation(userId, id);
        if (conversation == null) {
            return;
        }
        // 定时任务会话不允许从会话列表直接删除
        if (conversation.getType() == Conversation.ConversationType.TASK) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentTaskConversationDeleteDenied);
        }
        // devTargetType 可空（普通会话为 null），Set.of 的 contains(null) 会抛 NPE，先取出判空
        String devTargetType = conversation.getDevTargetType();
        // Agent/Plugin/Skill/PageApp 项目会话：项目存续期间不允许单独删除（随项目删除联动清理）；
        // 项目已不存在时放行，用于清理存量残骸会话
        if (devTargetType != null && PROJECT_BOUND_DEV_TARGET_TYPES.contains(devTargetType)
                && devTargetProjectExists(devTargetType, conversation.getDevTargetId())) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentProjectConversationDeleteDenied);
        }
        // 云端沙箱会话删除时同步清理工作区文件释放资源；个人沙箱不清理。
        // UserApp/NormalProject 会话的工作区为项目共享目录，不随单个会话删除清理，随项目删除统一清理
        if (devTargetType == null || !SHARED_WORKSPACE_DEV_TARGET_TYPES.contains(devTargetType)) {
            cleanupCloudSandboxWorkspace(conversation);
        }
        deleteConversationInternal(conversation);
        //不需要减少智能体统计会话数量
        // agentApplicationService.incAgentStatisticsCount(conversation.getAgentId(), "conv_count", -1);
    }

    /** 随项目删除的项目类型（Agent/Plugin/Skill/PageApp）对应项目是否仍然存在 */
    private boolean devTargetProjectExists(String devTargetType, String devTargetId) {
        ProjectTypeEnum type = ProjectTypeEnum.fromValue(devTargetType);
        if (type == null || StringUtils.isBlank(devTargetId)) {
            return false;
        }
        Long id;
        try {
            id = Long.parseLong(devTargetId.trim());
        } catch (NumberFormatException e) {
            return false;
        }
        try {
            return switch (type) {
                case Agent -> CollectionUtils.isNotEmpty(agentDomainService.queryListByIds(List.of(id)));
                case Plugin -> pluginApplicationService.queryById(id) != null;
                case Skill -> skillApplicationService.queryById(id) != null;
                case PageApp -> CollectionUtils.isNotEmpty(iCustomPageRpcService.listByIds(List.of(id), null));
                default -> false;
            };
        } catch (Exception e) {
            // 查询失败按项目存在处理（fail-closed）：放行会误删存续项目的会话与云端工作区，不可恢复；
            // 按存在拦截仅让本次删除失败，可重试
            log.warn("Failed to check dev target project exists, type={}, id={}", devTargetType, devTargetId, e);
            throw new BizException(ErrorCodeEnum.ERROR_REQUEST.getCode(), "项目状态查询失败，请稍后重试");
        }
    }

    /** 云端沙箱（GLOBAL）会话删除前清理其工作区文件；个人沙箱（USER）与无沙箱会话不处理 */
    private void cleanupCloudSandboxWorkspace(Conversation conversation) {
        String sandboxServerId = conversation.getSandboxServerId();
        if (StringUtils.isBlank(sandboxServerId) || "-1".equals(sandboxServerId)) {
            return;
        }
        try {
            Long serverId = Long.parseLong(sandboxServerId.trim());
            SandboxConfigRpcDto sandboxConfig = iSandboxConfigRpcService.queryByIdInTenant(serverId);
            if (sandboxConfig != null && sandboxConfig.getScope() == SandboxScopeEnum.GLOBAL) {
                agentWorkspaceApplicationService.deleteWorkspace(conversation.getUserId(), conversation.getId());
            }
        } catch (Exception e) {
            // 工作区清理失败不阻断会话删除，残留目录由沙箱侧回收策略兜底
            log.warn("Failed to cleanup cloud sandbox workspace for deleted conversation, cId={}", conversation.getId(), e);
        }
    }

    @Override
    public void deleteTaskConversationByTaskId(String taskId) {
        if (StringUtils.isBlank(taskId)) {
            return;
        }
        // TASK 会话 uid 即调度任务 taskId；仅联动 TASK 类型，避免误删同 uid 的其它会话
        Conversation conversation = conversationDomainService.getConversationByUid(taskId);
        if (conversation == null || conversation.getType() != Conversation.ConversationType.TASK) {
            return;
        }
        // 云端沙箱工作区一并清理（个人沙箱不清理）
        cleanupCloudSandboxWorkspace(conversation);
        deleteConversationInternal(conversation);
    }

    @Override
    public void deleteConversationDirect(Long conversationId) {
        if (conversationId == null) {
            return;
        }
        Conversation conversation = conversationDomainService.getConversation(conversationId);
        if (conversation == null) {
            return;
        }
        // 项目删除联动场景：云端沙箱工作区一并清理（个人沙箱不清理）
        cleanupCloudSandboxWorkspace(conversation);
        deleteConversationInternal(conversation);
    }

    /** 删会话行（含消息）并清理收藏关系；收藏清理失败不阻断删除，残留关系查询时自然过滤 */
    private void deleteConversationInternal(Conversation conversation) {
        // 执行中的会话先停流（TaskAgent 取消沙箱执行、普通会话设停止位），再删行；
        // 否则删除后执行流继续跑，产出的消息会写入已删会话成为孤儿消息
        if (conversation.getTaskStatus() == Conversation.ConversationTaskStatus.EXECUTING) {
            chatApplicationService.stopChat(conversation.getId().toString());
        }
        conversationDomainService.deleteConversation(conversation.getUserId(), conversation.getId());
        try {
            userTargetRelationDomainService.unRecordAll(Published.TargetType.Conversation, UserTargetRelation.OpType.Collect, conversation.getId());
        } catch (Exception e) {
            log.warn("Failed to clear collect relations for deleted conversation, cId={}", conversation.getId(), e);
        }
    }

    @Override
    public void updateConversationTopic(Long userId, ConversationUpdateDto conversationUpdateDto) {
        Long id = conversationUpdateDto.getId();
        TopicGenDto topicGenDto = null;
        if (StringUtils.isBlank(conversationUpdateDto.getTopic())) {
            Conversation conversation1 = conversationDomainService.getConversation(id);
            StringBuilder userPrompt = new StringBuilder();
            if (conversation1 != null && StringUtils.isNotBlank(conversation1.getSummary())) {
                userPrompt.append("## Historical session summary content is as follows:\n").append(conversation1.getSummary());
            }
            if (conversation1 != null && StringUtils.isNotBlank(conversation1.getTopic())) {
                userPrompt.append("## Historical session topic:\n").append(conversation1.getTopic());
            }
            String lang = "Thread title`s language based on the <content> as follows";
            if (RequestContext.get() != null && RequestContext.get().getLang() != null) {
                lang = "Thread title`s language " + RequestContext.get().getLang();
            }
            userPrompt.append("\n## The content that needs to generate a thread title is as follows. ").append(lang).append(":\n<content>").append(conversationUpdateDto.getFirstMessage()).append("</content>\n");
            try {
                topicGenDto = modelApplicationService.call(Prompts.CONVERSATION_TOPIC_PROMPT, userPrompt.toString(), new ParameterizedTypeReference<TopicGenDto>() {
                });
            } catch (Exception e) {
                log.warn("Thread title generate failed", e);
            }
            if (topicGenDto == null || StringUtils.isBlank(topicGenDto.getTopic()) || topicGenDto.getTopic().contains("JSON格式标题") || topicGenDto.getTopic().contains("JSON响应")) {
                conversationUpdateDto.setTopic(conversationUpdateDto.getFirstMessage().length() > 20 ? conversationUpdateDto.getFirstMessage().substring(0, 20) : conversationUpdateDto.getFirstMessage());
            } else {
                conversationUpdateDto.setTopic(topicGenDto.getTopic());
            }
        } else {
            try {
                topicGenDto = modelApplicationService.call(Prompts.CONVERSATION_SVG_PROMPT, "Generate an SVG icon based on the topic:\n" + conversationUpdateDto.getTopic(), new ParameterizedTypeReference<TopicGenDto>() {
                });
            } catch (Exception e) {
                log.warn("Thread title generate failed", e);
            }
        }
        if (topicGenDto != null && StringUtils.isNotBlank(topicGenDto.getSvgIcon())) {
            try {
                String fileUrl = agentApplicationService.uploadSvgIcon(topicGenDto.getSvgIcon(), conversationUpdateDto.getTopic() + ".svg");
                conversationUpdateDto.setIcon(fileUrl);
            } catch (Exception e) {
                //  ignore
                log.warn("uploadSvgIcon failed", e);
            }
        }

        Conversation conversation = new Conversation();
        conversation.setTopic(conversationUpdateDto.getTopic());
        conversation.setId(id);
        conversation.setTopicUpdated(1);
        if (conversation.getTopic() != null && conversation.getTopic().trim().startsWith("\"")) {
            conversation.setTopic(conversation.getTopic().replace("\"", "").replace("主题：", "").trim());
        }
        if (StringUtils.isNotBlank(conversationUpdateDto.getIcon())) {
            conversation.setIcon(conversationUpdateDto.getIcon());
        }
        conversationDomainService.updateConversation(userId, id, conversation);
    }

    @Override
    public ConversationDto getConversation(Long userId, Long id) {
        return convertConversationWithAgent(conversationDomainService.getConversation(userId, id));
    }

    @Override
    public ConversationDto getConversationByCid(Long id) {
        return convertConversation(conversationDomainService.getConversation(id));
    }

    @Override
    public List<ConversationDto> getConversationListByDevTarget(String targetType, Long targetId) {
        return conversationDomainService.getConversationListByDevTarget(targetType, targetId)
                .stream().map(this::convertConversation).toList();
    }

    @Override
    public int deleteByDevTarget(String targetType, Long targetId) {
        List<Conversation> conversations = conversationDomainService.getConversationListByDevTarget(targetType, targetId);
        if (conversations.isEmpty()) {
            return 0;
        }
        // UserApp/NormalProject 的工作区为项目共享目录，由项目删除流程按沙箱类型统一清理，此处不重复发 RPC；
        // 其余类型：云端沙箱会话同步删除工作区文件（个人沙箱不清理）
        boolean cleanupWorkspace = !SHARED_WORKSPACE_DEV_TARGET_TYPES.contains(targetType);
        int deleted = 0;
        for (Conversation conversation : conversations) {
            try {
                if (cleanupWorkspace) {
                    cleanupCloudSandboxWorkspace(conversation);
                }
                deleteConversationInternal(conversation);
                deleted++;
            } catch (Exception e) {
                // 单会话失败不阻断整体：已发出的工作区删除不可回滚，若此处抛出会触发调用方事务回滚、
                // 恢复已清空工作区的会话行；继续删其余会话，残留由用户从会话列表单独删除兜底
                log.warn("Failed to cascade delete dev target conversation, type={}, targetId={}, cId={}",
                        targetType, targetId, conversation.getId(), e);
            }
        }
        return deleted;
    }

    @Override
    public List<ConversationDto> getConversationListByDevTargets(String targetType, List<Long> targetIds) {
        return conversationDomainService.getConversationListByDevTargets(targetType, targetIds)
                .stream().map(this::convertConversation).toList();
    }

    @Override
    public ConversationDto getLatestConversationByDevTarget(Long userId, String targetType, Long targetId) {
        return convertConversation(conversationDomainService.getLatestConversationByDevTarget(userId, targetType, targetId));
    }

    @Override
    public ConversationDto getConversationWithLastMessageByCid(Long cid) {
        ConversationDto conversationByCid = getConversationByCid(cid);
        if (conversationByCid != null) {
            List<ChatMessageDto> messageList = TenantFunctions.callWithIgnoreCheck(() -> queryMessageList(cid, null, 2));
            if (messageList != null && !messageList.isEmpty()) {
                conversationByCid.setMessageList(messageList);
            }
        }
        return conversationByCid;
    }

    @Override
    public ConversationDto getConversationByUid(Long userId, String uid) {
        return convertConversationWithAgent(conversationDomainService.getConversationByUid(userId, uid));
    }

    @Override
    public ConversationDto getConversationByUid(String uid) {
        return convertConversationWithAgent(conversationDomainService.getConversationByUid(uid));
    }

    @Override
    public List<ConversationDto> getConversationListByCIds(List<Long> cIds) {
        return conversationDomainService.getConversationListByIds(cIds).stream().map(this::convertConversation).toList();
    }

    @Override
    public List<ConversationDto> queryConversationList(Long userId, Long agentId) {
        return queryConversationList(userId, agentId, null, 100, null);
    }

    public List<ConversationDto> queryConversationList(Long userId, Long agentId, Long lastId, Integer
            limit, String topic) {
        return queryConversationList(userId, agentId, lastId, limit, topic, ArchivedFilter.ALL);
    }

    @Override
    public List<ConversationDto> queryConversationList(Long userId, Long agentId, Long lastId, Integer limit, String topic,
                                                       ArchivedFilter archivedFilter) {
        return queryConversationList(userId, agentId, lastId, limit, topic, archivedFilter, null);
    }

    @Override
    public List<ConversationDto> queryConversationList(Long userId, Long agentId, Long lastId, Integer limit, String topic,
                                                       ArchivedFilter archivedFilter, CollectedFilter collectedFilter) {
        return queryConversationList(userId, agentId, lastId, limit, topic, archivedFilter, collectedFilter, null);
    }

    @Override
    public List<ConversationDto> queryConversationList(Long userId, Long agentId, Long lastId, Integer limit, String topic,
                                                       ArchivedFilter archivedFilter, CollectedFilter collectedFilter, ProjectConversationFilter projectFilter) {
        // 收藏过滤：解析当前用户收藏的会话ID，无收藏直接短路返回空
        List<Long> collectedIds = null;
        if (collectedFilter == CollectedFilter.ONLY) {
            collectedIds = userTargetRelationDomainService.queryAllCollectionIds(userId, Published.TargetType.Conversation);
            if (CollectionUtils.isEmpty(collectedIds)) {
                return List.of();
            }
        }
        List<Conversation> conversations = conversationDomainService.queryConversationList(userId, agentId, lastId,
                limit == null ? 10 : limit, topic, archivedFilter, collectedIds, projectFilter);
        List<ConversationDto> conversationDtos = conversations.stream().map(this::convertConversation).collect(Collectors.toList());
        List<Long> agentIds = conversations.stream().map(Conversation::getAgentId).collect(Collectors.toList());
        List<Long> devAgentIds = conversations.stream().filter(conversation -> ProjectTypeEnum.Agent.name().equals(conversation.getDevTargetType()) && conversation.getDevTargetId() != null).map(conversation -> Long.parseLong(conversation.getDevTargetId())).toList();
        List<Long> pageAppIds = conversations.stream().filter(conversation -> ProjectTypeEnum.PageApp.name().equals(conversation.getDevTargetType()) && conversation.getDevTargetId() != null).map(conversation -> Long.parseLong(conversation.getDevTargetId())).toList();
        agentIds.addAll(devAgentIds);
        if (CollectionUtils.isNotEmpty(agentIds)) {
            Map<Long, CustomPageDto> customPageDtoMap;
            if (CollectionUtils.isNotEmpty(pageAppIds)) {
                List<CustomPageDto> customPages = iCustomPageRpcService.listByIds(pageAppIds, null);
                customPageDtoMap = customPages.stream().collect(Collectors.toMap(CustomPageDto::getProjectId, customPage -> customPage, (v1, v2) -> v1));
            } else {
                customPageDtoMap = new HashMap<>();
            }
            List<AgentConfig> agentConfigs = agentDomainService.queryListByIds(agentIds);
            // agentConfigs 转map
            Map<Long, AgentDetailDto> agentConfigMap = agentConfigs.stream().map(agentConfig -> {
                AgentDetailDto agentDetailDto = new AgentDetailDto();
                agentDetailDto.setAgentId(agentConfig.getId());
                agentDetailDto.setName(agentConfig.getName());
                agentDetailDto.setType(agentConfig.getType());
                agentDetailDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(agentConfig.getIcon(), agentConfig.getName(), "agent", true));
                return agentDetailDto;
            }).collect(Collectors.toMap(AgentDetailDto::getAgentId, agentConfig -> agentConfig));
            conversationDtos = conversationDtos.stream().map(conversationDto -> {
                if (ProjectTypeEnum.Agent.name().equals(conversationDto.getDevTargetType()) && conversationDto.getDevTargetId() != null) {
                    AgentDetailDto agentDetailDto = agentConfigMap.get(Long.parseLong(conversationDto.getDevTargetId()));
                    if (agentDetailDto != null) {
                        conversationDto.setTopic(agentDetailDto.getName());
                        conversationDto.setSummary(agentDetailDto.getDescription());
                        conversationDto.setIcon(agentDetailDto.getIcon());
                    } else {
                        // 项目已经被删除，对应的会话也删除
                        conversationDomainService.deleteConversation(userId, conversationDto.getId());
                        return null;
                    }
                }
                if (ProjectTypeEnum.PageApp.name().equals(conversationDto.getDevTargetType()) && conversationDto.getDevTargetId() != null) {
                    CustomPageDto customPageDto = customPageDtoMap.get(Long.parseLong(conversationDto.getDevTargetId()));
                    if (customPageDto != null) {
                        conversationDto.setTopic(customPageDto.getName());
                        conversationDto.setSummary(customPageDto.getDescription());
                        conversationDto.setIcon(customPageDto.getIcon());
                    } else {
                        // 项目已经被删除，对应的会话也删除
                        conversationDomainService.deleteConversation(userId, conversationDto.getId());
                        return null;
                    }
                }
                conversationDto.setAgent(agentConfigMap.get(conversationDto.getAgentId()));
                return conversationDto;
            }).filter(Objects::nonNull).collect(Collectors.toList());
        }
        fillCollected(userId, conversationDtos);
        return conversationDtos;
    }

    /**
     * 回填当前用户收藏标记（会话列表展示星标用）
     */
    private void fillCollected(Long userId, List<ConversationDto> conversationDtos) {
        if (userId == null || CollectionUtils.isEmpty(conversationDtos)) {
            return;
        }
        List<Long> conversationIds = conversationDtos.stream().map(ConversationDto::getId).toList();
        Set<Long> collectedIds = userTargetRelationDomainService
                .queryUserTargetRelationByTargetIds(userId, Published.TargetType.Conversation, UserTargetRelation.OpType.Collect, conversationIds)
                .stream().map(UserTargetRelation::getTargetId).collect(Collectors.toSet());
        conversationDtos.forEach(conversationDto -> conversationDto.setCollected(collectedIds.contains(conversationDto.getId())));
    }

    @Override
    public List<ConversationDto> queryLatestConversationList(Long userId, List<Long> agentIds, String
            topic, Integer limit) {
        return conversationDomainService.queryLatestConversationList(userId, agentIds, topic, limit).stream().map(this::convertConversation).toList();
    }

    @Override
    public ConversationDto updateConversationFlags(Long userId, Long conversationId, Boolean pinned, Boolean archived) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId is required");
        }
        Conversation conversation = conversationDomainService.getConversation(userId, conversationId);
        if (conversation == null) {
            throw new BizException("Conversation not found");
        }
        Conversation update = new Conversation();
        update.setId(conversationId);
        if (pinned != null) {
            update.setPinned(pinned);
        }
        if (archived != null) {
            update.setArchived(archived);
        }
        conversationDomainService.updateConversation(userId, conversationId, update);
        log.info("Conversation flags updated, cId={}, pinned={}, archived={}", conversationId, pinned, archived);
        return getConversation(userId, conversationId);
    }

    @Override
    public void collectConversation(Long userId, Long conversationId) {
        requireOwnConversation(userId, conversationId);
        userTargetRelationDomainService.record(userId, Published.TargetType.Conversation, UserTargetRelation.OpType.Collect, conversationId);
    }

    @Override
    public void unCollectConversation(Long userId, Long conversationId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId is required");
        }
        userTargetRelationDomainService.unRecord(userId, Published.TargetType.Conversation, UserTargetRelation.OpType.Collect, conversationId);
    }

    /**
     * 会话归属校验：仅会话所属用户可操作
     */
    private void requireOwnConversation(Long userId, Long conversationId) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId is required");
        }
        Conversation conversation = conversationDomainService.getConversation(userId, conversationId);
        if (conversation == null) {
            throw new BizException("Conversation not found");
        }
    }

    @Override
    public List<ConversationDto> queryConversationListBySandboxServerId(Long sandboxServerId) {
        List<Conversation> conversations = conversationDomainService.queryConversationListBySandboxServerId(sandboxServerId);
        conversations.removeIf(conversation -> {
            try {
                return !sandboxAgentClient.checkAgentIfAlive(conversation.getId().toString());
            } catch (Exception e) {
                return true;
            }
        });
        return conversations.stream().map(this::convertConversation).toList();
    }

    @Override
    public List<ConversationDto> queryTaskConversationList(Long userId, Long
            agentId, Conversation.ConversationTaskStatus taskStatus) {
        List<Conversation> conversations = conversationDomainService.queryTaskConversationList(userId, agentId, taskStatus, 10);
        return conversations.stream().map(this::convertConversation).collect(Collectors.toList());
    }

    @Override
    public void summaryConversation(Long conversationId) {
        if (conversationId == null) {
            return;
        }
        log.debug("Starting session summary: {}", conversationId);
        Conversation conversation = conversationDomainService.getConversation(conversationId);
        if (conversation == null) {
            return;
        }
        RequestContext<Object> requestContext = RequestContext.get();
        boolean onUserThread = true;
        if (requestContext == null) {
            requestContext = new RequestContext<>();
            requestContext.setTenantId(conversation.getTenantId());
            onUserThread = false;
            RequestContext.set(requestContext);
        }
        try {
            if (conversation.getDevMode() != null && conversation.getDevMode().equals(YesOrNoEnum.Y.getKey())) {
                AgentConfig agentConfig = agentDomainService.queryById(conversation.getAgentId());
                if (agentConfig.getOpenLongMemory() != AgentConfig.OpenStatus.Open) {
                    return;
                }
            } else {
                PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Agent, conversation.getAgentId());
                if (publishedDto == null) {
                    return;
                }
                AgentConfigDto agentConfigDto = JSON.parseObject(publishedDto.getConfig(), AgentConfigDto.class);
                if (agentConfigDto.getOpenLongMemory() != AgentConfig.OpenStatus.Open) {
                    return;
                }
            }

            List<ChatMessageDto> cachedMessageList = conversationDomainService.queryConversationMessageList(conversationId, null, 4).stream().map(message -> {
                ChatMessageDto chatMessageDto = JSON.parseObject(message.getContent(), ChatMessageDto.class);
                chatMessageDto.setTime(message.getCreated());
                chatMessageDto.setId(message.getMessageId());
                chatMessageDto.setIndex(message.getId());
                return chatMessageDto;
            }).collect(Collectors.toList());
            Collections.reverse(cachedMessageList);
            //只有两条消息才进行总结（至少完成一轮会话）
            if (cachedMessageList.size() < 2) {
                return;
            }
            List<ChatMessageDto> userMessageList = cachedMessageList.stream().filter(message -> message.getRole() == ChatMessageDto.Role.USER).toList();
            if (userMessageList.isEmpty()) {
                log.warn("conversationId:{} has no user message", conversationId);
                return;
            }
            JSONArray jsonArray = new JSONArray();
            cachedMessageList.forEach(cachedMessage -> jsonArray.add(cachedMessage.getRole().name() + ":" + removeSystemTagContent(cachedMessage.getText()) + "\n"));
            log.debug("Starting session summary: {}", cachedMessageList);
            iMemoryRpcService.createMemory(MemoryMetaData.builder()
                    .agentId(conversation.getAgentId())
                    .userId(conversation.getUserId())
                    .context(jsonArray.toJSONString().replaceAll("<user-memory>[\\s\\S]*?</user-memory>", "").trim())
                    .userInput(userMessageList.get(userMessageList.size() - 1).getText().replaceAll("<user-memory>[\\s\\S]*?</user-memory>", "").trim())
                    .tenantId(conversation.getTenantId())
                    .build());
        } finally {
            if (!onUserThread) {
                RequestContext.remove();
            }
        }
    }

    @Override
    public void pushToSummaryQueue(Long conversationId) {
        //不再单独总结记忆
        //redisUtil.leftPush("conversation_summary_queue", conversationId.toString());
    }

    @Override
    public String queryMemory(Long tenantId, Long userId, Long agentId, String inputMessage, String context,
                              boolean justKeywordSearch, boolean filterSensitive) {
        return TenantFunctions.callWithIgnoreCheck(() -> iMemoryRpcService.searchMemoriesMd(tenantId, userId, agentId, inputMessage, context, justKeywordSearch, filterSensitive));
    }

    @Override
    public List<ChatMessageDto> queryConversationMessageList(Long userId, Long conversationId, Long index, int size) {
        List<ChatMessageDto> cachedMessageList = queryMessageList(conversationId, index, size);
        //过滤chatMessageDtoList中role为空的
        return cachedMessageList.stream().filter(chatMessageDto -> chatMessageDto.getRole() == ChatMessageDto.Role.USER || chatMessageDto.getRole() == ChatMessageDto.Role.ASSISTANT).map(chatMessageDto -> {
            //提取出<attachment>...</attachment>内容，然后去掉
            //提取出<attachment>...</attachment>
            if (chatMessageDto.getRole() == ChatMessageDto.Role.USER) {
                String attachmentContent = extractAttachmentContent(chatMessageDto.getText());
                if (JSON.isValid(attachmentContent)) {
                    chatMessageDto.setAttachments(JSON.parseArray(attachmentContent, AttachmentDto.class));
                    if (chatMessageDto.getAttachments() != null && !chatMessageDto.getAttachments().isEmpty()) {
                        chatMessageDto.getAttachments().forEach(attachmentDto -> {
                            if (attachmentDto.getMimeType() == null) {
                                attachmentDto.setMimeType("image/jpeg");//临时占位避免前端异常
                            }
                            if (attachmentDto.getFileKey() == null) {
                                attachmentDto.setFileKey(attachmentDto.getFileName());
                            }
                        });
                    }
                    chatMessageDto.setText(chatMessageDto.getText().replaceAll("<attachment>[\\s\\S]*?</attachment>", "").trim());
                }
                chatMessageDto.setText(chatMessageDto.getText().replaceAll("<user-prompt>[\\s\\S]*?</user-prompt>", "").trim());
                chatMessageDto.setText(chatMessageDto.getText().replaceAll("<user-reminder>[\\s\\S]*?</user-reminder>", "").trim());
                chatMessageDto.setText(chatMessageDto.getText().replaceAll("<user-memory>[\\s\\S]*?</user-memory>", "").trim());
                if (chatMessageDto.getText().contains("<user-message>") && chatMessageDto.getText().contains("</user-message>")) {
                    chatMessageDto.setText(chatMessageDto.getText().replace("<user-message>", "").replace("</user-message>", "").trim());
                }
                chatMessageDto.setText(chatMessageDto.getText().replaceAll("<tool_call_results>[\\s\\S]*?</tool_call_results>", "").trim());
            }
            if (chatMessageDto.getRole() == ChatMessageDto.Role.ASSISTANT) {
                String thinkContent = StringUtils.isNotBlank(chatMessageDto.getThink()) ? chatMessageDto.getThink() : extractThinkContent(chatMessageDto.getText());
                chatMessageDto.setThink(thinkContent);
                chatMessageDto.setText(removeSystemTagContent(chatMessageDto.getText()));
            }
            return chatMessageDto;
        }).collect(Collectors.toList());
    }

    @Override
    public void addRoundMessage(String conversationId, Message message) {
        if (conversationId == null) {
            return;
        }
        String conversationId0 = conversationId;
        if (conversationId0.startsWith("agent:")) {
            //主动调用加入的保持
            if (message instanceof ChatMessageDto) {
                conversationId0 = conversationId.replace("agent:", "");
            } else {
                return;
            }
        }
        ChatMessageDto chatMessage;
        if (message instanceof ChatMessageDto) {
            chatMessage = (ChatMessageDto) message;
        } else {
            chatMessage = new ChatMessageDto();
            chatMessage.setType(MessageTypeEnum.CHAT);
            chatMessage.setRole(ChatMessageDto.Role.valueOf(message.getMessageType().name()));
            chatMessage.setText(message.getText());
        }
        chatMessage.setTime(new Date());
        chatMessage.setId(generateUid(chatMessage.getId()));
        if (message.getText() == null) {
            chatMessage.setText("");
        } else {
            //每次自动调用知识库插件工作流数据信息移除不保存
            String text = message.getText().replaceAll("<query_knowledge_base_result>[\\s\\S]*?</query_knowledge_base_result>", "")
                    .replaceAll("<tool_auto_call_result>[\\s\\S]*?</tool_auto_call_result>", "");
            chatMessage.setText(text);
        }
        if (chatMessage.getText().contains("</think>") && !chatMessage.getText().contains("<think>")) {
            chatMessage.setText("<think>" + chatMessage.getText());
        }
        ConversationMessage conversationMessage = new ConversationMessage();
        try {
            conversationMessage.setConversationId(Long.parseLong(conversationId));
        } catch (NumberFormatException e) {
            return;
        }
        conversationMessage.setContent(JSON.toJSONString(chatMessage));
        conversationMessage.setMessageId(chatMessage.getId());
        if (chatMessage.getTenantId() == null) {
            Conversation conversation = TenantFunctions.callWithIgnoreCheck(() -> conversationDomainService.getConversation(conversationMessage.getConversationId()));
            chatMessage.setTenantId(conversation.getTenantId());
            chatMessage.setUserId(conversation.getUserId());
            chatMessage.setAgentId(conversation.getAgentId());
        }
        conversationMessage.setTenantId(chatMessage.getTenantId());
        conversationMessage.setUserId(chatMessage.getUserId());
        conversationMessage.setAgentId(chatMessage.getAgentId());
        TenantFunctions.callWithIgnoreCheck(() -> conversationDomainService.addConversationMessage(conversationMessage));
    }

    @Override
    public List<Message> getRoundMessages(String conversationId, int lastN) {
        if (conversationId.startsWith("agent:")) {
            conversationId = conversationId.replace("agent:", "");
        }
        //获取最新的lastN条消息
        List<ChatMessageDto> cachedMessageList0 = queryMessageList(Long.parseLong(conversationId), Long.MAX_VALUE, lastN);
        return ChatMessageDto.toMessages(cachedMessageList0);
    }

    private List<ChatMessageDto> queryMessageList(Long conversationId, Long index, int size) {
        List<ChatMessageDto> chatMessageList = queryMessageList0(conversationId, index, size);
        Collections.reverse(chatMessageList);
        return chatMessageList;
    }

    private List<ChatMessageDto> queryMessageList0(Long conversationId, Long index, int size) {
        return conversationDomainService.queryConversationMessageList(conversationId, index, size).stream().map(message -> {
            ChatMessageDto chatMessageDto = JSON.parseObject(message.getContent(), ChatMessageDto.class);
            chatMessageDto.setTime(message.getCreated());
            chatMessageDto.setId(message.getMessageId());
            chatMessageDto.setIndex(message.getId());
            if (chatMessageDto.getComponentExecutedList() != null) {
                chatMessageDto.getComponentExecutedList().forEach(componentExecuted -> {
                    if (componentExecuted instanceof JSONObject && !"Event".equals(((JSONObject) componentExecuted).getString("type"))) {
                        JsonTruncateUtil.truncate((JSONObject) componentExecuted);
                    }
                });
            }
            return chatMessageDto;
        }).collect(Collectors.toList());
    }

    @Override
    public List<AgentUserDto> queryAgentUserList(Long agentId, Long cursorUserId) {
        List<Long> longs = conversationDomainService.queryAgentUserIdList(agentId, cursorUserId);
        if (longs.isEmpty()) {
            return new ArrayList<>();
        }
        List<UserDto> userDtos = userApplicationService.queryUserListByIds(longs);
        //userDtos转map
        Map<Long, UserDto> userMap = userDtos.stream().collect(Collectors.toMap(UserDto::getId, userDto -> userDto));
        return longs.stream().map(longId -> {
            AgentUserDto agentUserDto = new AgentUserDto();
            agentUserDto.setUserId(longId);
            UserDto userDto = userMap.get(longId);
            if (userDto != null) {
                agentUserDto.setNickName(userDto.getNickName());
                agentUserDto.setAvatar(userDto.getAvatar());
            }
            return agentUserDto;
        }).collect(Collectors.toList());
    }

    @Override
    public boolean execute(ScheduleTaskDto scheduleTask) {
        //会话总结调整到每次会话结束了
        Set<Long> conversationIds = new HashSet<>();
        Object id = redisUtil.rightPop("conversation_summary_queue");
        while (id != null) {
            Long conversationId = Long.valueOf(id.toString());
            if (!conversationIds.contains(conversationId)) {
                try {
                    summaryConversation(conversationId);
                    conversationIds.add(conversationId);
                } catch (Exception e) {
                    //ignore
                    log.error("会话总结失败 {}", conversationId, e);
                }
            }
            id = redisUtil.rightPop("conversation_summary_queue");
        }
        //永远循环执行
        return false;
    }

    private static String extractAttachmentContent(String text) {
        String regex = "<attachment>(.*?)</attachment>";
        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private static String extractThinkContent(String text) {
        String regex = "<think>(.*?)</think>";
        Pattern pattern = Pattern.compile(regex, Pattern.DOTALL);
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    //生成唯一ID，且比较短
    private String generateUid(String id) {
        if (StringUtils.isNotBlank(id)) {
            return id;
        }
        return UUID.randomUUID().toString().replace("-", "");
    }

    @Override
    public Long nextConversationId(Long agentId, String sandboxServerId) {
        return conversationDomainService.nextConversationId(RequestContext.get().getUserId(), agentId, sandboxServerId);
    }

    @Override
    public boolean clearConversationMessages(Long conversationId) {
        return conversationDomainService.clearConversationMessages(conversationId);
    }
}
