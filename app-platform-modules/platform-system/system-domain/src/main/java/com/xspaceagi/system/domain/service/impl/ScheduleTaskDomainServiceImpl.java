package com.xspaceagi.system.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xspaceagi.system.domain.service.ScheduleTaskDomainService;
import com.xspaceagi.system.infra.dao.entity.ScheduleTask;
import com.xspaceagi.system.infra.dao.service.ScheduleTaskService;
import com.xspaceagi.system.sdk.retry.annotation.Retry;
import com.xspaceagi.system.sdk.service.TaskExecuteService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.quartz.CronExpression;
import org.quartz.CronScheduleBuilder;
import org.quartz.CronTrigger;
import org.quartz.TriggerBuilder;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 调度任务引擎。
 *
 * <p>设计约束：</p>
 * <ul>
 *     <li>不设执行超时，长任务合法；</li>
 *     <li>领取一条任务后交给执行线程，执行期间由心跳续租；</li>
 *     <li>进程存活即续租，进程退出后租约过期由回收器重新放回队列；</li>
 *     <li>回写带栅栏（exec_times），旧执行者的结果不会覆盖新一轮执行。</li>
 * </ul>
 *
 * <p>不使用新增列：租约使用 latest_exec_time，栅栏使用 exec_times，执行者信息只落在日志里。</p>
 */
@Slf4j
@Service
public class ScheduleTaskDomainServiceImpl implements ScheduleTaskDomainService {

    private static final long DEFAULT_MAX_EXEC_TIMES = Long.MAX_VALUE;

    private static final int MAX_ERROR_LENGTH = 2000;

    @Resource
    private ScheduleTaskService scheduleTaskService;

    private ApplicationContext applicationContext;

    @Autowired
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * 同时在执行的任务数。任务会长期占用一个槽位，按实际任务量调整。
     */
    @Value("${schedule.task.concurrency:100}")
    private int concurrency = 100;

    @Value("${schedule.task.dispatch-interval-millis:1000}")
    private long dispatchIntervalMillis = 1000L;

    /**
     * 执行租约。超过该时间没有心跳，认为执行者已经死亡。
     * 注意：这不是任务超时，活着的进程会一直续租，任务跑多久都不会被回收。
     */
    @Value("${schedule.task.lease-seconds:90}")
    private int leaseSeconds = 90;

    @Value("${schedule.task.reaper-interval-seconds:30}")
    private int reaperIntervalSeconds = 30;

    @Value("${schedule.task.completion-retry-times:3}")
    private int completionRetryTimes = 3;

    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(2, new CustomThreadFactory("schedule-task-scheduler"));

    /**
     * 本进程正在执行的任务，用于心跳续租。key 为 schedule_task.id。
     */
    private final ConcurrentHashMap<Long, RunningTask> runningTasks = new ConcurrentHashMap<>();

    /**
     * 槽位释放后立即唤醒一次调度，避免整点批量任务只能按秒级周期补充槽位。
     */
    private final AtomicBoolean dispatchScheduled = new AtomicBoolean(false);

    private volatile boolean running = false;

    private Semaphore slots;

    private ThreadPoolExecutor workerPool;

    @PostConstruct
    public void init() {
        int workerCount = Math.max(1, concurrency);
        this.slots = new Semaphore(workerCount);
        this.workerPool = new ThreadPoolExecutor(
                workerCount, workerCount,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(workerCount),
                new CustomThreadFactory("schedule-task-executor"),
                new ThreadPoolExecutor.AbortPolicy());
        this.running = true;

        // 用 scheduleWithFixedDelay 而不是 scheduleAtFixedRate：
        // 周期任务一旦抛出未捕获异常会被 JDK 永久取消，这里还额外包了一层 try/catch。
        scheduler.scheduleWithFixedDelay(this::safeDispatch, 5,
                Math.max(100L, dispatchIntervalMillis), TimeUnit.MILLISECONDS);

        long heartbeatSeconds = Math.max(5L, leaseSeconds / 3L);
        scheduler.scheduleWithFixedDelay(this::safeHeartbeat, heartbeatSeconds, heartbeatSeconds, TimeUnit.SECONDS);

        int reapSeconds = Math.max(5, reaperIntervalSeconds);
        scheduler.scheduleWithFixedDelay(this::safeReap, reapSeconds, reapSeconds, TimeUnit.SECONDS);

        // 注意：这里不再做 status = EXECUTING 的全表重置。
        // 旧实现会把其他实例正在执行的任务一并改回 CONTINUE，多实例下会重复执行；
        // 被中断的任务由 safeReap() 在租约过期后回收。
        log.info("schedule task dispatcher started, concurrency={}, leaseSeconds={}", workerCount, leaseSeconds);
    }

