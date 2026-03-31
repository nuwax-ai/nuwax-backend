package com.xspaceagi.system.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.domain.service.ScheduleTaskDomainService;
import com.xspaceagi.system.infra.dao.entity.ScheduleTask;
import com.xspaceagi.system.infra.dao.service.ScheduleTaskService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.xspaceagi.system.domain.service.impl.ScheduleTaskDomainServiceImpl.getNextExecDate;

@Slf4j
@Service
public class ScheduleTaskApplicationServiceImpl implements ScheduleTaskApiService {

    @Resource
    private ScheduleTaskDomainService scheduleTaskDomainService;

    @Resource
    private ScheduleTaskService scheduleTaskService;

    @Resource
    private UserApplicationService userApplicationService;

    @Override
    public Long start(ScheduleTaskDto scheduleTaskDto) {
        ScheduleTask scheduleTask = new ScheduleTask();
        BeanUtils.copyProperties(scheduleTaskDto, scheduleTask);
        scheduleTask.setTaskId(scheduleTaskDto.getTaskId());
        scheduleTask.setBeanId(scheduleTaskDto.getBeanId());
        scheduleTask.setCron(scheduleTaskDto.getCron());
        scheduleTask.setParams(scheduleTaskDto.getParams());
        scheduleTask.setMaxExecTimes(scheduleTaskDto.getMaxExecTimes());
        scheduleTask.setStatus(com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto.Status.CREATE);
        scheduleTask.setExecTimes(0L);
        try {
            return scheduleTaskDomainService.start(scheduleTask);
        } catch (Throwable e) {
            log.warn("start task error", e);
            return 0L;
        }
    }

    @Override
    public void update(ScheduleTaskDto scheduleTaskDto) {
        ScheduleTask scheduleTask = new ScheduleTask();
        scheduleTask.setTaskId(scheduleTaskDto.getTaskId());
        scheduleTask.setTaskId(scheduleTaskDto.getTaskId());
        scheduleTask.setBeanId(scheduleTaskDto.getBeanId());
        scheduleTask.setCron(scheduleTaskDto.getCron());
        scheduleTask.setParams(scheduleTaskDto.getParams());
        scheduleTask.setMaxExecTimes(scheduleTaskDto.getMaxExecTimes());
        scheduleTask.setLockTime(scheduleTaskDto.getLockTime());
        scheduleTaskDomainService.update(scheduleTask);
    }

    @Override
    public void updateById(ScheduleTaskDto scheduleTaskDto) {
        Assert.notNull(scheduleTaskDto.getId(), "id must be non-null");
        ScheduleTask scheduleTask = new ScheduleTask();
        BeanUtils.copyProperties(scheduleTaskDto, scheduleTask);
        scheduleTask.setBeanId(null);
        scheduleTask.setLockTime(null);
        scheduleTask.setCreatorId(null);
        scheduleTask.setExecTimes(null);
        scheduleTask.setMaxExecTimes(null);
        scheduleTask.setLatestExecTime(null);
        scheduleTask.setSpaceId(null);
        scheduleTask.setTenantId(null);
        if (scheduleTaskDto.getCron() != null) {
            scheduleTask.setLockTime(getNextExecDate(scheduleTaskDto.getCron()));
        }
        scheduleTaskService.updateById(scheduleTask);
    }

    @Override
    public void complete(String taskId) {
        scheduleTaskDomainService.complete(taskId);
    }

    @Override
    public void cancel(String taskId) {
        scheduleTaskDomainService.cancel(taskId);
    }

    @Override
    public void deleteById(Long id) {
        scheduleTaskService.removeById(id);
    }

