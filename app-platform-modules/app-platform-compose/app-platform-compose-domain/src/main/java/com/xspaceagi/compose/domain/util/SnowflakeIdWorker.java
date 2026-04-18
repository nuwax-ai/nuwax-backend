package com.xspaceagi.compose.domain.util;

import cn.hutool.core.lang.Snowflake;
import cn.hutool.core.util.IdUtil;
import com.xspaceagi.system.sdk.service.AbstractTaskExecuteService;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 雪花算法的工具类,用于生成主键id,给doris设置主键id使用
 */
@Component("snowflakeIdWorker")
@Slf4j
public class SnowflakeIdWorker extends AbstractTaskExecuteService {

    private static Snowflake snowflake;
    private static Long workerId = null;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    private static final String REDIS_WORKER_ID_KEY_PREFIX = "snowflake:worker:id:";
    private static final int MAX_WORKER_ID = 31; // 支持 0~31 共32个节点
    private static final long DATACENTER_ID = 1L;
    private static final long WORKER_ID_EXPIRE_SECONDS = 180; // 3分钟


    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;

    @PostConstruct
    public void init() {
        try {
            for (int i = 0; i <= MAX_WORKER_ID; i++) {
                Boolean success = null;
                try {
                    success = stringRedisTemplate.opsForValue().setIfAbsent(
                            REDIS_WORKER_ID_KEY_PREFIX + i,
                            "1",
                            WORKER_ID_EXPIRE_SECONDS,
                            TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Redis 不可用，直接抛出异常，应用拒绝启动
                    log.error("[SnowflakeIdWorker] Redis workerId allocation failed", e);
                    throw new RuntimeException("Redis 分配 workerId 失败", e);
                }
                if (Boolean.TRUE.equals(success)) {
                    workerId = (long) i;
                    break;
                }
            }
            if (workerId == null) {
                throw new RuntimeException("没有可用的workerId，请检查是否有节点未释放workerId或增大MAX_WORKER_ID");
            }
            // 定时续约（可选，防止服务长时间运行workerId过期）
            // 你可以用定时任务每隔一段时间刷新自己的workerId过期时间

            snowflake = IdUtil.getSnowflake(workerId, DATACENTER_ID);
        } catch (Exception e) {
            // 启动时分配失败，应用拒绝启动
            log.error("[SnowflakeIdWorker] init failed", e);
            throw e;
        }

        scheduleTaskApiService.start(ScheduleTaskDto.builder()
                .taskId("snowflakeIdWorker")
                .beanId("snowflakeIdWorker")
                .maxExecTimes(Long.MAX_VALUE)
                .cron(ScheduleTaskDto.Cron.EVERY_30_SECOND.getCron())
                .params(Map.of())
                .build());
    }

    @Override
    protected boolean execute(ScheduleTaskDto scheduleTaskDto) {
        try {
            refreshWorkerIdExpire();
        } catch (Exception e) {
            log.error("[SnowflakeIdWorker] heartbeat renew failed", e);
        }
        return false;
    }

    public void refreshWorkerIdExpire() {
        if (workerId != null) {
            try {
                stringRedisTemplate.expire(
                        REDIS_WORKER_ID_KEY_PREFIX + workerId,
                        WORKER_ID_EXPIRE_SECONDS,
                        TimeUnit.SECONDS);
            } catch (Exception e) {
                // 只记录日志，不抛出异常，防止定时任务线程崩溃
                log.error("[SnowflakeIdWorker] heartbeat Redis failed", e);
                // 可选：报警
            }
        }
    }

    // 优雅停机时主动释放workerId（可选）
    @PreDestroy
    public void releaseWorkerId() {
        if (workerId != null) {
            try {
                stringRedisTemplate.delete(REDIS_WORKER_ID_KEY_PREFIX + workerId);
            } catch (Exception e) {
                // 只记录日志，不影响主流程
                log.error("[SnowflakeIdWorker] release workerId", e);
            }
        }
    }

    /**
     * 生成雪花算法ID
     *
     * @return 雪花算法ID
     */
    public static long nextId() {
        return snowflake.nextId();
    }

    /**
     * 生成雪花算法ID字符串
     *
     * @return 雪花算法ID字符串
     */
    public static String nextIdStr() {
        return snowflake.nextIdStr();
    }
}
