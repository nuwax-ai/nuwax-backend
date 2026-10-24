package com.xspaceagi.agent.core.application.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.google.common.base.Joiner;
import com.xspaceagi.agent.core.adapter.application.*;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentComponentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.HookConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.bind.*;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.AgentNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowNodeDto;
import com.xspaceagi.agent.core.adapter.repository.entity.*;
import com.xspaceagi.agent.core.application.support.PlatformEnvHookSupport;
import com.xspaceagi.agent.core.domain.service.AgentDomainService;
import com.xspaceagi.agent.core.domain.service.ConversationDomainService;
import com.xspaceagi.agent.core.infra.component.agent.*;
import com.xspaceagi.agent.core.infra.component.agent.dto.AgentExecuteResult;
import com.xspaceagi.agent.core.infra.component.workflow.handler.QANodeHandler;
import com.xspaceagi.agent.core.infra.rpc.*;
import com.xspaceagi.agent.core.infra.sub.ChatMessagePubSubService;
import com.xspaceagi.agent.core.spec.enums.GlobalVariableEnum;
import com.xspaceagi.agent.core.spec.enums.MessageTypeEnum;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.log.sdk.service.ILogRpcService;
import com.xspaceagi.log.sdk.vo.LogDocument;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.enums.InstallTypeEnum;
import com.xspaceagi.pricing.sdk.dto.PriceEstimate;
import com.xspaceagi.pricing.sdk.dto.PricingConfigDTO;
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
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.BizType;
import com.xspaceagi.system.sdk.service.dto.PeriodType;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.cache.SimpleJvmHashCache;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.spec.utils.RedisUtil;
import com.xspaceagi.system.spec.utils.TimeWheel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.cache.SimpleJvmHashCache.DEFAULT_EXPIRE_AFTER_SECONDS;

@Slf4j
@Service
public class ChatApplicationServiceImpl implements ChatApplicationService {

    /**
     * 会话变量里存放 IM 上下文的键：IM 触发那次运行把 imVars 存下来，
     * 供「任务到点执行」那次运行（平台不注入任何 IM_* 变量）复原群/私聊身份。
     */
    private static final String CONVERSATION_IM_VARS_KEY = "IM_VARS";

    /**
     * IM 上下文里存放付费方覆盖的键。由上游 IM 渠道（{@code InternalApiController} 发起会话时
     * 把 {@code billUserId} 放进 variableParams）写入，本类在任务到点执行那次把它复原出来。
     */
    private static final String IM_VARS_BILL_USER_ID_KEY = "BILL_USER_ID";

    @Resource
    private ConversationDomainService conversationDomainService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private RedisUtil redisUtil;

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
    private AgentExecutor agentExecutor;

    @Resource
    private AgentGroupLoop agentGroupLoop;

    @Resource
    private AgentFlowLoop agentFlowLoop;

    @Resource
    private MasterAgentLoop masterAgentLoop;

    @Resource
    private HybridAgentLoop hybridAgentLoop;

    @Resource
    private GitRpcClient gitRpcClient;

    @Resource
    private SandboxAgentClient sandboxAgentClient;

    @Resource
    private SkillApplicationService skillApplicationService;

    @Resource
    private ResourcePricingRpcService resourcePricingRpcService;

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private ChatMessagePubSubService chatMessagePubSubService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private ConversationPermissionChecker conversationPermissionChecker;

    @Resource
    private AgentDomainService agentDomainService;

    @Resource
    private GiftCreditRpcService giftCreditRpcService;

    @Resource
    private UserChatBoxConfigService userChatBoxConfigService;

    @Value("${chatbot.engine:hybridAgent}")
    private String chatBotEngine;

    @Override
    public Flux<AgentOutputDto> chat(TryReqDto tryReqDto, Map<String, String> headersFromRequest, boolean isTempChat) {
        return chat(tryReqDto, headersFromRequest, isTempChat, null);
    }