    @PreDestroy
    public void destroy() {
        running = false;
        scheduler.shutdownNow();
        if (workerPool != null) {
            workerPool.shutdown();
        }
    }

    /**
     * 调度主循环：先拿并发槽位，再领取任务，避免"已标记 EXECUTING 但没有线程执行"。
     */
    private void safeDispatch() {
        if (!running) {
            return;
        }
        try {
            while (running && slots.tryAcquire()) {
                ClaimedTask claimed;
                try {
                    claimed = claimOne();
                } catch (Throwable t) {
                    slots.release();
                    log.error("claim schedule task failed", t);
                    return;
                }
                if (claimed == null) {
                    slots.release();
                    return;
                }
                try {
                    workerPool.execute(() -> execute(claimed));
                } catch (Throwable t) {
                    slots.release();
                    log.error("submit schedule task failed, id={}", claimed.task().getId(), t);
                    writeFailure(claimed, t);
                }
            }
        } catch (Throwable t) {
            // 任何异常都不能冒到调度周期任务上，否则整个调度会停摆
            log.error("schedule task dispatch loop error", t);
        }
    }

    /**
     * 合并唤醒：同一时刻多个任务结束只排一次调度，由这次调度把空闲槽位一次性填满。
     */
    private void wakeupDispatch() {
        if (!running) {
            return;
        }
        if (!dispatchScheduled.compareAndSet(false, true)) {
            return;
        }
        try {
            scheduler.execute(() -> {
                dispatchScheduled.set(false);
                safeDispatch();
            });
        } catch (Throwable t) {
            dispatchScheduled.set(false);
            log.warn("wakeup schedule dispatch failed", t);
        }
    }

    private void execute(ClaimedTask claimed) {
        ScheduleTask task = claimed.task();
        runningTasks.put(task.getId(), new RunningTask(task.getId(), claimed.token()));

        AtomicBoolean finished = new AtomicBoolean(false);
        AtomicReference<Boolean> resultRef = new AtomicReference<>();
        AtomicReference<Throwable> errorRef = new AtomicReference<>();

        try {
            Mono<Boolean> mono = callback(task);
            mono.doOnNext(resultRef::set)
                    .doOnError(errorRef::set)
                    .doFinally(signal -> {
                        if (!finished.compareAndSet(false, true)) {
                            return;
                        }
                        runningTasks.remove(task.getId());
                        try {
                            Throwable error = errorRef.get();
                            if (error != null) {
                                writeFailure(claimed, error);
                            } else {
                                writeResult(claimed, Boolean.TRUE.equals(resultRef.get()));
                            }
                        } catch (Throwable t) {
                            log.error("write back schedule task result failed, id={}", task.getId(), t);
                        } finally {
                            slots.release();
                            wakeupDispatch();
                        }
                    })
                    // 终止信号在 doFinally 中统一处理，这里只负责吞掉异常避免 onErrorDropped
                    .subscribe(v -> {
                    }, e -> {
                    });
        } catch (Throwable t) {
            if (finished.compareAndSet(false, true)) {
                runningTasks.remove(task.getId());
                try {
                    writeFailure(claimed, t);
                } finally {
                    slots.release();
                }
            }
        }
    }

