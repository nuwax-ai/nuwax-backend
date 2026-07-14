package com.xspaceagi.agent.core.application.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.google.common.base.Joiner;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.*;
import com.xspaceagi.agent.core.adapter.dto.config.bind.*;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.AgentNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.adapter.repository.entity.*;
import com.xspaceagi.agent.core.application.support.PlatformEnvHookSupport;
import com.xspaceagi.agent.core.domain.service.AgentDomainService;
import com.xspaceagi.agent.core.domain.service.ConversationDomainService;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.agent.AgentExecutor;
import com.xspaceagi.agent.core.infra.component.agent.SandboxAgentClient;
import com.xspaceagi.agent.core.infra.component.agent.dto.AgentExecuteResult;
import com.xspaceagi.agent.core.infra.component.workflow.handler.QANodeHandler;
import com.xspaceagi.agent.core.infra.rpc.GitRpcClient;
import com.xspaceagi.agent.core.infra.rpc.McpRpcService;
import com.xspaceagi.agent.core.infra.rpc.MetricRpcService;
import com.xspaceagi.agent.core.infra.rpc.ResourcePricingRpcService;
import com.xspaceagi.agent.core.infra.sub.ChatMessagePubSubService;
import com.xspaceagi.agent.core.spec.constant.Prompts;
import com.xspaceagi.agent.core.spec.enums.GlobalVariableEnum;
import com.xspaceagi.agent.core.spec.enums.MessageTypeEnum;
import com.xspaceagi.agent.core.spec.enums.TaskCron;
import com.xspaceagi.custompage.sdk.ICustomPageRpcService;
import com.xspaceagi.custompage.sdk.dto.CustomPageDto;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.log.sdk.service.ILogRpcService;
import com.xspaceagi.log.sdk.vo.LogDocument;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.enums.InstallTypeEnum;
import com.xspaceagi.memory.sdk.dto.MemoryMetaData;
import com.xspaceagi.memory.sdk.service.IMemoryRpcService;
import com.xspaceagi.pricing.sdk.dto.PriceEstimate;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.subscription.sdk.dto.UserSubscriptionDTO;
import com.xspaceagi.system.application.dto.EventDto;
import com.xspaceagi.system.application.dto.SendNotifyMessageDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.NotifyMessageApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.*;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.spec.utils.RedisUtil;
import com.xspaceagi.system.spec.utils.TimeWheel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.xspaceagi.agent.core.adapter.dto.ChatMessageDto.removeSystemTagContent;
import static com.xspaceagi.system.spec.cache.SimpleJvmHashCache.DEFAULT_EXPIRE_AFTER_SECONDS;

@Slf4j
@Service("conversationApplicationService")
public class ConversationApplicationServiceImpl extends AbstractTaskExecuteService implements ConversationApplicationService {

    private static final String DEFAULT_TOPIC = "Unnamed conversation";
    private static final int MAX_USER_AGENT_TASK_SIZE = 10;

    //计算上下文token总数，暂时不能超过32k，超过后丢弃
    private static final int MAX_TOKEN_WINDOW_SIZE = 64 * 1000;

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
    private SpacePermissionService spacePermissionService;

    @Resource
    private ILogRpcService iLogRpcService;

    @Resource
    private QANodeHandler qaNodeHandler;

    @Resource
    private TimeWheel timeWheel;

    @Resource
    private IFileAccessService iFileAccessService;

    @Resource
    private NotifyMessageApplicationService notifyMessageApplicationService;

    @Resource
    private AgentWorkspaceApplicationService agentWorkspaceApplicationService;

    @Resource
    private McpRpcService mcpRpcService;

    @Resource
    private ISandboxConfigRpcService iSandboxConfigRpcService;

    @Resource
    private MetricRpcService metricRpcService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Resource
    private UserAccessKeyApiService userAccessKeyApiService;

    @Resource
    private IMemoryRpcService iMemoryRpcService;

    @Resource
    private AgentExecutor agentExecutor;

    @Resource
    private GitRpcClient gitRpcClient;

    @Resource
    private SandboxAgentClient sandboxAgentClient;

    @Resource
    private SkillApplicationService skillApplicationService;

    @Resource
    private ResourcePricingRpcService resourcePricingRpcService;

