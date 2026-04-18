package com.xspaceagi.knowledge.api.task;

import com.xspaceagi.knowledge.core.application.service.IKnowledgeQaSegmentApplicationService;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@Component("knowledgeAutoGenerateQaEmbeddingsTask")
public class KnowledgeAutoGenerateQaEmbeddingsTask extends AbstractTaskExecuteService {

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private IKnowledgeQaSegmentApplicationService knowledgeQaSegmentApplicationService;

    @PostConstruct
    public void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("knowledgeAutoGenerateQaEmbeddingsTask")
                .beanId("knowledgeAutoGenerateQaEmbeddingsTask")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERY_5_MINUTE.getCron())
                .params(Map.of())
                .build());
    }

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTaskDto) {
        log.info("knowledgeAutoGenerateQaEmbeddingsTask 自动运行任务开始执行");
        try {
            this.autoGenerateQaEmbeddings();
            log.info("knowledgeAutoGenerateQaEmbeddingsTask 自动运行任务执行结束");
        } catch (Exception e) {
            log.error("Auto-run task failed", e);
        }
        return false;//false会一直循环执行
    }

    public void autoGenerateQaEmbeddings() {
        Integer reqId = ThreadLocalRandom.current().nextInt(100, 999999);
        MDC.put("tid", reqId + "" + Instant.now().toEpochMilli());

        // 分页查询,直到没有数据为止
        int pageNum = 1;
        int pageSize = 500;
        while (true) {
            var dataList = this.knowledgeQaSegmentApplicationService.queryListForEmbeddingQaAndEmbeddings(1,
                    pageSize,
                    pageNum);
            if (dataList.isEmpty()) {
                break;
            }
            pageNum++;
        }

    }
}
