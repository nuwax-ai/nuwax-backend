package com.xspaceagi.system.application.schedule;

import java.util.Date;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.xspaceagi.system.domain.service.ScheduleTaskDomainService;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

/**
 * schedule_task 终态行全局清理（每天凌晨 4 点，与 3 点的既有清理任务错峰）：
 * 物理删除终态（COMPLETE / OVERFLOW_MAX_EXEC_TIMES / CANCEL）且创建超过保留期的调度行
 */
@Slf4j
@Service(ScheduleTaskCleanupTaskExecuteService.BEAN_ID)
public class ScheduleTaskCleanupTaskExecuteService extends AbstractTaskExecuteService {

    public static final String BEAN_ID = "scheduleTaskCleanupTaskExecuteService";

    public static final String TASK_ID = "schedule-task-terminal-cleanup";

    /** 终态行保留期：3 天 */
    private static final long TERMINAL_RETENTION_MILLIS = 3 * 24 * 60 * 60_000L;

    /** 凌晨 4 点 */
    private static final String CRON_EVERYDAY_4AM = "0 0 4 * * ?";

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;
    @Resource
    private ScheduleTaskDomainService scheduleTaskDomainService;

    @PostConstruct
    public void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId(TASK_ID)
                .beanId(BEAN_ID)
                .cron(CRON_EVERYDAY_4AM)
                .maxExecTimes(Long.MAX_VALUE)
                .params(Map.of())
                .taskName("调度任务终态清理")
                .build());
        log.info("Schedule task terminal cleanup registered, taskId={}, cron={}", TASK_ID, CRON_EVERYDAY_4AM);
    }

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTask) {
        int purged = scheduleTaskDomainService.cleanupTerminalBefore(
                new Date(System.currentTimeMillis() - TERMINAL_RETENTION_MILLIS));
        if (purged > 0) {
            log.info("[schedule-task-cleanup] purged {} terminal schedule rows", purged);
        }
        return false;
    }
}
