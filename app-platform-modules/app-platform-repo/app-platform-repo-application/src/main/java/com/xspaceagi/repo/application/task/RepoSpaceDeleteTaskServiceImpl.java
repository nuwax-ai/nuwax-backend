package com.xspaceagi.repo.application.task;

import com.xspaceagi.repo.application.service.RepoPageApplicationService;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 空间删除级联清理（repo 文档）：消费 {@code delete_space_repo_queue}，软删该空间下全部页面。
 * <p>
 * 与 system 侧 publish 清理用的 {@code delete_space_queue} 分离——Redis 队列是「抢」语义，
 * 两个消费者共用一个队列会漏处理，故删空间时同时 push 两条队列，各清各的领域。
 */
@Slf4j
@Service("repoSpaceDeleteTaskService")
public class RepoSpaceDeleteTaskServiceImpl extends AbstractTaskExecuteService {

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private RepoPageApplicationService repoPageApplicationService;

    @Resource
    private RedisUtil redisUtil;

    @PostConstruct
    private void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("repoSpaceDeleteTaskService")
                .beanId("repoSpaceDeleteTaskService")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERY_MINUTE.getCron())
                .params(Map.of())
                .build());
    }

    @Override
    public boolean execute(ScheduleTaskDto scheduleTask) {
        Set<Long> spaceIds = new HashSet<>();
        Object id = redisUtil.rightPop("delete_space_repo_queue");
        while (id != null) {
            Long spaceId = Long.valueOf(id.toString());
            if (!spaceIds.contains(spaceId)) {
                try {
                    repoPageApplicationService.deleteBySpaceId(spaceId);
                    spaceIds.add(spaceId);
                } catch (Exception e) {
                    // 失败仅告警：页面残留可由下轮队列重试或存储侧兜底清理
                    log.error("删除空间关联文档失败 spaceId={}", spaceId, e);
                }
            }
            id = redisUtil.rightPop("delete_space_repo_queue");
        }
        return false;
    }
}
