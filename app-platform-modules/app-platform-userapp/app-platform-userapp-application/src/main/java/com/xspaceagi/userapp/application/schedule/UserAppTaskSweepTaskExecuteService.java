package com.xspaceagi.userapp.application.schedule;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.userapp.domain.model.UserAppTaskModel;
import com.xspaceagi.userapp.domain.service.IUserAppTaskDomainService;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

/**
 * userapp_task 兜底与清理任务（每 10 分钟一次）：
 * 1. 超龄活跃行强制置 failed：兜住"行已落库但轮询调度缺失"（进程在插行与注册调度间崩溃等），
 *    否则 active_guard 槽位被永久占用，应用无法再发起任务；
 * 2. 超龄终态行物理删除：控制 userapp_task 增长。
 * 对应的轮询调度行不在此处理，由 system 模块的全局终态调度清理统一回收。
 * 单行失败不影响其余行。
 */
@Slf4j
@Service(UserAppTaskSweepTaskExecuteService.BEAN_ID)
public class UserAppTaskSweepTaskExecuteService extends AbstractTaskExecuteService {

    public static final String BEAN_ID = "userAppTaskSweepTaskExecuteService";

    public static final String TASK_ID = "userapp-task-sweep";

    /** 活跃行兜底超时：轮询上限 40 分钟（MAX_POLL_TICKS=480 × 5s）+ 余量 */
    private static final long ACTIVE_TIMEOUT_MILLIS = 50 * 60_000L;

    /** 终态行保留期：3 天 */
    private static final long TERMINAL_RETENTION_MILLIS = 3 * 24 * 60 * 60_000L;

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;
    @Resource
    private IUserAppTaskDomainService userAppTaskDomainService;

    @PostConstruct
    public void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId(TASK_ID)
                .beanId(BEAN_ID)
                .cron(ScheduleTaskDto.Cron.EVERY_10_MINUTE.getCron())
                .maxExecTimes(Long.MAX_VALUE)
                .params(Map.of())
                .taskName("UserApp任务兜底与清理")
                .build());
        log.info("Userapp task sweep registered, taskId={}, cron={}", TASK_ID,
                ScheduleTaskDto.Cron.EVERY_10_MINUTE.getCron());
    }

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTask) {
        long now = System.currentTimeMillis();
        int forceFailed = 0;
        List<UserAppTaskModel> overdueActive = userAppTaskDomainService.findOverdueActive(new Date(now - ACTIVE_TIMEOUT_MILLIS));
        for (UserAppTaskModel row : overdueActive) {
            try {
                // 扫描跨租户：逐行恢复租户上下文，写操作带租户过滤
                RequestContext.setThreadTenantId(row.getTenantId());
                userAppTaskDomainService.markFailed(row.getId(), "任务状态超时（轮询调度缺失兜底）");
                forceFailed++;
                log.warn("[userapp-task-sweep] force fail overdue active row, id={}, appId={}, status={}",
                        row.getId(), row.getAppId(), row.getStatus());
            } catch (Exception e) {
                log.error("[userapp-task-sweep] force fail failed, retry next round, id={}", row.getId(), e);
            } finally {
                RequestContext.remove();
            }
        }

        int purged = 0;
        List<UserAppTaskModel> overdueTerminal = userAppTaskDomainService.findOverdueTerminal(new Date(now - TERMINAL_RETENTION_MILLIS));
        for (UserAppTaskModel row : overdueTerminal) {
            try {
                RequestContext.setThreadTenantId(row.getTenantId());
                // 仅删任务行；对应的轮询调度行由 system 模块的全局终态调度清理统一回收
                userAppTaskDomainService.deleteById(row.getId());
                purged++;
            } catch (Exception e) {
                log.error("[userapp-task-sweep] purge failed, retry next round, id={}", row.getId(), e);
            } finally {
                RequestContext.remove();
            }
        }
        if (forceFailed + purged > 0) {
            log.info("[userapp-task-sweep] done, forceFailed={}, purged={}", forceFailed, purged);
        }
        return false;
    }
}
