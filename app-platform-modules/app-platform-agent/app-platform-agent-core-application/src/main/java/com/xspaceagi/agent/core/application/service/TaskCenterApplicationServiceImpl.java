package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.application.WorkflowApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AgentOutputDto;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.TryReqDto;
import com.xspaceagi.agent.core.adapter.dto.WorkflowExecuteRequestDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.infra.component.agent.dto.AgentExecuteResult;
import com.xspaceagi.system.application.dto.SendNotifyMessageDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.NotifyMessageApplicationService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.sdk.service.TaskExecuteService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.I18nUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service("taskCenterApplicationService")
public class TaskCenterApplicationServiceImpl implements TaskExecuteService {

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private WorkflowApplicationService workflowApplicationService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private NotifyMessageApplicationService notifyMessageApplicationService;
    @Autowired
    private AgentApplicationService agentApplicationService;

    @Override
    public Mono<Boolean> asyncExecute(ScheduleTaskDto scheduleTask) {
        return Mono.create(sink -> {
            try {
                RequestContext<Object> requestContext = new RequestContext<>();
                requestContext.setTenantId(scheduleTask.getTenantId());
                requestContext.setTenantConfig(tenantConfigApplicationService.getTenantConfig(requestContext.getTenantId()));
                UserDto userDto = userApplicationService.queryById(scheduleTask.getCreatorId());
                requestContext.setUser(userDto);
                requestContext.setUserId(userDto.getId());
                requestContext.setLangMap(userDto.getLangMap());
                RequestContext.set(requestContext);
                if (scheduleTask.getTargetType().equals(Published.TargetType.Agent.name())) {
                    executeAgentTask(sink, requestContext, scheduleTask);
                } else if (scheduleTask.getTargetType().equals(Published.TargetType.Workflow.name())) {
                    executeWorkflowTask(sink, scheduleTask);
                } else {
                    sink.success(true);
                }
            } finally {
                RequestContext.remove();
            }
        });
    }

