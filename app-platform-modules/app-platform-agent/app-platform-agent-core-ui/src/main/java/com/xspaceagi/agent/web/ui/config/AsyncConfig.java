package com.xspaceagi.agent.web.ui.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池配置
 * <p>
 * 用于处理需要异步执行的任务，如企业微信智能机器人的消息处理
 */
@Slf4j
@Configuration
public class AsyncConfig {

    /**
     * 异步任务线程池
     * <p>
     * 配置说明：
     * - 核心线程数：5（保持活跃的线程数）
     * - 最大线程数：20（线程池最大线程数）
     * - 队列容量：100（等待执行的任务队列大小）
     * - 线程名前缀：async-task-
     * - 拒绝策略：CallerRunsPolicy（由调用线程处理该任务）
     */
    @Bean(name = "asyncTaskExecutor")
    public Executor asyncTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数：线程池保持活跃的线程数
        executor.setCorePoolSize(5);

        // 最大线程数：线程池允许的最大线程数
        executor.setMaxPoolSize(20);

        // 队列容量：等待执行的任务队列大小
        executor.setQueueCapacity(100);

        // 线程名前缀：便于日志追踪
        executor.setThreadNamePrefix("async-task-");

        // 线程空闲时间（秒）：超过核心线程数的空闲线程存活时间
        executor.setKeepAliveSeconds(60);

        // 拒绝策略：当队列满且线程数达到最大值时的处理策略
        // CallerRunsPolicy：由调用线程（提交任务的线程）处理该任务
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 等待所有任务完成后再关闭线程池
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // 等待时间（秒）：线程池关闭时最多等待任务完成的时间
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();

        log.info("异步任务线程池初始化完成: coreSize={}, maxSize={}, queueCapacity={}",
                executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());

        return executor;
    }
}
