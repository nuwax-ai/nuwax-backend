package com.xspaceagi.agent.core.application.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentComponentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.bind.*;
import com.xspaceagi.agent.core.adapter.repository.entity.*;
import com.xspaceagi.agent.core.domain.service.AgentDomainService;
import com.xspaceagi.agent.core.domain.service.ConversationDomainService;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.agent.AgentExecutor;
import com.xspaceagi.agent.core.infra.component.agent.SandboxAgentClient;
import com.xspaceagi.agent.core.infra.component.agent.dto.AgentExecuteResult;
import com.xspaceagi.agent.core.infra.component.model.dto.CallMessage;
import com.xspaceagi.agent.core.infra.component.workflow.handler.QANodeHandler;
import com.xspaceagi.agent.core.infra.rpc.McpRpcService;
import com.xspaceagi.agent.core.infra.rpc.MetricRpcService;
import com.xspaceagi.agent.core.spec.constant.Prompts;
import com.xspaceagi.agent.core.spec.enums.GlobalVariableEnum;
import com.xspaceagi.agent.core.spec.enums.MessageTypeEnum;
import com.xspaceagi.agent.core.spec.enums.TaskCron;
import com.xspaceagi.agent.core.spec.utils.TikTokensUtil;
import com.xspaceagi.log.sdk.reponse.AgentLogModelResponse;
import com.xspaceagi.log.sdk.request.AgentLogSearchParamsRequest;
import com.xspaceagi.log.sdk.service.ILogRpcService;
import com.xspaceagi.log.sdk.service.LogPlatformRpcService;
import com.xspaceagi.log.sdk.vo.LogDocument;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.enums.InstallTypeEnum;
import com.xspaceagi.memory.sdk.dto.MemoryMetaData;
import com.xspaceagi.memory.sdk.service.IMemoryRpcService;
import com.xspaceagi.sandbox.sdk.server.ISandboxConfigRpcService;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.application.dto.EventDto;
import com.xspaceagi.system.application.dto.SendNotifyMessageDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.NotifyMessageApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.server.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.*;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.FileAkUtil;
import com.xspaceagi.system.spec.utils.RedisUtil;
import com.xspaceagi.system.spec.utils.TimeWheel;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.cache.SimpleJvmHashCache.DEFAULT_EXPIRE_AFTER_SECONDS;

@Slf4j
@Service("conversationApplicationService")
public class ConversationApplicationServiceImpl extends AbstractTaskExecuteService implements ConversationApplicationService, ChatMemory {

    private static final String DEFAULT_TOPIC = "未命名会话";
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
    private LogPlatformRpcService logPlatformRpcService;

    @Resource
    private ILogRpcService iLogRpcService;

    @Resource
    private QANodeHandler qaNodeHandler;

    @Resource
    private TimeWheel timeWheel;

    @Resource
    private FileAkUtil fileAkUtil;

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
    private SandboxAgentClient sandboxAgentClient;

    @Resource
    private SkillApplicationService skillApplicationService;

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
    public ConversationDto createConversation(Long userId, Long agentId, boolean devMode, boolean tempChat, Map<String, Object> variables) {
        AgentConfigDto agentConfigDto = agentApplicationService.queryById(agentId);
        if (agentConfigDto == null) {
            throw new BizException("选择的智能体不存在");
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
            throw new BizException(String.format("用户在每个智能体进行中的定时任务不能超过%d个", MAX_USER_AGENT_TASK_SIZE));
        }
        //检查taskCron是否符合规范
        if (!ScheduleTaskDto.Cron.isValid(taskConversationAddOrUpdateDto.getTaskCron())) {
            throw new IllegalArgumentException("cron表达式不合法");
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
            throw new IllegalArgumentException("cron表达式不合法");
        }
        Assert.notNull(taskConversationAddOrUpdateDto.getId(), "会话ID不能为空");
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
        return conversationDto;
    }

