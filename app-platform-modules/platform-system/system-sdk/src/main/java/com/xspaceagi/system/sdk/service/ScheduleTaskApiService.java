package com.xspaceagi.system.sdk.service;


import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;

import java.util.List;

public interface ScheduleTaskApiService {

    Long start(ScheduleTaskDto scheduleTaskDto);

    void complete(String taskId);

    void cancel(String taskId);

    void update(ScheduleTaskDto scheduleTaskDto);

    void updateById(ScheduleTaskDto scheduleTaskDto);

    void updateToExecuteNow(Long id);

    ScheduleTaskDto queryById(Long id);

    void deleteById(Long id);

    List<ScheduleTaskDto> queryTaskListBySpaceId(Long spaceId);

    List<ScheduleTaskDto> queryTaskListByUserIdAndAgentId(Long userId, String agentId);

    /**
     * 分页查询任务列表（时间倒序）
     *
     * @param current  当前页
     * @param pageSize 每页大小
     * @return 分页结果
     */
    Page<ScheduleTaskDto> pageQuery(String name, List<Long> creators, Integer current, Integer pageSize);

    Long countUserTotalTasks(Long userId);
}
