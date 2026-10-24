package com.xspaceagi.system.domain.service;


import java.util.Date;

import com.xspaceagi.system.infra.dao.entity.ScheduleTask;

public interface ScheduleTaskDomainService {

    Long start(ScheduleTask scheduleTask);

    void update(ScheduleTask scheduleTask);

    void complete(String taskId);

    void cancel(String taskId);

    /**
     * 清理终态调度行（COMPLETE / OVERFLOW_MAX_EXEC_TIMES / CANCEL）中创建早于 deadline 的，
     * 分批物理删除。FAIL 仍会被引擎重试、EXECUTING 重启后恢复，均不清理。
     *
     * @return 实际删除行数
     */
    int cleanupTerminalBefore(Date deadline);
}
