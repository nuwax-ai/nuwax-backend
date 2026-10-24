package com.xspaceagi.agent.core.infra.sub;

import com.alibaba.fastjson2.JSON;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ChatMessagePubSubService} 的生命周期契约。
 *
 * <p>这些用例对应线上真实出现过的症状：
 * <ul>
 *   <li>失败分支先删缓冲后发终态 ⇒ Redis 里留下一条 1 小时不消失的 ERROR（终态 → flush → delete 顺序）；</li>
 *   <li>中断分支不发终态 ⇒ 订阅方永远等不到结束（终态关闭订阅）；</li>
 *   <li>重连竞态 ⇒ 回放为空、监听没挂上、连接挂死（注册/回放顺序 + 已收尾返回 null）；</li>
 *   <li>慢订阅 / 连接泄漏 ⇒ 订阅无限堆积（队列上限 + 每会话上限）；</li>
 *   <li>长会话 ⇒ 回放列表与内存缓冲无上限（LTRIM + pending 上限）。</li>
 * </ul>
 */
class ChatMessagePubSubServiceTest {

    private static final String CID = "1001";
    private static final String CHANNEL = "topic-chat-v1:" + CID;
    private static final String MESSAGE_KEY = CHANNEL + ":messages";

    private ChatMessagePubSubService service;
    private RedisTemplate<String, String> redisTemplate;
    private ListOperations<String, String> listOperations;
    private RedisMessageListenerContainer container;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisTemplate = mock(RedisTemplate.class);
        listOperations = mock(ListOperations.class);
        container = mock(RedisMessageListenerContainer.class);
        when(redisTemplate.opsForList()).thenReturn(listOperations);
        when(listOperations.range(anyString(), anyLong(), anyLong())).thenReturn(Collections.emptyList());
        when(redisTemplate.convertAndSend(anyString(), anyString())).thenReturn(1L);
        when(redisTemplate.delete(anyString())).thenReturn(true);

