package com.xspaceagi.agent.core.infra.sub;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Configuration
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);

        // 可选：自定义线程池
        AtomicInteger i = new AtomicInteger();
        container.setTaskExecutor(Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "redis-sub-" + i.getAndIncrement());
            t.setDaemon(true);
            return t;
        }));

        return container;
    }
}