    public Flux<AgentOutputDto> chat(TryReqDto tryReqDto, Map<String, String> headersFromRequest, boolean isTempChat, Boolean devMode) {
        log.info("ConversationApplicationServiceImpl.chat {}", tryReqDto);
        ConversationDto conversationDto = conversationApplicationService.getConversationByCid(tryReqDto.getConversationId());
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
        // imVars（IM 上下文）只该由 IM 触发的那次运行携带，其余来源按三种情况分别处理：
        // ①IM 触发：原样使用上游带进来的 imVars；
        // ②任务到点执行（from=task）：本次运行平台不注入任何 IM_* 变量，从会话变量里复原建任务时存下的那份，
        //   否则结果推不回原来的群/私聊（沙箱 AK 的 ext 也拿不到群身份）；
        // ③其他来源（网页对话等）：清空，不接受外部传入的 IM 身份。
        String chatFrom = RequestContext.get().getFrom();
        if ("nuwax_im".equals(chatFrom)) {
            // 保留上游传入的 imVars
        } else if ("task_center".equals(chatFrom)) {
            tryReqDto.setImVars(readConversationImVars(conversationDto));
            // 复原出的 IM 上下文是当初那次 IM 触发存下的快照，其中的付费方覆盖（BILL_USER_ID）要一并带回来，
            // 否则定时任务执行那次的权限校验（下面的 permCheckUserId）与计费会回落到任务归属人，与 IM 触发那次不一致。
            Long imBillUserId = readBillUserId(tryReqDto.getImVars());
            if (imBillUserId != null) {
                RequestContext.get().setBillUserId(imBillUserId);
            }
        } else {
            tryReqDto.setImVars(null);//非IM过来的消息去除参数
        }
        //getBillUserId为IM群聊拉智能体进群的人，谁拉进去的校验谁的权限
        Long permCheckUserId = RequestContext.get().getBillUserId() != null ? RequestContext.get().getBillUserId() : RequestContext.get().getUserId();
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
                    spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId(), permCheckUserId);
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
            agentConfigDto = tryReqDto.getAgentConfig() != null ? tryReqDto.getAgentConfig() : agentApplicationService.queryPublishedConfigForExecute(conversationDto.getAgentId());
            if (agentConfigDto != null) {
                PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(permCheckUserId, Published.TargetType.Agent, conversationDto.getAgentId());
                if (!publishedPermissionDto.isExecute()) {
                    String errorMsg = I18nUtil.systemMessage("Backend.Chat.Error.NoAgentChatPermission");
                    errorOutput.setError(errorMsg);
                    agentExecuteResult.setError(errorMsg);
                    return errorOutput(errorOutput, conversationDto);
                }
            }
            setFileAkForCustomAgent(agentConfigDto);
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
        UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(permCheckUserId);

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
                    String spaceUserRole = spacePermissionService.getSpaceUserRole(modelConfigDto1.getSpaceId(), RequestContext.get().getUserId());
                    hasPermission = modelConfigDto1.getScope() == ModelConfig.ModelScopeEnum.Space && spaceUserRole != null;
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
            List<PricingConfigDTO> pricingConfigs = priceEstimate.getPricingConfigs() == null ? new ArrayList<>() : priceEstimate.getPricingConfigs();
            Set<String> toolIds = pricingConfigs.stream().filter(pricingConfigDTO -> (pricingConfigDTO.getTargetType() == TargetTypeEnum.PLUGIN || pricingConfigDTO.getTargetType() == TargetTypeEnum.WORKFLOW)
                            && pricingConfigDTO.getStatus() != null && pricingConfigDTO.getStatus().equals(YesOrNoEnum.Y.getKey()))
                    .map(pricingConfigDTO -> pricingConfigDTO.getTargetType().name() + "-" + pricingConfigDTO.getTargetId()).collect(Collectors.toSet());
            errorOutput.setError(priceEstimate.getMessage());
            if (priceEstimate.getTargetType() == TargetTypeEnum.TOOL) {
                agentConfigDto.getAgentComponentConfigList().removeIf(componentConfigDto -> {
                    if (componentConfigDto.getType() == AgentComponentConfig.Type.Plugin && toolIds.contains(TargetTypeEnum.PLUGIN.name() + "-" + componentConfigDto.getTargetId())) {
                        return true;
                    }
                    return componentConfigDto.getType() == AgentComponentConfig.Type.Workflow && toolIds.contains(TargetTypeEnum.WORKFLOW.name() + "-" + componentConfigDto.getTargetId());
                });
            } else {
                if (priceEstimate.getTargetType() == TargetTypeEnum.MODEL) {
                    errorOutput.setErrorReason("CREDIT_INSUFFICIENT");
                }
                agentExecuteResult.setError(priceEstimate.getMessage());
                return errorOutput(errorOutput, conversationDto);
            }
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
        if (conversationDto.getVariables() == null) {
            conversationDto.setVariables(new HashMap<>());
        }
        if (tryReqDto.getImVars() != null) {
            conversationDto.getVariables().put(CONVERSATION_IM_VARS_KEY, tryReqDto.getImVars());
            // 会话变量最终由下面这次 updateConversation 落库（只写 conversationUpdate 上的字段），
            // 仅往内存 DTO 上 put 不会持久化 —— 那样任务到点执行时就复原不出 IM 上下文。
            conversationUpdate.setVariables(new HashMap<>(conversationDto.getVariables()));
            conversationUpdate.setType(Conversation.ConversationType.IM);
            //IM兜底恢复命令
            if (StringUtils.isNotBlank(conversationDto.getSandboxSessionId()) && tryReqDto.getMessage() != null && "/reset-session".equals(tryReqDto.getMessage().trim())) {
                conversationDto.setSandboxSessionId("");
                conversationUpdate.setSandboxSessionId("");
                tryReqDto.setMessage("之前的会话出了无法恢复的问题，现在请仔细读取工作区的内容以及相关的历史会话，尽量恢复你与用户的记忆");
            }
        }