        service = new ChatMessagePubSubService();
        inject("redisTemplate", redisTemplate);
        inject("container", container);
        // 终态宽限时间调短，测试不必真的等 1 秒
        inject("terminalGraceMillis", 100L);
    }

    @AfterEach
    void tearDown() {
        service.destroy();
    }

    @Test
    @DisplayName("失败终态：先发布终态、再 flush、最后删回放列表；终态后的迟到消息不再写回 Redis")
    void terminalIsPublishedBeforeReplayBufferIsDeleted() {
        service.publish(CID, "m1");

        service.finishPublish(CID, "ERROR");

        InOrder inOrder = inOrder(redisTemplate, listOperations);
        inOrder.verify(redisTemplate).convertAndSend(CHANNEL, "[\"m1\",\"ERROR\"]");
        inOrder.verify(listOperations).rightPushAll(eq(MESSAGE_KEY), eq(List.of("m1", "ERROR")));
        inOrder.verify(redisTemplate).delete(MESSAGE_KEY);

        // 收尾之后心跳/在途消息迟到：直接丢弃，不能把刚删掉的 key 重新写回来
        service.publish(CID, "LATE");
        service.flushAll();
        verify(redisTemplate, times(1)).convertAndSend(anyString(), anyString());
        verify(listOperations, times(1)).rightPushAll(anyString(), any(List.class));
    }

    @Test
    @DisplayName("中断终态：订阅方收到终态后关闭，不留下挂死连接")
    void terminalClosesSubscribers() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        AtomicInteger terminated = new AtomicInteger();
        java.util.concurrent.atomic.AtomicReference<ChatMessagePubSubService.Subscription> ref =
                new java.util.concurrent.atomic.AtomicReference<>();
        // 与 SSE 层一致：收到终态就主动关闭自己的订阅
        ChatMessagePubSubService.Subscription subscription = service.subscribe(CID, message -> {
            received.add(message);
            if ("FINAL_INTERRUPTED".equals(message) && ref.get() != null) {
                ref.get().close();
            }
        }, terminated::incrementAndGet);
        ref.set(subscription);

        service.finishPublish(CID, "FINAL_INTERRUPTED");
        // 模拟 Redis 把刚发布的终态投递到本实例
        service.dispatch(CID, "[\"FINAL_INTERRUPTED\"]");
        service.drainAll();

        assertEquals(List.of("FINAL_INTERRUPTED"), received);
        assertEquals(1, terminated.get(), "终态后应关闭订阅，否则 SSE 永不结束");

        // 宽限时间过后统一摘监听；重复 close 不应重复回调
        Thread.sleep(400);
        assertTrue(subscription.isClosed());
        assertEquals(1, terminated.get());
    }

    @Test
    @DisplayName("已收尾的频道再订阅返回 null：重连方不会挂上一个永远等不到终态的监听")
    void subscribeAfterFinishReturnsNull() {
        service.finishPublish(CID, "FINAL");

        ChatMessagePubSubService.Subscription subscription =
                service.subscribe(CID, message -> { }, () -> { });

        assertNull(subscription);
    }

    @Test
    @DisplayName("慢订阅：队列超过上限时逐出，而不是无限堆积内存")
    void slowSubscriberIsEvictedWhenQueueOverflows() {
        inject("subscriberQueueCapacity", 2);
        AtomicInteger terminated = new AtomicInteger();
        ChatMessagePubSubService.Subscription subscription =
                service.subscribe(CID, message -> { }, terminated::incrementAndGet);

        service.dispatch(CID, "[\"m1\"]");
        service.dispatch(CID, "[\"m2\"]");
        service.dispatch(CID, "[\"m3\"]");

        assertEquals(1, terminated.get(), "第三批超出容量 2，应逐出该订阅");
        assertTrue(subscription.isClosed());
    }

    @Test
    @DisplayName("正常的高速输出：单批超过 64 条时仍按消息条数计容量，不把健康连接当成慢订阅踢掉")
    void largeHealthyBatchIsNotEvicted() {
        List<String> messages = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            messages.add("m" + i);
        }
        AtomicInteger terminated = new AtomicInteger();
        ChatMessagePubSubService.Subscription subscription =
                service.subscribe(CID, message -> { }, terminated::incrementAndGet);

        service.dispatch(CID, JSON.toJSONString(messages));
        service.drainAll();

        assertFalse(subscription.isClosed(), "一批 500 条小于队列上限，不应逐出");
        assertEquals(0, terminated.get());
    }

    @Test
    @DisplayName("每会话订阅上限：超出时逐出最旧的连接（原子），新连接不受影响")
    void subscriptionLimitEvictsOldest() {
        inject("maxListenersPerConversation", 2);
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        AtomicInteger third = new AtomicInteger();

        ChatMessagePubSubService.Subscription s1 = service.subscribe(CID, m -> { }, first::incrementAndGet);
        ChatMessagePubSubService.Subscription s2 = service.subscribe(CID, m -> { }, second::incrementAndGet);
        ChatMessagePubSubService.Subscription s3 = service.subscribe(CID, m -> { }, third::incrementAndGet);

        assertTrue(s1.isClosed(), "最旧的订阅应被逐出");
        assertFalse(s2.isClosed());
        assertFalse(s3.isClosed());
        assertEquals(1, first.get());
        assertEquals(0, second.get());
        assertEquals(0, third.get());
    }

    @Test
    @DisplayName("重连：回放先于实时投递，且注册与读回放之间重复的批次只发一次")
    void replayIsDeliveredBeforeLiveAndDuplicatesAreDropped() {
        // 模拟竞态：订阅已注册、回放尚未读出的窗口里，同一批 m2 既被实时投递又落进了回放
        when(listOperations.range(anyString(), anyLong(), anyLong())).thenAnswer(invocation -> {
            service.dispatch(CID, "[\"m2\"]");
            service.dispatch(CID, "[\"m3\"]");
            return List.of("m1", "m2");
        });

        List<String> received = new CopyOnWriteArrayList<>();
        service.subscribe(CID, received::add, () -> { });
        service.drainAll();

        assertEquals(List.of("m1", "m2", "m3"), received,
                "顺序必须是 回放 → 实时，且 m2 不能因为竞态重复下发");
    }

    @Test
    @DisplayName("回放列表与内存缓冲都有上限：长会话不无限增长")
    void replayBufferAndPendingBufferAreBounded() {
        inject("pendingCapacity", 2000);
        inject("replayLimit", 1000);

        for (int i = 0; i < 5000; i++) {
            service.publish(CID, "m" + i);
        }
        service.flushAll();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> batch = ArgumentCaptor.forClass(List.class);
        verify(listOperations).rightPushAll(eq(MESSAGE_KEY), batch.capture());
        assertEquals(2000, batch.getValue().size(), "内存缓冲超过上限时应丢弃最旧的消息");
        assertEquals("m4999", batch.getValue().get(batch.getValue().size() - 1));

        verify(listOperations).trim(MESSAGE_KEY, -1000, -1);
        verify(redisTemplate).expire(eq(MESSAGE_KEY), eq(30L), any());
    }

    @Test
    @DisplayName("客户端断开：订阅立即摘除，之后的实时消息不再投递")
    void disconnectRemovesSubscription() {
        List<String> received = new CopyOnWriteArrayList<>();
        AtomicInteger terminated = new AtomicInteger();
        ChatMessagePubSubService.Subscription subscription =
                service.subscribe(CID, received::add, terminated::incrementAndGet);

        subscription.close();
        service.dispatch(CID, "[\"m1\"]");
        service.drainAll();

        assertTrue(received.isEmpty());
        assertEquals(1, terminated.get());
    }

    @Test
    @DisplayName("新一轮对话：open 会重开已收尾的频道并清掉上一轮回放，不会被上一轮的终态挡住")
    void openResetsFinishedChannel() {
        service.finishPublish(CID, "FINAL");

        service.open(CID);
        service.publish(CID, "m1");
        service.flushAll();

        verify(redisTemplate, times(2)).convertAndSend(eq(CHANNEL), anyString());
        verify(redisTemplate, times(2)).delete(MESSAGE_KEY);
        // open 之后可以重新订阅
        ChatMessagePubSubService.Subscription subscription =
                service.subscribe(CID, message -> { }, () -> { });
        assertTrue(subscription != null && !subscription.isClosed());
    }

    private void inject(String name, Object value) {
        try {
            Field field = ChatMessagePubSubService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(service, value);
        } catch (Exception e) {
            throw new IllegalStateException("注入字段失败: " + name, e);
        }
    }
}