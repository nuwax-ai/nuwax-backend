package com.xspaceagi.knowledge.core.spec.utils;

import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 知识库文档解析的进程内并发限制(带租约TTL强制回收)。
 * <p>
 * 限制"同时在解析中的文档总字节数",防止批量上传大文件时并行解析把堆内存打爆。
 * <p>
 * 三层防护:
 * <ul>
 *   <li><b>字节预算信号量</b>: fileSize ≤ 阈值的文档按 min(fileSize, 预算) 计费,
 *       钳制同时并行解析的文件总大小;</li>
 *   <li><b>大文件串行通道</b>: fileSize > 阈值转入 Semaphore(1) 串行,一本一本解析;</li>
 *   <li><b>租约TTL + 看门狗</b>: 每个租约记录获取时间,后台看门狗定期扫描,
 *       超过TTL仍未释放的租约<b>强制回收</b>(跨线程归还信号量许可),
 *       防止解析挂起导致预算/串行锁永久泄漏。挂起线程后续调用 close() 时
 *       因 AtomicBoolean 已置位而自动跳过(防双重释放)。</li>
 * </ul>
 */
@Slf4j
@Component
public class DocumentParseBudget {

    private static final long MIN_CHARGE_BYTES = 1024 * 1024;
    private static final long WATCHDOG_INTERVAL_SECONDS = 30;

    @Value("${knowledge.parse.budget-bytes:134217728}")
    private long budgetBytes;

    @Value("${knowledge.parse.big-file-threshold-bytes:104857600}")
    private long bigFileThresholdBytes;

    /**
     * 获取许可的超时秒数,默认 2 分钟(排队等待上限)。
     * 超时抛异常让重试接手,是快速失败手段;资源回收由租约TTL保障。
     */
    @Value("${knowledge.parse.acquire-timeout-seconds:120}")
    private long acquireTimeoutSeconds;

    /**
     * 租约存活上限秒数,默认 5 分钟。
     * 正常解析最慢 8.1s(fossen),5 分钟有 37 倍余量;超过此时间视为挂起,
     * 看门狗强制回收预算/串行锁,后续任务可正常获取。
     */
    @Value("${knowledge.parse.lease-ttl-seconds:300}")
    private long leaseTtlSeconds;

    private Semaphore budgetSemaphore;

    /** 大文件串行通道: Semaphore(1) 而非 ReentrantLock,支持看门狗跨线程强制释放 */
    private Semaphore bigFileSemaphore;

    /** 活跃租约注册表: lease → 获取时间戳,看门狗据此扫描超期 */
    private final ConcurrentHashMap<ManagedLease, Long> activeLeases = new ConcurrentHashMap<>();

    private ScheduledExecutorService watchdog;

