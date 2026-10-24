package com.xspaceagi.agent.web.ui.controller;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.ConversationQueryDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.dto.TaskConversationAddOrUpdateDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.spec.enums.TaskCron;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.infra.dao.entity.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "智能体定时任务相关接口")
@RestController
@RequestMapping("/api/agent/task")
@Slf4j
public class TaskConversationController {

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Operation(summary = "创建定时会话")
    @RequestMapping(path = "/create", method = RequestMethod.POST)
    public ReqResult<ConversationDto> create(@RequestBody TaskConversationAddOrUpdateDto taskConversationAddOrUpdateDto) {
        return createOrUpdate(taskConversationAddOrUpdateDto);
    }

    @Operation(summary = "更新定时会话")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    public ReqResult<ConversationDto> update(@RequestBody @Valid TaskConversationAddOrUpdateDto taskConversationAddOrUpdateDto) {
        Assert.notNull(taskConversationAddOrUpdateDto.getId(), "会话ID不能为空");
        return createOrUpdate(taskConversationAddOrUpdateDto);
    }

    private ReqResult<ConversationDto> createOrUpdate(TaskConversationAddOrUpdateDto taskConversationAddOrUpdateDto) {
        if (taskConversationAddOrUpdateDto.isDevMode()) {
            AgentConfigDto agentConfigDto = agentApplicationService.queryById(taskConversationAddOrUpdateDto.getAgentId());
            if (agentConfigDto == null) {
                return ReqResult.error("智能体不存在");
            }
            if (agentConfigDto.getOpenScheduledTask() != AgentConfig.OpenStatus.Open) {
                return ReqResult.error("智能体未开启定时会话");
            }
            spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId());
        } else {
            PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Agent, taskConversationAddOrUpdateDto.getAgentId());
            if (publishedDto == null) {
                return ReqResult.error("智能体不存在或已下架");
            }
            agentApplicationService.addOrUpdateRecentUsed(RequestContext.get().getUserId(), taskConversationAddOrUpdateDto.getAgentId(), publishedDto.getTargetSubType().name());
            AgentConfigDto agentConfigDto = JSON.parseObject(publishedDto.getConfig(), AgentConfigDto.class);
            if (agentConfigDto.getOpenScheduledTask() != AgentConfig.OpenStatus.Open) {
                return ReqResult.error("智能体未开启定时会话");
            }
        }

        if (taskConversationAddOrUpdateDto.getId() != null) {
            conversationApplicationService.updateTaskConversation(RequestContext.get().getUserId(), taskConversationAddOrUpdateDto);
            return ReqResult.success(conversationApplicationService.getConversation(RequestContext.get().getUserId(), taskConversationAddOrUpdateDto.getId()));
        }

        return ReqResult.success(conversationApplicationService.createTaskConversation(RequestContext.get().getUserId(), taskConversationAddOrUpdateDto));
    }


    @Operation(summary = "取消定时会话")
    @RequestMapping(path = "/cancel/{conversationId}", method = RequestMethod.POST)
    public ReqResult<ConversationDto> cancel(@PathVariable Long conversationId) {
        conversationApplicationService.cancelTaskConversation(RequestContext.get().getUserId(), conversationId);
        return ReqResult.success();
    }

    @Operation(summary = "查询用户定时会话列表")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<List<ConversationDto>> list(@RequestBody ConversationQueryDto conversationQueryDto) {
        List<ConversationDto> conversationDtoList = conversationApplicationService.queryTaskConversationList(RequestContext.get().getUserId(),
                conversationQueryDto.getAgentId(), conversationQueryDto.getTaskStatus());
        return ReqResult.success(conversationDtoList);
    }

    @Operation(summary = "可选定时范围")
    @RequestMapping(path = "/cron/list", method = RequestMethod.GET)
    public ReqResult<List<TaskCron.CronDto>> cronList() {
        UserDto user = (UserDto) RequestContext.get().getUser();
        if (user.getRole() == User.Role.Admin) {
            return ReqResult.success(TaskCron.getTaskCronList());
        } else {
            return ReqResult.success(TaskCron.getUserTaskCronList());
        }
    }
}
