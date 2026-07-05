package com.xspaceagi.agent.core.infra.sub;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class RedisPublisher {

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    /**
     * 发布消息到指定频道
     */
    public void publish(String channel, String message) {
        redisTemplate.convertAndSend(channel, message);
    }

}