    @Override
    public List<ScheduleTaskDto> queryTaskListBySpaceId(Long spaceId) {
        LambdaQueryWrapper<ScheduleTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ScheduleTask::getSpaceId, spaceId);
        queryWrapper.orderByDesc(ScheduleTask::getId);
        List<ScheduleTask> scheduleTaskList = scheduleTaskService.list(queryWrapper);
        //提取scheduleTaskList中creatorIds
        List<Long> creatorIds = scheduleTaskList.stream().map(ScheduleTask::getCreatorId).toList();
        Map<Long, UserDto> userDtoMap = userApplicationService.queryUserListByIds(creatorIds).stream().collect(Collectors.toMap(UserDto::getId, user -> user, (user1, user2) -> user1));
        return scheduleTaskList.stream().map(scheduleTask -> {
            ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
            BeanUtils.copyProperties(scheduleTask, scheduleTaskDto);
            if (scheduleTask.getCreatorId() != null && userDtoMap.containsKey(scheduleTask.getCreatorId())) {
                scheduleTaskDto.setCreator(ScheduleTaskDto.Creator.builder()
                        .userId(userDtoMap.get(scheduleTask.getCreatorId()).getId())
                        .userName(userDtoMap.get(scheduleTask.getCreatorId()).getUserName())
                        .avatar(userDtoMap.get(scheduleTask.getCreatorId()).getAvatar())
                        .build());
            }
            return scheduleTaskDto;
        }).collect(Collectors.toList());
    }

    @Override
    public void updateToExecuteNow(Long id) {
        ScheduleTask scheduleTask = new ScheduleTask();
        scheduleTask.setId(id);
        scheduleTask.setStatus(ScheduleTaskDto.Status.CONTINUE);
        scheduleTask.setLockTime(new Date());
        scheduleTaskService.updateById(scheduleTask);
    }

    @Override
    public ScheduleTaskDto queryById(Long id) {
        ScheduleTask scheduleTask = scheduleTaskService.getById(id);
        if (scheduleTask != null) {
            ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
            BeanUtils.copyProperties(scheduleTask, scheduleTaskDto);
            return scheduleTaskDto;
        }
        return null;
    }

    @Override
    public Page<ScheduleTaskDto> pageQuery(String name, List<Long> creators, Integer current, Integer pageSize) {
        LambdaQueryWrapper<ScheduleTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.like(StringUtils.isNotBlank(name), ScheduleTask::getTaskName, name);
        queryWrapper.in(creators != null && !creators.isEmpty(), ScheduleTask::getCreatorId, creators);
        queryWrapper.eq(ScheduleTask::getTenantId, RequestContext.get().getTenantId());
        queryWrapper.orderByDesc(ScheduleTask::getModified);

        Page<ScheduleTask> page = scheduleTaskService.page(new Page<>(current, pageSize), queryWrapper);

        //提取scheduleTaskList中creatorIds
        List<ScheduleTask> scheduleTaskList = page.getRecords();
        List<Long> creatorIds = scheduleTaskList.stream().map(ScheduleTask::getCreatorId).toList();
        Map<Long, UserDto> userDtoMap = userApplicationService.queryUserListByIds(creatorIds).stream().collect(Collectors.toMap(UserDto::getId, user -> user, (user1, user2) -> user1));

        Page<ScheduleTaskDto> dtoPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<ScheduleTaskDto> dtoList = scheduleTaskList.stream().map(scheduleTask -> {
            ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
            BeanUtils.copyProperties(scheduleTask, scheduleTaskDto);
            if (scheduleTask.getCreatorId() != null && userDtoMap.containsKey(scheduleTask.getCreatorId())) {
                scheduleTaskDto.setCreator(ScheduleTaskDto.Creator.builder()
                        .userId(userDtoMap.get(scheduleTask.getCreatorId()).getId())
                        .userName(userDtoMap.get(scheduleTask.getCreatorId()).getUserName())
                        .avatar(userDtoMap.get(scheduleTask.getCreatorId()).getAvatar())
                        .build());
            }
            return scheduleTaskDto;
        }).collect(Collectors.toList());
        dtoPage.setRecords(dtoList);
        return dtoPage;
    }

    @Override
    public Long countUserTotalTasks(Long userId) {
        LambdaQueryWrapper<ScheduleTask> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ScheduleTask::getCreatorId, userId);
        queryWrapper.gt(ScheduleTask::getSpaceId, 0);
        return scheduleTaskService.count(queryWrapper);
    }
}