    private ConversationDto convertConversationWithAgent(Conversation conversation) {
        if (conversation == null) {
            return null;
        }
        ConversationDto conversationDto = convertConversation(conversation);
        conversationDto.setAgent(agentApplicationService.queryAgentDetail(conversation.getAgentId(), conversation.getDevMode() != 1));
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
        TopicGenDto topicGenDto;
        if (StringUtils.isBlank(conversationUpdateDto.getTopic())) {
            Conversation conversation1 = conversationDomainService.getConversation(id);
            StringBuilder userPrompt = new StringBuilder();
            if (conversation1 != null && StringUtils.isNotBlank(conversation1.getSummary())) {
                userPrompt.append("## 历史会话总结内容如下：\n").append(conversation1.getSummary());
            }
            userPrompt.append("\n## 需要总结的标题内容如下：\n").append(conversationUpdateDto.getFirstMessage()).append("\n");
            userPrompt.append("---需要总结的内容结束---");
            topicGenDto = modelApplicationService.call(Prompts.CONVERSATION_TOPIC_PROMPT, userPrompt.toString(), new ParameterizedTypeReference<TopicGenDto>() {
            });
            if (topicGenDto == null || StringUtils.isBlank(topicGenDto.getTopic()) || topicGenDto.getTopic().contains("JSON格式标题") || topicGenDto.getTopic().contains("JSON响应")) {
                conversationUpdateDto.setTopic(DEFAULT_TOPIC);
            } else {
                conversationUpdateDto.setTopic(topicGenDto.getTopic());
            }
        }
        Conversation conversation = new Conversation();
        conversation.setTopic(conversationUpdateDto.getTopic());
        conversation.setId(id);
        conversation.setTopicUpdated(1);
        if (conversation.getTopic() != null && conversation.getTopic().trim().startsWith("\"")) {
            conversation.setTopic(conversation.getTopic().replace("\"", "").replace("主题：", "").trim());
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
        List<Long> agentIds = conversations.stream().map(Conversation::getAgentId).toList();
        if (CollectionUtils.isNotEmpty(agentIds)) {
            List<AgentConfig> agentConfigs = agentDomainService.queryListByIds(agentIds);
            // agentConfigs 转map
            Map<Long, AgentDetailDto> agentConfigMap = agentConfigs.stream().map(agentConfig -> {
                AgentDetailDto agentDetailDto = new AgentDetailDto();
                agentDetailDto.setAgentId(agentConfig.getId());
                agentDetailDto.setName(agentConfig.getName());
                agentDetailDto.setType(agentConfig.getType());
                return agentDetailDto;
            }).collect(Collectors.toMap(AgentDetailDto::getAgentId, agentConfig -> agentConfig));
            conversationDtos.forEach(conversationDto -> conversationDto.setAgent(agentConfigMap.get(conversationDto.getAgentId())));
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
        log.debug("开始总结会话: {}", conversationId);
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
            log.debug("开始总结会话: {}", cachedMessageList);
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
        return iMemoryRpcService.searchMemoriesMd(tenantId, userId, agentId, inputMessage, context, justKeywordSearch, filterSensitive);
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

    private String removeSystemTagContent(String text) {
        if (text == null) {
            return null;
        }
        return text.replaceAll("<think>[\\s\\S]*?</think>", "").trim()
                .replaceAll("```xml[\\s\\S]*?<tool_.*>[\\s\\S]*?</tool_.*>[\\s\\S]*?```", " ")
                .replaceAll("<tool_.*>[\\s\\S]*?</tool_.*>", " ");
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (conversationId == null) {
            return;
        }
        messages.forEach(message -> {
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
        });
    }

    @Override
    public List<Message> get(String conversationId, int lastN) {
        if (conversationId.startsWith("agent:")) {
            conversationId = conversationId.replace("agent:", "");
        }
        List<Message> all = new ArrayList<>();
        //获取最新的lastN条消息
        List<ChatMessageDto> cachedMessageList0 = queryMessageList(Long.parseLong(conversationId), Long.MAX_VALUE, lastN);

        //cachedMessageList转Message
        for (ChatMessageDto message : cachedMessageList0) {
            if (message.getRole().name().equals(MessageType.USER.name())) {
                all.add(new UserMessage(message.getText()));
            }
            if (message.getRole().name().equals(MessageType.ASSISTANT.name())) {
                String text = removeSystemTagContent(message.getText());
                if (text != null) {
                    text = text.replaceAll("<markdown-custom-process[^>]*>.*?</markdown-custom-process>", "");
                }
                all.add(new AssistantMessage(text));
            }
            if (message.getRole().name().equals(MessageType.SYSTEM.name())) {
                all.add(new SystemMessage(message.getText()));
            }
        }

        Collections.reverse(all);
        Iterator<Message> iterator = all.iterator();
        int tokenCount = 0;
        MessageType messageType = MessageType.ASSISTANT;
        while (iterator.hasNext()) {
            Message next = iterator.next();
            if (messageType != next.getMessageType()) {
                iterator.remove();
                continue;
            }
            messageType = messageType == MessageType.USER ? MessageType.ASSISTANT : MessageType.USER;
            if (tokenCount >= MAX_TOKEN_WINDOW_SIZE) {
                iterator.remove();
            } else {
                tokenCount += TikTokensUtil.tikTokensCount(next.getText());
            }
        }
        Collections.reverse(all);
        removeIfFirstMessageIsAssistant(all);
        return all;
    }

    //移除第一条消息不是User的内容，避免像deepseek第一条消息不是User消息时，导致无法正常工具调用
    private void removeIfFirstMessageIsAssistant(List<Message> all) {
        if (CollectionUtils.isEmpty(all)) {
            return;
        }
        if (all.get(0).getMessageType() == MessageType.ASSISTANT) {
            all.remove(0);
            removeIfFirstMessageIsAssistant(all);
        }
    }

    private List<ChatMessageDto> queryMessageList(Long conversationId, Long index, int size) {
        List<ChatMessageDto> chatMessageList = queryMessageList0(conversationId, index, size);
        if (chatMessageList.isEmpty()) {
            // TODO 后续某个版本去除该逻辑，当前是为了历史数据转移到数据表中
            Conversation conversation = TenantFunctions.callWithIgnoreCheck(() -> conversationDomainService.getConversation(conversationId));
            if (conversation.getTopicUpdated() == 1) {
                chatMessageList = getCachedMessageList(conversationId.toString());
                if (!chatMessageList.isEmpty()) {
                    chatMessageList.forEach(chatMessage -> {
                        chatMessage.setSenderId(String.valueOf(conversation.getUserId()));
                        chatMessage.setSenderType(chatMessage.getRole() == ChatMessageDto.Role.USER ? ChatMessageDto.SenderType.USER : ChatMessageDto.SenderType.AGENT);
                        chatMessage.setSenderId(chatMessage.getRole() == ChatMessageDto.Role.USER ? String.valueOf(conversation.getUserId()) : String.valueOf(conversation.getAgentId()));
                        chatMessage.setTenantId(conversation.getTenantId());
                        chatMessage.setAgentId(conversation.getAgentId());
                        chatMessage.setUserId(conversation.getUserId());
                        ConversationMessage conversationMessage = new ConversationMessage();
                        conversationMessage.setConversationId(conversationId);
                        conversationMessage.setTenantId(conversation.getTenantId());
                        conversationMessage.setUserId(conversation.getUserId());
                        conversationMessage.setAgentId(conversation.getAgentId());
                        conversationMessage.setMessageId(chatMessage.getId());
                        conversationMessage.setContent(JSON.toJSONString(chatMessage));
                        TenantFunctions.callWithIgnoreCheck(() -> conversationDomainService.addConversationMessage(conversationMessage));
                    });
                    chatMessageList = queryMessageList0(conversationId, index, size);
                    Collections.reverse(chatMessageList);
                }
            }
        } else {
            Collections.reverse(chatMessageList);
        }
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

    //根据会话ID查询CachedMessageList
    public List<ChatMessageDto> getCachedMessageList(String conversationId) {
        if (StringUtils.isBlank(conversationId)) {
            return new ArrayList<>();
        }
        List<ChatMessageDto> cachedMessageList = new ArrayList<>();
        List<Object> msgStrList = redisUtil.range(generateConversationKey(conversationId), 0, Long.MAX_VALUE);
        for (Object msgStr : msgStrList) {
            CallMessage cachedMessage = JSON.parseObject(msgStr.toString(), CallMessage.class);
            cachedMessage.setFinished(true);
            cachedMessageList.add(cachedMessage);
        }
        if (cachedMessageList.isEmpty()) {
            try {
                Conversation conversation = conversationDomainService.getConversation(Long.parseLong(conversationId));
                if (conversation != null && Objects.equals(conversation.getTopicUpdated(), YesOrNoEnum.Y.getKey())) {
                    IPage<AgentLogModelResponse> agentLogModelResponseIPage = logPlatformRpcService.searchAgentLogs(AgentLogSearchParamsRequest.builder().conversationId(conversationId).build(), 1L, 10L);
                    List<AgentLogModelResponse> records = agentLogModelResponseIPage.getRecords();
                    records.forEach(record -> {
                        ChatMessageDto chatMessage = new ChatMessageDto();
                        chatMessage.setRole(ChatMessageDto.Role.ASSISTANT);
                        chatMessage.setText(record.getOutput());
                        chatMessage.setTime(Date.from(record.getRequestEndTime().atZone(ZoneId.systemDefault()).toInstant()));
                        chatMessage.setId(record.getRequestId());
                        chatMessage.setType(MessageTypeEnum.CHAT);
                        if (chatMessage.getText().contains("</think>") && !chatMessage.getText().contains("<think>")) {
                            chatMessage.setText("<think>" + chatMessage.getText());
                        }
                        cachedMessageList.add(chatMessage);

                        chatMessage = new ChatMessageDto();
                        chatMessage.setRole(ChatMessageDto.Role.USER);
                        chatMessage.setText(record.getUserInput());
                        chatMessage.setTime(Date.from(record.getRequestEndTime().atZone(ZoneId.systemDefault()).toInstant()));
                        chatMessage.setId(record.getRequestId());
                        chatMessage.setType(MessageTypeEnum.CHAT);
                        cachedMessageList.add(chatMessage);
                    });
                    //cachedMessageList倒叙
                    Collections.reverse(cachedMessageList);
                }
            } catch (Exception e) {
                //ignore
                log.warn("会话不存在 {}", conversationId);
            }
        }
        return cachedMessageList;
    }

    @Override
    public List<AgentUserDto> queryAgentUserList(Long agentId, Long cursorUserId) {
        List<Long> longs = conversationDomainService.queryAgentUserIdList(agentId, cursorUserId);
        if (longs.size() == 0) {
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
    public void clear(String conversationId) {
        redisUtil.expire(generateConversationKey(conversationId), -1);
    }

    /**
     * 生成conversation key
     */
    public String generateConversationKey(String conversationId) {
        return "conversation:" + conversationId;
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
        ConversationDto conversationDto = getConversation(null, tryReqDto.getConversationId());
        AgentOutputDto errorOutput = new AgentOutputDto();
        errorOutput.setEventType(AgentOutputDto.EventTypeEnum.FINAL_RESULT);
        AgentExecuteResult agentExecuteResult = new AgentExecuteResult();
        agentExecuteResult.setSuccess(false);
        errorOutput.setData(agentExecuteResult);
        errorOutput.setCompleted(true);
        if (conversationDto == null) {
            agentExecuteResult.setError("会话不存在");
            errorOutput.setError("会话不存在");
            return errorOutput(errorOutput, null);
        }
        if (devMode != null) {
            conversationDto.setDevMode(devMode ? 1 : 0);
        }
        AgentConfigDto agentConfigDto;
        if (Objects.equals(conversationDto.getDevMode(), YesOrNoEnum.Y.getKey())) {
            agentConfigDto = agentApplicationService.queryConfigForTestExecute(conversationDto.getAgentId());
            if (agentConfigDto == null) {
                errorOutput.setError("智能体不存在");
                agentExecuteResult.setError("智能体不存在");
                return errorOutput(errorOutput, conversationDto);
            }
            if (!isTempChat) {
                //权限验证
                try {
                    spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId());
                    tryReqDto.setFilterSensitive(false);
                } catch (Exception e) {
                    errorOutput.setError("无智能体会话权限");
                    agentExecuteResult.setError("无智能体会话权限");
                    return errorOutput(errorOutput, conversationDto);
                }
            }
        } else {
            if (!conversationDto.getUserId().equals(RequestContext.get().getUserId())) {
                errorOutput.setError("无智能体会话权限");
                agentExecuteResult.setError("无智能体会话权限");
                return errorOutput(errorOutput, conversationDto);
            }
            agentConfigDto = agentApplicationService.queryPublishedConfigForExecute(conversationDto.getAgentId());
            if (agentConfigDto != null) {
                PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(Published.TargetType.Agent, conversationDto.getAgentId());
                if (!publishedPermissionDto.isExecute()) {
                    errorOutput.setError("无智能体会话权限");
                    agentExecuteResult.setError("无智能体会话权限");
                    return errorOutput(errorOutput, conversationDto);
                }
            }
        }
        if (agentConfigDto == null) {
            errorOutput.setError("智能体不存在或已下架");
            agentExecuteResult.setError("智能体不存在或已下架");
            return errorOutput(errorOutput, conversationDto);
        }

        if (agentConfigDto.getModelComponentConfig().getTargetConfig() == null) {
            errorOutput.setError("智能体模型未配置或已下架");
            agentExecuteResult.setError("智能体模型未配置或已下架");
            return errorOutput(errorOutput, conversationDto);
        }

        // 用户数据权限
        UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());

        //判断agent是否被管控，若被管控需判断有没有权限
        if (agentConfigDto.getAccessControl() != null && agentConfigDto.getAccessControl().equals(YesOrNoEnum.Y.getKey()) && !agentConfigDto.getCreatorId().equals(RequestContext.get().getUserId())) {
            if (userDataPermission.getAgentIds() == null || !userDataPermission.getAgentIds().contains(agentConfigDto.getId())) {
                errorOutput.setError("无智能体权限");
                agentExecuteResult.setError("无智能体权限");
                return errorOutput(errorOutput, conversationDto);
            }
        }

        //token检测
        Object targetConfig = agentConfigDto.getModelComponentConfig().getTargetConfig();
        if (targetConfig instanceof ModelConfigDto && ((ModelConfigDto) targetConfig).getScope() == ModelConfig.ModelScopeEnum.Tenant) {
            BigDecimal tokenCount = metricRpcService.queryMetricCurrent(RequestContext.get().getTenantId(), RequestContext.get().getUserId(), BizType.TOKEN_USAGE.getCode(), PeriodType.DAY);
            if (userDataPermission.getTokenLimit() != null && userDataPermission.getTokenLimit().getLimitPerDay() != null && userDataPermission.getTokenLimit().getLimitPerDay() >= 0
                    && tokenCount.compareTo(BigDecimal.valueOf(userDataPermission.getTokenLimit().getLimitPerDay())) >= 0) {
                errorOutput.setError("你已超过今日的token使用数量限制，你当前每日token的上限为" + userDataPermission.getTokenLimit().getLimitPerDay() + "，自有模型不受限制");
                agentExecuteResult.setError(errorOutput.getError());
                return errorOutput(errorOutput, conversationDto);
            }

            // 通用智能体对话次数管控
            if ("TaskAgent".equals(agentConfigDto.getType())) {
                BigDecimal promptCount = metricRpcService.queryMetricCurrent(RequestContext.get().getTenantId(), RequestContext.get().getUserId(), BizType.GENERAL_AGENT_CHAT.getCode(), PeriodType.DAY);
                if (userDataPermission.getAgentDailyPromptLimit() != null && userDataPermission.getAgentDailyPromptLimit() >= 0
                        && promptCount.compareTo(BigDecimal.valueOf(userDataPermission.getAgentDailyPromptLimit())) >= 0) {
                    errorOutput.setError("你已超过今日的对话次数限制，你当前每日对话的上限次数为" + userDataPermission.getAgentDailyPromptLimit() + "，自有模型不受限制");
                    agentExecuteResult.setError(errorOutput.getError());
                    return errorOutput(errorOutput, conversationDto);
                }
            }
        }

        Conversation conversationUpdate = null;
        // 变量记录
        if (tryReqDto.getVariableParams() != null && !tryReqDto.getVariableParams().isEmpty() && (conversationDto.getVariables() == null || conversationDto.getVariables().isEmpty())) {
            conversationUpdate = Conversation.builder().variables(tryReqDto.getVariableParams()).build();
        }

        if (!Objects.equals(conversationDto.getDevMode(), YesOrNoEnum.Y.getKey()) && (StringUtils.isBlank(tryReqDto.getFrom()) || tryReqDto.getFrom().equals("chat"))) {
            //增加最近使用
            if (agentConfigDto.getExtra() != null && agentConfigDto.getExtra().get("sandboxId") != null) {
                agentApplicationService.addOrUpdateRecentUsed(conversationDto.getUserId(), conversationDto.getAgentId(), conversationDto.getId());
            } else {
                agentApplicationService.addOrUpdateRecentUsed(conversationDto.getUserId(), conversationDto.getAgentId());
            }

            if (conversationDto.getTopicUpdated() == -1) {
                if (conversationUpdate == null) {
                    conversationUpdate = Conversation.builder().topicUpdated(0).build();
                } else {
                    conversationUpdate.setTopicUpdated(0);
                }

                long usedCount = conversationDomainService.agentUserCount(conversationDto.getUserId(), conversationDto.getAgentId());
                if (usedCount == 0) {
                    //使用过的用户数量+1
                    publishApplicationService.incStatisticsCount(Published.TargetType.Agent, conversationDto.getAgentId(), PublishedStatistics.Key.USER_COUNT.getKey(), 1L);
                }
                // 增加智能体统计会话数量
                publishApplicationService.incStatisticsCount(Published.TargetType.Agent, conversationDto.getAgentId(), PublishedStatistics.Key.CONV_COUNT.getKey(), 1L);
            }
        }
        if (conversationUpdate == null) {
            conversationUpdate = Conversation.builder().build();
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

        String requestId = UUID.randomUUID().toString().replace("-", "");
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
        AgentContext agentContext = new AgentContext();
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

        //任务型智能体MCP转换
        //开发编排模式每次都需要刷新mcp配置
        if ("TaskAgent".equals(agentConfigDto.getType())) {
            List<AgentComponentConfigDto> mcpToolComponentList = agentConfigDto.getAgentComponentConfigList().stream().filter(agentComponentConfig -> {
                if (agentComponentConfig.getType() == AgentComponentConfig.Type.Mcp && agentComponentConfig.getTargetConfig() instanceof McpDto mcpDto) {
                    return mcpDto.getInstallType() == InstallTypeEnum.COMPONENT;
                }
                return agentComponentConfig.getType() == AgentComponentConfig.Type.Plugin || agentComponentConfig.getType() == AgentComponentConfig.Type.Knowledge
                        || agentComponentConfig.getType() == AgentComponentConfig.Type.Table || agentComponentConfig.getType() == AgentComponentConfig.Type.Workflow;
            }).toList();
            if (!mcpToolComponentList.isEmpty()) {
                boolean isDev = false;
                if (conversationDto.getDevMode() != null && conversationDto.getDevMode().intValue() == YesOrNoEnum.Y.getKey()) {
                    agentApplicationService.buildProxyMcp(agentConfigDto, true);
                    isDev = true;
                }
                Map<String, Object> extra = agentConfigDto.getExtra();
                if (extra != null) {
                    try {
                        Long proxyMcpId = Long.parseLong(agentConfigDto.getExtra().get(isDev ? "devProxyMcpId" : "prodProxyMcpId").toString());
                        String exportMcpServerConfig = mcpRpcService.getExportMcpServerConfig(userDto.getId(), proxyMcpId);
                        agentConfigDto.setProxyMcpServerConfig(exportMcpServerConfig);
                    } catch (NumberFormatException e) {
                        //  ignore
                    }
                }
            }
        }

        boolean isMultiAgent = null != agentConfigDto.getAgentComponentConfigList().stream().filter(agentComponentConfigDto -> agentComponentConfigDto.getType() == AgentComponentConfig.Type.Agent).findFirst().orElse(null);
        if (isMultiAgent) {
            agentContext.setAgentContextFunction(id -> {
                AgentConfigDto agentConfig = agentApplicationService.queryPublishedConfigForExecute(id);
                if (agentConfig == null) {
                    return null;
                }
                AgentContext context = new AgentContext();
                context.setAgentConfig(agentConfig);
                return context;
            });
        }

        //附件追加AK
        if (tryReqDto.getAttachments() != null) {
            tryReqDto.getAttachments().forEach(attachmentDto -> attachmentDto.setFileUrl(fileAkUtil.getFileUrlWithAk(attachmentDto.getFileUrl())));
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
                log.info("创建沙箱会话成功：serverId {}, sessionId {}", cv.getSandboxServerId(), cv.getSandboxSessionId());
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
            }

            List<Long> skillIds = agentContext.getAgentConfig().getAgentComponentConfigList().stream().filter(agentComponentConfigDto -> agentComponentConfigDto.getType() == AgentComponentConfig.Type.Skill).map(AgentComponentConfigDto::getTargetId).collect(Collectors.toList());
            List<AgentComponentConfigDto> updatedSkills = agentContext.getAgentConfig().getAgentComponentConfigList().stream().filter(agentComponentConfigDto ->
                    // 技能发布时间大于会话更新时间
                    agentComponentConfigDto.getType() == AgentComponentConfig.Type.Skill && ((SkillConfigDto) agentComponentConfigDto.getTargetConfig()).getPublishDate().getTime() > conversationDto.getModified().getTime()
            ).toList();
            if (agentContext.getConversation().getSandboxSessionId() == null || !updatedSkills.isEmpty() || agentStopped) {
                log.info("开始创建workspace推送Skill， agentId {}, conversationId {}, skillIds {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), skillIds);
                try {
                    List<SubagentDto> subagents = null;
                    AgentComponentConfigDto componentConfigDto = agentContext.getAgentConfig().getAgentComponentConfigList().stream().filter(agentComponentConfigDto -> agentComponentConfigDto.getType() == AgentComponentConfig.Type.SubAgent).findFirst().orElse(null);
                    if (componentConfigDto != null && componentConfigDto.getBindConfig() instanceof SubAgentBindConfigDto subAgentBindConfigDto) {
                        if (CollectionUtils.isNotEmpty(subAgentBindConfigDto.getSubAgents())) {
                            subagents = subAgentBindConfigDto.getSubAgents().stream().map(subAgentDto -> {
                                SubagentDto subagentDto = new SubagentDto();
                                subagentDto.setName(subAgentDto.getName());
                                subagentDto.setContent(subAgentDto.getPrompt());
                                return subagentDto;
                            }).toList();
                        }
                    }
                    CreateWorkspaceDto createWorkspaceDto = CreateWorkspaceDto.builder()
                            .userId(agentContext.getUserId())
                            .cId(conversationDto.getId())
                            .skillIds(skillIds)
                            .subagents(subagents)
                            .build();
                    agentWorkspaceApplicationService.createWorkspace(createWorkspaceDto);
                } catch (Exception e) {
                    log.error("创建workspace推送Skill失败， agentId {}, conversationId {}, skillIds {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), skillIds, e);
                    errorOutput.setError(e.getMessage());
                    agentExecuteResult.setError(e.getMessage());
                    return errorOutput(errorOutput, conversationDto);
                }
                log.info("创建workspace推送Skill完成， agentId {}, conversationId {}, skillIds {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), skillIds);
            }
            if (CollectionUtils.isNotEmpty(userAtSkillConfigs)) {
                AddSkillsToWorkspaceDto addSkillsToWorkspaceDto = AddSkillsToWorkspaceDto.builder()
                        .userId(RequestContext.get().getUserId())
                        .cId(conversationDto.getId())
                        .skillConfigs(userAtSkillConfigs).build();
                agentWorkspaceApplicationService.addSkillsToWorkspace(addSkillsToWorkspaceDto);
                skillApplicationService.saveRecentlyUsedSkills(userAtSkillConfigs.stream().map(SkillConfigDto::getId).collect(Collectors.toList()));
            }
            //创建沙箱访问平台的accessKey，针对UserAccessKeyDto.AKTargetType.Sandbox已控制可访问范围
            UserAccessKeyDto userAccessKey = userAccessKeyApiService.queryAccessKey(agentContext.getUserId(), UserAccessKeyDto.AKTargetType.Sandbox, agentConfigDto.getId().toString());
            if (userAccessKey == null) {
                userAccessKey = userAccessKeyApiService.newAccessKey(agentContext.getUserId(), UserAccessKeyDto.AKTargetType.Sandbox, agentConfigDto.getId().toString());
            }
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

        Flux<AgentOutputDto> flux = agentExecutor.execute(agentContext);
        AtomicBoolean isComplete = agentContext.getFinished();
        AtomicBoolean sseCanceled = new AtomicBoolean(false);
        AtomicBoolean userStopped = new AtomicBoolean(false);
        AtomicLong lastActiveTime = new AtomicLong(System.currentTimeMillis());

        //智能体执行输出
        Disposable disposable = flux.onErrorResume(Mono::error)
                .subscribe(
                        msg -> {
                            lastActiveTime.set(System.currentTimeMillis());
                            Sinks.EmitResult emitResult = sink.tryEmitNext(msg);
                            if (emitResult.isFailure()) {
                                log.error("智能体输出失败，cid {}, error {}", agentContext.getConversationId(), emitResult);
                            }
                        },
                        e -> {
                            isComplete.set(true);
                            updateConversationStatus(agentContext, conversationDto, Conversation.ConversationTaskStatus.FAILED);
                            if (agentContext.isInterrupted()) {
                                log.info("智能体执行业务中断");
                                sink.tryEmitComplete();
                                return;
                            }
                            log.error("智能体执行异常", e);
                            sink.tryEmitComplete();
                            pushLogQueue(agentContext, tryReqDto.getFrom(), e.getMessage());
                        },
                        () -> {
                            log.info("智能体执行完成, cid {}", agentContext.getConversationId());
                            // 只统计平台级别模型
                            if ("TaskAgent".equals(agentConfigDto.getType()) && targetConfig instanceof ModelConfigDto && ((ModelConfigDto) targetConfig).getScope() == ModelConfig.ModelScopeEnum.Tenant) {
                                metricRpcService.incrementMetricAllPeriods(agentContext.getTenantConfig().getTenantId(), agentContext.getUserId(), BizType.GENERAL_AGENT_CHAT.getCode(), BigDecimal.ONE);
                            }
                            isComplete.set(true);
                            updateConversationStatus(agentContext, conversationDto, Conversation.ConversationTaskStatus.COMPLETE);
                            sink.tryEmitComplete();
                            pushLogQueue(agentContext, tryReqDto.getFrom(), null);
                            try {
                                RequestContext.set(requestContext);
                                Conversation conversation = conversationDomainService.getConversation(conversationDto.getId());
                                if (conversation.getTopicUpdated() == -1 && conversationDto.getDevMode() == 0 && conversationDto.getType() == Conversation.ConversationType.Chat) {
                                    log.info("更新会话主题, cid {}", agentContext.getConversationId());
                                    updateTopic(agentContext, conversationDto);
                                }
                                if (sseCanceled.get() && conversationDto.getDevMode() == 0 && conversationDto.getType() == Conversation.ConversationType.Chat) {
                                    log.info("会话链接断开，任务执行完成发送通知, cid {}, topic {}", agentContext.getConversationId(), conversationDto.getTopic());
                                    sendNotification(agentContext, conversation);
                                }
                            } finally {
                                RequestContext.remove();
                            }
                        });

        //心跳
        Disposable disposableHeartbeat = Flux.interval(Duration.ofSeconds(10))
                .map(tick -> {
                    log.info("心跳发送，cid {}, tick {}", agentContext.getConversationId(), tick);
                    if (tick >= 360) {// 一个小时如果都还没有结束，打印详细日志，排查问题
                        log.warn("智能体执行时间过长，cid {}", agentContext.getConversationId());
                        log.warn("执行日志，cid {}, log {}", agentContext.getConversationId(), JSON.toJSONString(AgentExecutor.buildAgentExecuteResult(agentContext)));
                        if (System.currentTimeMillis() - lastActiveTime.get() > 3600 * 2 * 1000L) {
                            //2个小时没有收到任何智能体消息，主动终止
                            log.warn("智能体执行等待过长，主动终止，cid {}", agentContext.getConversationId());
                            isComplete.set(true);
                            disposable.dispose();
                        }
                    }
                    AgentOutputDto agentOutputDto = new AgentOutputDto();
                    agentOutputDto.setRequestId(agentContext.getRequestId());
                    agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.HEART_BEAT);
                    return agentOutputDto;
                }).takeWhile(outputDto -> !isComplete.get())
                .doOnComplete(() -> log.info("心跳结束，cid {}", agentContext.getConversationId()))
                .doOnNext(sink::tryEmitNext)
                .subscribe();

        // 检测是否用户手动停止
        checkIfRequestStop(agentContext, sink, disposable, isComplete, userStopped);
        return sink.asFlux()
                .doOnComplete(() -> {
                    log.info("智能体执行SSE通道完成, cid {}", agentContext.getConversationId());
                    redisUtil.expire("chat.stop." + agentContext.getConversationId(), 0);
                    if (conversationDto.getTaskStatus() == null || conversationDto.getTaskStatus() == Conversation.ConversationTaskStatus.EXECUTING) {
                        updateConversationStatus(agentContext, conversationDto, Conversation.ConversationTaskStatus.COMPLETE);
                    }
                })
                .doOnCancel(() -> {
                    log.info("智能体执行SSE通道取消, cid {}", agentContext.getConversationId());
                    disposableHeartbeat.dispose();
                    sseCanceled.set(true);
                });
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
                .text("```\n" + errorOutput.getError() + "\n```")
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
        TenantFunctions.callWithIgnoreCheck(() -> conversationDomainService.updateConversation(conversationDto.getUserId(), conversationDto.getId(), conversationUpdate));

        // 发送事件消息
        if (conversationTaskStatus == Conversation.ConversationTaskStatus.COMPLETE || conversationTaskStatus == Conversation.ConversationTaskStatus.FAILED) {
            EventDto<Object> event = EventDto.builder()
                    .type(EventDto.EVENT_TYPE_CHAT_FINISHED)
                    .event(Map.of("conversationId", conversationDto.getId().toString(), "requestId", agentContext.getRequestId(), "status", conversationTaskStatus))
                    .build();
            notifyMessageApplicationService.publishEvent(agentContext.getUserId(), event);
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

    private void sendNotification(AgentContext agentContext, Conversation conversation) {
        if (StringUtils.isNotBlank(conversation.getTopic()) && conversation.getTopicUpdated() == 1) {
            notifyMessageApplicationService.sendNotifyMessage(SendNotifyMessageDto.builder()
                    .scope(NotifyMessage.MessageScope.System)
                    .content("会话 [" + conversation.getTopic() + "](/home/chat/" + conversation.getId() + "/" + conversation.getAgentId() + ") 输出完成，请到会话记录中查看")
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
            Object o = redisUtil.get("chat.stop." + agentContext.getConversationId());
            if (o != null) {
                Long stopTime = null;
                try {
                    stopTime = Long.parseLong(o.toString());
                } catch (NumberFormatException e) {
                    //
                }
                userStopped.set(true);
                //5秒内没有停止就强行停止
                if (stopTime != null && System.currentTimeMillis() - stopTime > 1000 * 5) {
                    redisUtil.expire("chat.stop." + agentContext.getConversationId(), 0);
                    isComplete.set(true);
                    AgentOutputDto finalOutput = AgentExecutor.buildFinalResultOutput(agentContext);
                    boolean failure = sink.tryEmitNext(finalOutput).isFailure();
                    if (failure) {
                        log.warn("final_result发送失败");
                    }
                    sink.tryEmitComplete();
                    disposable.dispose();
                } else {
                    agentContext.setInterrupted(true);
                }
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
            iLogRpcService.bulkIndex(List.of(logDocument));
        }
    }

    @Override
    public Long nextConversationId(Long agentId, String sandboxServerId) {
        return conversationDomainService.nextConversationId(RequestContext.get().getUserId(), agentId, sandboxServerId);
    }
}
