package com.xspaceagi.knowledge;

import com.xspaceagi.knowledge.core.spec.utils.DocumentParseBudget;
import com.xspaceagi.system.spec.exception.KnowledgeException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 文档解析字节预算限制器 DocumentParseBudget 的行为测试。
 * 覆盖: 计费/释放、大文件互斥、缺省计费、钳制、中断、超时、租约TTL强制回收。
 */
public class DocumentParseBudgetTest {

    private static final long MB = 1024 * 1024;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    private DocumentParseBudget newBudget(long budgetBytes, long bigFileThresholdBytes) {
        DocumentParseBudget budget = new DocumentParseBudget();
        ReflectionTestUtils.setField(budget, "budgetBytes", budgetBytes);
        ReflectionTestUtils.setField(budget, "bigFileThresholdBytes", bigFileThresholdBytes);
        ReflectionTestUtils.setField(budget, "acquireTimeoutSeconds", 60L);
        ReflectionTestUtils.setField(budget, "leaseTtlSeconds", 300L);
        budget.init();
        return budget;
    }

    @AfterEach
    public void tearDown() {
        executor.shutdownNow();
    }

    @Test
    public void testSmallFileChargedByBytesAndReturnedOnClose() {
        DocumentParseBudget budget = newBudget(128 * MB, 100 * MB);
        long before = budget.availableBudgetBytes();

        DocumentParseBudget.Lease lease = budget.acquire(10 * MB);
        assertEquals(before - 10 * MB, budget.availableBudgetBytes());

        lease.close();
        assertEquals(before, budget.availableBudgetBytes());
    }

    @Test
    public void testMissingOrInvalidFileSizeChargedMinimum() {
        DocumentParseBudget budget = newBudget(128 * MB, 100 * MB);
        long before = budget.availableBudgetBytes();

        try (DocumentParseBudget.Lease ignore = budget.acquire(null)) {
            assertEquals(before - MB, budget.availableBudgetBytes());
        }
        try (DocumentParseBudget.Lease ignore = budget.acquire(0L)) {
            assertEquals(before - MB, budget.availableBudgetBytes());
        }
        assertEquals(before, budget.availableBudgetBytes());
    }

    @Test
    public void testBigFileUsesSerialLaneAndIsGloballyExclusive() throws Exception {
        DocumentParseBudget budget = newBudget(128 * MB, 100 * MB);

        // 串行通道不占用字节预算
        long before = budget.availableBudgetBytes();
        DocumentParseBudget.Lease first = budget.acquire(150 * MB);
        assertEquals(before, budget.availableBudgetBytes());

        // 第二个超阈值文件必须等第一个释放后才能进入
        CountDownLatch entered = new CountDownLatch(1);
        Future<?> second = executor.submit(() -> {
            try (DocumentParseBudget.Lease ignore = budget.acquire(120 * MB)) {
                entered.countDown();
            } catch (Throwable t) {
                // 超时或不匹配则不触发
            }
        });
        assertFalse(entered.await(200, TimeUnit.MILLISECONDS), "第一个大文件未释放前,第二个不应进入串行通道");

        first.close();
        assertTrue(entered.await(5, TimeUnit.SECONDS), "释放后第二个大文件应进入串行通道");
        second.get(5, TimeUnit.SECONDS);
    }

    @Test
    public void testFileLargerThanBudgetClampedNoDeadlock() {
        DocumentParseBudget budget = newBudget(10 * MB, 100 * MB);

        // 预算被占满后,计费钳制到剩余许可数,不会永久阻塞
        DocumentParseBudget.Lease lease = budget.acquire(50 * MB);
        assertEquals(0, budget.availableBudgetBytes());

        lease.close();
        assertEquals(10 * MB, budget.availableBudgetBytes());
    }

    @Test
    public void testInterruptionWhileWaitingThrowsAndRestoresFlag() throws Exception {
        DocumentParseBudget budget = newBudget(2 * MB, 100 * MB);
        DocumentParseBudget.Lease holder = budget.acquire(2 * MB);

        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                budget.acquire(2 * MB);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        waiter.start();
        Thread.sleep(200);
        waiter.interrupt();
        waiter.join(5000);

        assertTrue(thrown.get() instanceof KnowledgeException, "等待预算被中断应抛出 KnowledgeException");
        assertTrue(waiter.isInterrupted(), "中断位应被恢复");

        holder.close();
    }

