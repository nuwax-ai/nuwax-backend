package com.xspaceagi.system.application.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.xspaceagi.im.sdk.rpc.IImEventRpcService;
import com.xspaceagi.im.sdk.rpc.IImMessageRpcService;
import com.xspaceagi.im.sdk.rpc.IImRpcAvailability;
import com.xspaceagi.im.sdk.rpc.dto.*;
import com.xspaceagi.system.application.dto.*;
import com.xspaceagi.system.application.service.AuthService;
import com.xspaceagi.system.application.service.NotifyMessageApplicationService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.domain.service.NotifyMessageDomainService;
import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.infra.dao.entity.NotifyMessageUser;
import com.xspaceagi.system.infra.dao.service.NotifyMessageService;
import com.xspaceagi.system.sdk.service.INotificationRpcService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.tenant.thread.TenantThreadPoolExecutor;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

@Slf4j
@Service
public class NotifyMessageApplicationServiceImpl implements NotifyMessageApplicationService, INotificationRpcService {

    /**
     * ★IM 系统通知的会话级幂等键前缀（落到 IM 消息层的 {@code clientMsgId}）。
     */
    private static final String IM_BIZ_MSG_ID_PREFIX = "notify-";

    /**
     * ★{@code messages/system-notify} 单次收件人上限（IM 侧硬约束 {@code SYSTEM_NOTIFY_MAX_TARGETS}）。
     *
     * <p>★IM 侧是<b>整批拒绝</b>而不是静默截断，所以这里必须自己分批：超过一批就等着整条通知推不出去。
     */
    private static final int IM_SYSTEM_NOTIFY_MAX_TARGETS = 100;

    /**
     * ★日志里最多展开几条逐条失败明细（避免 IM 出问题时按收件人数量刷屏）。
     */
    private static final int IM_FAILURE_DETAIL_LIMIT = 3;

    /**
     * ★平台事件桥到 IM 时的幂等键前缀（IM 侧拿它当帧体的 {@code eventId}）。
     */
    private static final String IM_EVENT_BIZ_MSG_ID_PREFIX = "plat-evt-";

    /**
     * ★{@code events/push} 的 {@code payload} 本地闸门（IM 侧上限 16KB）。
     *
     * <p>刻意留了 1KB 余量：这里量的是<b>平台侧</b> Jackson 的序列化长度，与 IM 侧的口径
     * 不必逐字相同（null 字段出不出现、转义习惯都可能不一样），量到的只是近似值。
     * 宁可少推一条超大事件（平台 {@code event_queue} 里那份仍然完整），也不要让 IM 回一个
     * {@code IM_90001} —— 那个错在日志里长得像「IM 坏了」，实际是「事件太大了」。
     */
    private static final int IM_EVENT_MAX_PAYLOAD_BYTES = 15 * 1024;

    /**
     * ★★<b>不</b>桥到 IM 的平台事件类型。
     *
     * <p>{@code new_notify_message} 在这里，是因为同一件事<b>已经</b>由
     * {@code messages/system-notify} 承载了（见 {@link #pushImNotify}）：通知消息落库后
     * 会给每个收件人在「系统通知」会话里发一条带未读红点、进历史的消息。再把它桥成一条
     * 自定义事件，收件人就会为<b>同一条通知</b>收到两次触达，而事件那份还不落库、离线即丢
     * ⇒ 纯亏。
     *
     * <p>★顺带挡掉一个放大：{@code Broadcast} 是<b>逐个收件人</b>调 {@link #publishEvent}
     * 的（每批 1000 人 ⇒ 1000 次），不排除的话一条全员通知会放大成 1000 次
     * {@code events/push}，而 {@code system-notify} 那边一次就发完了 100 人。
     *
     * <p>⇒ 要把通知也桥过去，改这个集合就行；但那等于推翻「通知走系统通知会话」的既定行为，
     * 先想清楚收件端会不会双弹。
     */
    private static final Set<String> IM_EVENT_EXCLUDED_TYPES = Set.of(EventDto.EVENT_TYPE_NEW_NOTIFY_MESSAGE);

    @Resource
    private NotifyMessageDomainService notifyMessageDomainService;

    @Resource
    private NotifyMessageService notifyMessageService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private AuthService authService;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private IImMessageRpcService imMessageRpcService;

    /**
     * ★IM 自定义事件通道（{@code events/push}）—— 与 {@link #imMessageRpcService} 是<b>同一个</b>
     * {@code ImRpcClient} 单例（它一次实现了 message / conversation / contact / event / agent
     * 五个接口），按接口注入即可，装配见 {@code ImRpcConfiguration}。
     */
    @Resource
    private IImEventRpcService imEventRpcService;

    /**
     * ★IM 适配的启用开关（{@code nuwax.im.adapter.enabled}）。未启用时整批跳过推送，见 {@link #pushImNotify}。
     */
    @Resource
    private IImRpcAvailability imRpcAvailability;

    private static final AtomicLong THREAD_COUNTER = new AtomicLong();