        if (!Objects.equals(conversationDto.getDevMode(), YesOrNoEnum.Y.getKey()) && (StringUtils.isBlank(tryReqDto.getFrom()) || tryReqDto.getFrom().equals("chat") || tryReqDto.getFrom().equals("MasterAgent"))) {
            //增加最近使用
            if (agentConfigDto.getExtra() != null && agentConfigDto.getExtra().get("sandboxId") != null) {
                agentApplicationService.addOrUpdateRecentUsed(conversationDto.getUserId(), conversationDto.getAgentId(), conversationDto.getId(), agentConfigDto.getType());
            } else {
                agentApplicationService.addOrUpdateRecentUsed(conversationDto.getUserId(), conversationDto.getAgentId(), agentConfigDto.getType());
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

        EventDto<Object> event = EventDto.builder()
                .type(EventDto.EVENT_TYPE_CHAT_START)
                .event(Map.of("conversationId", conversationDto.getId().toString(), "requestId", requestId, "status", Conversation.ConversationTaskStatus.EXECUTING))
                .build();
        notifyMessageApplicationService.publishEvent(RequestContext.get().getUserId(), event);

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
        agentContext.setSkillIds(tryReqDto.getSkillIds());
        agentContext.setSandboxId(tryReqDto.getSandboxId());
        agentContext.setUserDataPermission(userDataPermission);
        agentContext.setDefaultModelChanged(!isDefaultModel);
        agentContext.setAgentMode(tryReqDto.getAgentMode());
        agentContext.setImVars(tryReqDto.getImVars());
        agentContext.setFrom(tryReqDto.getFrom());
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

        StringBuilder userPromptBuilder = new StringBuilder();
        if (null != agentConfigDto.getUserPrompt()) {
            userPromptBuilder.append(agentConfigDto.getUserPrompt());
        }
        if (!userSelectedSkills.isEmpty()) {
            userPromptBuilder.append("\nPlease use the following skills to complete user tasks. The following skills may be newly added. If there are no relevant definitions in the context, please load them from the working directory.\n");
            userSelectedSkills.forEach(skillConfigDto -> {
                userPromptBuilder.append("- ").append(skillConfigDto.getEnName() == null ? skillConfigDto.getName() : skillConfigDto.getEnName()).append("\n");
            });

        }

        if (CollectionUtils.isNotEmpty(tryReqDto.getSelectedDocs())) {
            userPromptBuilder.append("\n已选择的资料库文档范围如下，需要管理操作或查询文档内容时请加载资料库文档管理相关技能\n");
            tryReqDto.getSelectedDocs().forEach(doc -> userPromptBuilder.append("- ").append(doc.getTitle()).append("(slugId: ").append(doc.getSlugId()).append(")\n"));
        }
        if (conversationDto.getVariables() != null && conversationDto.getVariables().containsKey("SLUG_ID")) {
            userPromptBuilder.append("\n当前上下文注意力到资料库文档管理 slugId: ").append(conversationDto.getVariables().get("SLUG_ID")).append("\n");
        }

        agentConfigDto.setUserPrompt(userPromptBuilder.toString());

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

        final RequestContext<Object> requestContext = RequestContext.get();
        // Hybrid 智能体（extra.isHybridAgent=1）：复杂任务走沙箱，沙箱会话创建后需持久化
        boolean isHybridAgent = (agentConfigDto.getExtra() != null && agentConfigDto.getExtra().containsKey("isHybridAgent"))
                || (agentConfigDto.getSystemPrompt() != null && agentConfigDto.getSystemPrompt().contains("<UseHybridAgent/>"));

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
            List<Long> updatedSkillIds = new ArrayList<>();
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
            checkIfSkillsUpdated(agentConfigDto, conversationDto, skillIds, subagents, hooksConfig, updateSkills, updatedSkillIds);
            //AgentGroup
            List<AgentComponentConfigDto> agentComponentConfigs = agentContext.getAgentConfig().getAgentComponentConfigList().stream().filter(agentComponentConfigDto -> agentComponentConfigDto.getType() == AgentComponentConfig.Type.Agent).toList();
            agentComponentConfigs.forEach(agentComponentConfigDto -> {
                if (agentComponentConfigDto.getTargetConfig() instanceof AgentConfigDto groupAgentConfigDto) {
                    groupAgentConfigDto.setIcon(DefaultIconUrlUtil.setDefaultIconUrl(groupAgentConfigDto.getIcon(), groupAgentConfigDto.getName(), "agent"));
                    setFileAkForCustomAgent(groupAgentConfigDto);
                    checkIfSkillsUpdated(groupAgentConfigDto, conversationDto, skillIds, subagents, hooksConfig, updateSkills, updatedSkillIds);
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
                                checkIfSkillsUpdated(agentNodeConfigDto.getAgentConfigDto(), conversationDto, skillIds, subagents, hooksConfig, updateSkills, updatedSkillIds);
                            }
                        }
                    }
                }
            }

            PlatformEnvHookSupport.apply(hookArgs, hooksConfig, hookScripts);

