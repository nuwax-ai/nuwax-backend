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
import org.springframework.beans.factory.annotation.Value;
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

    private static final String LOG_QUEUE_KEY = "log:queue";
    private static final String BILL_QUEUE_KEY = "bill:queue";

    /** 队列最多保留的日志条数，超出后丢弃最老的（队头）日志 */
    @Value("${log.queue.max-size:100}")
    private int maxQueueSize = 100;

    /** 单次调度最多消费的条数，避免一次拉空队列 */
    @Value("${log.queue.drain-batch-size:100}")
    private int drainBatchSize = 100;

    /** 单次 bulk 的估算字节上限，避免 ES 请求体过大 */
    @Value("${log.queue.max-batch-bytes:8388608}")
    private long maxBatchBytes = 8 * 1024 * 1024;

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
        long estimatedBytes = 0L;
        // 只消费有限条数，避免历史积压时一次性把所有文档读进堆
        while (logDocumentList.size() < Math.max(1, drainBatchSize)) {
            Object val = redisUtil.leftPop(LOG_QUEUE_KEY);
            if (val == null) {
                break;
            }
            LogDocument logDocument;
            try {
                logDocument = JSON.parseObject(val.toString(), LogDocument.class);
            } catch (Exception e) {
                // 单条脏数据不能阻断整批，直接丢弃
                log.warn("discard invalid log document from queue", e);
                continue;
            }
            if (logDocument == null) {
                continue;
            }
            logDocumentList.add(logDocument);
            estimatedBytes += estimateBytes(logDocument);
            // 按估算字节数提前截断，控制单次 bulk 的请求体大小
            if (estimatedBytes >= maxBatchBytes) {
                break;
            }
        }
        if (CollectionUtils.isEmpty(logDocumentList)) {
            return;
        }
        log.info("checkAndPushLogDocument0 drained size: {}, estimatedBytes: {}", logDocumentList.size(), estimatedBytes);
        try {
            iSearchRpcService.bulkIndex(logDocumentList.stream()
                    .map(logDocument -> (SearchDocument) logDocument)
                    .collect(Collectors.toList()));
        } catch (Exception e) {
            // 已经出队的数据重新入队，避免 ES 抖动时整批丢失；再次入队仍受队列上限约束
            log.error("bulkIndex failed, requeue {} documents", logDocumentList.size(), e);
            logDocumentList.forEach(this::requeueLogDocument);
        }
    }

    private void requeueLogDocument(LogDocument logDocument) {
        try {
            enqueueLogDocument(logDocument);
        } catch (Exception e) {
            log.error("requeue log document failed, id={}", logDocument.getId(), e);
        }
    }

    /**
     * 写入日志队列，并裁剪到最大长度：rightPush 写入队尾（最新），
     * trim(-maxQueueSize, -1) 保留最后 maxQueueSize 条，即丢弃最老的。
     */
    private void enqueueLogDocument(LogDocument logDocument) {
        redisUtil.rightPush(LOG_QUEUE_KEY, JSON.toJSONString(logDocument));
        redisUtil.trim(LOG_QUEUE_KEY, -Math.max(1, maxQueueSize), -1);
    }

    private static long estimateBytes(LogDocument logDocument) {
        return length(logDocument.getInput()) + length(logDocument.getOutput())
                + length(logDocument.getProcessData()) + 512L;
    }

    private static long length(String text) {
        // Java 堆内 char 占 2 字节，这里按 2 倍估算，宁可提前分批
        return text == null ? 0L : (long) text.length() * 2L;
    }

    @Override
    public void deleteLogDocument(String id) {
        iSearchRpcService.deleteDocument(LogDocument.class, id);
    }

    public void bulkIndex(List<LogDocument> list) {
        if (CollectionUtils.isEmpty(list)) {
            return;
        }
        list.forEach(this::enqueueLogDocument);
    }

    @Override
    public void pushTraceLog(Object traceContext0) {
        if (traceContext0 == null) {
            return;
        }
        TraceContext traceContext = (TraceContext) traceContext0;
        LogDocument logDocument = (LogDocument) traceContext.getLog();
        if (logDocument == null) {
            return;
        }
        logDocument.setApiKey(TraceContext.maskApiKey(traceContext.getApiKey()));
        enqueueLogDocument(logDocument);
        ((TraceContext) traceContext0).setLog(null);
        // 计费队列不做丢弃，避免影响账单
        redisUtil.rightPush(BILL_QUEUE_KEY, JSON.toJSONString(traceContext0));
    }

    public SearchResult search(DocumentSearchRequest documentSearchRequest) {
        return iSearchRpcService.search(documentSearchRequest);
    }

}
