package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.spec.enums.TaskCron;
import com.xspaceagi.agent.web.ui.controller.dto.*;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.infra.dao.entity.Space;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.server.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "沙盒Agent任务管理相关接口")
@RestController
@RequestMapping("/api/v1/4sandbox/task")
@Slf4j
public class TaskCenterForSandboxController {

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Operation(summary = "创建定时任务")
    @RequestMapping(path = "/create", method = RequestMethod.POST)
    public ReqResult<SandboxTaskCreateResult> create(@RequestBody SandboxTaskAddDto sandboxTaskAddDto) {
        Assert.notNull(sandboxTaskAddDto.getTaskName(), "任务名称不能为空");
        Assert.notNull(sandboxTaskAddDto.getExecuteType(), "任务执行类型不能为空");
        Assert.notNull(sandboxTaskAddDto.getMessage(), "任务执行内容消息不能为空");
        Assert.notNull(sandboxTaskAddDto.getConversationId(), "会话ID不能为空");
        UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());
        if (userDataPermission.getMaxScheduledTaskCount() != null && userDataPermission.getMaxScheduledTaskCount() >= 0) {
            if (scheduleTaskApiService.countUserTotalTasks(RequestContext.get().getUserId()) >= userDataPermission.getMaxScheduledTaskCount()) {
                return ReqResult.error("你能创建的任务数量已达上限，当前最多可创建任务数为" + userDataPermission.getMaxScheduledTaskCount());
            }
        }
        UserAccessKeyDto userAccessKeyDto = (UserAccessKeyDto) RequestContext.get().getUserAccessKey();
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Agent, Long.valueOf(userAccessKeyDto.getTargetId()));
        if (publishedDto == null) {
            return ReqResult.error("Agent不存在或已下架");
        }
        ConversationDto conversationByCid = conversationApplicationService.getConversationByCid(sandboxTaskAddDto.getConversationId());
        if (conversationByCid == null || !conversationByCid.getAgentId().equals(Long.valueOf(userAccessKeyDto.getTargetId()))) {
            return ReqResult.error("conversationId 错误，请从环境变量中获取");
        }
        List<SpaceDto> spaceDtos = spaceApplicationService.queryListByUserId(RequestContext.get().getUserId());
        Long spaceId = spaceDtos.contains(publishedDto.getSpaceId()) ? publishedDto.getSpaceId() : null;
        if (spaceId == null) {
            SpaceDto spaceDto1 = spaceDtos.stream().filter(spaceDto -> spaceDto.getType() == Space.Type.Personal).findFirst().orElse(null);
            if (spaceDto1 == null) {
                return ReqResult.error("用户缺少个人空间数据，无法创建当前任务");
            }
            spaceId = spaceDto1.getId();
        }

        ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
        scheduleTaskDto.setCron(sandboxTaskAddDto.getCron());
        scheduleTaskDto.setTargetType("Agent");
        scheduleTaskDto.setTargetName(publishedDto.getName());
        scheduleTaskDto.setTargetId(userAccessKeyDto.getTargetId());
        scheduleTaskDto.setTaskName(sandboxTaskAddDto.getTaskName());
        scheduleTaskDto.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        scheduleTaskDto.setTenantId(RequestContext.get().getTenantId());
        scheduleTaskDto.setCreatorId(RequestContext.get().getUserId());
        scheduleTaskDto.setBeanId("taskCenterApplicationService");
        scheduleTaskDto.setSpaceId(spaceId);
        if (sandboxTaskAddDto.getExecuteType().equals("Once")) {
            Assert.notNull(sandboxTaskAddDto.getLockTime(), "执行时间不能为空");
            scheduleTaskDto.setMaxExecTimes(1L);
            scheduleTaskDto.setCron(toCron(sandboxTaskAddDto.getLockTime().getTime()));
        } else {
            Assert.notNull(sandboxTaskAddDto.getCron(), "定时cron表达式不能为空");
            scheduleTaskDto.setMaxExecTimes(Long.MAX_VALUE);
        }
        Map<String, Object> params = new HashMap<>();
        params.put("conversationId", conversationByCid.getId());
        params.put("keepConversation", 1);
        params.put("message", sandboxTaskAddDto.getMessage());
        scheduleTaskDto.setParams(params);
        Long id = scheduleTaskApiService.start(scheduleTaskDto);
        SandboxTaskCreateResult sandboxTaskCreateResult = new SandboxTaskCreateResult();
        sandboxTaskCreateResult.setId(id);
        return ReqResult.success(sandboxTaskCreateResult);
    }


    @Operation(summary = "更新定时任务")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    public ReqResult<Void> update(@RequestBody SandboxTaskUpdateDto sandboxTaskUpdateDto) {
        ScheduleTaskDto scheduleTaskDto1 = scheduleTaskApiService.queryById(sandboxTaskUpdateDto.getId());
        if (scheduleTaskDto1 == null) {
            return ReqResult.error("任务不存在");
        }
        UserAccessKeyDto userAccessKeyDto = (UserAccessKeyDto) RequestContext.get().getUserAccessKey();
        if (!userAccessKeyDto.getTargetId().equals(scheduleTaskDto1.getTargetId())) {
            return ReqResult.error("Invalid task id");
        }
        ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
        scheduleTaskDto.setId(sandboxTaskUpdateDto.getId());
        scheduleTaskDto.setTaskName(sandboxTaskUpdateDto.getTaskName());
        if (sandboxTaskUpdateDto.getExecuteType() != null) {
            if (sandboxTaskUpdateDto.getExecuteType().equals("Once")) {
                Assert.notNull(sandboxTaskUpdateDto.getLockTime(), "执行时间不能为空");
                scheduleTaskDto.setCron(toCron(sandboxTaskUpdateDto.getLockTime().getTime()));
                scheduleTaskDto.setMaxExecTimes(1L);
            } else {
                Assert.notNull(sandboxTaskUpdateDto.getCron(), "定时cron表达式不能为空");
                scheduleTaskDto.setMaxExecTimes(Long.MAX_VALUE);
            }
        }
        if (StringUtils.isNotBlank(sandboxTaskUpdateDto.getMessage())) {
            Map<String, Object> params = (Map<String, Object>) scheduleTaskDto1.getParams();
            if (params != null) {
                params.put("message", sandboxTaskUpdateDto.getMessage());
            }
            scheduleTaskDto.setParams(params);
        }
        scheduleTaskApiService.updateById(scheduleTaskDto);
        return ReqResult.success();
    }


    @Operation(summary = "取消定时任务")
    @RequestMapping(path = "/cancel/{id}", method = RequestMethod.POST)
    public ReqResult<Void> cancel(@PathVariable Long id) {
        ScheduleTaskDto scheduleTaskDto1 = scheduleTaskApiService.queryById(id);
        if (scheduleTaskDto1 == null) {
            return ReqResult.error("任务不存在");
        }
        UserAccessKeyDto userAccessKeyDto = (UserAccessKeyDto) RequestContext.get().getUserAccessKey();
        if (!userAccessKeyDto.getTargetId().equals(scheduleTaskDto1.getTargetId())) {
            return ReqResult.error("Invalid task id");
        }
        ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
        scheduleTaskDto.setId(id);
        scheduleTaskDto.setStatus(ScheduleTaskDto.Status.CANCEL);
        scheduleTaskApiService.updateById(scheduleTaskDto);
        return ReqResult.success();
    }

    @Operation(summary = "查询当前Agent的定时任务列表")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<List<SandboxTaskDto>> list() {
        UserAccessKeyDto userAccessKeyDto = (UserAccessKeyDto) RequestContext.get().getUserAccessKey();
        List<ScheduleTaskDto> scheduleTaskDtoList = scheduleTaskApiService.queryTaskListByUserIdAndAgentId(RequestContext.get().getUserId(), userAccessKeyDto.getTargetId());
        return ReqResult.success(scheduleTaskDtoList.stream().map(scheduleTaskDto -> {
            SandboxTaskDto sandboxTaskDto = new SandboxTaskDto();
            sandboxTaskDto.setId(scheduleTaskDto.getId());
            sandboxTaskDto.setTaskName(scheduleTaskDto.getTaskName());
            sandboxTaskDto.setExecuteType(scheduleTaskDto.getMaxExecTimes() == 1 ? "Once" : "Loop");
            sandboxTaskDto.setCron(scheduleTaskDto.getCron());
            sandboxTaskDto.setLockTime(scheduleTaskDto.getLockTime());
            Map<String, Object> params = (Map<String, Object>) scheduleTaskDto.getParams();
            if (params != null) {
                sandboxTaskDto.setMessage(String.valueOf(params.get("message")));
                try {
                    sandboxTaskDto.setConversationId(Long.parseLong(String.valueOf(params.get("conversationId"))));
                } catch (NumberFormatException ignore) {
                }
            }
            return sandboxTaskDto;
        }).collect(Collectors.toList()));
    }


    private static String toCron(long timestamp) {
        try {
            // 转换为本地时间
            LocalDateTime dateTime = LocalDateTime.ofEpochSecond(timestamp / 1000, 0, ZoneId.systemDefault().getRules().getOffset(Instant.now()));
            // 生成 Cron 表达式（Spring 格式：秒 分 时 日 月 周 年）
            return String.format("%d %d %d %d %d ?",
                    dateTime.getSecond(),
                    dateTime.getMinute(),
                    dateTime.getHour(),
                    dateTime.getDayOfMonth(),
                    dateTime.getMonthValue());
        } catch (Exception e) {
            return null;
        }
    }
}