    /**
     * 心跳续租：只刷新执行中的行，进程活着就一直续，长任务不会被误杀。
     */
    private void safeHeartbeat() {
        if (!running || runningTasks.isEmpty()) {
            return;
        }
        try {
            Date now = new Date();
            for (RunningTask runningTask : runningTasks.values()) {
                try {
                    LambdaUpdateWrapper<ScheduleTask> updateWrapper = new LambdaUpdateWrapper<>();
                    updateWrapper.eq(ScheduleTask::getId, runningTask.id())
                            .eq(ScheduleTask::getStatus, ScheduleTaskDto.Status.EXECUTING)
                            .eq(ScheduleTask::getExecTimes, runningTask.token())
                            .set(ScheduleTask::getLatestExecTime, now);
                    if (!scheduleTaskService.update(updateWrapper)) {
                        log.warn("schedule task heartbeat ignored, id={}, execTimes={}",
                                runningTask.id(), runningTask.token());
                    }
                } catch (Throwable t) {
                    log.error("schedule task heartbeat failed, id={}", runningTask.id(), t);
                }
            }
        } catch (Throwable t) {
            log.error("schedule task heartbeat loop error", t);
        }
    }

    /**
     * 回收租约过期的执行中任务。任何实例都可以执行，行锁保证只有一个实例真正改到。
     */
    private void safeReap() {
        if (!running) {
            return;
        }
        try {
            Date deadline = new Date(System.currentTimeMillis() - Math.max(1, leaseSeconds) * 1000L);
            LambdaUpdateWrapper<ScheduleTask> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(ScheduleTask::getStatus, ScheduleTaskDto.Status.EXECUTING)
                    .and(wrapper -> wrapper.isNull(ScheduleTask::getLatestExecTime)
                            .or()
                            .lt(ScheduleTask::getLatestExecTime, deadline))
                    .set(ScheduleTask::getStatus, ScheduleTaskDto.Status.CONTINUE)
                    .set(ScheduleTask::getError, "recovered from expired execution lease")
                    .last("LIMIT 100");
            if (scheduleTaskService.update(updateWrapper)) {
                log.warn("schedule task reaper reclaimed expired EXECUTING rows, leaseSeconds={}", leaseSeconds);
            }
        } catch (Throwable t) {
            log.error("schedule task reaper error", t);
        }
    }

    public static class CustomThreadFactory implements ThreadFactory {

        private final String name;

        private final AtomicInteger count = new AtomicInteger(0);

        private CustomThreadFactory(String name) {
            this.name = name;
        }

        @Override
        public Thread newThread(Runnable r) {
            return new Thread(r, "CustomThread-" + name + "-" + count.incrementAndGet());
        }
    }

    /**
     * 领取一条到期的任务。
     *
     * <p>exec_times 在这里 +1，并把 +1 后的值作为本次执行的栅栏令牌。
     * 回写时必须带上该令牌，租约过期后旧执行者的结果会被丢弃。</p>
     */
    private ClaimedTask claimOne() {
        ScheduleTask task = selectOneCandidate();
        if (task == null) {
            return null;
        }

        Date nextLockTime = null;
        if (StringUtils.isNotBlank(task.getCron())) {
            try {
                nextLockTime = getNextExecDate(task.getCron());
            } catch (Throwable t) {
                // 非法 cron 属于毒任务：直接隔离，避免每秒重试堵死队首
                quarantine(task, "invalid cron expression: " + task.getCron());
                return null;
            }
        }

        long currentExecTimes = task.getExecTimes() == null ? 0L : task.getExecTimes();
        long token = currentExecTimes + 1;

        LambdaUpdateWrapper<ScheduleTask> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(ScheduleTask::getId, task.getId())
                .in(ScheduleTask::getStatus, ScheduleTaskDto.Status.CREATE,
                        ScheduleTaskDto.Status.FAIL, ScheduleTaskDto.Status.CONTINUE)
                .set(ScheduleTask::getStatus, ScheduleTaskDto.Status.EXECUTING)
                .set(ScheduleTask::getExecTimes, token)
                .set(ScheduleTask::getLatestExecTime, new Date());
        if (task.getExecTimes() == null) {
            updateWrapper.isNull(ScheduleTask::getExecTimes);
        } else {
            updateWrapper.eq(ScheduleTask::getExecTimes, currentExecTimes);
        }
        if (nextLockTime != null) {
            updateWrapper.set(ScheduleTask::getLockTime, nextLockTime);
        }

        if (!scheduleTaskService.update(updateWrapper)) {
            // 被其他实例抢走或状态已变化，交给下一轮
            return null;
        }
        return new ClaimedTask(task, token);
    }

