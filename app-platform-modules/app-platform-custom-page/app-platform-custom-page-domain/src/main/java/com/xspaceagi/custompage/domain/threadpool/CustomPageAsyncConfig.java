package com.xspaceagi.custompage.domain.threadpool;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 用户页面项目的线程池配置
 */
@Configuration
public class CustomPageAsyncConfig {

    /**
     * 短 IO：Flux 推送、/chat HTTP 调用等，避免被长连接占满。
     */
    @Bean("aiChatExecutor")
    public Executor aiChatExecutor() {
        return new ThreadPoolExecutor(
                20,
                100,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(500),
                namedThreadFactory("ai-chat-"),
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 长 IO：Agent 进度 SSE 订阅（阻塞读至流结束）。
     */
    @Bean("aiAgentProgressExecutor")
    public Executor aiAgentProgressExecutor() {
        return new ThreadPoolExecutor(
                20,
                200,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(100),
                namedThreadFactory("ai-agent-progress-"),
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    /**
     * DB 轮询回放：定时任务，不占用 aiChatExecutor 工作线程 sleep。
     */
    @Bean(name = "aiAgentProgressScheduler", destroyMethod = "shutdown")
    public ScheduledExecutorService aiAgentProgressScheduler() {
        return Executors.newScheduledThreadPool(4, namedThreadFactory("ai-agent-progress-sched-"));
    }

    private static ThreadFactory namedThreadFactory(String namePrefix) {
        return new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, namePrefix + threadNumber.getAndIncrement());
                t.setDaemon(true);
                return t;
            }
        };
    }
}