            if (agentContext.getConversation().getSandboxSessionId() == null || updateSkills[0] || agentStopped) {
                log.info("Start creating workspace and pushing skills, agentId {}, conversationId {}, skillIds {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), skillIds);
                try {
                    if (StringUtils.isNotBlank(tenantConfigDto.getUserComputerDefaultSkillIds())) {
                        Arrays.asList(tenantConfigDto.getUserComputerDefaultSkillIds().split(",")).forEach(id -> {
                            try {
                                skillIds.add(Long.parseLong(id));
                            } catch (NumberFormatException e) {
                                //
                            }
                        });
                    }
                    CreateWorkspaceDto createWorkspaceDto = CreateWorkspaceDto.builder()
                            .userId(agentContext.getUserId())
                            .agentId(agentConfigDto.getId())
                            .cId(conversationDto.getId())
                            .skillIds(skillIds)
                            .updateSkillIds(updatedSkillIds)
                            .subagents(subagents)
                            .hooksConfig(hooksConfig)
                            .hookScripts(hookScripts.isEmpty() ? null : hookScripts)
                            .build();
                    if (isHybridAgent) {
                        agentContext.setSandboxSkillPushConsumer((ctx) -> {
                            if (RequestContext.get() == null) {
                                try {
                                    RequestContext.set(requestContext);
                                    agentWorkspaceApplicationService.createWorkspace(createWorkspaceDto);
                                } finally {
                                    RequestContext.remove();
                                }
                            } else {
                                agentWorkspaceApplicationService.createWorkspace(createWorkspaceDto);
                            }
                        });
                    } else {
                        SkillPushResultDto skillPushResultDto = agentWorkspaceApplicationService.createWorkspace(createWorkspaceDto);
                        if (skillPushResultDto.hasFailures()) {
                            userPromptBuilder.append("\n").append("<extra-available-skills>");
                            skillPushResultDto.getFailedSkillList().forEach(failedSkill -> {
                                userPromptBuilder.append("\n<skill>")
                                        .append("<name>").append(failedSkill.getName()).append("</name>")
                                        .append("<description>").append(failedSkill.getDescription()).append("</description>")
                                        .append("<download-url>").append(failedSkill.getUrl()).append("</download-url>")
                                        .append("</skill>");
                            });
                            userPromptBuilder.append("<extra-available-skills>");
                        }
                        agentConfigDto.setUserPrompt(userPromptBuilder.toString());
                        conversationUpdate.setSandboxSessionId("");
                    }
                } catch (Exception e) {
                    log.error("Failed to create workspace and push skills, agentId {}, conversationId {}, skillIds {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), skillIds, e);
                    errorOutput.setError(e.getMessage());
                    agentExecuteResult.setError(e.getMessage());
                    return errorOutput(errorOutput, conversationDto);
                }
                log.info("Create workspace and push skills completed, agentId {}, conversationId {}, skillIds {}", agentContext.getAgentConfig().getId(), agentContext.getConversationId(), skillIds);
            }
            if (CollectionUtils.isNotEmpty(userAtSkillConfigs)) {
                try {
                    if (agentContext.getSandboxSkillPushConsumer() != null) {
                        agentContext.getSandboxSkillPushConsumer().accept(agentContext);
                    }
                    AddSkillsToWorkspaceDto addSkillsToWorkspaceDto = AddSkillsToWorkspaceDto.builder()
                            .userId(RequestContext.get().getUserId())
                            .agentId(agentConfigDto.getId())
                            .cId(conversationDto.getId())
                            .skillConfigs(userAtSkillConfigs).build();
                    agentWorkspaceApplicationService.addSkillsToWorkspace(addSkillsToWorkspaceDto);
                    skillApplicationService.saveRecentlyUsedSkills(userAtSkillConfigs.stream().map(SkillConfigDto::getId).collect(Collectors.toList()));
                } catch (Exception e) {
                    errorOutput.setError(e.getMessage());
                    agentExecuteResult.setError(e.getMessage());
                    return errorOutput(errorOutput, conversationDto);
                }
            }
            //创建沙箱访问平台的accessKey，针对UserAccessKeyDto.AKTargetType.Sandbox已控制可访问范围
            String agentConversation = agentConfigDto.getId() + "-" + tryReqDto.getConversationId();
            UserAccessKeyDto userAccessKey = userAccessKeyApiService.queryAccessKey(agentContext.getUserId(), UserAccessKeyDto.AKTargetType.Sandbox, agentConversation);
            if (userAccessKey == null) {
                userAccessKey = userAccessKeyApiService.newAccessKey(agentContext.getUserId(), UserAccessKeyDto.AKTargetType.Sandbox, agentConversation);
            }
            UserAccessKeyDto.UserAccessKeyConfig userAccessKeyConfig = UserAccessKeyDto.UserAccessKeyConfig.builder()
                    .isDevMode(devMode != null && devMode ? 1 : 0)
                    .enabled(true)
                    .conversationId(tryReqDto.getConversationId().toString())
                    .requestId(RequestContext.get().getRequestId())
                    .ext(tryReqDto.getImVars())
                    .traceContext(traceContext)
                    .build();
            userAccessKeyApiService.updateUserAccessKeyConfig(userAccessKey.getId(), userAccessKeyConfig);
            agentContext.getVariableParams().put("SANDBOX_ACCESS_KEY", userAccessKey.getAccessKey());
        }

        // 新一轮输出开始：清掉上一轮可能残留的回放缓冲（异常退出时列表会留到 TTL 到期）
        chatMessagePubSubService.open(agentContext.getConversationId());
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

        Flux<AgentOutputDto> flux;
        boolean isDeviceAgent = agentConfigDto.getExtra() != null && agentConfigDto.getExtra().containsKey("isDeviceAgent");
        if (isHybridAgent) {
            // 混合编排：简单任务直接工具调用，复杂任务走沙箱（输出统一收敛回本 loop）
            flux = hybridAgentLoop.execute(agentContext);
        } else if (isDeviceAgent && !"nuwax_im".equals(RequestContext.get().getFrom())) {
            flux = masterAgentLoop.execute(agentContext);
        } else if (AgentConfigDto.AgentSubType.Flow.name().equals(agentConfigDto.getSubType())) {
            // AgentFlow
            flux = agentFlowLoop.execute(agentContext);
        } else if (isMultiAgent) {
            //AgentGroup
            flux = agentGroupLoop.execute(agentContext);
        } else if ("ChatBot".equals(agentConfigDto.getType()) && "hybridAgent".equals(chatBotEngine)) {
            flux = hybridAgentLoop.execute(agentContext);
        } else {
            //Agent(ChatBot、Custom or General)
            flux = agentExecutor.execute(agentContext);
        }
        AtomicBoolean isComplete = agentContext.getFinished();
        AtomicBoolean sseCanceled = new AtomicBoolean(false);
        AtomicBoolean userStopped = new AtomicBoolean(false);
        AtomicBoolean gitAutoCommitted = new AtomicBoolean(false);
        AtomicLong lastActiveTime = new AtomicLong(System.currentTimeMillis());
        final boolean autoGitCommitEnabled = isVersionControlEnabled(RequestContext.get().getUserId(), agentConfigDto);

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
                            if (msg.isCompleted() || msg.getEventType() == AgentOutputDto.EventTypeEnum.FINAL_RESULT) {
                                //提前更新会话状态，避免前端查到的状态异常
                                Conversation update = Conversation.builder().build();
                                update.setTaskStatus(Conversation.ConversationTaskStatus.COMPLETE);
                                TenantFunctions.callWithIgnoreCheck(() -> conversationDomainService.updateConversation(conversationDto.getUserId(), conversationDto.getId(), update));
                            }
                            lastActiveTime.set(System.currentTimeMillis());
                            Sinks.EmitResult emitResult = sink.tryEmitNext(msg);
                            if (emitResult.isFailure()) {
                                log.debug("Agent output failed, cid {}, error {}", agentContext.getConversationId(), emitResult);
                            }
                            chatMessagePubSubService.publish(agentContext.getConversationId(), JSON.toJSONString(msg));
                        },
                        e -> {
                            isComplete.set(true);
                            updateConversationStatus(agentContext, conversationDto, Conversation.ConversationTaskStatus.FAILED);
                            // 终态必须先 publish，再由 finishPublish 统一 flush + 删除；
                            // 顺序反过来（先 finish 后 publish）会把刚删掉的回放 key 重新写回来。
                            if (agentContext.isInterrupted()) {
                                log.info("Agent execution interrupted");
                                AgentOutputDto finalOutput = AgentExecutor.buildFinalResultOutput(agentContext);
                                if (finalOutput.getData() instanceof AgentExecuteResult interruptedResult) {
                                    interruptedResult.setSuccess(true);
                                    interruptedResult.setError("Execution interrupted");
                                }
                                finalOutput.setError("Execution interrupted");
                                sink.tryEmitNext(finalOutput);
                                chatMessagePubSubService.publish(agentContext.getConversationId(), JSON.toJSONString(finalOutput));
                                chatMessagePubSubService.finishPublish(agentContext.getConversationId());
                                sink.tryEmitComplete();
                                return;
                            }
                            String errorMessage = StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
                            // 失败必须让浏览器可见，否则前端只收到 FINAL_RESULT(completed=true) 会显示"运行完毕"
                            AgentOutputDto errorEvent = new AgentOutputDto();
                            errorEvent.setRequestId(agentContext.getRequestId());
                            errorEvent.setEventType(AgentOutputDto.EventTypeEnum.ERROR);
                            errorEvent.setError(errorMessage);
                            errorEvent.setCompleted(true);
                            AgentExecuteResult errorResult = new AgentExecuteResult();
                            errorResult.setSuccess(false);
                            errorResult.setError(errorMessage);
                            errorEvent.setData(errorResult);
                            log.error("Agent execution exception", e);
                            sink.tryEmitNext(errorEvent);
                            chatMessagePubSubService.publish(agentContext.getConversationId(), JSON.toJSONString(errorEvent));
                            chatMessagePubSubService.finishPublish(agentContext.getConversationId());
                            sink.tryEmitComplete();
                            pushLogQueue(agentContext, tryReqDto.getFrom(), errorMessage);
                        },
                        () -> chatCompleted(requestContext, agentContext, tryReqDto, isComplete, sseCanceled, conversationDto, sink, finalTargetConfig, gitAutoCommitted, autoGitCommitEnabled));

        //心跳
        Flux.interval(Duration.ofSeconds(10)).map(tick -> {
                    log.info("Heartbeat sent, cid {}, tick {}", agentContext.getConversationId(), tick);
                    if (tick >= 360) {// 一个小时如果都还没有结束，打印详细日志，排查问题
                        log.warn("Agent execution time too long, cid {}", agentContext.getConversationId());
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
        // 终态（FINAL_RESULT）已在上面 publish 过，这里统一 flush 并清空回放，保证 Redis 不残留
        chatMessagePubSubService.finishPublish(agentContext.getConversationId());
        sink.tryEmitComplete();
        pushLogQueue(agentContext, tryReqDto.getFrom(), null);
        try {
            RequestContext.set(requestContext);
            Conversation conversation = conversationDomainService.getConversation(conversationDto.getId());
            if ((conversation.getTopicUpdated() == -1 || conversation.getTopicUpdated() == 0) && conversationDto.getDevMode() == 0 && conversationDto.getType() == Conversation.ConversationType.Chat) {
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

    @Override
    public Flux<AgentOutputDto> subscribe(Long conversationId) {

        ConversationDto conversation;
        try {
            conversation = conversationPermissionChecker.checkAndResponseConversation(conversationId);
        } catch (Exception e) {
            AgentOutputDto agentOutputDto = new AgentOutputDto();
            agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.ERROR);
            agentOutputDto.setError(e.getMessage());
            return Flux.just(agentOutputDto);
        }
        if (conversation.getTaskStatus() != Conversation.ConversationTaskStatus.EXECUTING) {
            AgentOutputDto agentOutputDto = new AgentOutputDto();
            agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.ERROR);
            agentOutputDto.setError("Conversation not executing");
            return Flux.just(agentOutputDto);
        }

        String cid = conversationId.toString();
        AtomicReference<ChatMessagePubSubService.Subscription> subscriptionRef = new AtomicReference<>();
        Flux<AgentOutputDto> flux = Flux.create(sink -> {
            AtomicBoolean terminated = new AtomicBoolean(false);
            Runnable terminate = () -> {
                if (terminated.compareAndSet(false, true)) {
                    sink.complete();
                }
            };
            Consumer<String> onMessage = payload -> {
                for (String message : splitPayload(payload)) {
                    if (terminated.get() || (subscriptionRef.get() != null && subscriptionRef.get().isClosed())) {
                        return;
                    }
                    AgentOutputDto agentOutputDto;
                    try {
                        agentOutputDto = JSON.parseObject(message, AgentOutputDto.class);
                    } catch (Exception e) {
                        log.error("parse message failed", e);
                        continue;
                    }
                    if (agentOutputDto == null) {
                        continue;
                    }
                    sink.next(agentOutputDto);
                    if (agentOutputDto.getEventType() == AgentOutputDto.EventTypeEnum.FINAL_RESULT
                            || agentOutputDto.getEventType() == AgentOutputDto.EventTypeEnum.ERROR) {
                        terminate.run();
                        ChatMessagePubSubService.Subscription subscription = subscriptionRef.get();
                        if (subscription != null) {
                            subscription.close();
                        }
                        return;
                    }
                }
            };

            // pubsub 服务内部完成「先注册 → 再读回放 → 按序投递」，注册之后到达的实时消息不会丢
            ChatMessagePubSubService.Subscription subscription =
                    chatMessagePubSubService.subscribe(cid, onMessage, terminate);
            if (subscription == null) {
                // 会话已经收尾（终态发完、回放已删）：直接结束，不挂监听，避免永远等不到结束
                log.info("会话已收尾，订阅直接结束, cid {}", cid);
                terminate.run();
                return;
            }
            subscriptionRef.set(subscription);
            if (terminated.get()) {
                // 回放里已经出现终态
                log.info("回放中已含终态，订阅结束, cid {}", cid);
                subscription.close();
                return;
            }

            // 注册/回放与任务结束之间仍有极窄的竞态窗口：回放里没有终态时要复查一次状态，
            // 否则会出现「任务已结束、监听还挂着、连接永不关闭」。
            // 延后一点给回放尾部的终态留出投递时间，同时把请求上下文带回异步线程。
            final RequestContext<Object> requestContext = RequestContext.get();
            Mono.delay(Duration.ofMillis(500)).subscribe(tick -> {
                try {
                    RequestContext.set(requestContext);
                    ConversationDto latest = TenantFunctions.callWithIgnoreCheck(
                            () -> conversationApplicationService.getConversationByCid(conversationId));
                    if (latest == null || latest.getTaskStatus() != Conversation.ConversationTaskStatus.EXECUTING) {
                        log.info("会话状态已不是执行中，结束订阅, cid {}, status {}",
                                cid, latest == null ? "null" : latest.getTaskStatus());
                        terminate.run();
                        ChatMessagePubSubService.Subscription current = subscriptionRef.get();
                        if (current != null) {
                            current.close();
                        }
                    }
                } finally {
                    RequestContext.remove();
                }
            });
        });
        // complete / cancel 都要摘监听：complete 由终态触发，cancel 由客户端断开触发
        return flux.doFinally(signal -> {
            ChatMessagePubSubService.Subscription subscription = subscriptionRef.get();
            if (subscription != null) {
                subscription.close();
            }
        });
    }

    private void setFileAkForCustomAgent(AgentConfigDto agentConfigDto) {
        if (agentConfigDto == null) {
            return;
        }
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

    private void checkIfSkillsUpdated(AgentConfigDto groupAgentConfigDto, ConversationDto conversationDto, List<Long> skillIds, List<SubagentDto> subagents, Map<String, List<HookEntryDto>> hooksConfig, boolean[] updateSkills, List<Long> updatedSkills) {
        groupAgentConfigDto.getAgentComponentConfigList().forEach(agentComponentConfigDto0 -> {
            if (agentComponentConfigDto0.getType() == AgentComponentConfig.Type.Skill) {
                if (!skillIds.contains(agentComponentConfigDto0.getTargetId())) {
                    skillIds.add(agentComponentConfigDto0.getTargetId());
                }
                if (((SkillConfigDto) agentComponentConfigDto0.getTargetConfig()).getPublishDate().getTime() > conversationDto.getModified().getTime()) {
                    updateSkills[0] = true;
                    updatedSkills.add(((SkillConfigDto) agentComponentConfigDto0.getTargetConfig()).getId());
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

        //积分赠送
        giftCreditRpcService.checkAndSendGiftCredit(tenantConfigDto, RequestContext.get().getUserId());

        //用户额外追加的技能，算在使用者身上
        if (CollectionUtils.isNotEmpty(tryReqDto.getSkillIds())) {
            PriceEstimate priceEstimate = resourcePricingRpcService.estimatePrice(RequestContext.get().getTenantId(), RequestContext.get().getUserId(), tryReqDto.getSkillIds().stream().map(skillId -> PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.SKILL).targetId(skillId.toString()).build()).collect(Collectors.toList()));
            if (!priceEstimate.isPass()) {
                priceEstimate.setTargetType(TargetTypeEnum.SKILL);
                return priceEstimate;
            }
        }

        Long resourceBillUserId = RequestContext.get().getUserId();
        UserSubscriptionDTO subscription = null;
        if (!devMode) {
            //检查用户是否订阅，编排模式下不判断是否订阅
            PriceEstimate priceEstimate = resourcePricingRpcService.estimatePrice(RequestContext.get().getTenantId(), RequestContext.get().getUserId(), List.of(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.AGENT).targetId(agentConfigDto.getId().toString()).build()));
            if (!priceEstimate.isPass()) {
                priceEstimate.setTargetType(TargetTypeEnum.AGENT);
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

        // 计费主体是否仍落在「请求发起方」一侧：false 表示上面因包干订阅/试用改由 Agent 创建者承担。
        // 该判定必须在下面应用覆盖之前取，它同时决定模型费是随主体走（每 token 计费）还是单独找使用者校验。
        boolean billedToRequester = resourceBillUserId.equals(RequestContext.get().getUserId());
        Long billUserIdOverride = RequestContext.get().getBillUserId();
        if (billUserIdOverride != null && billedToRequester) {
            // 内部调用显式指定付费方：只在费用原本由发起方承担时替换；包干订阅/试用已判给 Agent 创建者的不改
            resourceBillUserId = billUserIdOverride;
        }

        List<PriceEstimate.EstimateTarget> estimateTargets = new ArrayList<>();
        List<PriceEstimate.EstimateTarget> estimateSkillTargets = new ArrayList<>();
        Long targetId = agentConfigDto.getModelComponentConfig().getTargetId();
        if (targetId != null) {
            if (!isDefaultModel && !billedToRequester) {
                PriceEstimate priceEstimate = resourcePricingRpcService.estimatePrice(RequestContext.get().getTenantId(), RequestContext.get().getUserId(), List.of(PriceEstimate.EstimateTarget.builder().targetType(TargetTypeEnum.MODEL).targetId(targetId.toString()).build()));
                if (!priceEstimate.isPass()) {
                    priceEstimate.setTargetType(TargetTypeEnum.MODEL);
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
                    priceEstimate.setTargetType(TargetTypeEnum.SKILL);
                    priceEstimate.setMessage(message);
                    return priceEstimate;
                }
            } else {
                priceEstimate.setMessage(message);
                priceEstimate.setTargetType(TargetTypeEnum.SKILL);
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
            priceEstimate.setTargetType(TargetTypeEnum.TOOL);
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

    /**
     * 把 Redis 投递的一条 payload 拆成消息列表。
     * <p>同一批消息会合并成 JSON 数组发送；单条 JSON 也兼容（旧实例 / 手工补发）。
     */
    private static List<String> splitPayload(String payload) {
        if (payload == null) {
            return Collections.emptyList();
        }
        if (JSON.isValidArray(payload)) {
            JSONArray array = JSON.parseArray(payload);
            List<String> messages = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                messages.add(array.getString(i));
            }
            return messages;
        }
        return Collections.singletonList(payload);
    }

    /**
     * 取出会话里存下的 IM 上下文（{@link #CONVERSATION_IM_VARS_KEY}）。
     * 供「任务到点执行」那次运行复原群/私聊身份：那次运行由调度触发，平台不注入任何 IM_* 变量。
     *
     * @return 复原出的上下文；无存档或存档非法（空、非 Map）时返回 {@code null}，调用方按「无 IM 上下文」处理
     */
    private Map<String, Object> readConversationImVars(ConversationDto conversationDto) {
        if (conversationDto == null || conversationDto.getVariables() == null) {
            return null;
        }
        Object imVars = conversationDto.getVariables().get(CONVERSATION_IM_VARS_KEY);
        if (!(imVars instanceof Map<?, ?> vars) || vars.isEmpty()) {
            return null;
        }
        // imVars 经 JSON 反序列化回来，键统一按字符串归一，避免下游按字符串取值时落空
        Map<String, Object> restored = new HashMap<>();
        vars.forEach((key, value) -> {
            if (key != null) {
                restored.put(String.valueOf(key), value);
            }
        });
        return restored.isEmpty() ? null : restored;
    }

    /**
     * 取出 IM 上下文里的付费方覆盖（{@link #IM_VARS_BILL_USER_ID_KEY}，语义见
     * {@link com.xspaceagi.system.spec.common.RequestContext#getBillUserId()}）。
     *
     * <p>该值随会话变量做过一轮 JSON 往返，出现形态有四种：数字、数字字符串、{@code null}
     * （上游未指定付费方时会显式放入 null）、以及键不存在。后两种和解析失败一律按「未指定」处理 ——
     * 权限校验与计费回落到当前用户，不让一轮对话因为一个取不到的覆盖值而失败。
     *
     * @return 付费方用户 ID；无值或无法解析时返回 {@code null}
     */
    private Long readBillUserId(Map<String, Object> imVars) {
        if (imVars == null) {
            return null;
        }
        Object billUserId = imVars.get(IM_VARS_BILL_USER_ID_KEY);
        if (billUserId instanceof Number number) {
            return number.longValue();
        }
        if (billUserId instanceof String text && StringUtils.isNotBlank(text)) {
            try {
                return Long.parseLong(text.trim());
            } catch (NumberFormatException e) {
                log.warn("ChatApplicationServiceImpl.readBillUserId - BILL_USER_ID 无法解析为数字: {}", text);
            }
        }
        return null;
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
        // IM_VARS 不是用户变量、不在 variableParams 里，会被上面这轮覆盖式写抹掉；
        // 而它是「任务到点执行」那次运行唯一的 IM 身份来源，抹掉就再也找不回来，故显式带回。
        Object conversationImVars = conversationDto.getVariables() == null ? null
                : conversationDto.getVariables().get(CONVERSATION_IM_VARS_KEY);
        if (conversationImVars instanceof Map<?, ?> imVars && !imVars.isEmpty()) {
            conversationUpdate.getVariables().put(CONVERSATION_IM_VARS_KEY, imVars);
        }
        TenantFunctions.callWithIgnoreCheck(() -> conversationDomainService.updateConversation(conversationDto.getUserId(), conversationDto.getId(), conversationUpdate));

        // 发送事件消息
        if (conversationTaskStatus == Conversation.ConversationTaskStatus.COMPLETE || conversationTaskStatus == Conversation.ConversationTaskStatus.FAILED) {
            EventDto<Object> event = EventDto.builder()
                    .type(EventDto.EVENT_TYPE_CHAT_FINISHED)
                    .event(Map.of("conversationId", conversationDto.getId().toString(), "requestId", agentContext.getRequestId(), "status", conversationTaskStatus))
                    .build();
            notifyMessageApplicationService.publishEvent(agentContext.getUserId(), event);
            // 注意：这里只落库 + 发事件，不再顺手清空消息缓冲。
            // 收尾必须由「发完终态的那一方」显式调用 finishPublish，否则 ERROR/中断终态会被先删后发，
            // 把回放 key 重新写回来并留下 1 小时残影。
        }
    }

    private void updateTopic(AgentContext agentContext, ConversationDto conversationDto) {
        ConversationUpdateDto conversationUpdateDto = new ConversationUpdateDto();
        conversationUpdateDto.setId(Long.parseLong(agentContext.getConversationId()));
        conversationUpdateDto.setFirstMessage(agentContext.getMessage());
        conversationApplicationService.updateConversationTopic(agentContext.getUserId(), conversationUpdateDto);
        conversationDto.setTopic(conversationDto.getTopic());
        if (conversationDto.getTopic() != null) {
            conversationDto.setTopicUpdated(1);
        }
    }

    private boolean isVersionControlEnabled(Long userId, AgentConfigDto executingAgentConfig) {
        UserChatBoxConfigService.ChatBoxConfig userAgentChatBoxConfig = userChatBoxConfigService.getUserAgentChatBoxConfig(executingAgentConfig.getTenantId(), userId, executingAgentConfig.getId());
        if (userAgentChatBoxConfig.getEnableVersionControl() != null) {
            executingAgentConfig.setEnableVersionControl(userAgentChatBoxConfig.getEnableVersionControl());
        }
        if (userAgentChatBoxConfig.getAutoCommit() != null && userAgentChatBoxConfig.getAutoCommit().equals(YesOrNoEnum.Y.getKey())) {
            return false;
        }
        if ("ChatBot".equals(executingAgentConfig.getType())) {
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
        if (!isVersionControlEnabled(agentContext.getUserId(), agentContext.getAgentConfig())) {
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
                clearChatStopStatus(agentContext.getConversationId());
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

    public void clearChatStopStatus(String conversationId) {
        redisUtil.expire("chat.stop." + conversationId, 0);
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
    public void stopChat(String conversationId) {
        if (conversationId == null) {
            return;
        }
        long cid;
        try {
            cid = Long.parseLong(conversationId);
        } catch (NumberFormatException e) {
            // Compatible with previous API calls
            setChatStopStatus(conversationId);
            return;
        }
        ConversationDto conversation = conversationApplicationService.getConversationByCid(cid);
        if (conversation == null || conversation.getTaskStatus() != Conversation.ConversationTaskStatus.EXECUTING) {
            return;
        }
        AgentConfig agentConfig = TenantFunctions.callWithIgnoreCheck(() -> agentDomainService.queryById(conversation.getAgentId()));
        if (agentConfig != null && "TaskAgent".equals(agentConfig.getType())) {
            sandboxAgentClient.chatCancel(conversationId);
        }
        setChatStopStatus(conversationId);
        TenantFunctions.runWithIgnoreCheck(() -> conversationApplicationService.updateConversationStatus(conversation.getId(), Conversation.ConversationTaskStatus.CANCEL));
    }

    @Override
    public Long getSelectedModelId(Long userId, Long agentId) {
        try {
            String selectedKey = "agent.model.selected:" + userId + ":" + agentId;
            Object o = redisUtil.get(selectedKey);
            if (o != null) {
                return Long.parseLong(o.toString());
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }
}
