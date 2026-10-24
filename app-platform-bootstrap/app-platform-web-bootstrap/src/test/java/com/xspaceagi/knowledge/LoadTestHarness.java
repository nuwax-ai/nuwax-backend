package com.xspaceagi.knowledge;

import com.xspaceagi.knowledge.core.spec.utils.DocumentParseBudget;
import com.xspaceagi.knowledge.domain.knowledgeimageanalysis.TikaImageAwareParser;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 知识库解析并发防护(DocumentParseBudget + Tika图片感知解析)的压测工具。
 *
 * 用途: 调整 knowledge.parse.budget-bytes、升级 pdfbox/tika 后复测防护效果。
 * 模拟 batchAddInfo 提交风暴: N 个文档并发走
 * "文件整体读入内存(与 TikaParser 生产形态一致) + Tika 解析" 全流程。
 *
 * 不以 Test 结尾,不会被 surefire 自动执行;手动运行方式见同目录 README.md。
 *
 * 参数: <budgetBytes> <threads> <rounds> <pdf...>
 *  budgetBytes=0 表示禁用预算(对照组,模拟修复前的行为)
 */
public class LoadTestHarness {

    public static void main(String[] args) throws Exception {
        long budgetBytes = Long.parseLong(args[0]);
        int threads = Integer.parseInt(args[1]);
        int rounds = Integer.parseInt(args[2]);
        List<String> files = List.of(args).subList(3, args.length);

        DocumentParseBudget budget = null;
        if (budgetBytes > 0) {
            budget = new DocumentParseBudget();
            ReflectionTestUtils.setField(budget, "budgetBytes", budgetBytes);
            // 压测不触发大文件串行通道
            ReflectionTestUtils.setField(budget, "bigFileThresholdBytes", 1024L * 1024 * 1024);
            budget.init();
            System.out.println("[配置] 字节预算=" + (budgetBytes / 1048576) + "MB");
        } else {
            System.out.println("[配置] 字节预算=禁用(对照组,模拟修复前)");
        }
        System.out.println("[配置] 并发线程=" + threads + ", 文档数=" + files.size() + " x " + rounds + "轮, 堆上限=" + (Runtime.getRuntime().maxMemory() / 1048576) + "MB");

        final DocumentParseBudget budgetRef = budget;
        TikaImageAwareParser parser = new TikaImageAwareParser();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch done = new CountDownLatch(files.size() * rounds);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        AtomicLong peakUsed = new AtomicLong();

        Thread monitor = new Thread(() -> {
            while (true) {
                long used = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                peakUsed.accumulateAndGet(used, Math::max);
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        });
        monitor.setDaemon(true);
        monitor.start();
        long t0 = System.currentTimeMillis();

        for (int r = 0; r < rounds; r++) {
            for (String f : files) {
                pool.submit(() -> {
                    DocumentParseBudget.Lease lease = null;
                    try {
                        long size = Files.size(Path.of(f));
                        if (budgetRef != null) {
                            lease = budgetRef.acquire(size);
                        }
                        byte[] docBytes = readFile(f);
                        try (InputStream in = new ByteArrayInputStream(docBytes)) {
                            parser.parse(in, null);
                        }
                        ok.incrementAndGet();
                    } catch (Throwable t) {
                        fail.incrementAndGet();
                        if (t.toString().contains("OutOfMemory")) {
                            System.out.println("[OOM] " + t);
                        }
                    } finally {
                        if (lease != null) {
                            lease.close();
                        }
                        done.countDown();
                    }
                });
            }
        }
        pool.shutdown();
        boolean finished = done.await(30, TimeUnit.MINUTES);
        monitor.interrupt();

        System.out.println("==== 结果 ====");
        System.out.println("完成: " + finished + " | 成功=" + ok.get() + " 失败=" + fail.get()
                + " | 耗时=" + (System.currentTimeMillis() - t0) / 1000 + "s"
                + " | 堆峰值≈" + peakUsed.get() / 1048576 + "MB / 上限" + Runtime.getRuntime().maxMemory() / 1048576 + "MB");
        System.exit(finished && fail.get() == 0 ? 0 : 1);
    }

    private static byte[] readFile(String path) throws Exception {
        try (InputStream in = Files.newInputStream(Path.of(path));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toByteArray();
        }
    }
}