    private TenantThreadPoolExecutor scheduledExecutorService = new TenantThreadPoolExecutor(Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "notify-pub-" + THREAD_COUNTER.getAndIncrement());
        t.setDaemon(true);
        return t;
    }));

    @Override
    public Long sendNotifyMessage(SendNotifyMessageDto sendNotifyMessageDto) {
        NotifyMessage notifyMessage = NotifyMessage.builder()
                .tenantId(sendNotifyMessageDto.getTenantId())
                .scope(sendNotifyMessageDto.getScope())
                .senderId(sendNotifyMessageDto.getSenderId())
                .content(sendNotifyMessageDto.getContent())
                .build();
        notifyMessageService.save(notifyMessage);
        if (sendNotifyMessageDto.getScope() == NotifyMessage.MessageScope.Broadcast) {
            Long lastUserId = Long.MAX_VALUE;
            int size = 1000;
            while (true) {
                List<Long> ids = userApplicationService.queryUserIdList(lastUserId, size);
                if (ids.isEmpty()) {
                    break;
                }
                notifyMessageDomainService.addNotifyMessage(notifyMessage, ids, imEnabled());
                ids.forEach(userId -> publishEvent(userId, EventDto.builder().type(EventDto.EVENT_TYPE_NEW_NOTIFY_MESSAGE).event(notifyMessage).build()));
                pushImNotify(notifyMessage, ids);
                lastUserId = ids.get(ids.size() - 1);
                size = ids.size();
            }
        } else {
            notifyMessageDomainService.addNotifyMessage(notifyMessage, sendNotifyMessageDto.getUserIds(), imEnabled());
            sendNotifyMessageDto.getUserIds().forEach(userId -> publishEvent(userId, EventDto.builder().type(EventDto.EVENT_TYPE_NEW_NOTIFY_MESSAGE).event(notifyMessage).build()));
            pushImNotify(notifyMessage, sendNotifyMessageDto.getUserIds());
        }
        return notifyMessage.getId();
    }

    @Override
    public void sendNotifyMessage(Long userId, String message) {
        NotifyMessage notifyMessage = NotifyMessage.builder()
                .scope(NotifyMessage.MessageScope.System)
                .senderId(-1L)
                .content(message)
                .build();
        notifyMessageDomainService.addNotifyMessage(notifyMessage, List.of(userId), imEnabled());
        pushImNotify(notifyMessage, List.of(userId));
    }

    @Override
    public void publishEvent(Long userId, String eventType, Object data) {
        EventDto<Object> eventDto = new EventDto<>();
        eventDto.setType(eventType);
        eventDto.setEvent(data);
        publishEvent(userId, eventDto);
    }

    /**
     * ★★通知消息落库后追加 IM 推送 —— 自 DEV-291 起<b>所有 scope 统一走</b>
     * {@code messages/system-notify}（一次调用给 ≤100 个收件人各发一条系统通知）。
     *
     * <h2>★★为什么不再按 scope 分流</h2>
     *
     * <p>替换前有两条腿：{@code Broadcast} / {@code System} 走 {@code messages/system}
     * （系统消息，落库、进历史），其余（{@code Private}）走 {@code events/push}
     * （自定义事件，不落库、离线即丢）。走事件通道的<b>唯一理由</b>是
     * 「那条路能一次带一批 userIds，而 {@code messages/system} 一次只能寻址一个 toUserId」——
     * 代价是 {@code Private} 的通知既进不了 IM 历史、也没有未读红点。
     *
     * <p>{@code messages/system-notify} 把<b>批量</b>与<b>落库</b>同时给全了：一次 ≤100 人，
     * 每人落在自己与合成账号「系统通知」的单聊里，<b>带未读红点、进历史、可删除</b>
     * ⇒ 走事件通道的理由消失，两个分支合并成一条，见 {@link #sendImSystemNotify}。
     *
     * <p>★★<b>这是对调用方可见的行为变化</b>：IM 客户端若注册过 {@code notify.message.new}
     * 自定义事件处理器，本次替换后<b>不再收到该事件</b> —— 通知改由「系统通知」会话承载。
     *
     * <p>★这条裁定<b>至今有效</b>，且被 {@link #IM_EVENT_EXCLUDED_TYPES} 继续守着：
     * {@code publishEvent} 后来也接上了 IM 的 {@code events/push}（见 {@link #pushImEvent}），
     * 但 {@code new_notify_message} 被<b>刻意排除</b>在桥接之外 ⇒ 通知仍然只有「系统通知」这一条腿，
     * 不会因为多了事件桥就变成双发。平台侧的 {@code event_queue}（长连接推送）与 IM 无关，
     * 在 {@link #sendNotifyMessage} 里照常发。
     *
     * <h2>★actor 为什么取「本批第一个收件人」</h2>
     *
     * <p>{@code system-notify} 的请求体里<b>没有</b>发送身份字段（发送者由 IM 内部固定为合成账号），
     * {@code userId} 只用于 {@code runAs} + 租户上下文 + 审计归属，IM 侧对它只有两条硬约束：
     * {@code userId > 0}、{@code tenantId > 0}（{@code ImRpcRequests.requireContext}）。
     * 替换前那套「必须拿收件人本人当 actor」的约束（{@code requireSenderSegment} 对智能体号段
     * fail-closed ⇒ 发送方不能是智能体、且 {@code senderType} 只能是 3）在<b>这条路上不存在</b>。
     *
     * <p>即便如此也<b>不</b>取 {@code senderId}：它可能是 -1
     * （{@link #sendNotifyMessage(Long, String)} 那条路恒为 -1，过不了 {@code userId > 0}），
     * 也可能是智能体 id —— 那不是本租户的真人，把审计归属落在一个非人主体上，
     * 只会让日后排查多一层解释。
     * 而本批收件人里每个 id 都<b>刚被反查出所属租户</b>（查不到的已在
     * {@link #sendImSystemNotify} 里跳过）⇒「必然属于该租户、且必然是平台真人」，
     * 取 {@code batch.get(0)} 是这里最不容易出错的选择。
     *
     * <h2>★★幂等键的选取（这条不变量成立，整套分批才成立）</h2>
     *
     * <p>{@code bizMsgId = notify-<notifyId>-<本批第一个收件人>}。为什么是「批首」而不是
     * 「整批的哈希」或「每个收件人各一个」：
     * <ul>
     *   <li>IM 的幂等键是<b>整批一个</b>：同一个 {@code bizMsgId} 再调一次，IM 原样回放上次结果
     *       （{@code IM_20005}），既不重发也不补发 ⇒ 键必须能唯一标识「这一批是谁」；</li>
     *   <li>收件人先<b>去重</b>、再按租户分组、再按 100 定长划分 —— 划分出的是
     *       <b>互不相交的有序块</b>，每块的<b>首元素</b>在同一租户内必然唯一
     *       （两个块不可能以同一个 userId 开头）；</li>
     *   <li>⇒ 同一份输入重放，分块逐字相同 ⇒ 键逐字相同 ⇒ 幂等命中；而<b>不同</b>批次的键
     *       不会相撞，所以「两批真的各发了一条」不会被互相静默吞掉。</li>
     * </ul>
     *
     * <h2>★★逐条失败要读，但<b>不</b>自动重试</h2>
     *
     * <p>{@code failed} 非空 ⇒ 整体信封仍是成功（那是<b>业务结论</b>，不是调用失败），
     * 见 {@link #logSystemNotifyResult}：只记 WARN、带出 {@code bizMsgId} 与逐条原因，
     * 需要补发时由运维按 {@link ImSystemNotifyResultDto} 的纪律人工补
     * （<b>换新 bizMsgId 且只传 failed 里的 toUserIds</b>）。
     *
     * <p>★为什么不自动补发：IM 是追加的触达通道，通知的权威副本已在平台侧落库；
     * 自动补发会把「个别收件人查不到资料」这种偶发情况升级成一次重试风暴，
     * 而收益只是让一条本来就有平台内红点的通知再多一条 IM 红点。
     *
     * <h2>★不可逆副作用（IM 既定行为，非本方法引入）</h2>
     *
     * <p>通知不属于任何 IM 会话，只能按 {@code toUserId} 寻址；IM 侧对「系统通知」会话
     * 用的是 getOrCreate 语义 ⇒ <b>每个收件人会被自动建出一条（或复用）与「系统通知」的单聊</b>。
     * {@code Broadcast} 会给全平台每个用户各建一条。替换后这件事仍然发生，
     * 但<b>不再需要 per-收件人一次 RPC</b> —— 一批 100 人一次调用。
     *
     * <h2>★★未启用 IM 适配时整批跳过（{@code nuwax.im.adapter.enabled}）</h2>
     *
     * <p>这是本方法<b>第一件事</b>：开关关着就直接返回，一个人都不遍历、一次 RPC 都不走。
     *
     * <p>为什么不像别处那样「照发、让它抛、自己接住」：IM 关闭是<b>合法部署形态</b>
     * （没上 IM 的环境），不是故障。照发的话，{@code Broadcast} 会给每一个批次抛一次
     * {@code ImRpcException.disabled()}，于是「一条通知」在日志里放大的是一整屏无害的
     * 异常 —— 而真正的原因（配置里 {@code enabled=false}）在启动日志里已经说过一次了
     * （{@link com.xspaceagi.im.infra.rpc.ImRpcConfiguration} 的 WARN）。
     * ⇒ 这里按 {@code DEBUG} 记一行即止，<b>不</b>逐批 WARN。
     *
     * <p>★反过来，{@code enabled=true} 但 {@code base-url}/{@code secret} 没填属于
     * <b>配错了</b>，不在本闸门覆盖范围内，仍走下面的 per-批次 catch 大声记 WARN ——
     * 那种情况恰恰需要被看见，不能被静默跳过。
     *
     * <h2>★失败绝不阻断通知主流程</h2>
     *
     * <p>通知的权威副本已在平台侧落库（{@code notify_message} + {@code notify_message_user}），
     * IM 只是追加的一条触达通道。IM 未配置（{@code ImRpcException.notConfigured/disabled}）、
     * 网络失败、收件人查不到租户，一律只记 WARN —— 让异常冒出去会把「IM 没配好」
     * 变成「业务操作失败」，而调用方是提现审批、MCP 部署、文档评论这些主流程。
     *
     * <h2>★当前保持同步（不异步）</h2>
     *
     * <p>调用方拿到的返回与 IM 推送在同一个调用栈里，无隐藏行为；代价是 {@code Broadcast}
     * 的 ⌈N/100⌉ 次 RPC 压在这次调用的响应时间上（替换前是 N 次，量级降了两个数量级）。
     * 若线上群发量级仍不可接受，再单独改为异步 + 有界队列，而不是在这里悄悄开线程。
     */
    private void pushImNotify(NotifyMessage notifyMessage, List<Long> userIds) {
        if (notifyMessage == null || userIds == null || userIds.isEmpty()) {
            return;
        }
        if (!imEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("[NotifyMessage] IM 适配未启用（nuwax.im.adapter.enabled=false），整批跳过 IM 推送"
                        + "notifyId={} 收件人={} 人", notifyMessage.getId(), userIds.size());
            }
            return;
        }
        try {
            sendImSystemNotify(notifyMessage, userIds);
        } catch (Exception e) {
            log.warn("[NotifyMessage] ★IM 推送整体失败（通知本身已落库，不受影响）notifyId={} scope={} 收件人={} 人",
                    notifyMessage.getId(), notifyMessage.getScope(), userIds.size(), e);
        }
    }

    /**
     * IM 适配是否启用 —— 值来自 {@code nuwax.im.adapter.enabled}（{@code ImRpcProperties#enabled}，缺省 false）。
     *
     * <p>★取不到就当作「没启用」（fail-closed）：{@code imRpcAvailability} 理论上恒在
     * （{@code ImRpcConfiguration} 无条件注册该 bean），但一个装配异常的运行环境
     * 不该表现为「悄悄朝一个不确定的 IM 发消息」。
     *
     * <p>★这里只问「开没开」，不问「配得全不全」（base-url / secret）：后者是
     * <b>配错了</b>，需要在调用处大声暴露，不该被这个开关顺手吞掉 —— 见 {@link #pushImNotify}。
     */
    private boolean imEnabled() {
        if (imRpcAvailability == null) {
            return false;
        }
        try {
            return imRpcAvailability.enabled();
        } catch (Exception e) {
            log.warn("[NotifyMessage] ★读取 IM 启用状态失败，按未启用处理（本次不推 IM，通知本身不受影响）", e);
            return false;
        }
    }

    /**
     * ★统一通道：{@code messages/system-notify} —— 按租户分组、每租户每批 ≤
     * {@link #IM_SYSTEM_NOTIFY_MAX_TARGETS}。
     *
     * <p>分批与分租户<b>都是必须的</b>，不是优化：IM 侧 {@code tenantId} 来自执行上下文、
     * 一次调用只能一个；收件人超过上限则是<b>整批拒绝</b>（不截断）⇒ 不分批就连一条都发不出去。
     */
    private void sendImSystemNotify(NotifyMessage notifyMessage, List<Long> userIds) {
        // ★content 是本通知的【唯一】内容来源（msgType=text 的 body 就是 {"text": ...}，
        //   与 system 型判据完全相同，DEV-298 只是换了渲染形态）。
        //   空白内容会产出一个「点开什么都没有」的未读红点 —— 那比不推更糟，本地直接不发。
        String text = notifyMessage.getContent();
        if (StringUtils.isBlank(text)) {
            log.warn("[NotifyMessage] ★IM 推送跳过：通知内容为空（text 气泡消息的 text 必须非空，"
                            + "否则收件人只会看到一个点开什么都没有的未读红点）notifyId={} 收件人={} 人",
                    notifyMessage.getId(), userIds.size());
            return;
        }
        List<Long> validUserIds = userIds.stream()
                .filter(userId -> userId != null && userId > 0)
                .distinct()
                .toList();
        if (validUserIds.isEmpty()) {
            return;
        }
        Map<Long, Long> tenantIdMap = resolveTenantIdMap(validUserIds);
        // ★按租户分组：actor 与 tenantId 都是【本批】的，一次调用只能一个租户
        Map<Long, List<Long>> userIdsByTenant = new LinkedHashMap<>();
        for (Long userId : validUserIds) {
            Long tenantId = tenantIdOf(notifyMessage, tenantIdMap, userId);
            if (tenantId == null) {
                log.warn("[NotifyMessage] ★IM 推送跳过：通知没带 tenantId，按 userId={} 也反查不到租户"
                        + "（IM 要求 tenantId > 0）notifyId={}", userId, notifyMessage.getId());
                continue;
            }
            userIdsByTenant.computeIfAbsent(tenantId, key -> new ArrayList<>()).add(userId);
        }
        // ★body / ext 在整批内逐字相同（构造完只读），提到循环外建一次
        JsonNode body = JsonNodeFactory.instance.objectNode().put("text", text);
        String ext = imExt(notifyMessage);
        for (Map.Entry<Long, List<Long>> entry : userIdsByTenant.entrySet()) {
            List<Long> userIdsOfTenant = entry.getValue();
            for (int fromIndex = 0; fromIndex < userIdsOfTenant.size(); fromIndex += IM_SYSTEM_NOTIFY_MAX_TARGETS) {
                List<Long> batch = userIdsOfTenant.subList(fromIndex,
                        Math.min(fromIndex + IM_SYSTEM_NOTIFY_MAX_TARGETS, userIdsOfTenant.size()));
                // ★批首当幂等键的一半：去重后划分出的块互不相交 ⇒ 每块首元素唯一（见 pushImNotify 的 javadoc）
                String bizMsgId = imBizMsgId(notifyMessage.getId(), batch.get(0));
                try {
                    ImSystemNotifyResultDto result = imMessageRpcService.sendSystemNotify(ImSystemNotifyRequest.builder()
                            // ★actor = 本批首个收件人（只作 runAs / 租户上下文 / 审计归属，理由见 pushImNotify 的 javadoc）
                            .actor(batch.get(0), entry.getKey(), bizMsgId)
                            .toUserIds(batch)
                            // ★通知消息就是一段文字 ⇒ 固定 text（纯文本气泡，★DEV-298：白名单扩到
                            //   text/system/card/rich_text 四型后，产品拍板通知渲染成普通气泡，
                            //   不再是居中提示条；body 形状 {"text": ...} 两型相同，一字未动）
                            .msgType(ImSystemNotifyRequest.MSG_TYPE_TEXT)
                            .body(body)
                            .ext(ext)
                            .build());
                    logSystemNotifyResult(notifyMessage, entry.getKey(), batch, bizMsgId, result);
                } catch (Exception e) {
                    log.warn("[NotifyMessage] ★IM 系统通知整批失败（★这批里没有人收到；按上方纪律，"
                                    + "同一 bizMsgId 重试整批是安全的）notifyId={} tenantId={} 本批收件人={} 人 bizMsgId={}",
                            notifyMessage.getId(), entry.getKey(), batch.size(), bizMsgId, e);
                }
            }
        }
    }

    /**
     * ★读「对账单」：{@code failed} 非空 ⇒ 整体仍是<b>成功</b>（业务结论），只记 WARN、不自动重试。
     *
     * <p>日志里带上 {@code bizMsgId} 与逐条原因，是为了让运维能按
     * {@link ImSystemNotifyResultDto} 的纪律补发：<b>换新 bizMsgId、只传下面这些人</b>
     * （把已成功的混进去会真的再发一条）。
     */
    private void logSystemNotifyResult(NotifyMessage notifyMessage, Long tenantId, List<Long> batch,
                                       String bizMsgId, ImSystemNotifyResultDto result) {
        if (result == null) {
            return;
        }
        List<ImSystemNotifyFailedDto> failed = result.failedOrEmpty();
        if (failed.isEmpty()) {
            return;
        }
        log.warn("[NotifyMessage] ★IM 系统通知【部分收件人被跳过】（整体调用成功，需按纪律补发："
                        + "换新 bizMsgId 且只传下面这些人）notifyId={} tenantId={} 本批={} 人 成功={} 跳过={} bizMsgId={} 明细={}",
                notifyMessage.getId(), tenantId, batch.size(), result.sentCount(), failed.size(),
                bizMsgId, describeFailed(failed));
    }

    /**
     * ★失败明细的紧凑描述：最多展开 {@link #IM_FAILURE_DETAIL_LIMIT} 条，其余只报总数。
     */
    private String describeFailed(List<ImSystemNotifyFailedDto> failed) {
        String head = failed.stream()
                .limit(IM_FAILURE_DETAIL_LIMIT)
                .map(item -> item.toUserId() + "(" + item.reason() + ")")
                .collect(Collectors.joining(", "));
        return failed.size() <= IM_FAILURE_DETAIL_LIMIT
                ? head
                : head + ", ... 共 " + failed.size() + " 条";
    }

    /**
     * ★批量反查收件人的租户。
     *
     * <p>为什么必须兜底：IM 要求 {@code tenantId > 0}，而调用方多数根本没传 ——
     * {@code Broadcast}（唯一群发入口 {@code UserManageController#notify} 不设 tenantId）、
     * mcp 的 4 处、web 的 {@code NotifyMessageController}，以及
     * {@link #sendNotifyMessage(Long, String)}（构造的 NotifyMessage 压根没有 tenantId）。
     *
     * <p>★一次批量查而不是逐个查：{@code Broadcast} 每批就是 1000 人。
     * 事件桥（{@link #sendImCustomEvent}）复用本方法但只传一个 userId —— 它那边<b>没有</b>
     * 「通知自带 tenantId」这条退路，反查是它唯一的租户来源。
     *
     * <p>★反查失败返回空 Map —— 上层据此跳过，绝不猜一个租户顶上（猜错就是把通知投给错误的租户）。
     */
    private Map<Long, Long> resolveTenantIdMap(List<Long> userIds) {
        List<Long> distinctUserIds = userIds.stream()
                .filter(userId -> userId != null && userId > 0)
                .distinct()
                .toList();
        if (distinctUserIds.isEmpty()) {
            return Map.of();
        }
        try {
            return userApplicationService.queryUserListByIds(distinctUserIds).stream()
                    .filter(userDto -> userDto != null && userDto.getId() != null && userDto.getTenantId() != null)
                    .collect(Collectors.toMap(UserDto::getId, UserDto::getTenantId, (existing, ignored) -> existing));
        } catch (Exception e) {
            log.warn("[NotifyMessage] ★反查收件人租户失败 ⇒ 反查不到租户的那些人本轮一律跳过 IM 推送"
                    + "（★不猜一个租户顶上；自带 tenantId 的通知不受影响）收件人={} 人", distinctUserIds.size(), e);
            return Map.of();
        }
    }

    /**
     * ★通知自带的 tenantId 优先，缺了才按收件人反查。
     */
    private Long tenantIdOf(NotifyMessage notifyMessage, Map<Long, Long> tenantIdMap, Long userId) {
        Long tenantId = notifyMessage.getTenantId();
        if (tenantId != null && tenantId > 0) {
            return tenantId;
        }
        return tenantIdMap.get(userId);
    }

    /**
     * ★IM 写操作的幂等键（IM 侧用它当幂等闸门的键，并派生出消息层的 {@code clientMsgId}）。
     *
     * <p>{@code userId} 传的是<b>本批第一个收件人</b>（不是每个收件人各一个）—— 理由与整条不变量
     * 见 {@link #pushImNotify} 的 javadoc：IM 的幂等键是整批一个，而「块首」在同一租户内唯一。
     *
     * <p>有 notifyId 就用它 —— 同一个通知重发（重试、MQ 重投）命中 IM 幂等，不会重复落库。
     *
     * <p>★没有 notifyId 时（{@link #sendNotifyMessage(Long, String)} 只写收件人行、不落主表
     * ⇒ id 为 null）只能退到随机值：<b>宁可不去重，也不能拿「内容哈希」之类稳定的值去当键</b> ——
     * 那会让「同样的内容第二次通知同一个人」被 IM 静默吞掉，比重复发送更糟。
     */
    private String imBizMsgId(Long notifyId, Long userId) {
        if (notifyId != null) {
            return IM_BIZ_MSG_ID_PREFIX + notifyId + "-" + userId;
        }
        return IM_BIZ_MSG_ID_PREFIX + userId + "-" + UUID.randomUUID();
    }

    /**
     * ★{@code ext}（IM 侧原样透传给收件端、不做校验，JSON 原文上限 2048）。
     *
     * <p>内容是替换前那条自定义事件 payload 里的几个<b>关联字段</b>：整条腿从「事件」改成
     * 「系统通知」时，内容变成了消息正文本身，但如果不把关联信息一并带过去，
     * 收件端就只剩一段文字，连「这条 IM 通知对应平台侧哪条通知」都对不上。
     *
     * <p>★不重复带的三项及其原因：
     * <ul>
     *   <li>{@code content} —— 它就是消息正文（{@code body.text}），带两份只会让两边不一致；</li>
     *   <li>{@code tenantId} —— 一次调用的租户就是<b>上下文租户</b>，而通知自带的 tenantId
     *       可能为 null（多数调用方不传，靠反查兜底），带上反而容易被读成「这条通知的租户」；</li>
     *   <li>{@code created} —— 秒/毫秒口径在平台与 IM 两边并不统一，宁可让收件端以 IM 的
     *       {@code sendTime} 为准。</li>
     * </ul>
     *
     * <p>★notifyId 用字符串：雪花 ID 超出 JS 安全整数范围，收件端按 number 解析会丢精度。
     * （{@code ObjectNode} 的 {@code put} 对 null 写的是 JSON {@code null}，不是丢字段。）
     */
    private String imExt(NotifyMessage notifyMessage) {
        ObjectNode ext = JsonNodeFactory.instance.objectNode();
        ext.put("notifyId", notifyMessage.getId() == null ? null : String.valueOf(notifyMessage.getId()));
        ext.put("scope", notifyMessage.getScope() == null ? null : notifyMessage.getScope().name());
        ext.put("senderId", notifyMessage.getSenderId());
        return ext.toString();
    }

    @Override
    public List<NotifyMessageDto> queryNotifyMessageList(NotifyMessageQueryDto messageQueryDto) {
        List<NotifyMessageUser> notifyMessageUserList = notifyMessageDomainService.queryNotifyMessageUserList(messageQueryDto.getUserId(), messageQueryDto.getLastId(), messageQueryDto.getSize(), messageQueryDto.getReadStatus());
        //从notifyMessageUserList中获取notifyId列表，然后查询消息
        List<Long> notifyIds = notifyMessageUserList.stream().map(NotifyMessageUser::getNotifyId).toList();
        List<NotifyMessage> notifyMessageList = notifyMessageDomainService.queryNotifyMessageList(notifyIds);
        //获取senderId列表，然后查询用户信息，转成userId为key的map
        List<Long> senderIds = notifyMessageList.stream().map(NotifyMessage::getSenderId).toList();
        Map<Long, UserDto> userMap = userApplicationService.queryUserListByIds(senderIds).stream().collect(Collectors.toMap(UserDto::getId, userDto -> userDto));

        //notifyMessageList转以notfyId为key的map
        Map<Long, NotifyMessage> notifyMessageMap = notifyMessageList.stream().collect(Collectors.toMap(NotifyMessage::getId, notifyMessage -> notifyMessage, (o1, o2) -> o1));
        //notifyMessageUserList转List<NotifyMessageDto>
        return notifyMessageUserList.stream().map(notifyMessageUser -> {
                    NotifyMessage notifyMessage = notifyMessageMap.get(notifyMessageUser.getNotifyId());
                    NotifyMessageDto notifyMessageDto = new NotifyMessageDto();
                    if (notifyMessage == null) {
                        return notifyMessageDto;
                    }
                    notifyMessageDto.setId(notifyMessage.getId());
                    notifyMessageDto.setContent(notifyMessage.getContent());
                    notifyMessageDto.setReadStatus(notifyMessageUser.getReadStatus());
                    notifyMessageDto.setCreated(notifyMessage.getCreated());
                    UserDto userDto = userMap.get(notifyMessage.getSenderId());
                    if (userDto != null) {
                        notifyMessageDto.setSender(NotifyMessageDto.Sender.builder()
                                .userId(userDto.getId())
                                .userName(userDto.getUserName())
                                .nickName(userDto.getNickName())
                                .avatar(userDto.getAvatar())
                                .build());
                    } else {
                        notifyMessageDto.setSender(NotifyMessageDto.Sender.builder()
                                .userId(-1L)
                                .userName("--")
                                .nickName("--")
                                .avatar("")
                                .build());
                    }
                    if (notifyMessage.getScope() != NotifyMessage.MessageScope.Private) {
                        TenantConfigDto tenantConfigDto;
                        if (RequestContext.get() == null || !RequestContext.get().getTenantId().equals(notifyMessage.getTenantId())) {
                            tenantConfigDto = tenantConfigApplicationService.getTenantConfig(notifyMessage.getTenantId());
                        } else {
                            tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
                        }
                        notifyMessageDto.setSender(NotifyMessageDto.Sender.builder()
                                .userId(-1L)
                                .avatar(tenantConfigDto.getSiteLogo())
                                .nickName(tenantConfigDto.getOfficialUserName())
                                .userName(tenantConfigDto.getOfficialUserName())
                                .build());
                    }
                    return notifyMessageDto;
                }).toList().stream().filter(notifyMessageDto -> notifyMessageDto.getId() != null)
                .collect(Collectors.toList());
    }

    @Override
    public Long countUnreadNotifyMessage(Long userId) {
        return notifyMessageDomainService.countUnreadNotifyMessage(userId);
    }

    @Override
    public void updateReadStatus(Long userId, List<Long> notifyIds) {
        notifyMessageDomainService.updateReadStatus(userId, notifyIds);
    }

    @Override
    public void updateAllUnreadNotifyMessage(Long userId) {
        notifyMessageDomainService.updateAllUnreadNotifyMessage(userId);
    }

    /**
     * 给一个用户推一条平台事件，<b>两条腿</b>：
     *
     * <ol>
     *   <li><b>平台自己的 {@code event_queue}</b>（原有行为，一字未改）：按用户的每个在线
     *       {@code clientId} 各压一份进 Redis，等前端来 {@link #collectEventList} 拉。
     *       ★这是<b>权威副本</b> —— 落 Redis、有 24h 保留、离线回来还能拉到。</li>
     *   <li><b>IM 的自定义事件通道</b>（{@code events/push}）：让 IM 前端也能实时收到同一个事件，
     *       不必依赖平台的 clientId 长连接。见 {@link #pushImEvent}。</li>
     * </ol>
     *
     * <p>★★第 2 条腿是<b>尽力而为</b>：IM 未启用 / 反查不到租户 / payload 超限 / RPC 失败，
     * 一律只记日志，<b>绝不</b>冒泡 —— 本方法的调用方是聊天主流程（会话终态、异步组件回话），
     * 「IM 没配好」不能变成「这次对话失败」。
     */
    @Override
    public void publishEvent(Long userId, EventDto<?> eventDto) {
        Assert.notNull(userId, "userId must be non-null");
        Assert.notNull(eventDto, "eventDto must be non-null");
        // ★第 2 条腿：桥到 IM。放在 Redis 之后，且自己吞掉所有异常 ⇒ 上面那条腿的结果与它无关。
        if (!pushImEvent(userId, eventDto)) {
            authService.getUserClientIds(userId).forEach(clientId -> {
                if (StringUtils.isNotBlank(clientId)) {
                    redisUtil.leftPush("event_queue:" + clientId, JsonSerializeUtil.toJSONStringGeneric(eventDto));
                    redisUtil.expire("event_queue:" + clientId, 60 * 60 * 24);
                }
            });
        }
    }

    /**
     * ★★把平台事件桥到 IM 的自定义事件通道（{@code events/push}）。
     *
     * <h2>★这条通道<b>不承诺送达</b>，所以它只能是「追加」，不能是「替代」</h2>
     *
     * <p>{@code events/push} 帧取 P1 背压优先级（通道不可写时<b>直接丢弃</b>）、收件人离线即收不到、
     * <b>不落库、无补偿</b> ⇒ 丢了没有任何读路径能补回来。而平台 {@code event_queue} 那条腿
     * 落 Redis 且保留 24h。⇒ 两条腿<b>都发</b>，IM 这条只是让「正开着 IM 端的人」少等一次轮询；
     * 谁都不能把它当可靠信道用。
     *
     * <h2>★★哪些事件<b>不</b>桥（见 {@link #IM_EVENT_EXCLUDED_TYPES}）</h2>
     *
     * <p>{@code new_notify_message} 不桥：同一件事已由 {@code messages/system-notify} 承载
     * （带未读红点、进历史、可删除），再桥一条自定义事件就是<b>同一条通知两次触达</b>，
     * 而事件那份还不落库。顺带挡掉一个放大 —— {@code Broadcast} 是<b>逐个收件人</b>调本方法的
     * （每批 1000 人 ⇒ 1000 次），不排除的话一条全员通知会变成 1000 次 {@code events/push}。
     *
     * <h2>★{@code tenantId} 只按 userId 反查，<b>不</b>读 {@code RequestContext}</h2>
     *
     * <p>IM 硬要求 {@code tenantId > 0}，而 {@code EventDto} 里没有租户字段。看着最省的办法是读
     * {@code RequestContext.get().getTenantId()}，但本方法的三个调用方里有两个跑在<b>异步回调</b>
     * （模型 {@code doOnComplete} / {@code doOnError}）里，线程上下文要么缺失、要么残留着
     * <b>别的请求</b>的租户 —— 那是把事件推给错误租户，属于最坏的一类 bug（不报错、只是发错人）。
     * ⇒ 一律按收件人反查（复用 {@link #resolveTenantIdMap}），反查不到就<b>跳过、不猜</b>。
     *
     * <h2>★actor 就是收件人本人</h2>
     *
     * <p>{@code events/push} 的 {@code userId} 只作 {@code runAs} + 租户上下文 + 审计归属，
     * 收件人由 {@code userIds} 决定。这里两者是同一个人，且他刚被反查出属于该租户
     * ⇒ 天然满足 IM 的 {@code userId > 0} / {@code tenantId > 0}，不必像
     * {@link #sendImSystemNotify} 那样去挑「本批第一个收件人」。
     *
     * <h2>★幂等键用随机值（刻意不去重）</h2>
     *
     * <p>{@code bizMsgId} 必填，且它<b>同时就是</b>帧体的 {@code eventId}。{@code EventDto} 没有
     * 稳定 id，能拿来当键的只有内容 —— 但按 {@link #imBizMsgId} 里已经立过的纪律：
     * <b>宁可不去重，也不能拿内容哈希之类稳定的值当键</b>，否则「同样的事件第二次推给同一个人」
     * 会被 IM 静默吞掉（{@code IM_20005} 原样回放上次结果），那比重复推一次难查得多。
     * 而平台侧重复调本方法时，{@code event_queue} 里同样会压两份 ⇒ 两边行为一致，没有引入新的不一致。
     *
     * <h2>★未启用 IM 适配时一条都不发</h2>
     *
     * <p>本方法属 {@link IImRpcAvailability} 说的<b>自动追加通道</b>（没有任何人点过「发消息」）
     * ⇒ 先问开关，关着就整条跳过并按 {@code DEBUG} 记一行，<b>不</b>照发让它抛
     * {@code ImRpcException.disabled()} —— 那会在日志里放出一屏无害异常，而真正的原因
     * （配置里 {@code enabled=false}）启动时已经 WARN 过一次了。
     */
    private boolean pushImEvent(Long userId, EventDto<?> eventDto) {
        if (!shouldBridgeToIm(userId, eventDto)) {
            return false;
        }
        try {
            scheduledExecutorService.execute(() -> sendImCustomEvent(userId, eventDto));
            return true;
        } catch (Exception e) {
            log.warn("[NotifyMessage] ★IM 自定义事件推送失败（平台 event_queue 那条腿已完成，不受影响）userId={} type={}",
                    userId, eventDto.getType(), e);
        }
        return false;
    }

    /**
     * 这条事件要不要桥到 IM。★判据从<b>最便宜</b>的排到最贵的：纯内存的类型判断在前，
     * 读配置开关在后，反查租户（要查库）留到真决定发了才做 —— 被排除的事件一个字节都不多花。
     */
    private boolean shouldBridgeToIm(Long userId, EventDto<?> eventDto) {
        // IM 硬要求 userId > 0；本方法在 publishEvent 里已被 Assert.notNull 挡过 null，这里挡的是非正数
        if (userId == null || userId <= 0) {
            return false;
        }
        String type = eventDto.getType();
        // eventType 非空是 IM 的硬约束（≤64，值域由调用方定义 ⇒ 原样透传平台这套常量）
        if (StringUtils.isBlank(type) || IM_EVENT_EXCLUDED_TYPES.contains(type)) {
            return false;
        }
        // ★取不到 bean 就当作「没启用」（fail-closed），与 imEnabled() 对 availability 的处理同理
        if (imEventRpcService == null) {
            return false;
        }
        if (!imEnabled()) {
            if (log.isDebugEnabled()) {
                log.debug("[NotifyMessage] IM 适配未启用（nuwax.im.adapter.enabled=false），跳过 IM 自定义事件"
                        + "userId={} type={}", userId, type);
            }
            return false;
        }
        return true;
    }

    /**
     * ★真正发一次 {@code events/push}。★只有这里会去反查租户（判据通过之后才花这次查询）。
     */
    private void sendImCustomEvent(Long userId, EventDto<?> eventDto) {
        Long tenantId = resolveTenantIdMap(List.of(userId)).get(userId);
        if (tenantId == null || tenantId <= 0) {
            log.warn("[NotifyMessage] ★IM 自定义事件跳过：按 userId={} 反查不到租户（IM 要求 tenantId > 0，"
                            + "★这里绝不拿 RequestContext 里的租户顶，异步线程里那可能是别人的）type={}",
                    userId, eventDto.getType());
            return;
        }
        Map<String, Object> payload = imEventPayload(userId, eventDto);
        if (payload == null) {
            // 超限，imEventPayload 里已经记过 WARN
            return;
        }
        String bizMsgId = imEventBizMsgId(userId);
        ImCustomEventPushResultDto result = imEventRpcService.pushCustomEvent(ImCustomEventPushRequest.builder()
                // ★actor = 收件人本人（只作 runAs / 租户上下文 / 审计归属，理由见 pushImEvent 的 javadoc）
                .actor(userId, tenantId, bizMsgId)
                // ★单收件人：IM 的 userIds 是「这些人的全部在线端」，与任何会话无关。
                //   刻意不用 convId —— 平台事件是发给【人】的，不是发给某个会话的全体成员。
                .userIds(List.of(userId))
                .eventType(eventDto.getType())
                .payload(payload)
                .build());
        logImEventResult(userId, eventDto, bizMsgId, result);
    }

    /**
     * ★把 {@code EventDto.event} 转成 IM 要的 {@code Map<String, Object>} payload，并量一遍大小。
     *
     * <p>返回 {@code null} = 「这条别发了」（超限，已记 WARN）。
     *
     * <p>★★序列化必须用<b>不带类型信息</b>的 {@link JsonSerializeUtil#toJSONString}：
     * {@code toJSONStringGeneric} 会写出 {@code @class}（Java 全限定类名），那是平台自己
     * 回放 {@code event_queue} 才需要的口径 —— 透给 IM 前端既解不了、又白送一份内部包结构。
     * 同一个 {@code EventDto} 在两条腿上是<b>两种序列化</b>，这不是不一致，是两边读者不同。
     *
     * <p>★{@code event} 不是 JSON 对象（标量或数组）时包一层 {@code {"value": ...}}：
     * IM 侧 payload 的形状是对象，而包一层只是多一个壳，比把事件整个丢掉好。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> imEventPayload(Long userId, EventDto<?> eventDto) {
        Map<String, Object> payload = new LinkedHashMap<>();
        Object event = eventDto.getEvent();
        if (event != null) {
            String json = JsonSerializeUtil.toJSONString(event);
            try {
                Map<String, Object> parsed = JsonSerializeUtil.parseObject(json, Map.class);
                if (parsed != null) {
                    payload.putAll(parsed);
                }
            } catch (Exception e) {
                payload.put("value", event);
            }
        }
        String encoded = JsonSerializeUtil.toJSONString(payload);
        int bytes = encoded == null ? 0 : encoded.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > IM_EVENT_MAX_PAYLOAD_BYTES) {
            log.warn("[NotifyMessage] ★IM 自定义事件跳过：payload 超过上限（{} B > {} B）。★不截断——"
                            + "截断会给出损坏的 JSON，那比不发更糟；这条事件仍完整留在平台 event_queue 里"
                            + " userId={} type={}",
                    bytes, IM_EVENT_MAX_PAYLOAD_BYTES, userId, eventDto.getType());
            return null;
        }
        return payload;
    }

    /**
     * ★IM 写操作的幂等键（同时就是帧体的 {@code eventId}）—— 为什么用随机值见 {@link #pushImEvent}。
     */
    private String imEventBizMsgId(Long userId) {
        return IM_EVENT_BIZ_MSG_ID_PREFIX + userId + "-" + UUID.randomUUID();
    }

    /**
     * ★读 {@code events/push} 的三个读数 —— 它<b>没有一个是送达回执</b>，所以这里不拿它当成败判据。
     *
     * <p>{@code targetDevices == 0} 在本场景是<b>正常</b>的（这个人此刻没开 IM 端而已），
     * 平台 {@code event_queue} 那份还在、他下次拉得到 ⇒ 记 {@code DEBUG} 而不是 {@code WARN}。
     * 逐次 WARN 会让「用户没开 IM 客户端」这件天天发生的事把日志刷满，而它不需要任何人去处理。
     */
    private void logImEventResult(Long userId, EventDto<?> eventDto, String bizMsgId,
                                  ImCustomEventPushResultDto result) {
        if (result == null || !log.isDebugEnabled()) {
            return;
        }
        log.debug("[NotifyMessage] IM 自定义事件已推{} userId={} type={} bizMsgId={} 目标用户={} 在线设备={} 发出帧={}",
                result.nobodyReached() ? "出（★当前没人在线，离线即丢，权威副本在平台 event_queue）" : "出",
                userId, eventDto.getType(), bizMsgId,
                result.targetUsers(), result.targetDevices(), result.publishedFrames());
    }

    @Override
    public List<EventDto<?>> collectEventList(Long userId, String clientId) {
        if (StringUtils.isNotBlank(clientId)) {
            List<Object> eventList = redisUtil.range("event_queue:" + clientId, 0, -1);
            if (eventList != null && !eventList.isEmpty()) {
                return eventList.stream().map(event -> (EventDto<?>) JsonSerializeUtil.parseObjectGeneric(event.toString())).collect(Collectors.toList());
            }
        }
        return List.of();
    }

    @Override
    public void clearEventList(Long userId, String clientId) {
        if (StringUtils.isNotBlank(clientId)) {
            redisUtil.expire("event_queue:" + clientId, 0);
        }
    }
}
