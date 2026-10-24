package com.xspaceagi.userapp.application.schedule;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

/**
 * 应用任务轮询注册器：任务受理前注册统一调度轮询，直到终态（成功/失败/取消）或超时。
 */
@Slf4j
@Service
public class UserAppTaskPollRunner {

    /** 轮询任务执行 bean */
    public static final String TASK_BEAN_ID = "userAppTaskPollTaskExecuteService";

    /** 轮询上限次数（EVERY_5_SECOND，480 次 = 40 分钟；须大于构建产物归档超时 35 分钟，保证归档兜底可达） */
    public static final int MAX_POLL_TICKS = 480;

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    /** 平台统一调度任务的 taskId（同一行重复 start 为幂等续跑） */
    public static String taskIdForRow(Long rowId) {
        return "userapp-task-" + rowId;
    }

    /**
     * 注册统一调度轮询任务（每 5 秒一次，最多 {@link #MAX_POLL_TICKS} 次，约 30 分钟）。
     * 必须在下游 HTTP 调用之前注册，进程崩溃时残留的 pending 行也能被轮询兜底置终态。
     */
    public void startPoll(Long rowId, Long tenantId) {
        Map<String, Object> params = new HashMap<>();
        params.put("rowId", rowId);
        if (tenantId != null) {
            params.put("tenantId", tenantId);
        }
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId(taskIdForRow(rowId))
                .beanId(TASK_BEAN_ID)
                .cron(ScheduleTaskDto.Cron.EVERY_5_SECOND.getCron())
                .maxExecTimes((long) MAX_POLL_TICKS)
                .params(params)
                .taskName("UserApp任务状态轮询")
                .targetType("USER_APP")
                .targetId(String.valueOf(rowId))
                .build());
        log.info("Userapp dev task poll registered, rowId={}, tenantId={}", rowId, tenantId);
    }
}