    @PostConstruct
    public void init() {
        if (budgetBytes < MIN_CHARGE_BYTES) {
            budgetBytes = MIN_CHARGE_BYTES;
        }
        if (budgetBytes > Integer.MAX_VALUE) {
            budgetBytes = Integer.MAX_VALUE;
        }
        if (bigFileThresholdBytes < 0) {
            bigFileThresholdBytes = 0;
        }
        if (acquireTimeoutSeconds < 1) {
            acquireTimeoutSeconds = 1;
        }
        if (leaseTtlSeconds < 1) {
            leaseTtlSeconds = 1;
        }
        this.budgetSemaphore = new Semaphore((int) budgetBytes, true);
        this.bigFileSemaphore = new Semaphore(1, true);

        this.watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "parse-budget-watchdog");
            t.setDaemon(true);
            return t;
        });
        this.watchdog.scheduleAtFixedRate(this::reclaimExpiredLeases,
                WATCHDOG_INTERVAL_SECONDS, WATCHDOG_INTERVAL_SECONDS, TimeUnit.SECONDS);

        log.info("初始化文档解析预算[DocumentParseBudget]: budgetBytes={}, bigFileThresholdBytes={}, "
                        + "acquireTimeout={}s, leaseTTL={}s, watchdogInterval={}s",
                budgetBytes, bigFileThresholdBytes, acquireTimeoutSeconds, leaseTtlSeconds, WATCHDOG_INTERVAL_SECONDS);
    }

    @PreDestroy
    public void destroy() {
        if (watchdog != null) {
            watchdog.shutdownNow();
        }
    }

    /**
     * 获取解析许可(带超时+租约TTL)。
     * 调用方以 try-with-resources 持有;正常完成由 close() 释放,
     * 挂起超过TTL由看门狗强制回收(挂起线程的 close() 自动跳过)。
     */
    public Lease acquire(Long fileSize) {
        long size = fileSize == null || fileSize <= 0 ? MIN_CHARGE_BYTES : fileSize;
        if (size > bigFileThresholdBytes) {
            try {
                if (!bigFileSemaphore.tryAcquire(acquireTimeoutSeconds, TimeUnit.SECONDS)) {
                    throw new KnowledgeException(BizExceptionCodeEnum.knowledgeDocumentParseFailed.getCode(),
                            String.format("获取大文件串行解析许可超时(%d秒)", acquireTimeoutSeconds));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeDocumentParseFailed, e);
            }
            log.info("[DocumentParseBudget] 文件超过阈值,转入串行解析: fileSize={}", size);
            return newLease(bigFileSemaphore::release);
        }
        int charge = (int) Math.min(size, budgetBytes);
        try {
            if (!budgetSemaphore.tryAcquire(charge, acquireTimeoutSeconds, TimeUnit.SECONDS)) {
                throw new KnowledgeException(BizExceptionCodeEnum.knowledgeDocumentParseFailed.getCode(),
                        String.format("获取解析字节预算超时(%d秒,需要%dMB,剩余%dMB)",
                                acquireTimeoutSeconds, charge / 1048576, budgetSemaphore.availablePermits() / 1048576));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw KnowledgeException.build(BizExceptionCodeEnum.knowledgeDocumentParseFailed, e);
        }
        return newLease(() -> budgetSemaphore.release(charge));
    }

    private ManagedLease newLease(Runnable releaseAction) {
        ManagedLease lease = new ManagedLease(releaseAction);
        activeLeases.put(lease, System.currentTimeMillis());
        return lease;
    }

    /**
     * 看门狗: 扫描并强制回收超期租约。
     * ConcurrentHashMap.forEach 是弱一致的,迭代中删除安全。
     */
    private void reclaimExpiredLeases() {
        try {
            long now = System.currentTimeMillis();
            long ttlMs = leaseTtlSeconds * 1000;
            activeLeases.forEach((lease, createdAt) -> {
                long age = now - createdAt;
                if (age > ttlMs) {
                    lease.forceRelease();
                    log.error("[DocumentParseBudget] 租约超期强制回收: age={}s, ttl={}s "
                                    + "(解析挂起未正常释放,预算/串行锁已归还,挂起线程后续close()将跳过)",
                            age / 1000, leaseTtlSeconds);
                }
            });
        } catch (Throwable t) {
            log.error("[DocumentParseBudget] 看门狗执行异常", t);
        }
    }

    public long availableBudgetBytes() {
        return budgetSemaphore.availablePermits();
    }

    /**
     * 当前活跃租约数(观测用)
     */
    public int activeLeaseCount() {
        return activeLeases.size();
    }

    /**
     * 受管租约: 注册到activeLeases,支持看门狗跨线程强制回收。
     * close() 和 forceRelease() 均幂等(AtomicBoolean),不会双重释放。
     */
    private class ManagedLease implements Lease {
        private final AtomicBoolean released = new AtomicBoolean(false);
        private final Runnable releaseAction;

        ManagedLease(Runnable releaseAction) {
            this.releaseAction = releaseAction;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                activeLeases.remove(this);
                releaseAction.run();
            }
        }

        void forceRelease() {
            if (released.compareAndSet(false, true)) {
                activeLeases.remove(this);
                releaseAction.run();
            }
        }
    }

    public interface Lease extends AutoCloseable {
        @Override
        void close();
    }
}