    @Resource
    private ICustomPageRpcService iCustomPageRpcService;

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private ChatMessagePubSubService chatMessagePubSubService;

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
    public void createConversationForPageApp(Long userId, Long agentId) {
        if (conversationDomainService.agentUserCount(userId, agentId) == 0) {
            //使用过的用户数量+1
            publishApplicationService.incStatisticsCount(Published.TargetType.Agent, agentId, PublishedStatistics.Key.USER_COUNT.getKey(), 1L);
            Conversation conversation = new Conversation();
            conversation.setAgentId(agentId);
            conversation.setUserId(userId);
            conversation.setUid(UUID.randomUUID().toString().replace("-", ""));
            conversation.setTopic(DEFAULT_TOPIC);
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
    public ConversationDto createConversationForProjectDevelopment(Long tenantId, Long userId, Long devSpaceId, Long devAgentId, String targetType, Long targetId) {
        Conversation conversation = new Conversation();
        conversation.setTenantId(tenantId);
        conversation.setAgentId(devAgentId);
        conversation.setDevSpaceId(devSpaceId);
        conversation.setDevTargetType(targetType);
        conversation.setDevTargetId(targetId.toString());
        conversation.setUserId(userId);
        conversation.setUid(UUID.randomUUID().toString().replace("-", ""));
        conversation.setTopic("");
        conversation.setDevMode(0);
        conversation.setTenantId(RequestContext.get().getTenantId());
        conversation.setType(Conversation.ConversationType.Development);
        conversation.setTopicUpdated(1);
        Long sandboxId = iSandboxConfigRpcService.queryUserSelectedSandboxId(userId, devAgentId);
        if (sandboxId != null && sandboxId > 0) {
            conversation.setSandboxServerId(sandboxId.toString());
        }
        return TenantFunctions.callWithIgnoreCheck(() -> {
            conversationDomainService.createConversation(conversation);
            return convertConversationWithAgent(conversation);
        });
    }

    @Override
    public ConversationDto createConversation(Long userId, Long agentId, boolean devMode, boolean tempChat, Map<String, Object> variables) {
        AgentConfigDto agentConfigDto = agentApplicationService.queryById(agentId);
        if (agentConfigDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentSelectedNotFound);
        }
        if (conversationDomainService.agentUserCount(userId, agentId) == 0 && !devMode) {
            //使用过的用户数量+1
            publishApplicationService.incStatisticsCount(Published.TargetType.Agent, agentId, PublishedStatistics.Key.USER_COUNT.getKey(), 1L);
        }
        Conversation conversation = new Conversation();
        conversation.setAgentId(agentId);
        conversation.setUserId(userId);
        conversation.setUid(UUID.randomUUID().toString().replace("-", ""));
        conversation.setTopic(DEFAULT_TOPIC);
        conversation.setDevMode(devMode ? 1 : 0);
        conversation.setVariables(variables);
        conversation.setTenantId(RequestContext.get().getTenantId());
        if (tempChat) {
            conversation.setType(Conversation.ConversationType.TempChat);
        }
        //createdFrom
        boolean isCreatedFromVibeCoding = agentConfigDto.getExtra() != null && agentConfigDto.getExtra().get("createdFrom") != null && "VibeCoding".equals(agentConfigDto.getExtra().get("createdFrom"));
        if (devMode && (agentConfigDto.getDevAgentConversationId() != null || isCreatedFromVibeCoding)) {
            conversation.setType(Conversation.ConversationType.DevDebug);
            ConversationDto conversationByCid = getConversationByCid(agentConfigDto.getDevAgentConversationId());
            conversation.setSandboxServerId(conversationByCid.getSandboxServerId() == null ? "-1" : conversationByCid.getSandboxServerId());//为空时为选择了云端电脑
        }
        conversationDomainService.createConversation(conversation);
        // 增加智能体统计会话数量
        publishApplicationService.incStatisticsCount(Published.TargetType.Agent, agentId, PublishedStatistics.Key.CONV_COUNT.getKey(), 1L);

        if (devMode && !tempChat) {
            AgentConfigDto agentConfigDto1 = new AgentConfigDto();
            agentConfigDto1.setId(agentId);
            agentConfigDto1.setDevConversationId(conversation.getId());
            agentConfigDto1.setModified(agentConfigDto.getModified());
            agentApplicationService.update(agentConfigDto1);
        }

        return convertConversationWithAgent(conversation);
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
        conversationDto.setDevSpaceId(conversation.getDevSpaceId());
        conversationDto.setIcon(conversation.getIcon());
        return conversationDto;
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
        conversationDomainService.deleteConversation(userId, id);
        //不需要减少智能体统计会话数量
        // agentApplicationService.incAgentStatisticsCount(conversation.getAgentId(), "conv_count", -1);
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
                if (StringUtils.isNotBlank(topicGenDto.getSvgIcon())) {
                    try {
                        String fileUrl = agentApplicationService.uploadSvgIcon(topicGenDto.getSvgIcon(), conversationUpdateDto.getTopic() + ".svg");
                        conversationUpdateDto.setIcon(fileUrl);
                    } catch (Exception e) {
                        //  ignore
                        log.warn("uploadSvgIcon failed", e);
                    }
                }
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
    public ConversationDto getConversationByUid(Long userId, String uid) {
        return convertConversationWithAgent(conversationDomainService.getConversationByUid(userId, uid));
    }

    @Override
    public ConversationDto getConversationByUid(String uid) {
        return convertConversationWithAgent(conversationDomainService.getConversationByUid(uid));
    }

    @Override
    public List<ConversationDto> queryConversationList(Long userId, Long agentId) {
        return queryConversationList(userId, agentId, null, 100, null);
    }

    public List<ConversationDto> queryConversationList(Long userId, Long agentId, Long lastId, Integer limit, String topic) {
        List<Conversation> conversations = conversationDomainService.queryConversationList(userId, agentId, lastId, limit == null ? 10 : limit, topic);
        List<ConversationDto> conversationDtos = conversations.stream().map(this::convertConversation).collect(Collectors.toList());
        List<Long> agentIds = conversations.stream().map(Conversation::getAgentId).collect(Collectors.toList());
        List<Long> devAgentIds = conversations.stream().filter(conversation -> Published.TargetType.Agent.name().equals(conversation.getDevTargetType()) && conversation.getDevTargetId() != null).map(conversation -> Long.parseLong(conversation.getDevTargetId())).toList();
        List<Long> pageAppIds = conversations.stream().filter(conversation -> Published.TargetType.PageApp.name().equals(conversation.getDevTargetType()) && conversation.getDevTargetId() != null).map(conversation -> Long.parseLong(conversation.getDevTargetId())).toList();
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
                if (Published.TargetType.Agent.name().equals(conversationDto.getDevTargetType()) && conversationDto.getDevTargetId() != null) {
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
                if (Published.TargetType.PageApp.name().equals(conversationDto.getDevTargetType()) && conversationDto.getDevTargetId() != null) {
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
        return conversationDtos;
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
    public List<ConversationDto> queryTaskConversationList(Long userId, Long agentId, Conversation.ConversationTaskStatus taskStatus) {
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
        redisUtil.leftPush("conversation_summary_queue", conversationId.toString());
    }

    @Override
    public String queryMemory(Long tenantId, Long userId, Long agentId, String inputMessage, String context, boolean justKeywordSearch, boolean filterSensitive) {
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

    public List<ChatMessageDto> getRoundMessages(String conversationId, Long minId) {
        List<ChatMessageDto> messages = conversationDomainService.queryConversationMessageList(Long.parseLong(conversationId), minId).stream().map(message -> {
            ChatMessageDto chatMessageDto = JSON.parseObject(message.getContent(), ChatMessageDto.class);
            chatMessageDto.setTime(message.getCreated());
            chatMessageDto.setId(message.getMessageId());
            chatMessageDto.setIndex(message.getId());
            return chatMessageDto;
        }).collect(Collectors.toList());
        Collections.reverse(messages);
        while (!messages.isEmpty() && messages.get(0).getMessageType() == MessageType.ASSISTANT) {
            messages.remove(0);
        }
        for (ChatMessageDto message : messages) {
            String text = removeSystemTagContent(message.getText());
            if (text != null) {
                text = text.replaceAll("<user-memory>[\\s\\S]*?</user-memory>", "").replaceAll("<markdown-custom-process[^>]*>.*?</markdown-custom-process>", "").trim();
                message.setText(text);
            }
        }
        return messages;
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
    public void setChatStopStatus(String conversationId) {
        redisUtil.set("chat.stop." + conversationId, String.valueOf(System.currentTimeMillis()), 60);
    }

    @Override
    public boolean isChatStop(String conversationId) {
        return redisUtil.get("chat.stop." + conversationId) != null;
    }

    @Override
    public void clearChatStopStatus(String conversationId) {
        redisUtil.expire("chat.stop." + conversationId, 0);
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
    public Flux<AgentOutputDto> chat(TryReqDto tryReqDto, Map<String, String> headersFromRequest, boolean isTempChat) {
        return chat(tryReqDto, headersFromRequest, isTempChat, null);
    }

    public Flux<AgentOutputDto> chat(TryReqDto tryReqDto, Map<String, String> headersFromRequest, boolean isTempChat, Boolean devMode) {
        log.info("ConversationApplicationServiceImpl.chat {}", tryReqDto);
        ConversationDto conversationDto = getConversationByCid(tryReqDto.getConversationId());
        AgentOutputDto errorOutput = new AgentOutputDto();
        errorOutput.setEventType(AgentOutputDto.EventTypeEnum.FINAL_RESULT);
        AgentExecuteResult agentExecuteResult = new AgentExecuteResult();
        agentExecuteResult.setSuccess(false);
        errorOutput.setData(agentExecuteResult);
        errorOutput.setCompleted(true);
        if (conversationDto == null) {
            String errorMsg = I18nUtil.systemMessage("Backend.Chat.Error.ConversationNotFound");
            agentExecuteResult.setError(errorMsg);
            errorOutput.setError(errorMsg);
            return errorOutput(errorOutput, null);
        }
        if (devMode != null) {
            conversationDto.setDevMode(devMode ? 1 : 0);
        }
        devMode = Objects.equals(conversationDto.getDevMode(), YesOrNoEnum.Y.getKey());
        AgentConfigDto agentConfigDto;
        if (Objects.equals(conversationDto.getDevMode(), YesOrNoEnum.Y.getKey())) {
            agentConfigDto = agentApplicationService.queryConfigForTestExecute(conversationDto.getAgentId());
            if (agentConfigDto == null) {
                String errorMsg = I18nUtil.systemMessage("Backend.Chat.Error.AgentNotFound");
                errorOutput.setError(errorMsg);
                agentExecuteResult.setError(errorMsg);
                return errorOutput(errorOutput, conversationDto);
            }
            if (!isTempChat) {
                //权限验证
                try {
                    spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId());
                    tryReqDto.setFilterSensitive(false);
                } catch (Exception e) {
                    String errorMsg = I18nUtil.systemMessage("Backend.Chat.Error.NoAgentChatPermission");
                    errorOutput.setError(errorMsg);
                    agentExecuteResult.setError(errorMsg);
                    return errorOutput(errorOutput, conversationDto);
                }
            }
        } else {
            if (!conversationDto.getUserId().equals(RequestContext.get().getUserId())) {
                String errorMsg = I18nUtil.systemMessage("Backend.Chat.Error.NoAgentChatPermission");
                errorOutput.setError(errorMsg);
                agentExecuteResult.setError(errorMsg);
                return errorOutput(errorOutput, conversationDto);
            }
            agentConfigDto = agentApplicationService.queryPublishedConfigForExecute(conversationDto.getAgentId());
            if (agentConfigDto != null) {
                PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(Published.TargetType.Agent, conversationDto.getAgentId());
                if (!publishedPermissionDto.isExecute()) {
                    String errorMsg = I18nUtil.systemMessage("Backend.Chat.Error.NoAgentChatPermission");
                    errorOutput.setError(errorMsg);
                    agentExecuteResult.setError(errorMsg);
                    return errorOutput(errorOutput, conversationDto);
                }

                setFileAkForCustomAgent(agentConfigDto);
            }
        }
        if (agentConfigDto == null) {
            String errorMsg = I18nUtil.systemMessage("Backend.Chat.Error.AgentNotFoundOrOffline");
            errorOutput.setError(errorMsg);
            agentExecuteResult.setError(errorMsg);
            return errorOutput(errorOutput, conversationDto);
        }

        if (agentConfigDto.getModelComponentConfig().getTargetConfig() == null) {
            String errorMsg = I18nUtil.systemMessage("Backend.Chat.Error.AgentModelNotConfiguredOrOffline");
            errorOutput.setError(errorMsg);
            agentExecuteResult.setError(errorMsg);
            return errorOutput(errorOutput, conversationDto);
        }

        // 用户数据权限
        UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());

        //判断agent是否被管控，若被管控需判断有没有权限
        if (agentConfigDto.getAccessControl() != null && agentConfigDto.getAccessControl().equals(YesOrNoEnum.Y.getKey()) && !agentConfigDto.getCreatorId().equals(RequestContext.get().getUserId())) {
            if (userDataPermission.getAgentIds() == null || !userDataPermission.getAgentIds().contains(agentConfigDto.getId())) {
                String errorMsg = I18nUtil.systemMessage("Backend.Chat.Error.NoAgentPermission");
                errorOutput.setError(errorMsg);
                agentExecuteResult.setError(errorMsg);
                return errorOutput(errorOutput, conversationDto);
            }
        }

        Object targetConfig = agentConfigDto.getModelComponentConfig().getTargetConfig();
        // 模型变化后是否需要重启智能体（通用智能体需要重启生效）；全局模型在开启代理的情况下无需重启
        String selectedKey = "agent.model.selected:" + RequestContext.get().getUserId() + ":" + agentConfigDto.getId();
        boolean modelChanged = false;
        boolean isDefaultModel = true;
        // 用户选择了模型
        if (tryReqDto.getModelId() != null && YesOrNoEnum.Y.getKey().equals(agentConfigDto.getAllowOtherModel())) {
            isDefaultModel = targetConfig instanceof ModelConfigDto modelConfigDto && modelConfigDto.getId().equals(tryReqDto.getModelId());
            Object val = redisUtil.get(selectedKey);
            if (val != null) {
                try {
                    Long mid = Long.parseLong(val.toString());
                    if (!mid.equals(tryReqDto.getModelId())) {
                        modelChanged = true;
                    }
                } catch (NumberFormatException ignored) {
                }
            } else if (targetConfig instanceof ModelConfigDto modelConfigDto && !modelConfigDto.getId().equals(tryReqDto.getModelId())) {
                modelChanged = true;
            }

            ModelConfigDto modelConfigDto1 = modelApplicationService.queryModelConfigById(tryReqDto.getModelId());
            if (modelConfigDto1 != null) {
                boolean hasPermission = modelConfigDto1.getScope() == ModelConfig.ModelScopeEnum.Tenant;
                if (hasPermission && modelConfigDto1.getAccessControl() != null && modelConfigDto1.getAccessControl().equals(YesOrNoEnum.Y.getKey())) {
                    hasPermission = userDataPermission.getModelIds() != null && userDataPermission.getModelIds().contains(modelConfigDto1.getId());
                } else if (!hasPermission) {
                    hasPermission = modelConfigDto1.getScope() == ModelConfig.ModelScopeEnum.Space && modelConfigDto1.getCreatorId().equals(RequestContext.get().getUserId());
                }
                if (hasPermission) {
                    agentConfigDto.getModelComponentConfig().setTargetConfig(modelConfigDto1);
                    agentConfigDto.getModelComponentConfig().setTargetId(modelConfigDto1.getId());
                    targetConfig = modelConfigDto1;
                    redisUtil.set(selectedKey, tryReqDto.getModelId().toString());
                } else {
                    modelChanged = false;
                }
            } else {
                modelChanged = false;
            }
        }

        //付费检查，评估是否可以继续执行，当前只要积分大于零，且订阅过智能体就可以执行
        PriceEstimate priceEstimate = estimatePrice(agentConfigDto, tryReqDto, Objects.equals(conversationDto.getDevMode(), YesOrNoEnum.Y.getKey()), isDefaultModel);
        if (!priceEstimate.isPass()) {
            errorOutput.setError(priceEstimate.getMessage());
            agentExecuteResult.setError(priceEstimate.getMessage());
            return errorOutput(errorOutput, conversationDto);
        }

        if (priceEstimate.getResourceBillUserId() == null) {
            priceEstimate.setResourceBillUserId(RequestContext.get().getUserId());
        }

        //token检测
        if (targetConfig instanceof ModelConfigDto && ((ModelConfigDto) targetConfig).getScope() == ModelConfig.ModelScopeEnum.Tenant) {
            BigDecimal tokenCount = metricRpcService.queryMetricCurrent(RequestContext.get().getTenantId(), RequestContext.get().getUserId(), BizType.TOKEN_USAGE.getCode(), PeriodType.DAY);
            if (userDataPermission.getTokenLimit() != null && userDataPermission.getTokenLimit().getLimitPerDay() != null && userDataPermission.getTokenLimit().getLimitPerDay() >= 0
                    && tokenCount.compareTo(BigDecimal.valueOf(userDataPermission.getTokenLimit().getLimitPerDay())) >= 0) {
                String errorMsg = I18nUtil.systemMessage("Backend.Chat.Error.TokenLimitExceeded", userDataPermission.getTokenLimit().getLimitPerDay().toString());
                errorOutput.setError(errorMsg);
                agentExecuteResult.setError(errorMsg);
                return errorOutput(errorOutput, conversationDto);
            }

            // 通用智能体对话次数管控
            if ("TaskAgent".equals(agentConfigDto.getType())) {
                BigDecimal promptCount = metricRpcService.queryMetricCurrent(RequestContext.get().getTenantId(), RequestContext.get().getUserId(), BizType.GENERAL_AGENT_CHAT.getCode(), PeriodType.DAY);
                if (userDataPermission.getAgentDailyPromptLimit() != null && userDataPermission.getAgentDailyPromptLimit() >= 0
                        && promptCount.compareTo(BigDecimal.valueOf(userDataPermission.getAgentDailyPromptLimit())) >= 0) {
                    String errorMsg = I18nUtil.systemMessage("Backend.Chat.Error.ChatCountLimitExceeded", userDataPermission.getAgentDailyPromptLimit().toString());
                    errorOutput.setError(errorMsg);
                    agentExecuteResult.setError(errorMsg);
                    return errorOutput(errorOutput, conversationDto);
                }
            }
        }

        Conversation conversationUpdate = Conversation.builder().build();
        // 变量记录
        if (tryReqDto.getVariableParams() != null && !tryReqDto.getVariableParams().isEmpty() && (conversationDto.getVariables() == null || conversationDto.getVariables().isEmpty())) {
            conversationUpdate.setVariables(tryReqDto.getVariableParams());
        } else if (tryReqDto.getVariableParams() != null && !tryReqDto.getVariableParams().isEmpty()) {
            conversationDto.getVariables().putAll(tryReqDto.getVariableParams());
        }

        if (!Objects.equals(conversationDto.getDevMode(), YesOrNoEnum.Y.getKey()) && (StringUtils.isBlank(tryReqDto.getFrom()) || tryReqDto.getFrom().equals("chat"))) {
            //增加最近使用
            if (agentConfigDto.getExtra() != null && agentConfigDto.getExtra().get("sandboxId") != null) {
                agentApplicationService.addOrUpdateRecentUsed(conversationDto.getUserId(), conversationDto.getAgentId(), conversationDto.getId());
            } else {
                agentApplicationService.addOrUpdateRecentUsed(conversationDto.getUserId(), conversationDto.getAgentId());
            }

            if (conversationDto.getTopicUpdated() == -1) {
                conversationUpdate.setTopicUpdated(0);
                long usedCount = conversationDomainService.agentUserCount(conversationDto.getUserId(), conversationDto.getAgentId());
                if (usedCount == 0) {
                    //使用过的用户数量+1
                    publishApplicationService.incStatisticsCount(Published.TargetType.Agent, conversationDto.getAgentId(), PublishedStatistics.Key.USER_COUNT.getKey(), 1L);
                }
                // 增加智能体统计会话数量
                publishApplicationService.incStatisticsCount(Published.TargetType.Agent, conversationDto.getAgentId(), PublishedStatistics.Key.CONV_COUNT.getKey(), 1L);
            }
        }

        conversationUpdate.setTaskStatus(Conversation.ConversationTaskStatus.EXECUTING);
        conversationDto.setTaskStatus(Conversation.ConversationTaskStatus.EXECUTING);
        if (agentConfigDto.getExtra() != null && agentConfigDto.getExtra().get("sandboxId") != null) {
            conversationUpdate.setSandboxServerId(agentConfigDto.getExtra().get("sandboxId").toString());
            conversationDto.setSandboxServerId(conversationUpdate.getSandboxServerId());
        } else if (tryReqDto.getSandboxId() != null && tryReqDto.getSandboxId() > 0) {
            SandboxConfigRpcDto sandboxConfigRpcDto = iSandboxConfigRpcService.queryById(tryReqDto.getSandboxId());
            if (StringUtils.isBlank(conversationDto.getSandboxServerId()) && sandboxConfigRpcDto != null && sandboxConfigRpcDto.getScope() == SandboxScopeEnum.USER
                    && RequestContext.get().getUserId().equals(sandboxConfigRpcDto.getUserId())) {
                conversationUpdate.setSandboxServerId(sandboxConfigRpcDto.getId().toString());
                conversationDto.setSandboxServerId(conversationUpdate.getSandboxServerId());
            }
        }
        //编排模式下可更换沙盒
        if (Objects.equals(conversationDto.getDevMode(), YesOrNoEnum.Y.getKey()) && tryReqDto.getSandboxId() != null
                && tryReqDto.getSandboxId() == -1 && conversationDto.getSandboxServerId() != null && !"-1".equals(conversationDto.getSandboxServerId())) {
            try {
                SandboxConfigRpcDto sandboxConfigRpcDto = iSandboxConfigRpcService.queryById(Long.parseLong(conversationDto.getSandboxServerId()));
                if (sandboxConfigRpcDto != null && sandboxConfigRpcDto.getScope() == SandboxScopeEnum.USER) {
                    //从用户沙盒切换到云端电脑
                    conversationUpdate.setSandboxServerId("-1");
                    conversationDto.setSandboxServerId(conversationUpdate.getSandboxServerId());
                }
            } catch (NumberFormatException e) {
                // 忽略
                log.warn("sandboxId {} 转换异常", conversationDto.getSandboxServerId());
            }
        }
        conversationDomainService.updateConversation(conversationDto.getUserId(), conversationDto.getId(), conversationUpdate);

        String requestId = tryReqDto.getRequestId() == null ? UUID.randomUUID().toString().replace("-", "") : tryReqDto.getRequestId();
        QaDto qaDto = qaNodeHandler.getConversationQaInfo(redisUtil, conversationDto.getId().toString());
        if (qaDto != null) {
            if (StringUtils.isNotBlank(qaDto.getAnswer())) {
                qaDto.setAnswer(qaDto.getAnswer() + "\n" + tryReqDto.getMessage());
            } else {
                qaDto.setAnswer(tryReqDto.getMessage());
            }
            requestId = qaDto.getRequestId();
            qaNodeHandler.addOrUpdateConversationQaInfo(redisUtil, conversationDto.getId().toString(), qaDto);
            if (qaDto.getWorkflowId() != null) {
                agentConfigDto.getAgentComponentConfigList().forEach(agentComponentConfigDto -> {
                    if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Workflow && agentComponentConfigDto.getTargetId() != null && agentComponentConfigDto.getTargetId().equals(qaDto.getWorkflowId())) {
                        WorkflowBindConfigDto bindConfig = (WorkflowBindConfigDto) agentComponentConfigDto.getBindConfig();
                        bindConfig.setInvokeType(WorkflowBindConfigDto.WorkflowInvokeTypeEnum.AUTO);
                        bindConfig.setUseResultCache(true);
                    }
                });
            }
        }
        //追加全局提示词
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (StringUtils.isNotBlank(tenantConfigDto.getGlobalSystemPrompt())) {
            agentConfigDto.setSystemPrompt(tenantConfigDto.getGlobalSystemPrompt() + "\n" + agentConfigDto.getSystemPrompt());
        }

        UserDto userDto = (UserDto) RequestContext.get().getUser();
        TraceContext traceContext = TraceContext.builder()
                .traceId(requestId)
                .tenantId(RequestContext.get().getTenantId())
                .userId(RequestContext.get().getUserId())
                .conversationId(tryReqDto.getConversationId().toString())
                .userName(userDto.getUserName())
                .nickName(userDto.getNickName())
                .billUserId(priceEstimate.getResourceBillUserId())
                .enableSubscription(tenantConfigDto.getEnableSubscription() != null && tenantConfigDto.getEnableSubscription() == 1)
                .subscriptionId(priceEstimate.getSubscription() != null ? ((UserSubscriptionDTO) priceEstimate.getSubscription()).getId() : null)
                .traceTargets(new ArrayList<>())
                .build();

        TraceContext.TraceTarget traceTarget = TraceContext.TraceTarget.builder()
                .targetType(TraceContext.TraceTargetType.Agent)
                .targetId(agentConfigDto.getId().toString())
                .name(agentConfigDto.getName())
                .description(agentConfigDto.getDescription())
                .icon(agentConfigDto.getIcon())
                .build();

        traceContext.getTraceTargets().add(traceTarget);

        AgentContext agentContext = new AgentContext();
        agentContext.setTraceContext(traceContext);
        agentContext.setAgentConfig(agentConfigDto);
        agentContext.setHeaders(headersFromRequest);
        agentContext.setRequestId(requestId);
        agentContext.setConversationId(conversationDto.getId().toString());
        agentContext.setMessage(tryReqDto.getMessage());
        agentContext.setOriginalMessage(tryReqDto.getMessage());
        agentContext.setDebug(conversationDto.getDevMode() == 1);
        agentContext.setUser((UserDto) RequestContext.get().getUser());
        agentContext.setTenantConfig(tenantConfigDto);
        agentContext.setConversation(conversationDto);
        agentContext.setUserId(RequestContext.get().getUserId());
        agentContext.setUid(((UserDto) RequestContext.get().getUser()).getUid());
        agentContext.setUserName(userDto.getNickName() != null ? userDto.getNickName() : userDto.getUserName());
        agentContext.setAttachments(tryReqDto.getAttachments());
        agentContext.setUserDataPermission(userDataPermission);
        agentContext.setDefaultModelChanged(!isDefaultModel);
        agentContext.setAgentMode(tryReqDto.getAgentMode());
        if (tryReqDto.getFilterSensitive() == null) {
            // 自己开发的智能体不过滤敏感信息
            agentContext.setFilterSensitive(!agentContext.getUserId().equals(agentConfigDto.getCreatorId()));
        } else {
            agentContext.setFilterSensitive(tryReqDto.getFilterSensitive());
        }
        if (tryReqDto.getVariableParams() != null) {
            //移除tryReqDto.getVariableParams()中key为系统变量的值
            tryReqDto.getVariableParams().entrySet().removeIf(entry -> GlobalVariableEnum.isSystemVariable(entry.getKey()));
            agentContext.getVariableParams().putAll(tryReqDto.getVariableParams());
        }
        if (tryReqDto.getSelectedComponents() == null) {
            tryReqDto.setSelectedComponents(new ArrayList<>());
        }
        //过滤出模型
        List<TryReqDto.SelectedComponentDto> modelComponents = tryReqDto.getSelectedComponents().stream().filter(selectedComponentDto -> selectedComponentDto.getType() == AgentComponentConfig.Type.Model).collect(Collectors.toList());
        if (!modelComponents.isEmpty()) {
            ModelConfigDto modelConfigDto = modelApplicationService.queryModelConfigById(modelComponents.get(0).getId());
            if (modelConfigDto != null) {
                if (modelConfigDto.getScope() == ModelConfig.ModelScopeEnum.Tenant) {
                    agentConfigDto.getModelComponentConfig().setTargetConfig(modelConfigDto);
                } else {
                    ModelBindConfigDto bindConfig = (ModelBindConfigDto) agentConfigDto.getModelComponentConfig().getBindConfig();
                    if (bindConfig.getReasoningModelId() != null && bindConfig.getReasoningModelId().equals(modelConfigDto.getId())) {
                        agentConfigDto.getModelComponentConfig().setTargetConfig(modelConfigDto);
                    }
                }
            }
        }

        List<SkillConfigDto> userSelectedSkills = new ArrayList<>();
        //tryReqDto.getSelectedComponents()移除Model
        tryReqDto.getSelectedComponents().removeIf(selectedComponentDto -> selectedComponentDto.getType() == AgentComponentConfig.Type.Model);
        List<Long> selectedIds = tryReqDto.getSelectedComponents().stream().map(TryReqDto.SelectedComponentDto::getId).toList();
        agentConfigDto.getAgentComponentConfigList().removeIf(agentComponentConfigDto -> {
            //根据前端传递要自动调用的组件，把对应组件改成自动调用
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Workflow) {
                WorkflowBindConfigDto bindConfig = (WorkflowBindConfigDto) agentComponentConfigDto.getBindConfig();
                if (bindConfig.getInvokeType() == WorkflowBindConfigDto.WorkflowInvokeTypeEnum.MANUAL) {
                    //手动调用未选择则不调用
                    if (!selectedIds.contains(agentComponentConfigDto.getId())) {
                        return true;
                    }
                    bindConfig.setInvokeType(WorkflowBindConfigDto.WorkflowInvokeTypeEnum.AUTO);
                }
                if (bindConfig.getInvokeType() == WorkflowBindConfigDto.WorkflowInvokeTypeEnum.MANUAL_ON_DEMAND) {
                    //未选择则不调用
                    if (!selectedIds.contains(agentComponentConfigDto.getId())) {
                        return true;
                    }
                    //选择后调用改为按需调用
                    bindConfig.setInvokeType(WorkflowBindConfigDto.WorkflowInvokeTypeEnum.ON_DEMAND);
                }
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Plugin) {
                PluginBindConfigDto bindConfig = (PluginBindConfigDto) agentComponentConfigDto.getBindConfig();
                if (bindConfig.getInvokeType() == PluginBindConfigDto.PluginInvokeTypeEnum.MANUAL) {
                    //手动调用未选择则不调用
                    if (!selectedIds.contains(agentComponentConfigDto.getId())) {
                        return true;
                    }
                    bindConfig.setInvokeType(PluginBindConfigDto.PluginInvokeTypeEnum.AUTO);
                }
                if (bindConfig.getInvokeType() == PluginBindConfigDto.PluginInvokeTypeEnum.MANUAL_ON_DEMAND) {
                    //未选择则不调用
                    if (!selectedIds.contains(agentComponentConfigDto.getId())) {
                        return true;
                    }
                    //选择后调用改为按需调用
                    bindConfig.setInvokeType(PluginBindConfigDto.PluginInvokeTypeEnum.ON_DEMAND);
                }
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Knowledge) {
                KnowledgeBaseBindConfigDto bindConfig = (KnowledgeBaseBindConfigDto) agentComponentConfigDto.getBindConfig();
                if (bindConfig.getInvokeType() == KnowledgeBaseBindConfigDto.InvokeTypeEnum.MANUAL) {
                    //手动调用未选择则不调用
                    if (!selectedIds.contains(agentComponentConfigDto.getId())) {
                        return true;
                    }
                    bindConfig.setInvokeType(KnowledgeBaseBindConfigDto.InvokeTypeEnum.AUTO);
                }
                if (bindConfig.getInvokeType() == KnowledgeBaseBindConfigDto.InvokeTypeEnum.MANUAL_ON_DEMAND) {
                    //未选择则不调用
                    if (!selectedIds.contains(agentComponentConfigDto.getId())) {
                        return true;
                    }
                    //选择后调用改为按需调用
                    bindConfig.setInvokeType(KnowledgeBaseBindConfigDto.InvokeTypeEnum.ON_DEMAND);
                }
            }
            if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Skill) {
                SkillBindConfigDto bindConfig = (SkillBindConfigDto) agentComponentConfigDto.getBindConfig();
                if (bindConfig.getInvokeType() == SkillBindConfigDto.SkillInvokeTypeEnum.MANUAL_ON_DEMAND && selectedIds.contains(agentComponentConfigDto.getId())) {
                    SkillConfigDto skillConfigDto = (SkillConfigDto) agentComponentConfigDto.getTargetConfig();
                    userSelectedSkills.add(skillConfigDto);
                }
            }
            return false;
        });
        List<SkillConfigDto> userAtSkillConfigs = null;
        if (CollectionUtils.isNotEmpty(tryReqDto.getSkillIds())) {
            userAtSkillConfigs = skillApplicationService.queryUserRelatedPublishedSkillConfigs(RequestContext.get().getUserId(), tryReqDto.getSkillIds());
            userSelectedSkills.addAll(userAtSkillConfigs);
        }

        if (!userSelectedSkills.isEmpty()) {
            StringBuilder userPromptBuilder = new StringBuilder();
            if (null != agentConfigDto.getUserPrompt()) {
                userPromptBuilder.append(agentConfigDto.getUserPrompt());
            }
            userPromptBuilder.append("\nPlease use the following skills to complete user tasks. The following skills may be newly added. If there are no relevant definitions in the context, please load them from the working directory.\n");
            userSelectedSkills.forEach(skillConfigDto -> {
                userPromptBuilder.append("- ").append(skillConfigDto.getEnName() == null ? skillConfigDto.getName() : skillConfigDto.getEnName()).append("\n");
            });
            agentConfigDto.setUserPrompt(userPromptBuilder.toString());
        }

        boolean isMultiAgent = null != agentConfigDto.getAgentComponentConfigList().stream().filter(agentComponentConfigDto -> agentComponentConfigDto.getType() == AgentComponentConfig.Type.Agent).findFirst().orElse(null);
        //任务型智能体MCP转换
        //开发编排模式每次都需要刷新mcp配置
        if ("TaskAgent".equals(agentConfigDto.getType())) {
            boolean isDev = conversationDto.getDevMode() != null && conversationDto.getDevMode().intValue() == YesOrNoEnum.Y.getKey();
            updateProxyMcp(agentConfigDto, conversationDto.getId().toString(), traceContext, isDev);
            //AgentFlow和AgentGroup补全群内的Mcp
            if (isMultiAgent) {
                agentConfigDto.getAgentComponentConfigList().forEach(agentComponentConfigDto -> {
                    if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Agent && agentComponentConfigDto.getTargetConfig() instanceof AgentConfigDto agentConfigDto1) {
                        updateProxyMcp(agentConfigDto1, conversationDto.getId().toString(), traceContext, false);
                    }
                });
            }
            if (AgentConfigDto.AgentSubType.Flow.name().equals(agentConfigDto.getSubType())) {
                AgentComponentConfigDto componentConfigDto = agentConfigDto.getAgentComponentConfigList().stream().filter(agentComponentConfigDto -> agentComponentConfigDto.getType() == AgentComponentConfig.Type.Workflow).findFirst().orElse(null);
                if (componentConfigDto != null && componentConfigDto.getTargetConfig() instanceof WorkflowConfigDto workflowConfigDto) {
                    workflowConfigDto.getNodes().forEach(node -> {
                        if (node.getNodeConfig() instanceof AgentNodeConfigDto agentNodeConfigDto && agentNodeConfigDto.getAgentConfigDto() != null) {
                            agentNodeConfigDto.getAgentConfigDto().setIcon(DefaultIconUrlUtil.setDefaultIconUrl(agentNodeConfigDto.getAgentConfigDto().getIcon(), agentNodeConfigDto.getAgentConfigDto().getName(), "Agent"));
                            setFileAkForCustomAgent(agentNodeConfigDto.getAgentConfigDto());
                            updateProxyMcp(agentNodeConfigDto.getAgentConfigDto(), conversationDto.getId().toString(), traceContext, false);
                        }
                    });
                }
            }
        }

        //附件追加AK
        if (tryReqDto.getAttachments() != null) {
            tryReqDto.getAttachments().forEach(attachmentDto -> attachmentDto.setFileUrl(iFileAccessService.getFileUrlWithAk(attachmentDto.getFileUrl(), true)));
        }

        //任务模式下，模型上下文轮数改为0
        if (conversationDto.getType() == Conversation.ConversationType.TASK) {
            Object bindConfig = agentContext.getAgentConfig().getModelComponentConfig().getBindConfig();
            if (bindConfig instanceof ModelBindConfigDto) {
                ((ModelBindConfigDto) bindConfig).setContextRounds(0);
            }
        }

        if ("TaskAgent".equals(agentConfigDto.getType())) {
            agentContext.setSandboxSessionCreatedConsumer(cv -> {
                log.info("Sandbox session created successfully: serverId {}, sessionId {}", cv.getSandboxServerId(), cv.getSandboxSessionId());
                Conversation conversation = new Conversation();
                conversation.setSandboxServerId(cv.getSandboxServerId());
                conversation.setSandboxSessionId(cv.getSandboxSessionId());
                conversation.setModified(new Date());
                TenantFunctions.callWithIgnoreCheck(() -> conversationDomainService.updateConversation(conversationDto.getUserId(), conversationDto.getId(), conversation));
            });

            boolean agentStopped = false;
            //判断更新时间与session创建时间，如果session创建时间在agent更新时间之前，则停止之前的沙箱会话
            if (agentContext.getConversation().getSandboxSessionId() != null && agentContext.getConversation().getDevMode() == 1) {
                if (agentConfigDto.getModified().getTime() > agentContext.getConversation().getModified().getTime()) {
                    sandboxAgentClient.agentStop(agentContext.getConversationId());
                    agentStopped = true;
                }
            } else if (agentContext.getConversation().getSandboxSessionId() != null && agentConfigDto.getPublishDate().getTime() > agentContext.getConversation().getModified().getTime()) {
                sandboxAgentClient.agentStop(agentContext.getConversationId());
                agentStopped = true;
            } else if (modelChanged) {
                sandboxAgentClient.agentStop(agentContext.getConversationId());
            } else {
                //AgentGroup
                for (AgentComponentConfigDto agentComponentConfigDto : agentContext.getAgentConfig().getAgentComponentConfigList()) {
                    if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Agent) {
                        if (agentComponentConfigDto.getTargetConfig() instanceof AgentConfigDto agentConfigDto0) {
                            if (agentConfigDto0.getModified().getTime() > agentContext.getConversation().getModified().getTime()) {
                                sandboxAgentClient.agentStop(agentContext.getConversationId());
                                agentStopped = true;
                                break;
                            }
                        }
                    }
                }
                //AgentFlow
                if (AgentConfigDto.AgentSubType.Flow.name().equals(agentConfigDto.getSubType())) {
                    for (AgentComponentConfigDto agentComponentConfigDto : agentContext.getAgentConfig().getAgentComponentConfigList()) {
                        if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Workflow && agentComponentConfigDto.getTargetConfig() instanceof WorkflowConfigDto workflowConfigDto) {
                            if (devMode) {
                                List<WorkflowNodeCheckDto> workflowNodeCheckDtos = workflowApplicationService.validWorkflow(workflowConfigDto.getId());
                                if (CollectionUtils.isNotEmpty(workflowNodeCheckDtos)) {
                                    Map<Long, WorkflowNodeDto> nodeDtoMap = workflowConfigDto.getNodes().stream().collect(Collectors.toMap(WorkflowNodeDto::getId, node -> node, (o1, o2) -> o1));
                                    List<String> errorMessages = workflowNodeCheckDtos.stream().filter(workflowNodeCheckDto -> !workflowNodeCheckDto.isSuccess()).map(workflowNodeCheckDto -> {
                                        WorkflowNodeDto workflowNodeDto = nodeDtoMap.get(workflowNodeCheckDto.getNodeId());
                                        if (workflowNodeDto != null) {
                                            return workflowNodeCheckDto.getMessages().stream().map(message -> "[" + workflowNodeDto.getName() + "]" + message).toList();
                                        }
                                        return workflowNodeCheckDto.getMessages();
                                    }).flatMap(List::stream).collect(Collectors.toList());
                                    if (CollectionUtils.isNotEmpty(errorMessages)) {
                                        String join = Joiner.on("\n\n").join(errorMessages);
                                        errorOutput.setError(join);
                                        agentExecuteResult.setError(errorOutput.getError());
                                        return errorOutput(errorOutput, conversationDto);
                                    }
                                }
                            }
                            for (WorkflowNodeDto workflowNodeDto : workflowConfigDto.getNodes()) {
                                if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Agent && workflowNodeDto.getNodeConfig() instanceof AgentNodeConfigDto agentNodeConfigDto) {
                                    if (agentNodeConfigDto.getAgentConfigDto() == null) {
                                        errorOutput.setError("Agent configuration is incomplete. Please contact the developer for inspection.");
                                        agentExecuteResult.setError(errorOutput.getError());
                                        return errorOutput(errorOutput, conversationDto);
                                    }
                                    if (agentNodeConfigDto.getAgentConfigDto().getModified().getTime() > agentContext.getConversation().getModified().getTime()) {
                                        sandboxAgentClient.agentStop(agentContext.getConversationId());
                                        agentStopped = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                }
            }

            boolean[] updateSkills = {false};
            List<Long> skillIds = new ArrayList<>();
            List<SubagentDto> subagents = new ArrayList<>();
            Map<String, List<HookEntryDto>> hooksConfig = new HashMap<>();
            List<HookScriptDto> hookScripts = new ArrayList<>();
            Map<String, Object> hookArgs = new HashMap<>();
            agentContext.getVariableParams().forEach((key, value) -> {
                if (!GlobalVariableEnum.isSystemVariable(key) && !"SANDBOX_ACCESS_KEY".equals(key)) {
                    hookArgs.put(key, value);
                }
            });
            //MainAgent
            checkIfSkillsUpdated(agentConfigDto, conversationDto, skillIds, subagents, hooksConfig, updateSkills);
            //AgentGroup
            List<AgentComponentConfigDto> agentComponentConfigs = agentContext.getAgentConfig().getAgentComponentConfigList().stream().filter(agentComponentConfigDto -> agentComponentConfigDto.getType() == AgentComponentConfig.Type.Agent).toList();
            agentComponentConfigs.forEach(agentComponentConfigDto -> {
                if (agentComponentConfigDto.getTargetConfig() instanceof AgentConfigDto groupAgentConfigDto) {
                    groupAgentConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(groupAgentConfigDto.getIcon(), groupAgentConfigDto.getName(), "agent"));
                    setFileAkForCustomAgent(groupAgentConfigDto);
                    checkIfSkillsUpdated(groupAgentConfigDto, conversationDto, skillIds, subagents, hooksConfig, updateSkills);
                }
            });
            //AgentFlow
            if (AgentConfigDto.AgentSubType.Flow.name().equals(agentConfigDto.getSubType())) {
                for (AgentComponentConfigDto agentComponentConfigDto : agentContext.getAgentConfig().getAgentComponentConfigList()) {
                    if (agentComponentConfigDto.getType() == AgentComponentConfig.Type.Workflow && agentComponentConfigDto.getTargetConfig() instanceof WorkflowConfigDto workflowConfigDto) {
                        for (WorkflowNodeDto workflowNodeDto : workflowConfigDto.getNodes()) {
                            if (workflowNodeDto.getType() == WorkflowNodeConfig.NodeType.Agent && workflowNodeDto.getNodeConfig() instanceof AgentNodeConfigDto agentNodeConfigDto) {
                                if (agentNodeConfigDto.getAgentConfigDto() == null) {
                                    throw new BizException("Agent configuration is incomplete. Please contact the developer for inspection.");
                                }
                                checkIfSkillsUpdated(agentNodeConfigDto.getAgentConfigDto(), conversationDto, skillIds, subagents, hooksConfig, updateSkills);
                            }
                        }
                    }
                }
            }

            PlatformEnvHookSupport.apply(hookArgs, hooksConfig, hookScripts);

            if (agentContext.getConversation().getSandboxSessionId() == null || updateSkills[0] || agentStopped) {
                log.info("Start creating workspace and pushing skills, agentId {}, conversationId {}, skillIds {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), skillIds);
                try {
                    CreateWorkspaceDto createWorkspaceDto = CreateWorkspaceDto.builder()
                            .userId(agentContext.getUserId())
                            .cId(conversationDto.getId())
                            .skillIds(skillIds)
                            .subagents(subagents)
                            .hooksConfig(hooksConfig)
                            .hookScripts(hookScripts.isEmpty() ? null : hookScripts)
                            .build();
                    agentWorkspaceApplicationService.createWorkspace(createWorkspaceDto);
                } catch (Exception e) {
                    log.error("Failed to create workspace and push skills, agentId {}, conversationId {}, skillIds {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), skillIds, e);
                    errorOutput.setError(e.getMessage());
                    agentExecuteResult.setError(e.getMessage());
                    return errorOutput(errorOutput, conversationDto);
                }
                log.info("Create workspace and push skills completed, agentId {}, conversationId {}, skillIds {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), skillIds);
            }
            if (CollectionUtils.isNotEmpty(userAtSkillConfigs)) {
                AddSkillsToWorkspaceDto addSkillsToWorkspaceDto = AddSkillsToWorkspaceDto.builder()
                        .userId(RequestContext.get().getUserId())
                        .cId(conversationDto.getId())
                        .skillConfigs(userAtSkillConfigs).build();
                try {
                    agentWorkspaceApplicationService.addSkillsToWorkspace(addSkillsToWorkspaceDto);
                    skillApplicationService.saveRecentlyUsedSkills(userAtSkillConfigs.stream().map(SkillConfigDto::getId).collect(Collectors.toList()));
                } catch (Exception e) {
                    errorOutput.setError(e.getMessage());
                    agentExecuteResult.setError(e.getMessage());
                    return errorOutput(errorOutput, conversationDto);
                }
            }
            //创建沙箱访问平台的accessKey，针对UserAccessKeyDto.AKTargetType.Sandbox已控制可访问范围
            UserAccessKeyDto userAccessKey = userAccessKeyApiService.queryAccessKey(agentContext.getUserId(), UserAccessKeyDto.AKTargetType.Sandbox, agentConfigDto.getId().toString());
            if (userAccessKey == null) {
                userAccessKey = userAccessKeyApiService.newAccessKey(agentContext.getUserId(), UserAccessKeyDto.AKTargetType.Sandbox, agentConfigDto.getId().toString());
            }
            UserAccessKeyDto.UserAccessKeyConfig userAccessKeyConfig = UserAccessKeyDto.UserAccessKeyConfig.builder()
                    .isDevMode(devMode != null && devMode ? 1 : 0)
                    .enabled(true)
                    .conversationId(tryReqDto.getConversationId().toString())
                    .requestId(RequestContext.get().getRequestId())
                    .traceContext(traceContext)
                    .build();
            userAccessKeyApiService.updateUserAccessKeyConfig(userAccessKey.getId(), userAccessKeyConfig);
            agentContext.getVariableParams().put("SANDBOX_ACCESS_KEY", userAccessKey.getAccessKey());
        }

        Sinks.Many<AgentOutputDto> sink = Sinks.many().multicast().onBackpressureBuffer();
        agentContext.setOutputConsumer(outputDto -> {
            sink.tryEmitNext(outputDto);
            if (outputDto.isCompleted()) {
                sink.tryEmitComplete();
            }
        });
        //日志记录
        SimpleJvmHashCache.putHash(agentContext.getRequestId(), "logId", agentContext.getRequestId(), DEFAULT_EXPIRE_AFTER_SECONDS);
        SimpleJvmHashCache.putHash(agentContext.getRequestId(), "logType", "Agent", DEFAULT_EXPIRE_AFTER_SECONDS);
        SimpleJvmHashCache.putHash(agentContext.getRequestId(), "userMessage", agentContext.getMessage(), DEFAULT_EXPIRE_AFTER_SECONDS);
        SimpleJvmHashCache.putHash(agentContext.getRequestId(), "conversationId", agentContext.getConversationId(), DEFAULT_EXPIRE_AFTER_SECONDS);
        SimpleJvmHashCache.putHash(agentContext.getRequestId(), "agentId", agentContext.getAgentConfig().getId(), DEFAULT_EXPIRE_AFTER_SECONDS);
        SimpleJvmHashCache.putHash(agentContext.getRequestId(), "spaceId", agentContext.getAgentConfig().getSpaceId(), DEFAULT_EXPIRE_AFTER_SECONDS);
        SimpleJvmHashCache.putHash(agentContext.getRequestId(), "agentName", agentContext.getAgentConfig().getName(), DEFAULT_EXPIRE_AFTER_SECONDS);
        SimpleJvmHashCache.putHash(agentContext.getRequestId(), "attachments", agentContext.getAttachments(), DEFAULT_EXPIRE_AFTER_SECONDS);
        SimpleJvmHashCache.putHash(agentContext.getRequestId(), "uid", agentContext.getUid(), DEFAULT_EXPIRE_AFTER_SECONDS);
        SimpleJvmHashCache.putHash(agentContext.getRequestId(), "userId", agentContext.getUserId(), DEFAULT_EXPIRE_AFTER_SECONDS);
        SimpleJvmHashCache.putHash(agentContext.getRequestId(), "tenantId", RequestContext.get().getTenantId(), DEFAULT_EXPIRE_AFTER_SECONDS);
        SimpleJvmHashCache.putHash(agentContext.getRequestId(), "userName", agentContext.getUserName(), DEFAULT_EXPIRE_AFTER_SECONDS);
        SimpleJvmHashCache.putHash(agentContext.getRequestId(), "agentExecuteResult", agentContext.getAgentExecuteResult(), DEFAULT_EXPIRE_AFTER_SECONDS);

        final RequestContext<Object> requestContext = RequestContext.get();
        Flux<AgentOutputDto> flux;

        //Agent(ChatBot、Custom or General)
        flux = agentExecutor.execute(agentContext);

        AtomicBoolean isComplete = agentContext.getFinished();
        AtomicBoolean sseCanceled = new AtomicBoolean(false);
        AtomicBoolean userStopped = new AtomicBoolean(false);
        AtomicBoolean gitAutoCommitted = new AtomicBoolean(false);
        AtomicLong lastActiveTime = new AtomicLong(System.currentTimeMillis());
        final boolean autoGitCommitEnabled = isVersionControlEnabled(agentConfigDto);

        //智能体执行输出
        Object finalTargetConfig = targetConfig;
        Disposable disposable = flux.onErrorResume(Mono::error)
                .doOnCancel(() -> {
                    AgentOutputDto agentOutputDto = AgentExecutor.buildFinalResultOutput(agentContext);
                    chatMessagePubSubService.publish(agentContext.getConversationId(), JSON.toJSONString(agentOutputDto));
                    chatCompleted(requestContext, agentContext, tryReqDto, isComplete, sseCanceled, conversationDto, sink, finalTargetConfig, gitAutoCommitted, autoGitCommitEnabled);
                })
                .subscribe(
                        msg -> {
                            lastActiveTime.set(System.currentTimeMillis());
                            Sinks.EmitResult emitResult = sink.tryEmitNext(msg);
                            if (emitResult.isFailure()) {
                                log.error("Agent output failed, cid {}, error {}", agentContext.getConversationId(), emitResult);
                            }
                            chatMessagePubSubService.publish(agentContext.getConversationId(), JSON.toJSONString(msg));
                        },
                        e -> {
                            isComplete.set(true);
                            updateConversationStatus(agentContext, conversationDto, Conversation.ConversationTaskStatus.FAILED);
                            if (agentContext.isInterrupted()) {
                                log.info("Agent execution interrupted");
                                sink.tryEmitComplete();
                                return;
                            }
                            log.error("Agent execution exception", e);
                            sink.tryEmitComplete();
                            pushLogQueue(agentContext, tryReqDto.getFrom(), e.getMessage());
                        },
                        () -> chatCompleted(requestContext, agentContext, tryReqDto, isComplete, sseCanceled, conversationDto, sink, finalTargetConfig, gitAutoCommitted, autoGitCommitEnabled));

        //心跳
        Flux.interval(Duration.ofSeconds(10)).map(tick -> {
                    log.info("Heartbeat sent, cid {}, tick {}", agentContext.getConversationId(), tick);
                    if (tick >= 360) {// 一个小时如果都还没有结束，打印详细日志，排查问题
                        log.warn("Agent execution time too long, cid {}", agentContext.getConversationId());
                        log.warn("Execution log, cid {}, log {}", agentContext.getConversationId(), JSON.toJSONString(AgentExecutor.buildAgentExecuteResult(agentContext)));
                        if (System.currentTimeMillis() - lastActiveTime.get() > 3600 * 2 * 1000L) {
                            //2个小时没有收到任何智能体消息，主动终止
                            log.warn("Agent execution wait too long, actively terminate, cid {}", agentContext.getConversationId());
                            isComplete.set(true);
                            disposable.dispose();
                        }
                    }
                    AgentOutputDto agentOutputDto = new AgentOutputDto();
                    agentOutputDto.setRequestId(agentContext.getRequestId());
                    agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.HEART_BEAT);
                    chatMessagePubSubService.publish(agentContext.getConversationId(), JSON.toJSONString(agentOutputDto));
                    return agentOutputDto;
                }).takeWhile(outputDto -> !isComplete.get())
                .doOnComplete(() -> log.info("Heartbeat ended, cid {}", agentContext.getConversationId()))
                .doOnNext(sink::tryEmitNext)
                .subscribe();

        // 检测是否用户手动停止
        checkIfRequestStop(agentContext, sink, disposable, isComplete, userStopped);
        return sink.asFlux()
                .doOnComplete(() -> {
                    log.info("Agent execution SSE channel completed, cid {}", agentContext.getConversationId());
                    clearChatStopStatus(agentContext.getConversationId());
                    if (conversationDto.getTaskStatus() == null || conversationDto.getTaskStatus() == Conversation.ConversationTaskStatus.EXECUTING) {
                        updateConversationStatus(agentContext, conversationDto, Conversation.ConversationTaskStatus.COMPLETE);
                    }
                })
                .doOnCancel(() -> {
                    log.info("Agent execution SSE channel cancelled, cid {}", agentContext.getConversationId());
                    sseCanceled.set(true);
                });
    }

    private void chatCompleted(RequestContext<?> requestContext, AgentContext agentContext, TryReqDto tryReqDto, AtomicBoolean isComplete,
                               AtomicBoolean sseCanceled, ConversationDto conversationDto, Sinks.Many<AgentOutputDto> sink, Object finalTargetConfig,
                               AtomicBoolean gitAutoCommitted, boolean autoGitCommitEnabled) {
        log.info("Agent execution completed, cid {}", agentContext.getConversationId());
        AgentConfigDto agentConfigDto = agentContext.getAgentConfig();
        // 只统计平台级别模型
        if ("TaskAgent".equals(agentConfigDto.getType()) && finalTargetConfig instanceof ModelConfigDto && ((ModelConfigDto) finalTargetConfig).getScope() == ModelConfig.ModelScopeEnum.Tenant) {
            metricRpcService.incrementMetricAllPeriods(agentContext.getTenantConfig().getTenantId(), agentContext.getUserId(), BizType.GENERAL_AGENT_CHAT.getCode(), BigDecimal.ONE);
        }
        isComplete.set(true);
        if (autoGitCommitEnabled) {
            tryAutoGitCommit(agentContext, gitAutoCommitted);
        }
        updateConversationStatus(agentContext, conversationDto, Conversation.ConversationTaskStatus.COMPLETE);
        sink.tryEmitComplete();
        pushLogQueue(agentContext, tryReqDto.getFrom(), null);
        try {
            RequestContext.set(requestContext);
            Conversation conversation = conversationDomainService.getConversation(conversationDto.getId());
            if (conversation.getTopicUpdated() == -1 && conversationDto.getDevMode() == 0 && conversationDto.getType() == Conversation.ConversationType.Chat) {
                log.info("Update conversation topic, cid {}", agentContext.getConversationId());
                updateTopic(agentContext, conversationDto);
            }
            if (sseCanceled.get() && conversationDto.getDevMode() == 0 && conversationDto.getType() == Conversation.ConversationType.Chat) {
                log.info("Conversation connection disconnected, task execution completed send notification, cid {}, topic {}", agentContext.getConversationId(), conversationDto.getTopic());
                sendNotification(agentContext, conversation);
            }
        } finally {
            RequestContext.remove();
        }
    }

    private void setFileAkForCustomAgent(AgentConfigDto agentConfigDto) {
        List<AgentPublishVersionDto> publishVersion = agentConfigDto.getPublishVersion();
        if (publishVersion != null && !publishVersion.isEmpty()) {
            publishVersion.forEach(publishVersionDto -> {
                if (publishVersionDto.getPackages() != null) {
                    publishVersionDto.getPackages().forEach(packageArtifact -> packageArtifact.setUrl(iFileAccessService.getFileUrlWithAk(packageArtifact.getUrl(), true)));
                }
            });
        }
    }

    private void updateProxyMcp(AgentConfigDto agentConfigDto, String conversationId, TraceContext traceContext, boolean isDev) {
        if (!AgentConfigDto.AgentSubType.General.name().equals(agentConfigDto.getSubType()) && !AgentConfigDto.AgentSubType.Custom.name().equals(agentConfigDto.getSubType())) {
            return;
        }
        List<AgentComponentConfigDto> mcpToolComponentList = agentConfigDto.getAgentComponentConfigList().stream().filter(agentComponentConfig -> {
            if (agentComponentConfig.getType() == AgentComponentConfig.Type.Mcp && agentComponentConfig.getTargetConfig() instanceof McpDto mcpDto) {
                return mcpDto.getInstallType() == InstallTypeEnum.COMPONENT;
            }
            return agentComponentConfig.getType() == AgentComponentConfig.Type.Plugin || agentComponentConfig.getType() == AgentComponentConfig.Type.Knowledge
                    || agentComponentConfig.getType() == AgentComponentConfig.Type.Table || agentComponentConfig.getType() == AgentComponentConfig.Type.Workflow;
        }).toList();
        if (mcpToolComponentList.isEmpty()) {
            return;
        }
        if (isDev) {
            agentApplicationService.buildProxyMcp(agentConfigDto, true);
        }
        Map<String, Object> extra = agentConfigDto.getExtra();
        if (extra != null && (extra.containsKey("devProxyMcpId") || extra.containsKey("prodProxyMcpId"))) {
            try {
                Long proxyMcpId = Long.parseLong(agentConfigDto.getExtra().get(isDev ? "devProxyMcpId" : "prodProxyMcpId").toString());
                UserAccessKeyDto.UserAccessKeyConfig userAccessKeyConfig = UserAccessKeyDto.UserAccessKeyConfig.builder()
                        .isDevMode(isDev ? 1 : 0)
                        .enabled(true)
                        .conversationId(conversationId)
                        .requestId(RequestContext.get().getRequestId())
                        .traceContext(traceContext)
                        .build();

                String exportMcpServerConfig = mcpRpcService.getExportMcpServerConfig(traceContext.getUserId(), proxyMcpId, userAccessKeyConfig);
                agentConfigDto.setProxyMcpServerConfig(exportMcpServerConfig);
            } catch (Exception e) {
                //  ignore
                log.warn("Failed to get export mcp server config", e);
            }
        }
    }

    private void checkIfSkillsUpdated(AgentConfigDto groupAgentConfigDto, ConversationDto conversationDto, List<Long> skillIds, List<SubagentDto> subagents, Map<String, List<HookEntryDto>> hooksConfig, boolean[] updateSkills) {
        groupAgentConfigDto.getAgentComponentConfigList().forEach(agentComponentConfigDto0 -> {
            if (agentComponentConfigDto0.getType() == AgentComponentConfig.Type.Skill) {
                if (!skillIds.contains(agentComponentConfigDto0.getTargetId())) {
                    skillIds.add(agentComponentConfigDto0.getTargetId());
                }
                if (((SkillConfigDto) agentComponentConfigDto0.getTargetConfig()).getPublishDate().getTime() > conversationDto.getModified().getTime()) {
                    updateSkills[0] = true;
                }
            }

            if (agentComponentConfigDto0.getType() == AgentComponentConfig.Type.SubAgent) {
                if (agentComponentConfigDto0.getBindConfig() instanceof SubAgentBindConfigDto subAgentBindConfigDto) {
                    if (CollectionUtils.isNotEmpty(subAgentBindConfigDto.getSubAgents())) {
                        subagents.addAll(subAgentBindConfigDto.getSubAgents().stream().map(subAgentDto -> {
                            SubagentDto subagentDto = new SubagentDto();
                            subagentDto.setName(subAgentDto.getName());
                            subagentDto.setContent(subAgentDto.getPrompt());
                            return subagentDto;
                        }).toList());
                    }
                }
            }

            if (agentComponentConfigDto0.getType() == AgentComponentConfig.Type.Hook) {
                if (agentComponentConfigDto0.getBindConfig() instanceof HookConfigDto hookConfigDto) {
                    if (CollectionUtils.isNotEmpty(hookConfigDto.getHooks())) {
                        hookConfigDto.getHooks().forEach(hook -> {
                            List<HookEntryDto> hookEntryDtos = hooksConfig.computeIfAbsent(hook.getEvent(), k -> new ArrayList<>());
                            HookEntryDto hookEntryDto = new HookEntryDto();
                            hookEntryDto.setMatcher(hook.getMatcher());
                            hookEntryDto.setHooks(List.of(JSON.parse(hook.getConfig())));
                            hookEntryDtos.add(hookEntryDto);
                        });
                    }
                }
            }
        });
    }

    private PriceEstimate estimatePrice(AgentConfigDto agentConfigDto, TryReqDto tryReqDto, boolean devMode, boolean isDefaultModel) {
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfigDto.getEnableSubscription() == null || tenantConfigDto.getEnableSubscription() == 0) {
            return PriceEstimate.builder().pass(true).build();
        }
        //用户额外追加的技能，算在使用者身上
        if (CollectionUtils.isNotEmpty(tryReqDto.getSkillIds())) {
            PriceEstimate priceEstimate = resourcePricingRpcService.estimatePrice(RequestContext.get().getTenantId(), RequestContext.get().getUserId(), tryReqDto.getSkillIds().stream().map(skillId -> PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.SKILL).targetId(skillId.toString()).build()).collect(Collectors.toList()));
            if (!priceEstimate.isPass()) {
                return priceEstimate;
            }
        }

        Long resourceBillUserId = RequestContext.get().getUserId();
        UserSubscriptionDTO subscription = null;
        if (!devMode) {
            //检查用户是否订阅，编排模式下不判断是否订阅
            PriceEstimate priceEstimate = resourcePricingRpcService.estimatePrice(RequestContext.get().getTenantId(), RequestContext.get().getUserId(), List.of(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.AGENT).targetId(agentConfigDto.getId().toString()).build()));
            if (!priceEstimate.isPass()) {
                return priceEstimate;
            }
            subscription = priceEstimate.getSubscription() != null ? (UserSubscriptionDTO) priceEstimate.getSubscription() : null;
            if (subscription != null && (subscription.getPlan().getFunctionOnly() == null || !subscription.getPlan().getFunctionOnly())) {
                // 订阅计划为包干订阅，则计费到创建者
                resourceBillUserId = agentConfigDto.getCreatorId();
            }
            //用户未订阅，则试用计费到创建者
            if (priceEstimate.isTrial()) {
                resourceBillUserId = agentConfigDto.getCreatorId();
            }
        }

        List<PriceEstimate.EstimateTarget> estimateTargets = new ArrayList<>();
        List<PriceEstimate.EstimateTarget> estimateSkillTargets = new ArrayList<>();
        Long targetId = agentConfigDto.getModelComponentConfig().getTargetId();
        if (targetId != null) {
            if (!isDefaultModel && !resourceBillUserId.equals(RequestContext.get().getUserId())) {
                PriceEstimate priceEstimate = resourcePricingRpcService.estimatePrice(RequestContext.get().getTenantId(), RequestContext.get().getUserId(), List.of(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.MODEL).targetId(targetId.toString()).build()));
                if (!priceEstimate.isPass()) {
                    return priceEstimate;
                }
            } else {
                estimateTargets.add(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.MODEL).targetId(targetId.toString()).build());
            }
        }
        resourcePricingRpcService.completeAgentEstimate(agentConfigDto, estimateTargets, estimateSkillTargets, new HashSet<>());
        PriceEstimate priceEstimate = resourcePricingRpcService.estimatePrice(RequestContext.get().getTenantId(), agentConfigDto.getCreatorId(), estimateSkillTargets);
        if (!priceEstimate.isPass()) {
            String message = I18nUtil.systemMessage("Backend.Chat.Error.SkillSubscriptionExpiredContactDeveloper", priceEstimate.getTargetId());
            if (agentConfigDto.getCreatorId().equals(RequestContext.get().getUserId())) {
                message = I18nUtil.systemMessage("Backend.Chat.Error.SkillSubscriptionExpiredCheckOwner", priceEstimate.getTargetId());
            }
            if (!RequestContext.get().getUserId().equals(agentConfigDto.getCreatorId())) {
                priceEstimate = resourcePricingRpcService.estimatePrice(RequestContext.get().getTenantId(), RequestContext.get().getUserId(), estimateSkillTargets);
                if (!priceEstimate.isPass()) {
                    priceEstimate.setMessage(message);
                    return priceEstimate;
                }
            } else {
                priceEstimate.setMessage(message);
                return priceEstimate;
            }
        }

        priceEstimate = resourcePricingRpcService.estimatePrice(RequestContext.get().getTenantId(), resourceBillUserId, estimateTargets);
        if (!priceEstimate.isPass()) {
            String message = I18nUtil.systemMessage("Backend.Chat.Error.DeveloperCreditInsufficient");
            if (resourceBillUserId.equals(RequestContext.get().getUserId())) {
                message = I18nUtil.systemMessage("Backend.Chat.Error.UserCreditInsufficient");
            }
            priceEstimate.setMessage(message);
            return priceEstimate;
        }

        return PriceEstimate.builder().pass(true).subscription(subscription).resourceBillUserId(resourceBillUserId).build();
    }

