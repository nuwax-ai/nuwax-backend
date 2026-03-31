package com.xspaceagi.im.application.wechat;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.im.application.ImAgentOutputProcessService;
import com.xspaceagi.im.application.ImChannelConfigApplicationService;
import com.xspaceagi.im.application.ImSessionApplicationService;
import com.xspaceagi.im.application.WechatIlinkAgentApplicationService;
import com.xspaceagi.im.application.dto.ImChannelConfigDto;
import com.xspaceagi.im.infra.dao.enitity.ImSession;
import com.xspaceagi.im.infra.enums.ImChannelEnum;
import com.xspaceagi.im.infra.enums.ImChatTypeEnum;
import com.xspaceagi.im.infra.enums.ImTargetTypeEnum;
import com.xspaceagi.im.wechat.ilink.IlinkConstants;
import com.xspaceagi.im.wechat.ilink.IlinkHttpClient;
import com.xspaceagi.im.wechat.ilink.WechatIlinkMessageHelper;
import com.xspaceagi.im.wechat.ilink.WechatIlinkProtocolExtras;
import com.xspaceagi.im.wechat.ilink.dto.GetUpdatesResp;
import com.xspaceagi.im.wechat.ilink.dto.MessageItem;
import com.xspaceagi.im.wechat.ilink.dto.WeixinMessage;
import com.xspaceagi.system.sdk.service.ScheduleTaskApiService;
import com.xspaceagi.system.sdk.service.dto.ScheduleTaskDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 微信 iLink getUpdates 长轮询。
 * 调度模型：每个应拉取的渠道在 {@code schedule_task} 中一条记录（{@link #ILINK_POLL_TASK_ID_PREFIX}），
 * 由系统定时任务模块按 {@link #pollScheduleCron} 触发 {@link WechatIlinkPollScheduleTask}，
 * 每次执行只做<strong>一轮</strong> {@link #pollOnce}（内含长轮询 HTTP）。并发度由任务条数与调度执行决定。
 * 注册/注销：{@link #reconcileSingleConfig(Long)} 应对单条配置变更；{@link #reconcileWorkers()} 用于启动全量对齐。
 * 多实例：{@link #LOCK_PREFIX} 分布式锁保证同一 {@code configId} 同一时刻仅一个实例执行 getUpdates。
 */
@Slf4j
@Service
public class WechatIlinkLongPollService {

    /** 与 {@code schedule_task.task_id} 对应，格式：{@value #ILINK_POLL_TASK_ID_PREFIX}{configId} */
    public static final String ILINK_POLL_TASK_ID_PREFIX = "wechat_ilink_poll_";

    /** 与 {@link WechatIlinkPollScheduleTask} 上 {@code @Component} 名称一致，供 {@link #ensureScheduleTask} 注册 */
    public static final String SCHEDULE_TASK_BEAN_ID = "wechatIlinkPollScheduleTask";

    /** Redis：getUpdates 游标、消息幂等、分布式锁、配置缓存（field=configId） */
    private static final String BUF_PREFIX = "wechat_ilink:buf:";
    private static final String MSG_PREFIX = "wechat_ilink:msg:";
    private static final String LOCK_PREFIX = "wechat_ilink:poll_lock:";
    private static final String REDIS_POLL_CFG_CACHE_HASH_KEY = "wechat_ilink:poll_cfg";
    private static final long TYPING_KEEPALIVE_INTERVAL_SECONDS = 5L;

    private static final int MSG_TTL_SECONDS = 300;
    private static final int LOCK_TTL_SECONDS = 55;
    /** getUpdates 游标 buf 在 Redis 中的过期时间（秒）；正常轮询会不断续期 */
    private static final long BUF_TTL_SECONDS = 86400L;
    private static final String INSTANCE_ID = UUID.randomUUID().toString();
    private static final ScheduledExecutorService TYPING_KEEPALIVE_EXECUTOR = Executors.newScheduledThreadPool(
            2,
            r -> {
                Thread t = new Thread(r, "wechat-ilink-typing-keepalive");
                t.setDaemon(true);
                return t;
            }
    );

    /** 本进程最近一次 reconcile 的 configId 列表，用于全量 reconcile 时对比并注销已下线任务 */
    private volatile List<Long> pollConfigSnapshot = List.of();
    private final Object pollSnapshotLock = new Object();

    /** 空游标仍返回 -14 的连续次数；会话失效需重新扫码 */
    private final ConcurrentHashMap<Long, Integer> ilinkEmptyBufSessionTimeoutStreak = new ConcurrentHashMap<>();

    /** 共用 Hash 上配置缓存 TTL（秒）；≤0 表示不写/不读 Redis 缓存（每次打库） */
    private final int pollConfigCacheTtlSeconds = IlinkConstants.DEFAULT_POLL_CONFIG_CACHE_TTL_SECONDS;

    /** 调度任务 cron，默认约每秒触发一次 */
    private final String pollScheduleCron = IlinkConstants.DEFAULT_POLL_SCHEDULE_CRON;
    /** 冷启动（buf 为空）时 getUpdates 超时，默认 35s */
    private final int getUpdatesColdTimeoutMs = IlinkConstants.DEFAULT_GET_UPDATES_POLL_TIMEOUT_MS;
    /** 热态（buf 非空）时 getUpdates 超时，默认 300ms */
    private final int getUpdatesHotTimeoutMs = IlinkConstants.DEFAULT_GET_UPDATES_HOT_TIMEOUT_MS;

    @Resource
    private ScheduleTaskApiService scheduleTaskApiService;
    @Resource
    private RedisUtil redisUtil;
    @Resource
    private IlinkHttpClient ilinkHttpClient;
    @Resource
    private ImAgentOutputProcessService imAgentOutputProcessService;
    @Resource
    private ImChannelConfigApplicationService imChannelConfigApplicationService;
    @Resource
    private WechatIlinkAgentApplicationService wechatIlinkAgentApplicationService;
    @Resource
    private ImSessionApplicationService imSessionApplicationService;
    @Resource
    private WechatIlinkInboundMediaService wechatIlinkInboundMediaService;
    @Resource
    private WechatIlinkContextTokenStore wechatIlinkContextTokenStore;

//    @EventListener(ApplicationReadyEvent.class)
//    public void onApplicationReady() {
//        reconcileWorkers();
//    }

    /**
     * 配置中的 botToken / baseUrl 更新后调用：清空 getUpdates 游标与 -14 计数，避免旧游标与新 token 不匹配。
     */
    public void invalidatePollCursor(Long configId) {
        if (configId == null) {
            return;
        }
        try {
            redisUtil.set(BUF_PREFIX + configId, "", BUF_TTL_SECONDS);
            // Rebind/update should not be blocked by a stale in-flight lock from previous token/session.
            redisUtil.deleteKey(LOCK_PREFIX + configId);
        } catch (Exception e) {
            log.debug("wechat ilink clear poll lock failed, configId={}", configId, e);
        }
        ilinkEmptyBufSessionTimeoutStreak.remove(configId);
        try {
            redisUtil.hashDelete(REDIS_POLL_CFG_CACHE_HASH_KEY, String.valueOf(configId));
        } catch (Exception e) {
            log.warn("wechat ilink delete poll config cache failed, configId={}", configId, e);
        }
        try {
            // Rebind creates a new bot session; old context tokens may map to stale conversation context.
            wechatIlinkContextTokenStore.clearForConfig(configId);
        } catch (Exception e) {
            log.warn("wechat ilink clear context tokens on cursor invalidation failed, configId={}", configId, e);
        }
        log.debug("wechat ilink poll cursor invalidated, configId={}", configId);
    }

    /**
     * 配置已从库中删除（逻辑删除）后调用：取消调度任务、清理本渠道轮询相关的 Redis 与内存快照。
     * <p>
     * 说明：消息幂等键（前缀 {@code wechat_ilink:msg:}）按 messageId 存储，无法按 configId 批量删除，依赖 TTL 自然过期。
     */
    public void removePollForDeletedConfig(Long configId) {
        if (configId == null) {
            return;
        }
        cancelScheduleTask(configId);
        try {
            redisUtil.deleteKey(BUF_PREFIX + configId);
            redisUtil.deleteKey(LOCK_PREFIX + configId);
            redisUtil.hashDelete(REDIS_POLL_CFG_CACHE_HASH_KEY, String.valueOf(configId));
        } catch (Exception e) {
            log.warn("wechat ilink remove poll redis keys failed, configId={}", configId, e);
        }
        try {
            wechatIlinkContextTokenStore.clearForConfig(configId);
        } catch (Exception e) {
            log.warn("wechat ilink clear context tokens failed, configId={}", configId, e);
        }
        ilinkEmptyBufSessionTimeoutStreak.remove(configId);
        synchronized (pollSnapshotLock) {
            List<Long> snap = new ArrayList<>(pollConfigSnapshot);
            snap.remove(configId);
            pollConfigSnapshot = Collections.unmodifiableList(snap);
        }
        log.info("wechat ilink poll removed for deleted configId={}", configId);
    }

    /**
     * 单个渠道变更时调用：只查该 config，注册或注销 {@code schedule_task}，并更新内存快照。
     * 启动或全量对齐请用 {@link #reconcileWorkers()}。
     */
    public void reconcileSingleConfig(Long configId) {
        if (configId == null) {
            return;
        }
        ImChannelConfigDto dto = imChannelConfigApplicationService.getDtoById(configId);
        boolean eligible = isWechatIlinkPollEligible(dto);
        synchronized (pollSnapshotLock) {
            List<Long> snap = new ArrayList<>(pollConfigSnapshot);
            if (eligible) {
                ensureScheduleTask(configId);
                if (!snap.contains(configId)) {
                    snap.add(configId);
                }
            } else {
                cancelScheduleTask(configId);
                snap.remove(configId);
                try {
                    redisUtil.deleteKey(BUF_PREFIX + configId);
                    redisUtil.deleteKey(LOCK_PREFIX + configId);
                } catch (Exception e) {
                    log.debug("wechat ilink remove poll buf/lock on ineligible, configId={}", configId, e);
                }
                if (pollConfigCacheTtlSeconds > 0) {
                    try {
                        redisUtil.hashDelete(REDIS_POLL_CFG_CACHE_HASH_KEY, String.valueOf(configId));
                    } catch (Exception e) {
                        log.debug("wechat ilink remove poll cfg cache field, configId={}", configId, e);
                    }
                }
                ilinkEmptyBufSessionTimeoutStreak.remove(configId);
            }
            pollConfigSnapshot = Collections.unmodifiableList(snap);
        }
        log.debug("wechat ilink reconcile single configId={}, eligible={}", configId, eligible);
    }

    /** 是否应注册调度任务并执行拉取（渠道类型、启用、botToken） */
    static boolean isWechatIlinkPollEligible(ImChannelConfigDto dto) {
        if (dto == null || dto.getId() == null) {
            return false;
        }
        if (!ImChannelEnum.WECHAT_ILINK.getCode().equals(dto.getChannel())) {
            return false;
        }
        if (Boolean.FALSE.equals(dto.getEnabled())) {
            return false;
        }
        if (dto.getWechatIlink() == null || StringUtils.isBlank(dto.getWechatIlink().getBotToken())) {
            return false;
        }
        return true;
    }

    /**
     * 全量刷新：分页查询所有启用的微信 iLink 配置，注册/注销 {@code schedule_task}。
     * 用于启动时或需要与 DB 完全一致时。
     */
    public void reconcileWorkers() {
        List<Long> previous = new ArrayList<>(pollConfigSnapshot);
        List<Long> ids = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<ImChannelConfigDto> list = imChannelConfigApplicationService.listWechatIlinkEnabledByPage(offset, 100);
            if (list == null || list.isEmpty()) {
                break;
            }
            for (ImChannelConfigDto dto : list) {
                if (dto.getId() == null) {
                    continue;
                }
                if (dto.getWechatIlink() == null || StringUtils.isBlank(dto.getWechatIlink().getBotToken())) {
                    continue;
                }
                ids.add(dto.getId());
            }
            offset += 100;
            if (list.size() < 100) {
                break;
            }
        }
        Set<Long> nextSet = new HashSet<>(ids);
        for (Long oldId : previous) {
            if (!nextSet.contains(oldId)) {
                cancelScheduleTask(oldId);
            }
        }
        for (Long configId : ids) {
            ensureScheduleTask(configId);
        }
        synchronized (pollSnapshotLock) {
            pollConfigSnapshot = Collections.unmodifiableList(ids);
        }
        prunePollConfigCacheFieldsNotIn(ids);
        log.info("wechat ilink poll reconciled, configCount={}, scheduleTasks synced", ids.size());
    }

    private void ensureScheduleTask(Long configId) {
        if (configId == null) {
            return;
        }
        try {
            scheduleTaskApiService.start(ScheduleTaskDto.builder()
                    .taskId(ILINK_POLL_TASK_ID_PREFIX + configId)
                    .beanId(SCHEDULE_TASK_BEAN_ID)
                    .cron(pollScheduleCron)
                    .maxExecTimes(Long.MAX_VALUE)
                    .params(Map.of("configId", configId))
                    .taskName("微信iLink消息拉取")
                    .targetType("WechatIlink")
                    .targetId(String.valueOf(configId))
                    .build());
        } catch (Exception e) {
            log.warn("wechat ilink register schedule task failed, configId={}", configId, e);
        }
    }

    private void cancelScheduleTask(Long configId) {
        if (configId == null) {
            return;
        }
        try {
            scheduleTaskApiService.cancel(ILINK_POLL_TASK_ID_PREFIX + configId);
        } catch (Exception e) {
            log.warn("wechat ilink cancel schedule task failed, configId={}", configId, e);
        }
    }

    /**
     * 供 {@link WechatIlinkPollScheduleTask} 调用：单次调度入口，内部只做一轮 {@link #pollOnce}。
     */
    public void executePollOnce(Long configId) {
        pollOnce(configId);
    }

    /**
     * 重绑/配置更新后强制收敛一次运行态：取消旧任务、清缓存、重建任务并立即拉取一轮。
     * 保持现有线程模型，不引入额外常驻轮询线程。
     */
    public void forceReloadAndPollOnce(Long configId) {
        if (configId == null) {
            return;
        }
        cancelScheduleTask(configId);
        invalidatePollCursor(configId);
        reconcileSingleConfig(configId);
        executePollOnce(configId);
        log.debug("wechat ilink force reload and poll once done, configId={}", configId);
    }

    /**
     * 轮询列表刷新后，删除共用 Hash 中已不在列表内的 field，避免残留 id 占用内存。
     */
    private void prunePollConfigCacheFieldsNotIn(List<Long> activeIds) {
        if (pollConfigCacheTtlSeconds <= 0) {
            return;
        }
        try {
            Set<Object> fields = redisUtil.hashKeys(REDIS_POLL_CFG_CACHE_HASH_KEY);
            if (fields == null || fields.isEmpty()) {
                return;
            }
            Set<Long> active = new HashSet<>(activeIds);
            for (Object f : fields) {
                if (f == null) {
                    continue;
                }
                String fk = f.toString();
                if (StringUtils.isBlank(fk)) {
                    continue;
                }
                long pid;
                try {
                    pid = Long.parseLong(fk);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (!active.contains(pid)) {
                    redisUtil.hashDelete(REDIS_POLL_CFG_CACHE_HASH_KEY, fk);
                }
            }
        } catch (Exception e) {
            log.debug("wechat ilink prune poll config cache fields failed", e);
        }
    }

    /**
     * 优先读 Redis Hash 缓存，未命中再查库并回写；与 {@link #invalidatePollCursor(Long)} 配合保证 token 等变更可见。
     */
    private ImChannelConfigDto loadDtoForPoll(Long configId) {
        if (pollConfigCacheTtlSeconds <= 0) {
            return imChannelConfigApplicationService.getDtoById(configId);
        }
        String field = String.valueOf(configId);
        try {
            Object cached = redisUtil.hashGet(REDIS_POLL_CFG_CACHE_HASH_KEY, field);
            if (cached instanceof String s && StringUtils.isNotBlank(s)) {
                ImChannelConfigDto dto = JSON.parseObject(s, ImChannelConfigDto.class);
                if (dto != null && dto.getId() != null) {
                    return dto;
                }
            }
        } catch (Exception e) {
            log.debug("wechat ilink poll config cache read failed, configId={}", configId, e);
        }
        ImChannelConfigDto dto = imChannelConfigApplicationService.getDtoById(configId);
        if (dto != null) {
            putPollConfigCache(configId, dto);
        }
        return dto;
    }

    private void putPollConfigCache(Long configId, ImChannelConfigDto dto) {
        if (pollConfigCacheTtlSeconds <= 0 || dto == null) {
            return;
        }
        try {
            redisUtil.hashPut(REDIS_POLL_CFG_CACHE_HASH_KEY, String.valueOf(configId), JSON.toJSONString(dto));
            redisUtil.expire(REDIS_POLL_CFG_CACHE_HASH_KEY, pollConfigCacheTtlSeconds);
        } catch (Exception e) {
            log.warn("wechat ilink put poll config cache failed, configId={}", configId, e);
        }
    }

    /**
     * 单次调度内的一轮拉取：抢锁 → getUpdates（长轮询阻塞）→ 处理消息。
     * 资格与 {@link #reconcileSingleConfig(Long)} / 全量列表一致，统一用 {@link #isWechatIlinkPollEligible}。
     * <p>
     * 资格判断走 {@link #loadDtoForPoll(Long)}（优先 Redis Hash，减轻 DB；允许与库表短暂不一致）。
     * 若当前视图下不可拉取，仍调用 {@link #reconcileSingleConfig(Long)}：内部会再查库对齐，必要时 {@code cancel} 调度并清理 buf/锁/快照。
     * 重新启用后由配置变更路径 {@code reconcileSingleConfig} 会 {@link #ensureScheduleTask(Long)} 注册任务。
     */
    private void pollOnce(Long configId) {
        try {
            ImChannelConfigDto dto = loadDtoForPoll(configId);
            if (!isWechatIlinkPollEligible(dto)) {
                log.debug("wechat ilink poll ineligible, reconcile to cancel schedule if needed, configId={}", configId);
                reconcileSingleConfig(configId);
                return;
            }

            String lockKey = LOCK_PREFIX + configId;
            if (!redisUtil.setIfAbsent(lockKey, INSTANCE_ID, LOCK_TTL_SECONDS)) {
                return;
            }
            try {
                ImChannelConfigDto.WechatIlinkConfig w = dto.getWechatIlink();
                String baseUrl = StringUtils.defaultIfBlank(w.getBaseUrl(), IlinkConstants.DEFAULT_BASE_URL);
                String bufKey = BUF_PREFIX + configId;
                String buf = redisUtil.get(bufKey) instanceof String s ? s : "";
                int timeout = resolveGetUpdatesTimeoutMs(buf);
                String ilinkAccountId = w.getIlinkAccountId();
                GetUpdatesResp resp = ilinkHttpClient.getUpdatesLenient(baseUrl, w.getBotToken(), buf, timeout, ilinkAccountId);
                
                Integer ret = resp.getRet();
                Integer ec = resp.getErrcode();
                Integer code = ec != null ? ec : ret;
                boolean hasApiError = (ret != null && ret != 0) || (ec != null && ec != 0);

                if (hasApiError && code == IlinkConstants.GET_UPDATES_ERR_SESSION_TIMEOUT) {
                    redisUtil.set(bufKey, "", BUF_TTL_SECONDS);
                    if (StringUtils.isNotBlank(buf)) {
                        ilinkEmptyBufSessionTimeoutStreak.remove(configId);
                        log.debug("wechat ilink getUpdates -14, cleared non-empty cursor, configId={}", configId);
                        return;
                    }
                    int streak = ilinkEmptyBufSessionTimeoutStreak.merge(configId, 1, Integer::sum);
                    if (streak == 1) {
                        log.warn("wechat ilink getUpdates session timeout (ret={}, errcode={}) with empty cursor; "
                                + "upstream session or botToken is invalid — re-scan QR to refresh bot token. configId={}", ret, ec, configId);
                        imChannelConfigApplicationService.disableWechatIlinkOnSessionExpired(configId);
                    } else if (streak % 20 == 0) {
                        log.warn("wechat ilink getUpdates still session-timeout with empty cursor (ret={}, errcode={}, streak={}), configId={}",
                                ret, ec, streak, configId);
                    }
                    return;
                }
                if (hasApiError) {
                    log.warn("wechat ilink getUpdates error, configId={}, ret={}, errcode={}, errmsg={}",
                            configId, ret, ec, resp.resolveErrmsg());
                    return;
                }
                ilinkEmptyBufSessionTimeoutStreak.remove(configId);
                if (resp.getGetUpdatesBuf() != null) {
                    redisUtil.set(bufKey, resp.getGetUpdatesBuf(), BUF_TTL_SECONDS);
                }
                if (resp.getMsgs() != null && !resp.getMsgs().isEmpty()) {
                    handleInboundMessagesBatch(resp.getMsgs(), dto);
                }
            } finally {
                redisUtil.unlock(lockKey);
            }
        } catch (Exception e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.warn("wechat ilink poll error configId={}", configId, e);
        }
    }

    /** buf 为空视为重绑/冷启动建链阶段，使用长超时；非空走热态短超时。 */
    private int resolveGetUpdatesTimeoutMs(String buf) {
        int cold = Math.max(getUpdatesColdTimeoutMs, 1);
        int hot = Math.max(getUpdatesHotTimeoutMs, 1);
        return StringUtils.isBlank(buf) ? cold : hot;
    }

    /**
     * 一次 getUpdates 若返回多条用户消息：按 {@code from_user_id} 分组（同一批内），
     * 同组内合并为一条文本 + 合并附件后只调用一次智能体；单条仍走 {@link #handleInboundMessage}。
     * <p>
     * 若按 {@code from + context_token} 分组，上游常对每条消息给不同 {@code context_token}，会拆成多组并连续调智能体，
     * 在沙箱仍 busy 时第二条会失败（9010）。
     */
    private void handleInboundMessagesBatch(List<WeixinMessage> msgs, ImChannelConfigDto channelDto) {
        if (msgs == null || msgs.isEmpty()) {
            return;
        }
        List<WeixinMessage> userMsgs = new ArrayList<>();
        for (WeixinMessage m : msgs) {
            if (m != null && WechatIlinkMessageHelper.isUserMessage(m)) {
                userMsgs.add(m);
            }
        }
        if (userMsgs.isEmpty()) {
            return;
        }
        Map<String, List<WeixinMessage>> groups = new LinkedHashMap<>();
        for (WeixinMessage m : userMsgs) {
            String from = m.getFromUserId();
            if (StringUtils.isBlank(from)) {
                log.warn("wechat ilink skip message in batch: missing from mid={}", m.getMessageId());
                continue;
            }
            groups.computeIfAbsent(from, k -> new ArrayList<>()).add(m);
        }
        Comparator<WeixinMessage> byTime = Comparator
                .comparing((WeixinMessage x) -> Optional.ofNullable(x.getCreateTimeMs()).orElse(0L))
                .thenComparing(x -> Optional.ofNullable(x.getMessageId()).orElse(0L));
        for (List<WeixinMessage> group : groups.values()) {
            group.sort(byTime);
        }
        for (List<WeixinMessage> group : groups.values()) {
            if (group.isEmpty()) {
                continue;
            }
            if (group.size() == 1) {
                handleInboundMessage(group.get(0), channelDto);
            } else {
                handleMergedInboundMessages(group, channelDto);
            }
        }
    }

    /**
     * 同一会话、同一批中的多条消息合并为一条入参；若任一条 messageId 已处理则整批跳过（防重复投递）。
     * 回复使用组内时间序最后一条非空 {@code context_token}（与上游逐条 token 对齐）。
     */
    private void handleMergedInboundMessages(List<WeixinMessage> group, ImChannelConfigDto channelDto) {
        for (WeixinMessage m : group) {
            Long mid = m.getMessageId();
            if (mid != null && redisUtil.get(MSG_PREFIX + mid) != null) {
                log.debug("wechat ilink merged batch skip: duplicate messageId={}", mid);
                return;
            }
        }
        WeixinMessage first = group.get(0);
        String from = first.getFromUserId();
        for (WeixinMessage m : group) {
            if (m != null && StringUtils.isNotBlank(m.getContextToken())) {
                wechatIlinkContextTokenStore.save(channelDto.getId(), from, m.getContextToken());
            }
        }
        String contextToken = resolveReplyContextToken(group);
        if (StringUtils.isBlank(contextToken)) {
            contextToken = wechatIlinkContextTokenStore.get(channelDto.getId(), from);
        }
        if (StringUtils.isBlank(contextToken)) {
            log.warn("wechat ilink merged batch: no context_token in message or Redis, still dispatch agent, messageIds={}",
                    group.stream().map(WeixinMessage::getMessageId).toList());
        }
        for (WeixinMessage m : group) {
            Long mid = m.getMessageId();
            if (mid != null) {
                redisUtil.set(MSG_PREFIX + mid, "1", MSG_TTL_SECONDS);
            }
        }
        List<AttachmentDto> allAttachments = new ArrayList<>();
        List<String> parts = new ArrayList<>();
        for (WeixinMessage m : group) {
            var att = wechatIlinkInboundMediaService.buildAttachmentsFromMessage(m, channelDto);
            if (att != null && !att.isEmpty()) {
                allAttachments.addAll(att);
            }
            String part = buildInboundUserText(m);
            if (StringUtils.isBlank(part)) {
                if (att != null && !att.isEmpty()) {
                    part = "[用户发送了附件]";
                } else {
                    part = "[用户发送了非文本内容]";
                }
            }
            parts.add(part);
        }
        String mergedText = String.join("\n\n", parts);
        if (StringUtils.isBlank(mergedText) && !allAttachments.isEmpty()) {
            mergedText = "[用户发送了附件]";
        }
        if (StringUtils.isBlank(mergedText) && allAttachments.isEmpty()) {
            mergedText = "[用户发送了非文本内容]";
        }
        log.info("wechat ilink dispatch agent (merged), configId={}, count={}, fromUserId={}, messageIds={}",
                channelDto.getId(), group.size(), from,
                group.stream().map(WeixinMessage::getMessageId).toList());
        runAgentAndReply(channelDto, from, contextToken, mergedText, allAttachments);
    }

    /** 从后往前取第一条非空 context_token，供合并回复与最新一条用户消息对齐。 */
    private static String resolveReplyContextToken(List<WeixinMessage> group) {
        if (group == null || group.isEmpty()) {
            return null;
        }
        for (int i = group.size() - 1; i >= 0; i--) {
            String ct = group.get(i).getContextToken();
            if (StringUtils.isNotBlank(ct)) {
                return ct;
            }
        }
        return null;
    }

    private void handleInboundMessage(WeixinMessage msg, ImChannelConfigDto channelDto) {
        if (msg == null) {
            return;
        }
        if (!WechatIlinkMessageHelper.isUserMessage(msg)) {
            return;
        }
        Long mid = msg.getMessageId();
        if (mid != null) {
            String dedupKey = MSG_PREFIX + mid;
            if (redisUtil.get(dedupKey) != null) {
                return;
            }
            redisUtil.set(dedupKey, "1", MSG_TTL_SECONDS);
        }
        String from = msg.getFromUserId();
        String contextToken = msg.getContextToken();
        if (StringUtils.isBlank(from)) {
            log.warn("wechat ilink skip message: missing from mid={}", mid);
            return;
        }
        if (StringUtils.isNotBlank(contextToken)) {
            wechatIlinkContextTokenStore.save(channelDto.getId(), from, contextToken);
        } else {
            contextToken = wechatIlinkContextTokenStore.get(channelDto.getId(), from);
        }
        if (StringUtils.isBlank(contextToken)) {
            log.warn("wechat ilink: no context_token in message or Redis, still dispatch agent, mid={}", mid);
        }

        String userText = buildInboundUserText(msg);
        var attachments = wechatIlinkInboundMediaService.buildAttachmentsFromMessage(msg, channelDto);
        if (StringUtils.isBlank(userText)) {
            if (attachments != null && !attachments.isEmpty()) {
                userText = "[用户发送了附件]";
            } else {
                userText = "[用户发送了非文本内容]";
            }
        }

        final String finalUserText = userText;
        final var finalAttachments = attachments;
        final String finalContextToken = contextToken;
        log.info("wechat ilink dispatch agent, configId={}, messageId={}, fromUserId={}", channelDto.getId(), mid, from);
        runAgentAndReply(channelDto, from, finalContextToken, finalUserText, finalAttachments);
    }

    private String buildInboundUserText(WeixinMessage msg) {
        String plain = WechatIlinkMessageHelper.extractFirstText(msg);
        if (StringUtils.isNotBlank(plain)) {
            return plain;
        }
        if (msg.getItemList() == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (MessageItem it : msg.getItemList()) {
            if (it == null || it.getType() == null) {
                continue;
            }
            switch (it.getType()) {
                case 2 -> sb.append("[用户发送了图片]\n");
                case 3 -> sb.append("[用户发送了语音]\n");
                case 4 -> sb.append("[用户发送了文件]\n");
                case 5 -> sb.append("[用户发送了视频]\n");
                default -> {
                }
            }
        }
        return sb.toString().trim();
    }

    /**
     * 非阻塞：智能体在 {@link reactor.core.scheduler.Schedulers#boundedElastic()} 上执行，本方法立即返回，回复在回调中发送。
     */
    private void runAgentAndReply(ImChannelConfigDto channelDto,
                                  String fromUserId,
                                  String contextToken,
                                  String userMessage,
                                  List<AttachmentDto> attachments) {
        ImChannelConfigDto.WechatIlinkConfig w = channelDto.getWechatIlink();
        if (w == null) {
            return;
        }
        if (isNewCommand(userMessage) && (attachments == null || attachments.isEmpty())) {
            try {
                createNewConversationForWechatIlink(channelDto, fromUserId);
                String baseUrl = StringUtils.defaultIfBlank(w.getBaseUrl(), IlinkConstants.DEFAULT_BASE_URL);
                String replyToken = effectiveContextTokenForReply(channelDto.getId(), fromUserId, contextToken);
                WeixinMessage reply = WechatIlinkMessageHelper.buildTextReply(
                        fromUserId, replyToken, "已为你创建新会话，后续消息默认走新会话");
                ilinkHttpClient.sendMessage(baseUrl, w.getBotToken(), reply, 15_000);
            } catch (Exception e) {
                log.error("wechat ilink create new conversation failed, configId={}, fromUserId={}",
                        channelDto.getId(), fromUserId, e);
                sendWechatIlinkErrorReply(channelDto.getId(), w, fromUserId, contextToken, e.getMessage());
            }
            return;
        }
        log.info("wechat ilink agent execute start, configId={}, fromUserId={}", channelDto.getId(), fromUserId);
        String baseUrl = StringUtils.defaultIfBlank(w.getBaseUrl(), IlinkConstants.DEFAULT_BASE_URL);
        String typingToken = effectiveContextTokenForReply(channelDto.getId(), fromUserId, contextToken);
        AtomicBoolean typingStarted = new AtomicBoolean(sendTypingStartSafely(w, baseUrl, fromUserId, typingToken));
        ScheduledFuture<?> typingHeartbeat = startTypingKeepalive(w, baseUrl, fromUserId, typingToken, typingStarted);
        wechatIlinkAgentApplicationService.executeAgentWithConv(
                        fromUserId,
                        userMessage,
                        attachments != null ? attachments : Collections.emptyList(),
                        channelDto.getTenantId(),
                        channelDto.getUserId(),
                        channelDto.getAgentId(),
                        fromUserId)
                .doFinally(st -> {
                    stopTypingKeepalive(typingHeartbeat);
                    if (typingStarted.get()) {
                        sendTypingCancelSafely(w, baseUrl, fromUserId, typingToken);
                    }
                })
                .subscribe(
                        result -> {
                            try {
                                String processed = imAgentOutputProcessService.processAgentOutput(
                                        result.getText(), result.getConversationId(), result.getAgentId(),
                                        channelDto.getTenantId(), channelDto.getUserId(), ImChannelEnum.WECHAT_ILINK.getCode());
                                if (StringUtils.isBlank(processed)) {
                                    processed = "已处理";
                                }
                                String replyToken = effectiveContextTokenForReply(channelDto.getId(), fromUserId, contextToken);
                                WeixinMessage reply = WechatIlinkMessageHelper.buildTextReply(fromUserId, replyToken, processed);
                                ilinkHttpClient.sendMessage(baseUrl, w.getBotToken(), reply, 15_000);
                                log.info("wechat ilink reply sent, configId={}, fromUserId={}, textLen={}", channelDto.getId(), fromUserId,
                                        processed.length());
                            } catch (Exception e) {
                                log.error("wechat ilink agent/reply failed fromUserId={}", fromUserId, e);
                                sendWechatIlinkErrorReply(channelDto.getId(), w, fromUserId, contextToken, e.getMessage());
                            }
                        },
                        err -> {
                            log.error("wechat ilink agent mono error fromUserId={}", fromUserId, err);
                            sendWechatIlinkErrorReply(channelDto.getId(), w, fromUserId, contextToken, err.getMessage());
                        });
    }

    private static boolean isNewCommand(String userMessage) {
        return "/new".equals(StringUtils.trimToEmpty(userMessage));
    }

    private void createNewConversationForWechatIlink(ImChannelConfigDto channelDto, String fromUserId) {
        // /new 命令从长轮询后台线程进入，无 HTTP RequestContext，需补齐最小租户上下文
        boolean hasRequestContext = RequestContext.get() != null;
        if (!hasRequestContext) {
            RequestContext<Object> requestContext = new RequestContext<>();
            requestContext.setTenantId(channelDto.getTenantId());
            RequestContext.set(requestContext);
        }
        try {
            ImSession imSession = ImSession.builder()
                    .channel(ImChannelEnum.WECHAT_ILINK.getCode())
                    .targetType(ImTargetTypeEnum.BOT.getCode())
                    .sessionKey(fromUserId)
                    .sessionName(fromUserId)
                    .chatType(ImChatTypeEnum.PRIVATE.getCode())
                    .userId(channelDto.getUserId())
                    .agentId(channelDto.getAgentId())
                    .tenantId(channelDto.getTenantId())
                    .build();
            imSessionApplicationService.createNewConversationId(imSession);
        } finally {
            if (!hasRequestContext) {
                RequestContext.remove();
            }
        }
    }

    private boolean sendTypingStartSafely(ImChannelConfigDto.WechatIlinkConfig w,
                                          String baseUrl,
                                          String fromUserId,
                                          String contextToken) {
        if (w == null || StringUtils.isBlank(w.getBotToken()) || StringUtils.isBlank(fromUserId) || StringUtils.isBlank(contextToken)) {
            return false;
        }
        try {
            WechatIlinkProtocolExtras.sendTypingIndicator(ilinkHttpClient, baseUrl, w.getBotToken(), fromUserId, contextToken);
            return true;
        } catch (Exception e) {
            log.debug("wechat ilink typing start failed, fromUserId={}", fromUserId, e);
            return false;
        }
    }

    private void sendTypingCancelSafely(ImChannelConfigDto.WechatIlinkConfig w,
                                        String baseUrl,
                                        String fromUserId,
                                        String contextToken) {
        if (w == null || StringUtils.isBlank(w.getBotToken()) || StringUtils.isBlank(fromUserId) || StringUtils.isBlank(contextToken)) {
            return;
        }
        try {
            WechatIlinkProtocolExtras.cancelTypingIndicator(ilinkHttpClient, baseUrl, w.getBotToken(), fromUserId, contextToken);
        } catch (Exception e) {
            log.debug("wechat ilink typing cancel failed, fromUserId={}", fromUserId, e);
        }
    }

    private ScheduledFuture<?> startTypingKeepalive(ImChannelConfigDto.WechatIlinkConfig w,
                                                    String baseUrl,
                                                    String fromUserId,
                                                    String contextToken,
                                                    AtomicBoolean typingStarted) {
        if (w == null || StringUtils.isBlank(w.getBotToken()) || StringUtils.isBlank(fromUserId) || StringUtils.isBlank(contextToken)) {
            return null;
        }
        return TYPING_KEEPALIVE_EXECUTOR.scheduleAtFixedRate(() -> {
            boolean ok = sendTypingStartSafely(w, baseUrl, fromUserId, contextToken);
            if (ok) {
                typingStarted.set(true);
            }
        }, TYPING_KEEPALIVE_INTERVAL_SECONDS, TYPING_KEEPALIVE_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stopTypingKeepalive(ScheduledFuture<?> future) {
        if (future == null) {
            return;
        }
        try {
            future.cancel(false);
        } catch (Exception e) {
            log.debug("wechat ilink typing keepalive cancel failed", e);
        }
    }

    /**
     * 优先使用当次入站 token，否则 Redis 最近 token；仍无则返回 null（对齐 openclaw-weixin 1.0.3 允许无 context 发送）。
     */
    private String effectiveContextTokenForReply(Long configId, String fromUserId, String messageToken) {
        if (StringUtils.isNotBlank(messageToken)) {
            return messageToken;
        }
        String cached = wechatIlinkContextTokenStore.get(configId, fromUserId);
        if (StringUtils.isNotBlank(cached)) {
            log.debug("wechat ilink: context_token fallback from Redis configId={}, fromUserId={}", configId, fromUserId);
            return cached;
        }
        log.warn("wechat ilink: no context_token for configId={}, fromUserId={}, sending without context (align 1.0.3)", configId, fromUserId);
        return null;
    }

    private void sendWechatIlinkErrorReply(Long configId,
                                           ImChannelConfigDto.WechatIlinkConfig w,
                                           String fromUserId,
                                           String contextToken,
                                           String errMsg) {
        try {
            String baseUrl = StringUtils.defaultIfBlank(w.getBaseUrl(), IlinkConstants.DEFAULT_BASE_URL);
            String replyToken = effectiveContextTokenForReply(configId, fromUserId, contextToken);
            WeixinMessage errReply = WechatIlinkMessageHelper.buildTextReply(fromUserId, replyToken,
                    "执行异常: " + (errMsg != null ? errMsg : "未知错误"));
            ilinkHttpClient.sendMessage(baseUrl, w.getBotToken(), errReply, 15_000);
        } catch (Exception ignored) {
            // ignore
        }
    }
}
