package com.xspaceagi.agent.core.infra.sub;

import com.alibaba.fastjson2.JSON;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Slf4j
@Service
public class ChatMessagePubSubService {

    @Resource
    private RedisMessageListenerContainer container;

    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    // channel -> 消息缓冲区
    private final Map<String, List<String>> buffer = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

    private volatile boolean subscribed = false;

    @PostConstruct
    public void init() {
        // 每200ms批量发送
        scheduler.scheduleAtFixedRate(this::flushAll, 1000, 200, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdown();
    }

    /**
     * 动态订阅频道
     */
    public void subscribe(String conversationId, Consumer<String> onMessage) {
        if (!subscribed) {
            synchronized (this) {
                if (!subscribed) {
                    MessageListener listener = (message, pattern) -> {
                        Object deserialize = redisTemplate.getValueSerializer().deserialize(message.getBody());
                        String topic = new String(message.getChannel());
                        String cId = topic.split(":")[1];
                        if (listeners.containsKey(cId) && deserialize != null) {
                            List<Consumer<String>> consumers = new ArrayList<>(listeners.get(cId));
                            for (Consumer<String> consumer : consumers) {
                                consumer.accept(deserialize.toString());
                            }
                        }
                    };
                    container.addMessageListener(listener, new PatternTopic(chatChannel()));
                    subscribed = true;
                }
            }
        }
        listeners.computeIfAbsent(conversationId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(onMessage);
    }

    /**
     * 取消订阅
     */
    public synchronized void unsubscribe(String conversationId, Consumer<String> listener) {
        List<Consumer<String>> consumers = listeners.get(conversationId);
        if (consumers != null) {
            consumers.remove(listener);
            if (consumers.isEmpty()) {
                listeners.remove(conversationId);
            }
        }
    }

    /**
     * 消息不直接发送，先入缓冲区
     */
    public void publish(String conversationId, String message) {
        buffer.computeIfAbsent(buildChatChannel(conversationId), k -> Collections.synchronizedList(new ArrayList<>()))
                .add(message);
    }

    /**
     * 发送完成，清空缓冲区
     */
    public void finishPublish(String conversationId) {
        flush(buildChatChannel(conversationId));
        buffer.remove(buildChatChannel(conversationId));
        redisTemplate.delete(buildChatChannel(conversationId) + ":messages");
    }

    public List<String> getMessages(String conversationId) {
        return redisTemplate.opsForList().range(buildChatChannel(conversationId) + ":messages", 0, -1);
    }

    /**
     * 批量发送指定频道
     */
    private void flush(String channel) {
        List<String> messages = buffer.get(channel);
        if (messages == null || messages.isEmpty()) return;

        synchronized (messages) {
            if (messages.isEmpty()) return;

            // 合并为 JSON 数组，一次 publish 搞定
            try {
                redisTemplate.convertAndSend(channel, JSON.toJSONString(messages));
                redisTemplate.opsForList().rightPushAll(channel + ":messages", messages);
                redisTemplate.expire(channel + ":messages", 1, TimeUnit.HOURS);
                messages.clear();
            } catch (Exception e) {
                log.error("批量发送失败", e);
            }
        }
    }

    private String chatChannel() {
        return "topic-chat-v1:*";
    }

    private String buildChatChannel(String conversationId) {
        return "topic-chat-v1:" + conversationId;
    }

    /**
     * 定期 flush 所有频道
     */
    private void flushAll() {
        buffer.keySet().forEach(this::flush);
    }
}
