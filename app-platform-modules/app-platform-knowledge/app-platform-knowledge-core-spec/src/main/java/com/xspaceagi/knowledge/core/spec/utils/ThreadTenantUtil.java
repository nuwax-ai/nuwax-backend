package com.xspaceagi.knowledge.core.spec.utils;

import com.xspaceagi.system.spec.tenant.thread.TenantThreadPoolExecutor;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Executors;

/**
 * 公共线程池工具
 */
@Slf4j
@Component
public class ThreadTenantUtil {

    /**
     * 分段,重试等业务的线程池
     */
    private TenantThreadPoolExecutor executor;
    /**
     * 知识库其他的业务任务,如更新文件大小
     */
    private TenantThreadPoolExecutor scheduledExecutorService;
    /**
     * 分段使用独立线程执行
     */
    private TenantThreadPoolExecutor rawExecutor;

    /**
     * 向量化(embedding)专用线程池,跨知识库并发向量化
     */
    private TenantThreadPoolExecutor embeddingExecutor;

    /**
     * 分段(文档解析)线程数: 吞吐旋钮(非安全阀——内存安全由 DocumentParseBudget 字节预算
     * 和图片直拷/像素守卫保障)。图片直拷修复后单书堆从100倍降至3-5倍,
     * 多线程只是在预算信号量上排队等待,不额外消耗堆,可放心调高提升小文件吞吐
     */
    @Value("${knowledge.parse.raw-executor-threads:20}")
    private int rawExecutorThreads;

    @PostConstruct
    public void init() {
        // 原始线程池
        executor = new TenantThreadPoolExecutor(Executors.newFixedThreadPool(3));

        scheduledExecutorService = new TenantThreadPoolExecutor(Executors.newScheduledThreadPool(10));

        rawExecutor = new TenantThreadPoolExecutor(Executors.newFixedThreadPool(Math.max(1, rawExecutorThreads)));

        // 向量化专用线程池:跨知识库并发,线程数受 embedding 模型 QPS 约束
        embeddingExecutor = new TenantThreadPoolExecutor(Executors.newFixedThreadPool(40));

        log.info("初始化线程池[ThreadTenantUtil]");
    }

    @PreDestroy
    public void destroy() {
        log.info("服务停止运行,关闭线程池");
        if (executor != null) {
            executor.shutdownNow();
        }

        if (scheduledExecutorService != null) {
            scheduledExecutorService.shutdownNow();
        }

        if (rawExecutor != null) {
            rawExecutor.shutdownNow();
        }

        if (embeddingExecutor != null) {
            embeddingExecutor.shutdownNow();
        }

    }

    /**
     * 获取通用线程池
     *
     * @return
     */
    public TenantThreadPoolExecutor obtainCommonExecutor() {
        return this.executor;
    }

    /**
     * 其他常规的知识库业务
     *
     * @return
     */
    public TenantThreadPoolExecutor obtainOtherScheduledExecutor() {
        return this.scheduledExecutorService;
    }

    /**
     * 分段使用独立线程执行
     * 
     * @return
     */
    public TenantThreadPoolExecutor obtainRawExecutor() {
        return this.rawExecutor;
    }

    /**
     * 向量化(embedding)专用线程池
     *
     * @return
     */
    public TenantThreadPoolExecutor obtainEmbeddingExecutor() {
        return this.embeddingExecutor;
    }

}
