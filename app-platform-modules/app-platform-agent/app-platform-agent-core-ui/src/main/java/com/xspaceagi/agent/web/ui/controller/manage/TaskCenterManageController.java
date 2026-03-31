package com.xspaceagi.agent.web.ui.controller.manage;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.spec.enums.TaskCron;
import com.xspaceagi.agent.web.ui.controller.dto.ScheduleTaskUpdateDto;
import com.xspaceagi.agent.web.ui.controller.manage.dto.ManageQueryRequest;
import com.xspaceagi.system.application.util.DefaultIconUrlUtil;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.*;

@Tag(name = "系统管理 - 任务中心相关接口")
@RestController
@RequestMapping("/api/system/task")
@Slf4j
public class TaskCenterManageController extends BaseManageController {

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @RequireResource(TASK_MANAGE_MODIFY)
    @Operation(summary = "更新任务")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    public ReqResult<Void> update(@RequestBody @Valid ScheduleTaskUpdateDto scheduleTaskUpdateDto) {
        ScheduleTaskDto scheduleTaskDto1 = scheduleTaskApiService.queryById(scheduleTaskUpdateDto.getId());
        if (scheduleTaskDto1 == null) {
            return ReqResult.error("任务不存在");
        }
        checkTenantId(scheduleTaskDto1);
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
            Assert.notNull(scheduleTaskUpdateDto.getLockTime(), "锁定时间不能为空");
            scheduleTaskDto.setMaxExecTimes(1L);
            scheduleTaskDto.setCron(toCron(scheduleTaskUpdateDto.getLockTime().getTime()));
        } else {
            scheduleTaskDto.setMaxExecTimes(Long.MAX_VALUE);
        }
        scheduleTaskApiService.updateById(scheduleTaskDto);
        return ReqResult.success();
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

    private void checkTenantId(ScheduleTaskDto scheduleTaskDto1) {
        if (!scheduleTaskDto1.getTenantId().equals(RequestContext.get().getTenantId())) {
            throw new BizException("无操作权限");
        }
    }

    @RequireResource(TASK_MANAGE_DELETE)
    @Operation(summary = "删除任务")
    @RequestMapping(path = "/delete/{id}", method = RequestMethod.POST)
    public ReqResult<Void> delete(@PathVariable Long id) {
        ScheduleTaskDto scheduleTaskDto1 = scheduleTaskApiService.queryById(id);
        if (scheduleTaskDto1 == null) {
            return ReqResult.error("任务不存在");
        }
        checkTenantId(scheduleTaskDto1);
        scheduleTaskApiService.deleteById(id);
        return ReqResult.success();
    }

    @RequireResource(TASK_MANAGE_EXECUTE_MANUAL)
    @Operation(summary = "执行任务")
    @RequestMapping(path = "/execute/{id}", method = RequestMethod.POST)
    public ReqResult<Void> execute(@PathVariable Long id) {
        ScheduleTaskDto scheduleTaskDto1 = scheduleTaskApiService.queryById(id);
        if (scheduleTaskDto1 == null) {
            return ReqResult.error("任务不存在");
        }
        checkTenantId(scheduleTaskDto1);
        scheduleTaskApiService.updateToExecuteNow(id);
        return ReqResult.success();
    }

    @RequireResource(TASK_MANAGE_CANCEL)
    @Operation(summary = "取消任务")
    @RequestMapping(path = "/cancel/{id}", method = RequestMethod.POST)
    public ReqResult<Void> cancel(@PathVariable Long id) {
        ScheduleTaskDto scheduleTaskDto1 = scheduleTaskApiService.queryById(id);
        if (scheduleTaskDto1 == null) {
            return ReqResult.error("任务不存在");
        }
        checkTenantId(scheduleTaskDto1);
        ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
        scheduleTaskDto.setId(id);
        scheduleTaskDto.setStatus(ScheduleTaskDto.Status.CANCEL);
        scheduleTaskApiService.updateById(scheduleTaskDto);
        return ReqResult.success();
    }

    @RequireResource(TASK_MANAGE_ENABLE)
    @Operation(summary = "启用任务")
    @RequestMapping(path = "/enable/{id}", method = RequestMethod.POST)
    public ReqResult<Void> enable(@PathVariable Long id) {
        ScheduleTaskDto scheduleTaskDto1 = scheduleTaskApiService.queryById(id);
        if (scheduleTaskDto1 == null) {
            return ReqResult.error("任务不存在");
        }
        checkTenantId(scheduleTaskDto1);
        ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
        scheduleTaskDto.setId(id);
        scheduleTaskDto.setStatus(ScheduleTaskDto.Status.CONTINUE);
        scheduleTaskApiService.updateById(scheduleTaskDto);
        return ReqResult.success();
    }

    @RequireResource(TASK_MANAGE_QUERY_LIST)
    @Operation(summary = "查询空间下任务列表")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<Page<ScheduleTaskDto>> list(@RequestBody ManageQueryRequest request) {
        completeCreatorIds(request);
        Page<ScheduleTaskDto> page = scheduleTaskApiService.pageQuery(request.getName(), request.getCreatorIds(), request.getPageNo(), request.getPageSize());
        List<ScheduleTaskDto> scheduleTaskDtoList = page.getRecords();
        for (ScheduleTaskDto scheduleTaskDto : scheduleTaskDtoList) {
            checkTenantId(scheduleTaskDto);
            break;
        }
        //获取类型为Agent的任务列表
        List<ScheduleTaskDto> agentTaskDtoList = scheduleTaskDtoList.stream().filter(scheduleTaskDto -> scheduleTaskDto.getTargetType().equals(Published.TargetType.Agent.name())).toList();
        //获取targetIds
        List<Long> agentIds = agentTaskDtoList.stream().map(scheduleTaskDto -> Long.valueOf(scheduleTaskDto.getTargetId())).toList();
        List<PublishedDto> publishedDtoList = publishApplicationService.queryPublishedList(Published.TargetType.Agent, agentIds);
        //转agentId为key的map
        Map<Long, PublishedDto> agentDtoMap = publishedDtoList.stream().collect(Collectors.toMap(PublishedDto::getTargetId, publishedDto -> publishedDto, (v1, v2) -> v1));
        //同理获取Workflow的map
        //wfPublishedDtoList
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
        return ReqResult.success(page);
    }

    @Operation(summary = "可选定时范围")
    @RequestMapping(path = "/cron/list", method = RequestMethod.GET)
    public ReqResult<List<TaskCron.CronDto>> cronList() {
        return ReqResult.success(TaskCron.getTaskCronList());
    }
}