    @Test
    public void testAcquireTimeoutWhenBudgetHeldForever() throws Exception {
        DocumentParseBudget budget = new DocumentParseBudget();
        ReflectionTestUtils.setField(budget, "budgetBytes", 2L * MB);
        ReflectionTestUtils.setField(budget, "bigFileThresholdBytes", 1024L * MB);
        ReflectionTestUtils.setField(budget, "acquireTimeoutSeconds", 1L);
        ReflectionTestUtils.setField(budget, "leaseTtlSeconds", 300L);
        budget.init();

        // 第一个任务占满全部预算(模拟挂起,永不close)
        DocumentParseBudget.Lease holder = budget.acquire(2 * MB);
        assertEquals(0, budget.availableBudgetBytes());

        // 第二个任务在1秒内拿不到预算 → 超时
        long t0 = System.currentTimeMillis();
        try {
            budget.acquire(2 * MB);
            org.junit.jupiter.api.Assertions.fail("应抛出超时异常");
        } catch (KnowledgeException e) {
            long elapsed = System.currentTimeMillis() - t0;
            assertTrue(elapsed >= 900 && elapsed < 5000, "应在约1秒后超时,实际 " + elapsed + "ms");
            assertTrue(e.getMessage().contains("超时"), "异常消息应包含超时信息: " + e.getMessage());
        } finally {
            holder.close();
        }
        assertEquals(2 * MB, budget.availableBudgetBytes(), "释放后预算应恢复");
    }

    @Test
    public void testBigFileLockTimeout() throws Exception {
        DocumentParseBudget budget = new DocumentParseBudget();
        ReflectionTestUtils.setField(budget, "budgetBytes", 128L * MB);
        ReflectionTestUtils.setField(budget, "bigFileThresholdBytes", 0L);
        ReflectionTestUtils.setField(budget, "acquireTimeoutSeconds", 1L);
        ReflectionTestUtils.setField(budget, "leaseTtlSeconds", 300L);
        budget.init();

        // 第一个大文件持有串行许可(在主线程)
        DocumentParseBudget.Lease holder = budget.acquire(1 * MB);

        // 第二个大文件在不同线程抢(Semaphore无重入问题,但仍需验证互斥)
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread waiter = new Thread(() -> {
            try {
                budget.acquire(1 * MB);
            } catch (Throwable t) {
                thrown.set(t);
            }
        });
        waiter.start();
        waiter.join(5000);

        assertTrue(thrown.get() instanceof KnowledgeException, "应抛出串行超时异常");
        assertTrue(((KnowledgeException) thrown.get()).getMessage().contains("超时"),
                "异常消息应包含超时: " + thrown.get().getMessage());

        holder.close();

        // 释放后可以再次获取
        try (DocumentParseBudget.Lease lease = budget.acquire(1 * MB)) {
            assertNotNull(lease);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void testLeaseTtlForceReclaim() throws Exception {
        DocumentParseBudget budget = new DocumentParseBudget();
        ReflectionTestUtils.setField(budget, "budgetBytes", 10L * MB);
        ReflectionTestUtils.setField(budget, "bigFileThresholdBytes", 1024L * MB);
        ReflectionTestUtils.setField(budget, "acquireTimeoutSeconds", 60L);
        ReflectionTestUtils.setField(budget, "leaseTtlSeconds", 1L); // TTL=1秒
        budget.init();

        // 模拟挂起: 获取租约后不close
        DocumentParseBudget.Lease hungLease = budget.acquire(10 * MB);
        assertEquals(0, budget.availableBudgetBytes());
        assertEquals(1, budget.activeLeaseCount());

        // 等TTL过期
        Thread.sleep(1500);

        // 手动触发看门狗(测试不等待30秒周期)
        ReflectionTestUtils.invokeMethod(budget, "reclaimExpiredLeases");

        // 验证: 预算被强制回收
        assertEquals(10 * MB, budget.availableBudgetBytes(), "TTL过期后预算应被看门狗强制回收");
        assertEquals(0, budget.activeLeaseCount(), "活跃租约应被清除");

        // 验证: 挂起线程后续close()是no-op(不双重释放)
        hungLease.close();
        assertEquals(10 * MB, budget.availableBudgetBytes(), "挂起线程close()应跳过,不导致预算超发");

        // 验证: 新任务可以正常获取
        try (DocumentParseBudget.Lease newLease = budget.acquire(5 * MB)) {
            assertNotNull(newLease);
            assertEquals(5 * MB, budget.availableBudgetBytes());
        }
        assertEquals(10 * MB, budget.availableBudgetBytes());
    }
}
