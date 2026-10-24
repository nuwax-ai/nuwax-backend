package com.xspaceagi.agent.web.ui.controller.api;

import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedPermissionDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.web.ui.controller.dto.ScheduleTaskAddDto;
import com.xspaceagi.agent.web.ui.controller.dto.ScheduleTaskUpdateDto;
import com.xspaceagi.agent.web.ui.controller.dto.api.TaskListQueryDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.I18nUtil;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 开放 API - 任务中心（定时任务）控制器。
 * <p>
 * 基于 {@code TaskCenterForSandboxController} 的定时任务管理逻辑，面向开放 API（/api/v1）。
 * 与沙盒控制器的差异：
 * <ul>
 *   <li>目标对象由 targetType + targetId 显式指定，支持 <b>Agent</b> 与 <b>Workflow</b> 两类（沙盒仅 Agent）</li>
 *   <li>管理任务时做显式权限校验：发布对象权限 + 空间成员 + 租户归属（对齐开放 API 惯例，不依赖内部 @RequireResource 资源码）</li>
 * </ul>
 */
@Tag(name = "开放API-任务中心相关接口")
@RestController
@RequestMapping("/api/v1/schedule/task")
@Slf4j
public class TaskCenterApiController {

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Operation(summary = "创建定时任务")
    @RequestMapping(path = "/create", method = RequestMethod.POST)
    public ReqResult<ScheduleTaskDto> create(@RequestBody ScheduleTaskAddDto scheduleTaskAddDto) {
        Published.TargetType targetType = checkTargetType(scheduleTaskAddDto.getTargetType());
        // 目标对象必须存在且已发布
        PublishedDto publishedDto = publishApplicationService.queryPublished(targetType, Long.valueOf(scheduleTaskAddDto.getTargetId()));
        if (publishedDto == null) {
            return ReqResult.error("Target object does not exist or has been removed");
        }
        // 目标对象执行权限
        PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(targetType, Long.valueOf(scheduleTaskAddDto.getTargetId()));
        if (!publishedPermissionDto.isExecute()) {
            return ReqResult.error("No permission for the relevant target object");
        }
        // 任务数量上限
        UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());
        if (userDataPermission.getMaxScheduledTaskCount() != null && userDataPermission.getMaxScheduledTaskCount() >= 0) {
            Long l = scheduleTaskApiService.countUserTotalTasks(RequestContext.get().getUserId());
            if (l >= userDataPermission.getMaxScheduledTaskCount()) {
                return ReqResult.error(I18nUtil.systemMessage("Backend.TaskCenter.TaskLimitReached", userDataPermission.getMaxScheduledTaskCount().toString()));
            }
        }
        // 任务空间：spaceId 省略时默认使用用户个人空间；否则校验用户为空间成员
        Long spaceId = scheduleTaskAddDto.getSpaceId();
        if (spaceId == null) {
            spaceId = spaceApplicationService.getPersonalSpaceId(RequestContext.get().getUserId());
            if (spaceId == null) {
                return ReqResult.error("User has no personal space, cannot create task");
            }
            scheduleTaskAddDto.setSpaceId(spaceId);
        } else {
            spacePermissionService.checkSpaceUserPermission(spaceId);
        }

        ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
        BeanUtils.copyProperties(scheduleTaskAddDto, scheduleTaskDto);
        scheduleTaskDto.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        scheduleTaskDto.setTenantId(RequestContext.get().getTenantId());
        scheduleTaskDto.setCreatorId(RequestContext.get().getUserId());
        scheduleTaskDto.setBeanId("taskCenterApplicationService");
        if (scheduleTaskAddDto.getMaxExecTimes() != null && scheduleTaskAddDto.getMaxExecTimes() == 1) {
            Assert.notNull(scheduleTaskAddDto.getLockTime(), "Lock time cannot be null");
            scheduleTaskDto.setMaxExecTimes(1L);
            scheduleTaskDto.setCron(toCron(scheduleTaskAddDto.getLockTime().getTime()));
        } else {
            scheduleTaskDto.setMaxExecTimes(Long.MAX_VALUE);
        }
        createAgentTaskConversationIfNeed(scheduleTaskAddDto.getKeepConversation(), scheduleTaskDto);
        Long id = scheduleTaskApiService.start(scheduleTaskDto);
        ScheduleTaskDto scheduleTaskDto1 = scheduleTaskApiService.queryById(id);
        completeScheduleTask(scheduleTaskDto1);
        return ReqResult.success(scheduleTaskDto1);
    }

    @Operation(summary = "更新定时任务")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    public ReqResult<Void> update(@RequestBody @Valid ScheduleTaskUpdateDto scheduleTaskUpdateDto) {
        ScheduleTaskDto scheduleTaskDto1 = scheduleTaskApiService.queryById(scheduleTaskUpdateDto.getId());
        if (scheduleTaskDto1 == null) {
            return ReqResult.error("Task does not exist");
        }
        checkTenantId(scheduleTaskDto1);
        spacePermissionService.checkSpaceUserPermission(scheduleTaskDto1.getSpaceId());
        // 智能体任务更新时保留原会话（params 中的 conversationId）
        if ("Agent".equals(scheduleTaskUpdateDto.getTargetType())) {
            if (scheduleTaskDto1.getParams() instanceof Map<?, ?>) {
                Object conversationId = ((Map<?, ?>) scheduleTaskDto1.getParams()).get("conversationId");
                if (conversationId != null && scheduleTaskUpdateDto.getParams() instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked") Map<String, Object> params = (Map<String, Object>) scheduleTaskUpdateDto.getParams();
                    params.put("conversationId", conversationId);
                }
            }
        }
        ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
        BeanUtils.copyProperties(scheduleTaskUpdateDto, scheduleTaskDto);
        createAgentTaskConversationIfNeed(scheduleTaskUpdateDto.getKeepConversation(), scheduleTaskDto);
        if (scheduleTaskUpdateDto.getMaxExecTimes() != null && scheduleTaskUpdateDto.getMaxExecTimes() == 1) {
            Assert.notNull(scheduleTaskUpdateDto.getLockTime(), "Lock time cannot be null");
            scheduleTaskDto.setMaxExecTimes(1L);
            scheduleTaskDto.setCron(toCron(scheduleTaskUpdateDto.getLockTime().getTime()));
        } else {
            scheduleTaskDto.setMaxExecTimes(Long.MAX_VALUE);
        }
        scheduleTaskApiService.updateById(scheduleTaskDto);
        return ReqResult.success();
    }

    @Operation(summary = "删除定时任务")
    @RequestMapping(path = "/{id}/delete", method = RequestMethod.POST)
    public ReqResult<Void> delete(@PathVariable Long id) {
        ScheduleTaskDto scheduleTaskDto1 = checkTaskAndPermission(id);
        scheduleTaskApiService.deleteById(scheduleTaskDto1.getId());
        return ReqResult.success();
    }

    @Operation(summary = "执行定时任务")
    @RequestMapping(path = "/{id}/execute", method = RequestMethod.POST)
    public ReqResult<Void> execute(@PathVariable Long id) {
        ScheduleTaskDto scheduleTaskDto1 = checkTaskAndPermission(id);
        scheduleTaskApiService.updateToExecuteNow(scheduleTaskDto1.getId());
        return ReqResult.success();
    }

    @Operation(summary = "取消定时任务")
    @RequestMapping(path = "/{id}/cancel", method = RequestMethod.POST)
    public ReqResult<Void> cancel(@PathVariable Long id) {
        ScheduleTaskDto scheduleTaskDto1 = checkTaskAndPermission(id);
        ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
        scheduleTaskDto.setId(id);
        scheduleTaskDto.setStatus(ScheduleTaskDto.Status.CANCEL);
        scheduleTaskApiService.updateById(scheduleTaskDto);
        return ReqResult.success();
    }

    @Operation(summary = "启用定时任务")
    @RequestMapping(path = "/{id}/enable", method = RequestMethod.POST)
    public ReqResult<Void> enable(@PathVariable Long id) {
        ScheduleTaskDto scheduleTaskDto1 = checkTaskAndPermission(id);
        ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
        scheduleTaskDto.setId(id);
        scheduleTaskDto.setStatus(ScheduleTaskDto.Status.CONTINUE);
        scheduleTaskApiService.updateById(scheduleTaskDto);
        return ReqResult.success();
    }

    @Operation(summary = "查询任务列表", description = "按空间(spaceId) 或 按当前用户在某智能体下(agentId) 查询，两者必选其一")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<List<ScheduleTaskDto>> list(@RequestBody TaskListQueryDto queryDto) {
        Long spaceId = queryDto.getSpaceId();
        String agentId = queryDto.getAgentId();
        boolean hasSpace = spaceId != null;
        boolean hasAgent = StringUtils.isNotBlank(agentId);
        if (hasSpace == hasAgent) {
            return ReqResult.error("spaceId and agentId must provide exactly one");
        }
        return hasSpace ? listBySpace(spaceId) : listByAgent(agentId);
    }

    /**
     * 按空间查询任务列表，富化 Agent/Workflow 名称图标，清理已下架对象的孤儿任务。
     */
    private ReqResult<List<ScheduleTaskDto>> listBySpace(Long spaceId) {
        spacePermissionService.checkSpaceUserPermission(spaceId);
        List<ScheduleTaskDto> scheduleTaskDtoList = scheduleTaskApiService.queryTaskListBySpaceId(spaceId);
        for (ScheduleTaskDto scheduleTaskDto : scheduleTaskDtoList) {
            checkTenantId(scheduleTaskDto);
            break;
        }
        // 获取类型为 Agent 的任务列表
        List<ScheduleTaskDto> agentTaskDtoList = scheduleTaskDtoList.stream().filter(scheduleTaskDto -> scheduleTaskDto.getTargetType().equals(Published.TargetType.Agent.name())).toList();
        List<Long> agentIds = agentTaskDtoList.stream().map(scheduleTaskDto -> Long.valueOf(scheduleTaskDto.getTargetId())).toList();
        List<PublishedDto> publishedDtoList = publishApplicationService.queryPublishedList(Published.TargetType.Agent, agentIds);
        Map<Long, PublishedDto> agentDtoMap = publishedDtoList.stream().collect(Collectors.toMap(PublishedDto::getTargetId, publishedDto -> publishedDto, (v1, v2) -> v1));
        // 同理获取 Workflow 的 map
        List<ScheduleTaskDto> wfTaskDtoList = scheduleTaskDtoList.stream().filter(scheduleTaskDto -> scheduleTaskDto.getTargetType().equals(Published.TargetType.Workflow.name())).toList();
        List<Long> wfIds = wfTaskDtoList.stream().map(scheduleTaskDto -> Long.valueOf(scheduleTaskDto.getTargetId())).toList();
        List<PublishedDto> workflowDtoList = publishApplicationService.queryPublishedList(Published.TargetType.Workflow, wfIds);
        Map<Long, PublishedDto> workflowDtoMap = workflowDtoList.stream().collect(Collectors.toMap(PublishedDto::getTargetId, publishedDto -> publishedDto, (v1, v2) -> v1));

        scheduleTaskDtoList.forEach(scheduleTaskDto -> {
            if (scheduleTaskDto.getTargetType().equals(Published.TargetType.Agent.name())) {
                PublishedDto publishedDto = agentDtoMap.get(Long.valueOf(scheduleTaskDto.getTargetId()));
                if (publishedDto != null) {
                    scheduleTaskDto.setTargetName(publishedDto.getName());
                    scheduleTaskDto.setTargetIcon(DefaultIconUrlUtil.setDefaultIconUrl(publishedDto.getIcon(), publishedDto.getName(), "agent"));
                    scheduleTaskDto.setBeanId(null);
                }
            } else if (scheduleTaskDto.getTargetType().equals(Published.TargetType.Workflow.name())) {
                PublishedDto publishedDto = workflowDtoMap.get(Long.valueOf(scheduleTaskDto.getTargetId()));
                if (publishedDto != null) {
                    scheduleTaskDto.setTargetName(publishedDto.getName());
                    scheduleTaskDto.setTargetIcon(DefaultIconUrlUtil.setDefaultIconUrl(publishedDto.getIcon(), publishedDto.getName(), "workflow"));
                    scheduleTaskDto.setBeanId(null);
                }
            }
        });
        scheduleTaskDtoList.removeIf(scheduleTaskDto -> {
            if (scheduleTaskDto.getTargetName() == null) {
                scheduleTaskApiService.deleteById(scheduleTaskDto.getId());
            }
            return scheduleTaskDto.getTargetName() == null;
        });
        return ReqResult.success(scheduleTaskDtoList);
    }

    /**
     * 按当前用户 + 智能体查询任务列表（仅当前用户创建的任务，天然隔离其他用户）。
     */
    private ReqResult<List<ScheduleTaskDto>> listByAgent(String agentId) {
        Long targetId;
        try {
            targetId = Long.valueOf(agentId);
        } catch (NumberFormatException e) {
            return ReqResult.error("Invalid agent id");
        }
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Agent, targetId);
        if (publishedDto == null) {
            return ReqResult.error("Agent does not exist or has been removed");
        }
        List<ScheduleTaskDto> taskDtoList = scheduleTaskApiService.queryTaskListByUserIdAndAgentId(RequestContext.get().getUserId(), agentId);
        taskDtoList.forEach(this::completeScheduleTask);
        return ReqResult.success(taskDtoList);
    }

    /**
     * 校验目标类型：仅支持 Agent / Workflow
     */
    private Published.TargetType checkTargetType(String targetType) {
        Published.TargetType type;
        try {
            type = Published.TargetType.valueOf(targetType);
        } catch (Exception e) {
            throw new BizException("Unsupported target type, only Agent or Workflow is allowed");
        }
        if (type != Published.TargetType.Agent && type != Published.TargetType.Workflow) {
            throw new BizException("Unsupported target type, only Agent or Workflow is allowed");
        }
        return type;
    }

    /**
     * 查询任务并做权限校验：任务存在 + 属于当前租户 + 所在空间为当前用户可访问。
     */
    private ScheduleTaskDto checkTaskAndPermission(Long id) {
        ScheduleTaskDto scheduleTaskDto = scheduleTaskApiService.queryById(id);
        if (scheduleTaskDto == null) {
            throw new BizException("Task does not exist");
        }
        checkTenantId(scheduleTaskDto);
        spacePermissionService.checkSpaceUserPermission(scheduleTaskDto.getSpaceId());
        return scheduleTaskDto;
    }

    private void checkTenantId(ScheduleTaskDto scheduleTaskDto) {
        if (!scheduleTaskDto.getTenantId().equals(RequestContext.get().getTenantId())) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
    }

    private void createAgentTaskConversationIfNeed(Integer keepConversation, ScheduleTaskDto scheduleTaskDto) {
        if ("Agent".equals(scheduleTaskDto.getTargetType())) {
            Map<String, Object> params = (Map<String, Object>) scheduleTaskDto.getParams();
            if (keepConversation != null && keepConversation.equals(1)) {
                if (!params.containsKey("conversationId")) {
                    ConversationDto conversationDto = conversationApplicationService.createConversationForTaskCenter(RequestContext.get().getTenantId(), RequestContext.get().getUserId(), Long.parseLong(scheduleTaskDto.getTargetId()));
                    params.put("conversationId", conversationDto.getId());
                }
                params.put("keepConversation", 1);
            } else {
                params.put("keepConversation", 0);
                params.remove("conversationId");
            }
        }
    }

    private void completeScheduleTask(ScheduleTaskDto scheduleTaskDto) {
        if (scheduleTaskDto == null) {
            return;
        }
        PublishedDto publishedDto = null;
        if ("Agent".equals(scheduleTaskDto.getTargetType())) {
            publishedDto = publishApplicationService.queryPublished(Published.TargetType.Agent, Long.valueOf(scheduleTaskDto.getTargetId()));
        }
        if ("Workflow".equals(scheduleTaskDto.getTargetType())) {
            publishedDto = publishApplicationService.queryPublished(Published.TargetType.Workflow, Long.valueOf(scheduleTaskDto.getTargetId()));
        }
        if (publishedDto == null) {
            return;
        }
        scheduleTaskDto.setBeanId(null);
        scheduleTaskDto.setTargetName(publishedDto.getName());
        scheduleTaskDto.setTargetIcon(DefaultIconUrlUtil.setDefaultIconUrl(publishedDto.getIcon(), publishedDto.getName(), "agent"));
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
