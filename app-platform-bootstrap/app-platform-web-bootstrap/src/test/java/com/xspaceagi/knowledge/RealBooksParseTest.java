package com.xspaceagi.knowledge;

import com.xspaceagi.knowledge.core.spec.utils.Constants;
import com.xspaceagi.knowledge.core.spec.utils.DocumentParseBudget;
import com.xspaceagi.knowledge.domain.knowledgeimageanalysis.DocumentImageParseResult;
import com.xspaceagi.knowledge.domain.knowledgeimageanalysis.DocumentImageParseService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 PDF 书籍的本地解析测试(不依赖 Spring 容器/DB/LLM)。
 *
 * <p>覆盖三层验证:
 * <ol>
 *   <li>单本基线: 真实书籍走生产同款 Tika 图片感知解析,产出文本量/图片数/耗时;</li>
 *   <li>上限适配性: 验证真实书籍的图片与文本量级落在防护上限之内(图片20MB/文本2000万字符);</li>
 *   <li>批量并发: DocumentParseBudget(128MB)约束下多本书并发解析,验证排队行为与峰值并行度。</li>
 * </ol>
 *
 * <p>测试数据通过目录引用,不进 git: 目录取系统属性 {@code real.pdf.dir} 或环境变量
 * {@code REAL_PDF_DIR},默认 {@code ~/Downloads/realtest};目录不存在时全部用例自动跳过,
 * 因此 CI 中安全。本地运行:
 * <pre>
 * mvn -pl app-platform-bootstrap/app-platform-web-bootstrap -am test \
 *   -Dtest=RealBooksParseTest -Dreal.pdf.dir=/path/to/books
 * </pre>
 *
 * <p>注意: SMART 分段的 LLM 调用、QA 生成、向量化不在此覆盖(需模型与DB),
 * 由测试环境部署验证。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class RealBooksParseTest {

    private static final String DEFAULT_DIR = System.getProperty("user.home") + "/Downloads/realtest";
    private static List<Path> books;

    @BeforeAll
    static void loadBooks() {
        String dir = System.getProperty("real.pdf.dir", System.getenv().getOrDefault("REAL_PDF_DIR", DEFAULT_DIR));
        Path path = Path.of(dir);
        assumeTrueWithMessage(Files.isDirectory(path), "真实PDF目录不存在,跳过: " + dir
                + " (可通过 -Dreal.pdf.dir=... 或环境变量 REAL_PDF_DIR 指定)");
        try (Stream<Path> s = Files.list(path)) {
            books = s.filter(p -> p.getFileName().toString().toLowerCase().endsWith(".pdf")).sorted().toList();
        } catch (IOException e) {
            books = List.of();
        }
        assumeTrueWithMessage(!books.isEmpty(), "目录中没有PDF文件: " + dir);
        System.out.println("[Setup] 测试书籍 " + books.size() + " 本, 来自 " + dir);
    }

    private static void assumeTrueWithMessage(boolean condition, String message) {
        org.junit.jupiter.api.Assumptions.assumeTrue(condition, message);
    }

    /** 无操作的上传回调: 不上传S3,仅驱动图片提取路径(含20MB有界读取) */
    private static final com.xspaceagi.knowledge.domain.knowledgeimageanalysis.ImageUploadCallback NOOP_UPLOADER =
            (bytes, fileName, contentType) -> null;

    private static byte[] readBook(Path book) throws IOException {
        try (InputStream in = Files.newInputStream(book)) {
            return in.readAllBytes();
        }
    }

    /** 生产路由: 与 TikaParser.chunk 相同的 DocumentImageParseService(pdf→PDFBox, docx→POI, 其他→Tika兜底) */
    private static final DocumentImageParseService PROD_ROUTER = new DocumentImageParseService(null);

    private static DocumentImageParseResult parse(Path book) throws IOException {
        try (InputStream in = new ByteArrayInputStream(readBook(book))) {
            DocumentImageParseResult result = PROD_ROUTER.parse(in, book.getFileName().toString(), NOOP_UPLOADER);
            assertNotNull(result, book + " 解析结果为null");
            assertTrue(result.isSuccess(), book + " 解析失败: " + result.getErrorMessage());
            return result;
        }
    }

    @Test
    @Order(1)
    public void singleBookBaseline() throws IOException {
        for (Path book : books) {
            Runtime.getRuntime().gc();
            long heapBefore = usedHeap();
            long t0 = System.currentTimeMillis();
            DocumentImageParseResult result = parse(book);
            long costMs = System.currentTimeMillis() - t0;

            long maxImage = result.getImages() == null ? 0
                    : result.getImages().stream().mapToLong(i -> (long) i.getSize()).max().orElse(0);
            System.out.printf("[基线] %-46s %6.1fMB | 耗时 %6.1fs | 文本 %8d字符 | 图片 %3d张(最大 %.1fMB) | 堆增量 %dMB%n",
                    book.getFileName(), Files.size(book) / 1048576.0, costMs / 1000.0,
                    result.getContent() == null ? 0 : result.getContent().length(),
                    result.getTotalImages(), maxImage / 1048576.0, (usedHeap() - heapBefore) / 1048576);

            assertTrue(result.getContent() == null || result.getContent().length() >= 0, "文本非负");
        }
    }

    @Test
    @Order(2)
    public void realBooksFitWithinGuardLimits() throws IOException {
        for (Path book : books) {
            DocumentImageParseResult result = parse(book);
            long maxImage = result.getImages() == null ? 0
                    : result.getImages().stream().mapToLong(i -> (long) i.getSize()).max().orElse(0);
            int textLen = result.getContent() == null ? 0 : result.getContent().length();
            System.out.printf("[上限] %-46s 最大图片 %.1fMB / 20MB, 文本 %d / %d 字符%n",
                    book.getFileName(), maxImage / 1048576.0, textLen, Constants.TIKA_TEXT_WRITE_LIMIT);
            // 真实书籍应天然落在防护上限内(不触发跳图/截断);若断言失败说明上限与真实数据不匹配,需重估阈值
            assertTrue(maxImage <= 20L * 1024 * 1024, book + " 存在超过20MB的单张图片: " + maxImage);
            assertTrue(textLen < Constants.TIKA_TEXT_WRITE_LIMIT, book + " 文本超出缓冲上限: " + textLen);
        }
    }

    @Test
    @Order(3)
    public void batchParseUnderBudget() throws Exception {
        DocumentParseBudget budget = new DocumentParseBudget();
        ReflectionTestUtils.setField(budget, "budgetBytes", 128L * 1024 * 1024);
        ReflectionTestUtils.setField(budget, "bigFileThresholdBytes", 1024L * 1024 * 1024);
        // 无Spring环境时@Value不生效,long默认为0会被init()修正为1秒,排队1秒即超时
        ReflectionTestUtils.setField(budget, "acquireTimeoutSeconds", 300L);
        ReflectionTestUtils.setField(budget, "leaseTtlSeconds", 600L);
        budget.init();

        int threads = Math.max(4, books.size() * 2);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakConcurrency = new AtomicInteger();
        AtomicLong peakUsed = new AtomicLong();
        Thread monitor = new Thread(() -> {
            while (true) {
                peakUsed.accumulateAndGet(usedHeap(), Math::max);
                try { Thread.sleep(50); } catch (InterruptedException e) { return; }
            }
        });
        monitor.setDaemon(true);
        monitor.start();

        List<Path> tasks = new ArrayList<>(books);
        tasks.addAll(books); // 两轮,模拟批量上传压力
        CountDownLatch done = new CountDownLatch(tasks.size());
        long t0 = System.currentTimeMillis();

        for (Path book : tasks) {
            pool.submit(() -> {
                DocumentParseBudget.Lease lease = null;
                try {
                    lease = budget.acquire(Files.size(book));
                    int now = inFlight.incrementAndGet();
                    peakConcurrency.accumulateAndGet(now, Math::max);
                    parse(book);
                    inFlight.decrementAndGet();
                    ok.incrementAndGet();
                    System.out.printf("[批量] 完成 %-42s (当前并行%d)%n", book.getFileName(), now);
                } catch (Throwable t) {
                    inFlight.decrementAndGet();
                    fail.incrementAndGet();
                    System.out.println("[批量] 失败 " + book.getFileName() + ": " + t);
                } finally {
                    if (lease != null) {
                        lease.close();
                    }
                    done.countDown();
                }
            });
        }
        pool.shutdown();
        boolean finished = done.await(40, TimeUnit.MINUTES);
        monitor.interrupt();

        System.out.printf("[批量汇总] 任务%d 成功%d 失败%d | 峰值并行解析 %d 路 | 堆峰值 %dMB | 耗时 %.1fs%n",
                tasks.size(), ok.get(), fail.get(), peakConcurrency.get(),
                peakUsed.get() / 1048576, (System.currentTimeMillis() - t0) / 1000.0);

        assertTrue(finished, "批量解析未在时限内完成");
        assertEquals(0, fail.get(), "存在失败任务");
        // 128MB预算下,50-61MB的大书最多2路并行;加上小书余量,峰值不应超过4路
        assertTrue(peakConcurrency.get() <= 4,
                "峰值并行解析超出预算预期: " + peakConcurrency.get() + " (预算128MB)");
        assertTrue(peakConcurrency.get() >= 2, "未观察到并发解析,样本不足");
    }

    private static long usedHeap() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
}
