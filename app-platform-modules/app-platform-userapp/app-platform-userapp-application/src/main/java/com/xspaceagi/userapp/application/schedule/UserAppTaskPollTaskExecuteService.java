package com.xspaceagi.userapp.application.schedule;

import java.util.Map;

import org.springframework.stereotype.Service;

import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.userapp.application.service.IUserAppTaskApplicationService;
import com.xspaceagi.userapp.domain.service.IUserAppTaskDomainService;
import com.xspaceagi.userapp.domain.service.UserAppTaskPollOutcome;

import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service(UserAppTaskPollRunner.TASK_BEAN_ID)
public class UserAppTaskPollTaskExecuteService extends AbstractTaskExecuteService {

    @Resource
    private IUserAppTaskDomainService userAppTaskDomainService;

    @Resource
    private IUserAppTaskApplicationService userAppTaskApplicationService;

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTask) {
        Long rowId = resolveLongParam(scheduleTask.getParams(), "rowId");
        if (rowId == null) {
            log.error("[userapp-task-poll] schedule task params missing rowId, stop task. taskId={}", scheduleTask.getTaskId());
            return true;
        }
        Long tenantId = resolveLongParam(scheduleTask.getParams(), "tenantId");
        if (tenantId == null) {
            // 数据安全红线：无租户上下文一律拒绝执行，绝不回退到无租户过滤查询
            log.error("[userapp-task-poll] schedule task params missing tenantId, stop task. taskId={}, rowId={}", scheduleTask.getTaskId(), rowId);
            return true;
        }
        long maxExec = scheduleTask.getMaxExecTimes() != null ? scheduleTask.getMaxExecTimes() : UserAppTaskPollRunner.MAX_POLL_TICKS;
        long execTimes = scheduleTask.getExecTimes() != null ? scheduleTask.getExecTimes() : 0L;
        try {
            RequestContext.setThreadTenantId(tenantId);
            UserAppTaskPollOutcome outcome = userAppTaskDomainService.pollOnce(rowId, (int) execTimes,
                    (int) maxExec);
            if (outcome.isBuildArtifactReady()) {
                // BUILD 任务下游已完成：归档产物（下载→上传文件服务→记发布版本）后终结任务；
                // 归档失败返回 false，任务行保持 running 由下轮重试
                boolean archived;
                try {
                    archived = userAppTaskApplicationService.processBuildArtifact(rowId);
                } catch (Exception e) {
                    log.error("[userapp-task-poll] process build artifact error, retry next tick. rowId={}", rowId, e);
                    archived = false;
                }
                if (!archived && execTimes + 1 >= maxExec) {
                    // 轮询末次仍归档未成：强制置终态，防止行随调度结束永久挂起占用守卫槽位
                    //（调度引擎对返回 false 且次数耗尽的任务直接 COMPLETE，不再有下轮）
                    userAppTaskDomainService.finalizeBuild(rowId, "构建产物处理超时");
                    return true;
                }
                return archived;
            }
            return outcome.isFinished();
        } finally {
            RequestContext.remove();
        }
    }

    private static Long resolveLongParam(Object params, String key) {
        if (params instanceof Map<?, ?> map) {
            Object v = map.get(key);
            if (v instanceof Number n) {
                return n.longValue();
            }
            if (v instanceof String s && !s.isBlank()) {
                try {
                    return Long.parseLong(s.trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
