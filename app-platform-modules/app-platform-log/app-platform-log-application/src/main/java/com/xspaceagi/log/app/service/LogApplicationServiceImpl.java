package com.xspaceagi.log.app.service;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.log.sdk.request.DocumentSearchRequest;
import com.xspaceagi.log.sdk.service.ILogRpcService;
import com.xspaceagi.log.sdk.service.ISearchRpcService;
import com.xspaceagi.log.sdk.vo.LogDocument;
import com.xspaceagi.log.sdk.vo.SearchDocument;
import com.xspaceagi.log.sdk.vo.SearchResult;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service("logApplicationService")
public class LogApplicationServiceImpl extends AbstractTaskExecuteService implements ILogRpcService {

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private ISearchRpcService iSearchRpcService;

    @PostConstruct
    public void init() {
        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("logApplicationService")
                .beanId("logApplicationService")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERY_2_SECOND.getCron())
                .params(Map.of())
                .build());
    }

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTaskDto) {
        try {
            checkAndPushLogDocument0();
        } catch (Exception e) {
            log.error("checkAndPushLogDocument0 error", e);
        }
        return false;
    }

    private void checkAndPushLogDocument0() {
        List<LogDocument> logDocumentList = new ArrayList<>();
        Object val = redisUtil.rightPop("log:queue");
        while (val != null) {
            LogDocument logDocument = JSON.parseObject(val.toString(), LogDocument.class);
            if (logDocument != null) {
                logDocumentList.add(logDocument);
            }
            val = redisUtil.rightPop("log:queue");
        }
        if (CollectionUtils.isNotEmpty(logDocumentList)) {
            log.info("checkAndPushLogDocument0 logDocumentList size: {}", logDocumentList.size());
            iSearchRpcService.bulkIndex(logDocumentList.stream().map(logDocument -> (SearchDocument) logDocument).collect(Collectors.toList()));
        }
    }

    @Override
    public void deleteLogDocument(String id) {
        iSearchRpcService.deleteDocument(LogDocument.class, id);
    }

    public void bulkIndex(List<LogDocument> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        list.forEach(item -> redisUtil.rightPush("log:queue", JSON.toJSONString(item)));
    }

    @Override
    public void pushTraceLog(Object traceContext0) {
        if (traceContext0 == null) {
            return;
        }
        redisUtil.rightPush("log:queue", JSON.toJSONString(((TraceContext) traceContext0).getLog()));
        ((TraceContext) traceContext0).setLog(null);
        redisUtil.rightPush("bill:queue", JSON.toJSONString(traceContext0));
    }

    public SearchResult search(DocumentSearchRequest documentSearchRequest) {
        return iSearchRpcService.search(documentSearchRequest);
    }

}
