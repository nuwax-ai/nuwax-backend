package com.xspaceagi.agent.core.application.service;

import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AgentOutputDto;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.TryReqDto;
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
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.I18nUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import reactor.core.publisher.Mono;
import reactor.core.publisher.MonoSink;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service("conversationTaskService")
public class ConversationTaskExecuteServiceImpl implements TaskExecuteService {

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private NotifyMessageApplicationService notifyMessageApplicationService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Override
    public Mono<Boolean> asyncExecute(ScheduleTaskDto scheduleTask) {
        return Mono.create(booleanMonoSink -> execute0(booleanMonoSink, scheduleTask, 0));
    }

    public void execute0(MonoSink<Boolean> sink, ScheduleTaskDto scheduleTask, int times) {
        log.debug("ConversationTaskExecuteServiceImpl.execute() - scheduleTask: {}", scheduleTask.getParams());
        Map<String, Object> params = (Map<String, Object>) scheduleTask.getParams();
        RequestContext requestContext = new RequestContext<>();
        requestContext.setTenantId(Long.parseLong(params.getOrDefault("tenantId", "1").toString()));
        requestContext.setTenantConfig(tenantConfigApplicationService.getTenantConfig(requestContext.getTenantId()));
        RequestContext.set(requestContext);
        ConversationDto conversation = conversationApplicationService.getConversation(null, Long.parseLong(params.get("id").toString()));
        if (conversation == null) {
            sink.success(true);
            return;
        }
        List<TryReqDto.SelectedComponentDto> selectedComponents = new ArrayList<>();
        if (conversation.getAgent() != null && !CollectionUtils.isEmpty(conversation.getAgent().getManualComponents())) {
            conversation.getAgent().getManualComponents().forEach(agentComponent -> {
                if (Objects.equals(agentComponent.getDefaultSelected(), YesOrNoEnum.Y.getKey())) {
                    TryReqDto.SelectedComponentDto selectedComponentDto = new TryReqDto.SelectedComponentDto();
                    selectedComponentDto.setId(agentComponent.getId());
                    selectedComponentDto.setType(agentComponent.getType());
                    selectedComponents.add(selectedComponentDto);
                }
            });
        }
        TryReqDto tryReqDto = new TryReqDto();
        tryReqDto.setDebug(conversation.getDevMode() != null && conversation.getDevMode() == 1);
        tryReqDto.setConversationId(conversation.getId());
        tryReqDto.setMessage(conversation.getSummary());
        tryReqDto.setSelectedComponents(selectedComponents);
        tryReqDto.setVariableParams(conversation.getVariables());
        tryReqDto.setFrom("task");
        try {
            UserDto userDto = userApplicationService.queryById(conversation.getUserId());
            requestContext.setUser(userDto);
            RequestContext.get().setUserId(userDto.getId());
            AtomicBoolean retry = new AtomicBoolean(false);
            conversationApplicationService.chat(tryReqDto, new HashMap<>(), false).doOnComplete(() -> {
                log.debug("ConversationTaskExecuteServiceImpl.execute() - doOnComplete {}=>{}", conversation.getId(), conversation.getTopic());
                if (!retry.get()) {
                    sink.success(false);
                }
            }).subscribe((res) -> {
                if (res instanceof AgentOutputDto) {
                    //任何情况下结束都会返回FINAL_RESULT
                    if (res.getEventType() == AgentOutputDto.EventTypeEnum.FINAL_RESULT) {
                        AgentExecuteResult agentExecuteResult = (AgentExecuteResult) res.getData();
                        if (!agentExecuteResult.getSuccess()) {
                            //最多执行三次
                            if (times == 2) {
                                log.error("ConversationTaskExecuteServiceImpl.execute() - execute0 times: {}", times);
                                try {
                                    TenantFunctions.callWithIgnoreCheck(() -> notifyMessageApplicationService.sendNotifyMessage(SendNotifyMessageDto.builder()
                                            .tenantId(requestContext.getTenantId())
                                            .scope(NotifyMessage.MessageScope.System)
                                            .content(I18nUtil.systemMessage(userDto.getLangMap(), "Agent.AsyncExecute.Error.notifyMessage", conversation.getTopic(), res.getError()))
                                            .senderId(userDto.getId())
                                            .userIds(Collections.singletonList(conversation.getUserId()))
                                            .build()));
                                } catch (Exception e) {
                                    sink.error(e);
                                }
                                return;
                            }
                            retry.set(true);
                            execute0(sink, scheduleTask, times + 1);
                        } else {
                            sink.success(false);//任务返回false表示下次继续执行
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
