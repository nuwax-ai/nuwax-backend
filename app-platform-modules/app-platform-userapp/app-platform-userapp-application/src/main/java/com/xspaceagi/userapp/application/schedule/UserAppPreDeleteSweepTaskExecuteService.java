package com.xspaceagi.userapp.application.schedule;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.userapp.application.service.IUserAppApplicationService;
import com.xspaceagi.userapp.sdk.IUserAppRpcService;
import com.xspaceagi.userapp.sdk.dto.UserAppDto;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;

import lombok.extern.slf4j.Slf4j;

/**
 * UserApp 预删除清理任务（每天夜里执行一次）
 * 单个项目失败不影响其余项目。
 */
@Slf4j
@Service(UserAppPreDeleteSweepTaskExecuteService.BEAN_ID)
public class UserAppPreDeleteSweepTaskExecuteService extends AbstractTaskExecuteService {

    public static final String BEAN_ID = "userAppPreDeleteSweepTaskExecuteService";

    public static final String TASK_ID = "userapp-pre-delete-sweep";

    /** 凌晨 3 点 */
    private static final String CRON_EVERYDAY_3AM = "0 0 3 * * ?";

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;
    @Resource
    private IUserAppRpcService userAppRpcService;
    @Resource
    private IUserAppApplicationService userAppApplicationService;

    @PostConstruct
    public void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId(TASK_ID)
                .beanId(BEAN_ID)
                .cron(CRON_EVERYDAY_3AM)
                .maxExecTimes(Long.MAX_VALUE)
                .params(Map.of())
                .taskName("UserApp预删除清理")
                .build());
        log.info("Userapp pre-delete sweep task registered, taskId={}, cron={}", TASK_ID, CRON_EVERYDAY_3AM);
    }

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTask) {
        List<UserAppDto> dueProjects = userAppRpcService.listPreDeleteDue();
        int purged = 0;
        for (UserAppDto project : dueProjects) {
            try {
                // 扫描跨租户：逐行恢复项目租户上下文，物理删除/会话查询都带租户过滤
                RequestContext.setThreadTenantId(project.getTenantId());
                // 先清理工作目录，成功再物理删除行：行删除失败可整体重试，避免产生无法重试的孤儿目录
                if (userAppApplicationService.cleanupWorkspace(project.getId())) {
                    userAppRpcService.purge(project.getId());
                    purged++;
                }
            } catch (Exception e) {
                log.error("[userapp-pre-delete-sweep] purge failed, retry next night, appId={}", project.getId(), e);
            } finally {
                RequestContext.remove();
            }
        }
        log.info("[userapp-pre-delete-sweep] done, due={}, purged={}", dueProjects.size(), purged);
        return false;
    }
}