    private Flux<AgentOutputDto> errorOutput(AgentOutputDto errorOutput, ConversationDto conversationDto) {
        if (conversationDto != null && conversationDto.getTaskStatus() == Conversation.ConversationTaskStatus.EXECUTING) {
            Conversation conversationUpdate = new Conversation();
            conversationUpdate.setTaskStatus(Conversation.ConversationTaskStatus.FAILED);
            conversationDomainService.updateConversation(conversationDto.getUserId(), conversationDto.getId(), conversationUpdate);
        }
        AgentOutputDto agentOutputDto = new AgentOutputDto();
        agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.MESSAGE);
        ChatMessageDto chatMessageDto = ChatMessageDto.builder()
                .role(ChatMessageDto.Role.ASSISTANT)
                .type(MessageTypeEnum.CHAT)
                .text(errorOutput.getError())
                .finished(true)
                .finishReason("ERROR")
                .build();
        agentOutputDto.setData(chatMessageDto);
        return Flux.just(agentOutputDto, errorOutput);
    }

    private void updateConversationStatus(AgentContext agentContext, ConversationDto conversationDto, Conversation.ConversationTaskStatus conversationTaskStatus) {
        conversationDto.setTaskStatus(conversationTaskStatus);
        Conversation conversationUpdate = new Conversation();
        conversationUpdate.setTaskStatus(conversationTaskStatus);
        //从agentContext.getVariableParams()中获取非系统变量的内容
        conversationUpdate.setVariables(new HashMap<>());
        agentContext.getVariableParams().forEach((key, value) -> {
            if (!GlobalVariableEnum.isSystemVariable(key) && !"SANDBOX_ACCESS_KEY".equals(key)) {
                conversationUpdate.getVariables().put(key, value);
            }
        });
        TenantFunctions.callWithIgnoreCheck(() -> conversationDomainService.updateConversation(conversationDto.getUserId(), conversationDto.getId(), conversationUpdate));

        // 发送事件消息
        if (conversationTaskStatus == Conversation.ConversationTaskStatus.COMPLETE || conversationTaskStatus == Conversation.ConversationTaskStatus.FAILED) {
            EventDto<Object> event = EventDto.builder()
                    .type(EventDto.EVENT_TYPE_CHAT_FINISHED)
                    .event(Map.of("conversationId", conversationDto.getId().toString(), "requestId", agentContext.getRequestId(), "status", conversationTaskStatus))
                    .build();
            notifyMessageApplicationService.publishEvent(agentContext.getUserId(), event);
            chatMessagePubSubService.finishPublish(conversationDto.getId().toString());
        }
    }

    private void updateTopic(AgentContext agentContext, ConversationDto conversationDto) {
        ConversationUpdateDto conversationUpdateDto = new ConversationUpdateDto();
        conversationUpdateDto.setId(Long.parseLong(agentContext.getConversationId()));
        conversationUpdateDto.setFirstMessage(agentContext.getMessage());
        updateConversationTopic(agentContext.getUserId(), conversationUpdateDto);
        conversationDto.setTopic(conversationDto.getTopic());
        if (conversationDto.getTopic() != null) {
            conversationDto.setTopicUpdated(1);
        }
    }

    private boolean isVersionControlEnabled(AgentConfigDto executingAgentConfig) {
        if (executingAgentConfig == null || "ChatBot".equals(executingAgentConfig.getType())) {
            return false;
        }
        return YesOrNoEnum.Y.getKey().equals(executingAgentConfig.getEnableVersionControl());
    }

    private void tryAutoGitCommit(AgentContext agentContext, AtomicBoolean gitCommitted) {
        if (gitCommitted.get()) {
            return;
        }
        if (autoGitCommit(agentContext)) {
            gitCommitted.set(true);
        }
    }

    /**
     * TaskAgent 执行完成后自动 git add + commit，由 git 服务端判断是否有可提交变更
     *
     * @return 是否已成功提交
     */
    private boolean autoGitCommit(AgentContext agentContext) {
        if (!isVersionControlEnabled(agentContext.getAgentConfig())) {
            return false;
        }
        try {
            log.info("Auto git commit start, cid={}", agentContext.getConversationId());
            Long cId = Long.valueOf(agentContext.getConversationId());
            Long userId = agentContext.getUserId();
            String authorName = agentContext.getUserName();
            String message = "auto commit: " + (agentContext.getOriginalMessage() != null
                    ? agentContext.getOriginalMessage().replaceAll("[\\r\\n]+", " ").trim()
                    : "Agent file changes");
            if (message.length() > 200) {
                message = message.substring(0, 200);
            }
            gitRpcClient.commitTaskAgent(cId, userId, message, null, authorName, null);
            log.info("Auto git commit success, cid={}", agentContext.getConversationId());
            return true;
        } catch (Exception e) {
            log.warn("Auto git commit failed, cid={}", agentContext.getConversationId(), e);
            return false;
        }
    }

    private void sendNotification(AgentContext agentContext, Conversation conversation) {
        if (StringUtils.isNotBlank(conversation.getTopic()) && conversation.getTopicUpdated() == 1) {
            String content = I18nUtil.systemMessage(agentContext.getUser().getLangMap(), "Backend.Chat.Notification.ConversationCompleted",
                    conversation.getTopic(),
                    "/home/chat/" + conversation.getId() + "/" + conversation.getAgentId());
            notifyMessageApplicationService.sendNotifyMessage(SendNotifyMessageDto.builder()
                    .scope(NotifyMessage.MessageScope.System)
                    .content(content)
                    .senderId(agentContext.getUserId())
                    .userIds(Collections.singletonList(agentContext.getUserId()))
                    .build());
        }
    }

    private void checkIfRequestStop(AgentContext agentContext, Sinks.Many<AgentOutputDto> sink, Disposable
            disposable, AtomicBoolean isComplete, AtomicBoolean userStopped) {
        timeWheel.schedule((res) -> {
            if (disposable.isDisposed() || isComplete.get()) {
                return;
            }
            if (isChatStop(agentContext.getConversationId())) {
                userStopped.set(true);
                //5秒内没有停止就强行停止
                clearChatStopStatus(agentContext.getConversationId());
                isComplete.set(true);
                AgentOutputDto finalOutput = AgentExecutor.buildFinalResultOutput(agentContext);
                boolean failure = sink.tryEmitNext(finalOutput).isFailure();
                if (failure) {
                    log.warn("final_result send failed");
                }
                sink.tryEmitComplete();
                agentContext.setInterrupted(true);
                disposable.dispose();
            }
            checkIfRequestStop(agentContext, sink, disposable, isComplete, userStopped);
        }, 1);
    }

    private void pushLogQueue(AgentContext agentContext, String from, String errorMsg) {
        Map<String, Object> hashAll = SimpleJvmHashCache.getHashAll(agentContext.getRequestId());
        if (hashAll != null) {
            AgentExecuteResult agentExecuteResult = AgentExecutor.buildAgentExecuteResult(agentContext);
            hashAll.put("agentExecuteResult", agentExecuteResult);
            hashAll.remove("modelExecuteInfos");
            SimpleJvmHashCache.removeHashAll(agentContext.getRequestId());
            if (agentExecuteResult == null) {
                agentExecuteResult = new AgentExecuteResult();
                agentExecuteResult.setSuccess(true);
            }
            if (agentExecuteResult.getSuccess() == null) {
                agentExecuteResult.setSuccess(true);
            }
            LogDocument logDocument = LogDocument.builder()
                    .input(hashAll.get("userMessage") != null ? hashAll.get("userMessage").toString() : null)
                    .output(agentExecuteResult.getOutputText())
                    .processData(JSON.toJSONString(agentExecuteResult))
                    .inputToken(agentExecuteResult.getPromptTokens())
                    .outputToken(agentExecuteResult.getCompletionTokens())
                    .requestStartTime(agentExecuteResult.getStartTime() == null ? System.currentTimeMillis() : agentExecuteResult.getStartTime())
                    .requestEndTime(agentExecuteResult.getEndTime() == null ? System.currentTimeMillis() : agentExecuteResult.getEndTime())
                    .resultCode(agentExecuteResult.getSuccess() && errorMsg == null ? "0000" : "0001")
                    .resultMsg(agentExecuteResult.getError() == null ? errorMsg : agentExecuteResult.getError())
                    .createTime(System.currentTimeMillis())
                    .targetType("Agent")
                    .targetName(agentContext.getAgentConfig().getName())
                    .targetId(String.valueOf(agentContext.getAgentConfig().getId()))
                    .conversationId(agentContext.getConversationId())
                    .userId(agentContext.getUserId())
                    .userName(agentContext.getUserName())
                    .spaceId(agentContext.getAgentConfig().getSpaceId())
                    .tenantId(agentContext.getAgentConfig().getTenantId())
                    .id(UUID.randomUUID().toString().replace("-", ""))
                    .requestId(agentContext.getRequestId())
                    .from(from)
                    .build();
            agentContext.getTraceContext().setLog(logDocument);
            agentContext.getTraceContext().setError(errorMsg != null);
            agentContext.getTraceContext().setErrorMessage(errorMsg);
            agentContext.getTraceContext().setErrorCode("0001");
            iLogRpcService.pushTraceLog(agentContext.getTraceContext());
        }
    }

    @Override
    public Long nextConversationId(Long agentId, String sandboxServerId) {
        return conversationDomainService.nextConversationId(RequestContext.get().getUserId(), agentId, sandboxServerId);
    }
}