    private ScheduleTask selectOneCandidate() {
        String taskServerInfo = System.getenv("TASK_SERVER_INFO");
        LambdaQueryWrapper<ScheduleTask> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.lt(ScheduleTask::getLockTime, new Date());
        lambdaQueryWrapper.in(ScheduleTask::getStatus, List.of(ScheduleTaskDto.Status.CREATE,
                ScheduleTaskDto.Status.FAIL, ScheduleTaskDto.Status.CONTINUE));
        if (StringUtils.isNotBlank(taskServerInfo)) {
            lambdaQueryWrapper.and(wrapper -> wrapper.eq(ScheduleTask::getServerInfo, taskServerInfo)
                    .or().isNull(ScheduleTask::getServerInfo));
        } else {
            lambdaQueryWrapper.isNull(ScheduleTask::getServerInfo);
        }
        lambdaQueryWrapper.orderByAsc(ScheduleTask::getLockTime);
        lambdaQueryWrapper.last("LIMIT 1");
        return scheduleTaskService.getOne(lambdaQueryWrapper, false);
    }

    private void quarantine(ScheduleTask task, String reason) {
        try {
            // 带上状态条件，避免误伤刚好被其他实例领取并开始执行的行
            LambdaUpdateWrapper<ScheduleTask> updateWrapper = new LambdaUpdateWrapper<>();
            updateWrapper.eq(ScheduleTask::getId, task.getId())
                    .in(ScheduleTask::getStatus, ScheduleTaskDto.Status.CREATE,
                            ScheduleTaskDto.Status.FAIL, ScheduleTaskDto.Status.CONTINUE)
                    .set(ScheduleTask::getStatus, ScheduleTaskDto.Status.OVERFLOW_MAX_EXEC_TIMES)
                    .set(ScheduleTask::getError, buildError(reason));
            scheduleTaskService.update(updateWrapper);
        } catch (Throwable t) {
            log.error("quarantine schedule task failed, id={}", task.getId(), t);
        }
        log.error("schedule task quarantined, id={}, taskId={}, reason={}",
                task.getId(), task.getTaskId(), reason);
    }

    private void writeResult(ClaimedTask claimed, boolean complete) {
        ScheduleTask task = claimed.task();
        ScheduleTaskDto.Status nextStatus;
        String error = "";

        if (complete) {
            nextStatus = ScheduleTaskDto.Status.COMPLETE;
        } else if (StringUtils.isBlank(task.getCron())) {
            // 没有 cron 时 lock_time 不会推进，继续停在 CONTINUE 会被立即重新领取形成忙循环
            nextStatus = ScheduleTaskDto.Status.COMPLETE;
            error = "task has no cron, finished after one run";
            log.warn("schedule task has no cron, mark COMPLETE to avoid busy loop, id={}, taskId={}",
                    task.getId(), task.getTaskId());
        } else {
            long maxExecTimes = task.getMaxExecTimes() == null ? DEFAULT_MAX_EXEC_TIMES : task.getMaxExecTimes();
            nextStatus = claimed.token() >= maxExecTimes
                    ? ScheduleTaskDto.Status.COMPLETE
                    : ScheduleTaskDto.Status.CONTINUE;
        }
        updateFinish(claimed, nextStatus, error, null);
    }