    private void executeWorkflowTask(MonoSink<Boolean> sink, ScheduleTaskDto scheduleTask) {
        WorkflowConfigDto workflowConfigDto = workflowApplicationService.queryPublishedWorkflowConfig(Long.parseLong(scheduleTask.getTargetId()), scheduleTask.getSpaceId(), true);
        if (workflowConfigDto == null) {
            sink.error(BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentWorkflowOffline));
            return;
        }
        if (!(scheduleTask.getParams() instanceof Map<?, ?> params)) {
            sink.error(new IllegalArgumentException("Invalid task parameters"));
            return;
        }
        WorkflowExecuteRequestDto workflowExecuteRequestDto = new WorkflowExecuteRequestDto();
        workflowExecuteRequestDto.setWorkflowId(workflowConfigDto.getId());
        //noinspection unchecked
        workflowExecuteRequestDto.setParams((Map<String, Object>) scheduleTask.getParams());
        workflowExecuteRequestDto.setRequestId(UUID.randomUUID().toString().replace("-", ""));
        workflowExecuteRequestDto.setFrom("task_center");
        workflowApplicationService.executeWorkflow(workflowExecuteRequestDto, workflowConfigDto).subscribe(workflowExecutingDto -> {
        }, throwable -> {
            sink.error(throwable);
            log.error("执行工作流任务失败, {}", scheduleTask, throwable);
        }, () -> sink.success(false));
    }

    private void executeAgentTask(MonoSink<Boolean> sink, RequestContext<Object> requestContext, ScheduleTaskDto scheduleTask) {
        if (!(scheduleTask.getParams() instanceof Map<?, ?> params)) {
            sink.error(new IllegalArgumentException("Invalid task parameters"));
            return;
        }
        if (!params.containsKey("message")) {
            sink.error(new IllegalArgumentException("Invalid task parameters"));
            return;
        }
        ConversationDto conversation = null;
        if (params.containsKey("conversationId")) {
            conversation = conversationApplicationService.getConversation(null, Long.parseLong(params.get("conversationId").toString()));
        }
        if (conversation == null) {
            conversation = conversationApplicationService.createConversationForTaskCenter(scheduleTask.getTenantId(), scheduleTask.getCreatorId(), Long.parseLong(scheduleTask.getTargetId()));
        }
        List<TryReqDto.SelectedComponentDto> selectedComponents = new ArrayList<>();
        if (conversation.getAgent() != null && !CollectionUtils.isEmpty(conversation.getAgent().getManualComponents())) {
            conversation.getAgent().getManualComponents().forEach(agentComponent -> {
                if (Objects.equals(agentComponent.getDefaultSelected(), YesOrNoEnum.Y.getKey())) {
                    TryReqDto.SelectedComponentDto selectedComponentDto = new TryReqDto.SelectedComponentDto();
                    selectedComponentDto.setType(agentComponent.getType());
                    selectedComponentDto.setId(agentComponent.getId());
                    selectedComponents.add(selectedComponentDto);
                }
            });
        }
        TryReqDto tryReqDto = new TryReqDto();
        tryReqDto.setDebug(false);
        tryReqDto.setConversationId(conversation.getId());
        tryReqDto.setMessage(params.get("message").toString());
        tryReqDto.setSelectedComponents(selectedComponents);
        try {
            if (YesOrNoEnum.Y.getKey().equals(conversation.getAgent().getAllowOtherModel())) {
                List<ModelConfigDto> modelConfigDtos = agentApplicationService.queryUserCanSelectModelListForAgent(conversation.getUserId(), conversation.getAgentId());
                if (!CollectionUtils.isEmpty(modelConfigDtos)) {
                    tryReqDto.setModelId(modelConfigDtos.get(0).getId());
                }
            }
        } catch (Exception e) {
            // ignore
        }
        //noinspection unchecked
        tryReqDto.setVariableParams(params.get("variables") instanceof Map<?, ?> ? (Map<String, Object>) params.get("variables") : null);
        tryReqDto.setFrom("task_center");
        executeAgentTask0(sink, requestContext, tryReqDto, scheduleTask, 0);
    }

    private void executeAgentTask0(MonoSink<Boolean> sink, RequestContext<Object> requestContext, TryReqDto tryReqDto, ScheduleTaskDto scheduleTask, int times) {
        RequestContext.set(requestContext);
        AtomicBoolean retry = new AtomicBoolean(false);
        try {
            conversationApplicationService.chat(tryReqDto, new HashMap<>(), false)
                    .doOnComplete(() -> {
                        log.info("TaskCenterApplicationServiceImpl.executeAgentTask() - doOnComplete {} => {}, 执行成功 {}", scheduleTask.getTargetId(), scheduleTask.getTaskName(), !retry.get());
                        if (!retry.get()) {
                            sink.success(false);
                        }
                    }).subscribe((res) -> {
                        if (res != null) {
                            //任何情况下结束都会返回FINAL_RESULT
                            if (res.getEventType() == AgentOutputDto.EventTypeEnum.FINAL_RESULT) {
                                AgentExecuteResult agentExecuteResult = (AgentExecuteResult) res.getData();
                                if (!agentExecuteResult.getSuccess()) {
                                    //最多执行三次
                                    if (times == 2) {
                                        log.error("TaskCenterApplicationServiceImpl.executeAgentTask() - retry times: {}", times);
                                        try {
                                            TenantFunctions.callWithIgnoreCheck(() -> notifyMessageApplicationService.sendNotifyMessage(SendNotifyMessageDto.builder()
                                                    .tenantId(scheduleTask.getTenantId())
                                                    .scope(NotifyMessage.MessageScope.System)
                                                    .content(I18nUtil.systemMessage(requestContext.getLangMap(), "Agent.AsyncExecute.Error.notifyMessage", scheduleTask.getTaskName(), res.getError()))
                                                    .senderId(scheduleTask.getCreatorId())
                                                    .userIds(Collections.singletonList(scheduleTask.getCreatorId()))
                                                    .build()));
                                        } catch (Exception e) {
                                            log.error("TaskCenterApplicationServiceImpl.executeAgentTask() - send notify message error", e);
                                        }
                                        sink.error(BizException.of(ErrorCodeEnum.INVALID_PARAM,
                                                BizExceptionCodeEnum.validationFailedWithDetail,
                                                agentExecuteResult.getError()));//任务返回false表示下次继续执行
                                        return;
                                    }
                                    retry.set(true);
                                    executeAgentTask0(sink, requestContext, tryReqDto, scheduleTask, times + 1);
                                }
                            }
                        }
                    }, error -> {
                        sink.success(false);//任务返回false表示下次继续执行
                    });
        } finally {
            RequestContext.remove();
        }
    }
}
