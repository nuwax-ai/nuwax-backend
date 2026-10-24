package com.xspaceagi.agent.core.infra.sub;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 会话消息的 pub/sub 总线 + 回放缓冲。
 *
 * <p>一条聊天事件有两份投递路径，必须是同一份队列的两个视图：
 * <ol>
 *   <li><b>实时</b>：发布方把待发消息攒在内存里（{@link #publish}），每 {@code FLUSH_INTERVAL_MS}
 *       合并成一批，先 {@code PUBLISH} 到 Redis，再 {@code RPUSH} 进回放列表；</li>
 *   <li><b>回放</b>：重连的订阅方先读回放列表（{@link #getMessages}），之后从 Redis 订阅继续收。</li>
 * </ol>
 *
 * <p>这里的每条约束都对应一个曾经真实出现过的故障：
 * <ul>
 *   <li><b>终态先发、再删</b>：{@link #finishPublish(String, String)} 先把待发消息 + 终态一起发出去，
 *       最后才删回放列表。反过来（先删后发）会让终态把刚删掉的 key 重新写回来，Redis 里留一条永不清理的尾巴；</li>
 *   <li><b>关闭标志</b>：收尾后 {@link #publish} 直接丢弃。心跳、在途的收尾消息都不会再复写 key；</li>
 *   <li><b>每会话订阅上限 + 有界队列</b>：连接泄漏与慢订阅不能把内存吃光，超限逐出而不是无限堆积；</li>
 *   <li><b>先注册后回放</b>：订阅方注册之后才读回放列表，注册期间到达的实时消息先入队，
 *       回放读完再按序投递，不会出现"回放为空、监听没挂上、连接永远不结束"。</li>
 * </ul>
 *
 * <p>Redis 消息格式保持原样（一个 JSON 数组字符串），新旧实例可以混跑。
 */
@Slf4j
@Service
public class ChatMessagePubSubService {

    /** Redis pub/sub 频道前缀，与历史版本保持一致（跨实例兼容） */
    private static final String CHANNEL_PREFIX = "topic-chat-v1:";

    private static final String CHANNEL_PATTERN = CHANNEL_PREFIX + "*";

    private static final String MESSAGES_SUFFIX = ":messages";

    /** 待发消息合并成一批的间隔 */
    private static final long FLUSH_INTERVAL_MS = 200;

    /** 订阅队列的泵间隔 */
    private static final long PUMP_INTERVAL_MS = 20;

    /** 终态发出后，留给 Redis 投递到本地订阅者的时间，之后摘监听 */
    private static final long CLOSED_CHANNEL_RETENTION_SECONDS = 300;

    @Resource
    private RedisMessageListenerContainer container;

    @Resource
    private RedisTemplate<String, String> redisTemplate;

    /** conversationId -> 频道状态（待发缓冲 + 订阅者 + 关闭标志） */
    private final Map<String, ChannelState> channels = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, r -> {
        Thread t = new Thread(r, "chat-pubsub-scheduler");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean subscribed = false;

    /** 回放列表最多保留多少条，超出的从头部裁掉 */
    @Value("${chat.pubsub.replay-limit:10000}")
    private int replayLimit = 10000;

    /** 回放列表 TTL（分钟）。任务结束后列表会被立即删除，这里只兜底异常退出 */
    @Value("${chat.pubsub.replay-ttl-minutes:30}")
    private long replayTtlMinutes = 30;

    /** 单个会话同时允许的订阅连接数 */
    @Value("${chat.pubsub.max-listeners-per-conversation:10}")
    private int maxListenersPerConversation = 10;

    /**
     * 单个订阅的待投递消息条数。
     * <p>注意单位是「条」不是「批」：一次 flush 最多可带 {@link #pendingCapacity} 条，
     * 队列必须能容纳至少一整批再加余量，否则正常的高速输出（流式 token、工具大结果）
     * 会在同一批里瞬间超过上限，把健康连接当成慢订阅踢掉。
     */
    @Value("${chat.pubsub.subscriber-queue-capacity:8192}")
    private int subscriberQueueCapacity = 8192;

    /** 单个会话内存待发消息上限，防止 Redis 长时间不可用时把内存吃光 */
    @Value("${chat.pubsub.pending-capacity:10000}")
    private int pendingCapacity = 10000;

    /** 订阅空闲多久判定为死连接（分钟）。执行期每 10s 有心跳，超过该值说明连接已死 */
    @Value("${chat.pubsub.idle-timeout-minutes:30}")
    private long idleTimeoutMinutes = 30;

    /** 终态发出后到摘监听的宽限时间 */
    @Value("${chat.pubsub.terminal-grace-ms:1000}")
    private long terminalGraceMillis = 1000;

    @PostConstruct
    public void init() {
        scheduler.scheduleAtFixedRate(this::flushAll, FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        scheduler.scheduleAtFixedRate(this::drainAll, PUMP_INTERVAL_MS, PUMP_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void destroy() {
        scheduler.shutdownNow();
    }

    /**
     * 开始一轮新的对话输出：重置本会话的缓冲区，丢弃上一轮可能残留的回放。
     * <p>必须在本次运行的第一次 {@link #publish} 之前调用，否则上一轮异常退出留下的列表会被重连方当成
     * 本轮消息回放出去。
     */
    public void open(String conversationId) {
        ChannelState state = channels.computeIfAbsent(conversationId, ChannelState::new);
        synchronized (state.lock) {
            state.closed = false;
            state.closedAt = 0;
            state.pending.clear();
        }
        deleteMessagesQuietly(conversationId);
    }

    /**
     * 注册订阅者：先注册（实时消息入队），再读回放，最后按「回放 → 实时」顺序投递。
     *
     * @param conversationId 会话 ID
     * @param onMessage      单条消息（JSON 字符串）的回调，可能在订阅调用线程或泵线程执行
     * @param onTerminate    订阅被逐出/关闭时的回调，用于结束对应的 SSE 流
     * @return 订阅句柄；会话已收尾（{@link #finishPublish}）时返回 {@code null}
     */
    public Subscription subscribe(String conversationId, Consumer<String> onMessage, Runnable onTerminate) {
        ensureRedisListener();
        ChannelState state = channels.computeIfAbsent(conversationId, ChannelState::new);
        Subscription subscription = new Subscription(state, onMessage, onTerminate);

        List<Subscription> evicted = new ArrayList<>(1);
        synchronized (state.lock) {
            if (state.closed) {
                // 终态已经发过（回放列表也已删除），这里再挂监听就永远等不到结束
                return null;
            }
            while (state.subscriptions.size() >= Math.max(1, maxListenersPerConversation)) {
                Iterator<Subscription> iterator = state.subscriptions.iterator();
                Subscription oldest = iterator.next();
                iterator.remove();
                evicted.add(oldest);
            }
            state.subscriptions.add(subscription);
        }
        evicted.forEach(Subscription::close);

        subscription.pause();
        List<String> replay = getMessages(conversationId);
        replayAndResume(subscription, replay);
        return subscription;
    }

    /**
     * 发布一条消息。先入内存缓冲，由 {@link #flushAll} 批量发出去。
     * <p>频道已收尾时直接丢弃 —— 这是"终态之后不再产生任何 Redis 写入"的闸门。
     */
    public void publish(String conversationId, String message) {
        if (message == null) {
            return;
        }
        ChannelState state = channels.computeIfAbsent(conversationId, ChannelState::new);
        synchronized (state.lock) {
            if (state.closed) {
                log.debug("会话已收尾，丢弃迟到消息, cid {}", conversationId);
                return;
            }
            state.pending.addLast(message);
            trimPending(state);
        }
    }

    /**
     * 收尾：把待发消息和终态一起发出去，然后删除回放列表、摘监听、关闭频道。
     * <p>顺序固定为「终态 → flush → delete → 摘监听 → 关闭」，且幂等。
     */
    public void finishPublish(String conversationId) {
        finishPublish(conversationId, null);
    }

    /**
     * @param terminalMessage 终态消息（FINAL_RESULT / ERROR 的 JSON），为 {@code null} 时表示终态已经
     *                        在之前的 {@link #publish} 里发过了
     */
    public void finishPublish(String conversationId, String terminalMessage) {
        ChannelState state = channels.computeIfAbsent(conversationId, ChannelState::new);
        List<String> batch;
        long closedAt;
        synchronized (state.lock) {
            if (state.closed) {
                return;
            }
            state.closed = true;
            state.closedAt = System.currentTimeMillis();
            closedAt = state.closedAt;
            if (terminalMessage != null) {
                state.pending.addLast(terminalMessage);
                trimPending(state);
            }
            batch = new ArrayList<>(state.pending);
            state.pending.clear();
        }
        if (!batch.isEmpty()) {
            pushToRedis(state, batch);
        }
        // 终态已经通过 pubsub 下发，回放列表不再需要，立即删除，避免留下最后一轮残影
        deleteMessagesQuietly(conversationId);
        scheduler.schedule(() -> {
            // 留一点时间让终态投递到本地订阅者，然后统一摘监听并关闭。
            // 检查 closedAt：期间可能已经开了新一轮（open 会把 closed 置回 false），不能误伤新连接/新回放。
            List<Subscription> subscriptions;
            synchronized (state.lock) {
                if (!state.closed || state.closedAt != closedAt) {
                    return;
                }
                subscriptions = new ArrayList<>(state.subscriptions);
                state.subscriptions.clear();
                // 在锁内删除，避免与新一轮 open/publish 交错
                deleteMessagesQuietly(conversationId);
            }
            subscriptions.forEach(Subscription::close);
            scheduler.schedule(() -> removeClosedChannel(state, closedAt),
                    CLOSED_CHANNEL_RETENTION_SECONDS, TimeUnit.SECONDS);
        }, Math.max(0, terminalGraceMillis), TimeUnit.MILLISECONDS);
    }

    /**
     * 回放列表（按时间正序，最多 {@code replayLimit} 条）。
     * <p>读取失败返回空列表而不是抛异常：重连方还应继续挂监听收实时消息。
     */
    public List<String> getMessages(String conversationId) {
        try {
            List<String> messages = redisTemplate.opsForList()
                    .range(messagesKey(conversationId), -replayLimit, -1);
            return messages == null ? Collections.emptyList() : messages;
        } catch (Exception e) {
            log.error("读取会话回放失败, cid {}", conversationId, e);
            return Collections.emptyList();
        }
    }

    /**
     * 先注册、后回放的投递实现：
     * <ol>
     *   <li>把暂停期间入队的实时消息取出来；</li>
     *   <li>丢掉与回放尾部重复的消息（flush 先 PUBLISH 后 RPUSH，注册与读回放之间两边都可能拿到同一批；RPUSH 是原子的，所以一批要么全在回放里、要么全不在）；</li>
     *   <li>先逐条发回放，再把未被覆盖的实时消息放回队首，恢复泵。</li>
     * </ol>
     */
    private void replayAndResume(Subscription subscription, List<String> replay) {
        List<String> queued;
        synchronized (subscription.lock) {
            if (subscription.closed) {
                return;
            }
            queued = new ArrayList<>(subscription.queue);
            subscription.queue.clear();
        }

        int skip = 0;
        while (skip < queued.size() && !replay.isEmpty()
                && queued.get(skip).equals(replay.get(replay.size() - 1))) {
            skip++;
        }

        for (String message : replay) {
            if (subscription.closed) {
                return;
            }
            deliver(subscription, message);
        }

        synchronized (subscription.lock) {
            if (subscription.closed) {
                return;
            }
            // 回放期间新到的实时批次已经在队列里，老的实时批次要插到它们前面，顺序才是 回放 → 实时
            for (int i = queued.size() - 1; i >= skip; i--) {
                subscription.queue.addFirst(queued.get(i));
            }
            while (subscription.queue.size() > subscriberQueueCapacity) {
                subscription.queue.removeFirst();
            }
            subscription.paused = false;
        }
    }

    private void deliver(Subscription subscription, String payload) {
        try {
            subscription.consumer.accept(payload);
        } catch (Exception e) {
            log.error("推送会话消息失败, cid {}", subscription.state.conversationId, e);
            subscription.close();
        }
    }

    /**
     * 批量发送指定频道
     */
    private void flush(ChannelState state) {
        List<String> batch;
        synchronized (state.lock) {
            if (state.closed || state.pending.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(state.pending);
            state.pending.clear();
        }
        pushToRedis(state, batch);
    }

    private void pushToRedis(ChannelState state, List<String> batch) {
        if (batch.isEmpty()) {
            return;
        }
        String messageKey = messagesKey(state.conversationId);
        try {
            // 顺序不能反：先 PUBLISH 让在线订阅者拿到，再写回放列表
            redisTemplate.convertAndSend(state.channel, JSON.toJSONString(batch));
            redisTemplate.opsForList().rightPushAll(messageKey, batch);
            redisTemplate.opsForList().trim(messageKey, -replayLimit, -1);
            redisTemplate.expire(messageKey, replayTtlMinutes, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("批量发送失败, cid {}", state.conversationId, e);
            synchronized (state.lock) {
                // 收尾阶段不重排：频道已关闭，重排只会让下一轮 flush 复写 key
                if (!state.closed) {
                    for (int i = batch.size() - 1; i >= 0; i--) {
                        state.pending.addFirst(batch.get(i));
                    }
                    trimPending(state);
                }
            }
        }
    }

    private void deleteMessagesQuietly(String conversationId) {
        try {
            redisTemplate.delete(messagesKey(conversationId));
        } catch (Exception e) {
            log.warn("删除会话回放失败, cid {}", conversationId, e);
        }
    }

    private void removeClosedChannel(ChannelState state, long closedAt) {
        synchronized (state.lock) {
            // 期间可能已经开了新一轮（open 会把 closed 置回 false），不能误删
            if (state.closed && state.closedAt == closedAt) {
                channels.remove(state.conversationId, state);
            }
        }
    }

    private void trimPending(ChannelState state) {
        while (state.pending.size() > pendingCapacity) {
            state.pending.removeFirst();
        }
    }

    /**
     * 定期 flush 所有频道
     */
    void flushAll() {
        for (ChannelState state : channels.values()) {
            try {
                flush(state);
            } catch (Exception e) {
                log.error("flush 失败, cid {}", state.conversationId, e);
            }
        }
    }

    /**
     * 泵：把订阅队列里的批次投递出去，并清理空闲连接。
     */
    void drainAll() {
        long now = System.currentTimeMillis();
        long idleMillis = TimeUnit.MINUTES.toMillis(Math.max(1, idleTimeoutMinutes));
        for (ChannelState state : channels.values()) {
            List<Subscription> subscriptions;
            synchronized (state.lock) {
                if (state.subscriptions.isEmpty()) {
                    continue;
                }
                subscriptions = new ArrayList<>(state.subscriptions);
            }
            for (Subscription subscription : subscriptions) {
                try {
                    subscription.drain();
                } catch (Exception e) {
                    log.error("投递会话消息失败, cid {}", state.conversationId, e);
                    subscription.close();
                    continue;
                }
                if (!subscription.closed
                        && !subscription.paused
                        && now - subscription.lastActivity > idleMillis) {
                    log.warn("会话订阅空闲超时，关闭, cid {}, idleMs {}", state.conversationId, now - subscription.lastActivity);
                    subscription.close();
                }
            }
        }
    }

    /**
     * Redis 订阅只注册一次：所有会话共用一条 pattern 监听，靠本地 {@link #channels} 分流。
     */
    private void ensureRedisListener() {
        if (subscribed) {
            return;
        }
        synchronized (this) {
            if (subscribed) {
                return;
            }
            MessageListener listener = (message, pattern) -> {
                try {
                    String topic = new String(message.getChannel(), StandardCharsets.UTF_8);
                    if (!topic.startsWith(CHANNEL_PREFIX)) {
                        return;
                    }
                    String conversationId = topic.substring(CHANNEL_PREFIX.length());
                    Object deserialized = redisTemplate.getValueSerializer().deserialize(message.getBody());
                    if (deserialized != null) {
                        dispatch(conversationId, deserialized.toString());
                    }
                } catch (Exception e) {
                    log.error("处理 Redis 会话消息失败", e);
                }
            };
            container.addMessageListener(listener, new PatternTopic(CHANNEL_PATTERN));
            subscribed = true;
        }
    }

    /**
     * 把一批实时消息投递到各订阅队列；队满说明订阅者已经跟不上，直接逐出。
     */
    void dispatch(String conversationId, String payload) {
        ChannelState state = channels.get(conversationId);
        if (state == null) {
            return;
        }
        // 线上的 payload 是一批消息（JSON 数组）；拆开后再入队，订阅方永远只见到单条消息
        List<String> messages = splitPayload(payload);
        if (messages.isEmpty()) {
            return;
        }
        List<Subscription> subscriptions;
        synchronized (state.lock) {
            subscriptions = new ArrayList<>(state.subscriptions);
        }
        for (Subscription subscription : subscriptions) {
            for (String message : messages) {
                if (subscription.closed) {
                    break;
                }
                if (!subscription.offer(message)) {
                    log.warn("订阅队列已满（上限 {} 条），逐出慢订阅, cid {}", subscriberQueueCapacity, conversationId);
                    subscription.close();
                    break;
                }
            }
        }
    }

    private String messagesKey(String conversationId) {
        return CHANNEL_PREFIX + conversationId + MESSAGES_SUFFIX;
    }

    /**
     * 把一条 payload 拆成消息列表。历史上的 payload 都是 JSON 数组；单条 JSON 也兼容。
     */
    private static List<String> splitPayload(String payload) {
        if (payload == null) {
            return Collections.emptyList();
        }
        if (JSON.isValidArray(payload)) {
            JSONArray array = JSON.parseArray(payload);
            List<String> messages = new ArrayList<>(array.size());
            for (int i = 0; i < array.size(); i++) {
                messages.add(array.getString(i));
            }
            return messages;
        }
        return Collections.singletonList(payload);
    }

    /**
     * 一个 SSE 连接在本机的订阅状态。
     */
    public final class Subscription {

        private final ChannelState state;

        private final Consumer<String> consumer;

        private final Runnable onTerminate;

        private final Object lock = new Object();

        /** 待投递的单条消息（Redis 上的批量 payload 在 dispatch 里已拆开） */
        private final ArrayDeque<String> queue = new ArrayDeque<>();

        /** 回放尚未读完：只入队、不投递，保证回放在实时消息之前 */
        private volatile boolean paused;

        private volatile boolean closed;

        private volatile long lastActivity = System.currentTimeMillis();

        private Subscription(ChannelState state, Consumer<String> consumer, Runnable onTerminate) {
            this.state = state;
            this.consumer = consumer;
            this.onTerminate = onTerminate;
        }

        private void pause() {
            paused = true;
        }

        /**
         * @return {@code false} 表示队列已满（需要逐出）或已关闭
         */
        boolean offer(String payload) {
            synchronized (lock) {
                if (closed) {
                    return false;
                }
                if (queue.size() >= Math.max(1, subscriberQueueCapacity)) {
                    return false;
                }
                queue.addLast(payload);
                lastActivity = System.currentTimeMillis();
                return true;
            }
        }

        void drain() {
            while (true) {
                String payload;
                synchronized (lock) {
                    if (closed || paused) {
                        return;
                    }
                    payload = queue.pollFirst();
                }
                if (payload == null) {
                    return;
                }
                deliver(this, payload);
                lastActivity = System.currentTimeMillis();
            }
        }

        /**
         * 幂等关闭：清空队列、从频道摘除、通知调用方结束 SSE。
         */
        public void close() {
            synchronized (lock) {
                if (closed) {
                    return;
                }
                closed = true;
                queue.clear();
            }
            synchronized (state.lock) {
                state.subscriptions.remove(this);
            }
            try {
                onTerminate.run();
            } catch (Exception e) {
                log.warn("订阅关闭回调失败, cid {}", state.conversationId, e);
            }
        }

        public boolean isClosed() {
            return closed;
        }
    }

    private static final class ChannelState {

        private final String conversationId;

        private final String channel;

        private final Object lock = new Object();

        /** 尚未 flush 的消息 */
        private final ArrayDeque<String> pending = new ArrayDeque<>();

        /** 本机会话订阅者，按注册顺序排列（超限时逐出最旧的那个） */
        private final LinkedHashSet<Subscription> subscriptions = new LinkedHashSet<>();

        private boolean closed;

        private long closedAt;

        private ChannelState(String conversationId) {
            this.conversationId = conversationId;
            this.channel = CHANNEL_PREFIX + conversationId;
        }
    }
}