    private void writeFailure(ClaimedTask claimed, Throwable throwable) {
        ScheduleTask task = claimed.task();
        long maxExecTimes = task.getMaxExecTimes() == null ? DEFAULT_MAX_EXEC_TIMES : task.getMaxExecTimes();
        ScheduleTaskDto.Status nextStatus;
        Date nextLockTime = null;

        if (claimed.token() >= maxExecTimes) {
            nextStatus = ScheduleTaskDto.Status.OVERFLOW_MAX_EXEC_TIMES;
        } else if (StringUtils.isBlank(task.getCron())) {
            nextStatus = ScheduleTaskDto.Status.COMPLETE;
        } else {
            nextStatus = ScheduleTaskDto.Status.FAIL;
            try {
                nextLockTime = getNextExecDate(task.getCron());
            } catch (Throwable t) {
                // cron 已损坏，重试也不会有结果，直接隔离
                nextStatus = ScheduleTaskDto.Status.OVERFLOW_MAX_EXEC_TIMES;
            }
        }
        updateFinish(claimed, nextStatus, buildError(throwable), nextLockTime);
    }

    /**
     * 带栅栏和重试的状态回写。
     *
     * <p>栅栏条件：id + status=EXECUTING + exec_times=令牌。
     * 命中 0 行说明任务已被取消、删除，或租约已被回收后交给了新的执行者，本次结果直接丢弃。</p>
     */
    private void updateFinish(ClaimedTask claimed, ScheduleTaskDto.Status status, String error, Date lockTime) {
        ScheduleTask task = claimed.task();
        int maxAttempts = Math.max(1, completionRetryTimes);
        Throwable lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                LambdaUpdateWrapper<ScheduleTask> updateWrapper = new LambdaUpdateWrapper<>();
                updateWrapper.eq(ScheduleTask::getId, task.getId())
                        .eq(ScheduleTask::getStatus, ScheduleTaskDto.Status.EXECUTING)
                        .eq(ScheduleTask::getExecTimes, claimed.token())
                        .set(ScheduleTask::getStatus, status)
                        .set(ScheduleTask::getError, error);
                if (lockTime != null) {
                    updateWrapper.set(ScheduleTask::getLockTime, lockTime);
                }
                if (scheduleTaskService.update(updateWrapper)) {
                    log.debug("schedule task finished, id={}, taskId={}, execTimes={}, status={}",
                            task.getId(), task.getTaskId(), claimed.token(), status);
                    return;
                }
                // 栅栏未命中：任务已被取消/删除，或已由新的执行者接管
                log.warn("schedule task finish ignored, id={}, taskId={}, execTimes={}, targetStatus={}",
                        task.getId(), task.getTaskId(), claimed.token(), status);
                return;
            } catch (Throwable t) {
                lastError = t;
                log.warn("schedule task finish update failed, id={}, attempt={}/{}",
                        task.getId(), attempt, maxAttempts, t);
                sleepQuietly(200L * attempt);
            }
        }
        // 兜底：租约过期后回收器会把行改回 CONTINUE，至少再执行一次
        log.error("schedule task finish update failed after retries, id={}, taskId={}, targetStatus={}",
                task.getId(), task.getTaskId(), status, lastError);
    }

    private Mono<Boolean> callback(ScheduleTask scheduleTask) {
        TaskExecuteService taskExecuteService;
        try {
            taskExecuteService = (TaskExecuteService) applicationContext.getBean(scheduleTask.getBeanId());
        } catch (Exception e) {
            if (e instanceof NoSuchBeanDefinitionException) {
                log.error("任务回调业务模块不存在 {}", scheduleTask.getBeanId());
                return Mono.just(true);// 任务回调业务模块不存在，后续不再执行
            }
            log.error("获取任务回调业务模块失败 {}", scheduleTask.getBeanId(), e);
            return Mono.just(false);
        }
        ScheduleTaskDto scheduleTaskDto = new ScheduleTaskDto();
        BeanUtils.copyProperties(scheduleTask, scheduleTaskDto);
        // 保持原有语义：任务拿到的是自增前的 execTimes，需要判断"本次是不是最后一次"的业务自行 +1
        try {
            Mono<Boolean> result = taskExecuteService.asyncExecute(scheduleTaskDto);
            return result == null ? Mono.just(false) : result;
        } catch (Exception e) {
            log.error("任务执行入口调用失败 beanId={}", scheduleTask.getBeanId(), e);
            return Mono.just(false);
        }
    }

    private ScheduleTask queryOne(String taskId) {
        LambdaQueryWrapper<ScheduleTask> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(ScheduleTask::getTaskId, taskId);
        return scheduleTaskService.getOne(lambdaQueryWrapper, false);
    }

    @Retry
    @Override
    public Long start(ScheduleTask scheduleTask) {
        if (StringUtils.isBlank(scheduleTask.getCron())) {
            throw new IllegalArgumentException("schedule task cron must not be blank, taskId=" + scheduleTask.getTaskId());
        }
        if (!CronExpression.isValidExpression(scheduleTask.getCron())) {
            throw new IllegalArgumentException("invalid cron expression: " + scheduleTask.getCron()
                    + ", taskId=" + scheduleTask.getTaskId());
        }

        ScheduleTask scheduleTask1 = queryOne(scheduleTask.getTaskId());
        if (null != scheduleTask1) {
            // 曾按 taskId 注销后，再次 register 时若仅 return，
            // 记录仍为 CANCEL，重新start需把同 taskId 任务拉回到 CREATE。
            if (scheduleTask1.getStatus() != ScheduleTaskDto.Status.CREATE
                    && scheduleTask1.getStatus() != ScheduleTaskDto.Status.EXECUTING
                    && scheduleTask1.getStatus() != ScheduleTaskDto.Status.CONTINUE) {
                if (StringUtils.isNotBlank(scheduleTask.getCron())) {
                    scheduleTask1.setCron(scheduleTask.getCron());
                }
                if (StringUtils.isNotBlank(scheduleTask.getBeanId())) {
                    scheduleTask1.setBeanId(scheduleTask.getBeanId());
                }
                if (scheduleTask.getParams() != null) {
                    scheduleTask1.setParams(scheduleTask.getParams());
                }
                if (scheduleTask.getMaxExecTimes() != null) {
                    scheduleTask1.setMaxExecTimes(scheduleTask.getMaxExecTimes());
                }
                if (StringUtils.isNotBlank(scheduleTask.getTaskName())) {
                    scheduleTask1.setTaskName(scheduleTask.getTaskName());
                }
                if (StringUtils.isNotBlank(scheduleTask.getTargetType())) {
                    scheduleTask1.setTargetType(scheduleTask.getTargetType());
                }
                if (StringUtils.isNotBlank(scheduleTask.getTargetId())) {
                    scheduleTask1.setTargetId(scheduleTask.getTargetId());
                }
                scheduleTask1.setStatus(ScheduleTaskDto.Status.CREATE);
                scheduleTask1.setExecTimes(0L);
                scheduleTask1.setError("");
                scheduleTask1.setLockTime(getNextExecDate(scheduleTask1.getCron()));
                scheduleTaskService.updateById(scheduleTask1);
                return scheduleTask1.getId();
            }
            return scheduleTask1.getId();
        }
        scheduleTask.setStatus(ScheduleTaskDto.Status.CREATE);
        if (scheduleTask.getLockTime() == null) {
            scheduleTask.setLockTime(getNextExecDate(scheduleTask.getCron()));
        }
        scheduleTaskService.save(scheduleTask);
        return scheduleTask.getId();
    }

    @Override
    public void update(ScheduleTask scheduleTask) {
        //根据taskId更新
        LambdaUpdateWrapper<ScheduleTask> lambdaQueryWrapper = new LambdaUpdateWrapper<>();
        lambdaQueryWrapper.eq(ScheduleTask::getTaskId, scheduleTask.getTaskId());
        if (StringUtils.isNotBlank(scheduleTask.getCron())) {
            scheduleTask.setLockTime(getNextExecDate(scheduleTask.getCron()));
        }
        scheduleTaskService.update(scheduleTask, lambdaQueryWrapper);
    }

    @Override
    public void complete(String taskId) {
        LambdaQueryWrapper<ScheduleTask> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(ScheduleTask::getTaskId, taskId);
        ScheduleTask scheduleTask = scheduleTaskService.getOne(lambdaQueryWrapper);
        if (null != scheduleTask) {
            scheduleTask.setStatus(ScheduleTaskDto.Status.COMPLETE);
            scheduleTaskService.updateById(scheduleTask);
        }
    }

    @Override
    public void cancel(String taskId) {
        LambdaQueryWrapper<ScheduleTask> lambdaQueryWrapper = new LambdaQueryWrapper<>();
        lambdaQueryWrapper.eq(ScheduleTask::getTaskId, taskId);
        lambdaQueryWrapper.notIn(ScheduleTask::getStatus, ScheduleTaskDto.Status.CANCEL, ScheduleTaskDto.Status.COMPLETE, ScheduleTaskDto.Status.OVERFLOW_MAX_EXEC_TIMES, ScheduleTaskDto.Status.FAIL);
        ScheduleTask scheduleTask = scheduleTaskService.getOne(lambdaQueryWrapper, false);
        if (null != scheduleTask) {
            scheduleTask.setStatus(ScheduleTaskDto.Status.CANCEL);
            scheduleTaskService.updateById(scheduleTask);
        }
    }

    /** 终态清理分批大小：控制单条 DELETE 行数，避免存量首清时长事务/大锁 */
    private static final int CLEANUP_BATCH_SIZE = 2000;

    @Override
    public int cleanupTerminalBefore(Date deadline) {
        int total = 0;
        while (true) {
            int deleted = scheduleTaskService.getBaseMapper().delete(new LambdaQueryWrapper<ScheduleTask>()
                    .in(ScheduleTask::getStatus, ScheduleTaskDto.Status.COMPLETE,
                            ScheduleTaskDto.Status.OVERFLOW_MAX_EXEC_TIMES,
                            ScheduleTaskDto.Status.CANCEL)
                    .lt(ScheduleTask::getCreated, deadline)
                    .last("LIMIT " + CLEANUP_BATCH_SIZE));
            total += deleted;
            if (deleted < CLEANUP_BATCH_SIZE) {
                break;
            }
        }
        return total;
    }

    public static Date getNextExecDate(String cron) {
        if (!CronExpression.isValidExpression(cron)) {
            throw new IllegalArgumentException("invalid cron expression: " + cron);
        }
        CronTrigger trigger = TriggerBuilder.newTrigger().withIdentity("Caclulate Date").withSchedule(CronScheduleBuilder.cronSchedule(cron)).build();
        Date time = trigger.getStartTime();
        return trigger.getFireTimeAfter(time);
    }

    private static String buildError(Throwable throwable) {
        if (throwable == null) {
            return "";
        }
        String message = throwable.getMessage();
        String text = throwable.getClass().getSimpleName()
                + (StringUtils.isBlank(message) ? "" : ": " + message);
        return text.length() > MAX_ERROR_LENGTH ? text.substring(0, MAX_ERROR_LENGTH) : text;
    }

    private static String buildError(String reason) {
        if (reason == null) {
            return "";
        }
        return reason.length() > MAX_ERROR_LENGTH ? reason.substring(0, MAX_ERROR_LENGTH) : reason;
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 一次领取结果。token 是本次执行的栅栏令牌（exec_times 自增后的值）。
     */
    private record ClaimedTask(ScheduleTask task, long token) {
    }

    private record RunningTask(Long id, long token) {
    }
}
