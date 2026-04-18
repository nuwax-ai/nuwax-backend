package com.xspaceagi.knowledge.api.task;

import com.xspaceagi.knowledge.domain.service.IKnowledgeTaskArchiveAndRetryDomainService;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component("knowledgeAutoArchiveTask")
public class KnowledgeAutoArchiveTask extends AbstractTaskExecuteService {

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private IKnowledgeTaskArchiveAndRetryDomainService knowledgeTaskArchiveAndRetryDomainService;

    @PostConstruct
    public void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("knowledgeAutoArchiveTask")
                .beanId("knowledgeAutoArchiveTask")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERYDAY_0_2.getCron())
                .params(Map.of())
                .build());
    }

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTaskDto) {
        log.info("knowledgeAutoArchiveTask 自动运行任务开始执行");
        try {
            this.knowledgeTaskArchiveAndRetryDomainService.archiveTask(15);
            log.info("knowledgeAutoArchiveTask 自动运行任务执行结束");
        } catch (Exception e) {
            log.error("Auto-run task failed", e);
        }
        return false;//false会一直循环执行
    }
}
