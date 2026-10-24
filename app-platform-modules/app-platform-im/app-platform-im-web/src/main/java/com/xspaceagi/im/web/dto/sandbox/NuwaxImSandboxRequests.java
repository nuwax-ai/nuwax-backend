package com.xspaceagi.im.web.dto.sandbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.xspaceagi.im.sdk.rpc.dto.ImCardBody;

import java.util.List;
import java.util.Map;

/**
 * 沙箱 IM 技能 API 的全部请求体（{@code /api/v1/4sandbox/nuwax-im/**}）。
 *
 * <h2>★★这里<b>故意没有</b> {@code userId} / {@code tenantId} 字段</h2>
 *
 * <p>底层 IM 契约的每个请求都要求 {@code userId}（「以谁的身份执行」），而技能是<b>以用户身份</b>
 * 发消息的 —— 身份只能来自调用方 AK 解析出的登录态（{@code RequestContext}）。
 *
 * <p>如果请求体里留着这两个字段，即便控制器「每次都覆盖」，它依然是一份<b>看起来可以指定身份</b>
 * 的接口：下一个改这个控制器的人、或者照 Swagger 写代码的调用方，都会以为它能用。
 * 把它们从类型里删掉，「身份不可伪造」就从「靠人记得覆盖」变成了<b>编译期事实</b>。
 *
 * <h2>★{@code bizMsgId} 的取舍</h2>
 *
 * <p>IM 的写操作要求幂等键 {@code bizMsgId}，重试时必须复用同一个值。本层允许调用方传入
 * （推送重试 / 上游已有业务单号时用），缺省由控制器生成 UUID，
 * 并<b>无论成败都回显在响应里</b> —— 见 {@link NuwaxImWriteResult}。
 *
 * <p>★注意：若响应在网络层丢失而调用方又没自己带 {@code bizMsgId}，那次请求就无法安全重试
 * （重发会导致重复消息）。要「发送 + 重试」的链路请自带 {@code bizMsgId}。
 *
 * <h2>★{@code SendSystem} / {@code SystemNotify} / {@code PushEvent} 是「管理员端点」的请求体</h2>
 *
 * <p>这三个 record 对应的端点在控制器上<b>前置了管理员闸门</b>（非管理员 {@code 4030}）。
 * 它们的危险性质不在身份而在别处，各自写在 record 上方的注释里。
 * 但「管理员才能调」<b>不等于</b>「可以放宽身份纪律」—— 它们同样<b>没有</b>
 * {@code userId} / {@code tenantId}，身份一律取自 AK。
 */
public final class NuwaxImSandboxRequests {

    private NuwaxImSandboxRequests() {
    }

    // ================================================================== messages（9）

    /** 单聊发消息。会话不存在时由 IM 自动创建。 */
    public record SendP2p(
            /** 接收方平台用户 ID。 */
            Long toUserId,
            /** 消息类型（如 {@code text} / {@code image}），以 IM 契约的枚举为准。 */
            String msgType,
            /** 消息体，结构随 {@code msgType} 而定，原样透传给 IM。 */
            JsonNode body,
            /** 扩展字段（JSON 字符串，可空）。 */
            String ext,
            String bizMsgId,
            String traceId) {
    }

    /**
     * 群发消息。
     *
     * <p>★★★<b>这里刻意【没有】"以谁的身份发"的字段</b> —— 它曾经叫 {@code sendAsAgent}
     * （注释写"默认 true"），但<b>控制器从未读过它</b>，已于 2026-09-23 删除。删它的理由不是"没实现"，
     * 而是<b>这个决定不该由调用方给</b>：
     *
     * <ul>
     *   <li>真正的判据在 AK 的 {@code config.ext} 里 —— 由 IM 下发、每轮刷新，见控制器的
     *       {@code groupActorUserId}：带 {@code GROUP_VIRTUAL_USER_ID}（本次 4.10 由群会话触发）
     *       就用智能体虚拟身份，不带（用户在单聊里要发群消息）就用 AK 归属人本人；</li>
     *   <li>再放一个请求字段就是<b>同一件事的第二个数据源</b>：两个来源冲突时以谁为准没有答案。
     *       这种"两处都能配、实际只有一处是真的"的字段本仓已有先例并被点名要避开 ——
     *       见控制器 {@code agentVirtualUserId} 的注释：{@code UserAccessKeyConfig.agentVirtualUserId}
     *       是个同义的类型化字段，但全仓没有任何地方给它赋值，读起来像能配、其实只有 ext 那份是真的。</li>
     * </ul>
     *
     * <p>★<b>仍然发 {@code sendAsAgent} 的老调用方不受影响</b>：它会被静默忽略（Spring Boot 默认
     * {@code FAIL_ON_UNKNOWN_PROPERTIES=false}，本仓也没有任何 {@code spring.jackson} 覆盖），
     * 而行为与旧注释"默认以 Agent 身份"一致 —— 群会话触发时本来就以智能体身份发。
     * ★但<b>不要再把它加回来</b>：它加回来的那一刻，"到底以谁的身份发"就有两个答案了。
     */
    public record SendGroup(
            /** 会话 ID。 */
            Long convId,
            String msgType,
            JsonNode body,
            /** 引用回复的消息 ID（可空）。 */
            Long replyToMsgId,
            /** @ 的用户 ID 列表（可空，上限以 IM 契约为准）。 */
            List<Long> mentionUserIds,
            /** 是否 @ 全体（可空，缺省 false）。 */
            Boolean mentionAll,
            String ext,
            String bizMsgId,
            String traceId) {
    }

    /**
     * 系统 / Agent 消息（{@code senderType=2} 机器人、{@code 3} 系统）。
     *
     * <p>★<b>仅管理员可用</b> —— 控制器在该端点上前置了管理员闸门（非管理员 {@code 4030}）。
     * 理由见控制器 {@code sendSystem} 上方的注释：{@code senderType=2|3} 在 IM 侧
     * <b>不适用</b>禁言与用户频控，用它等于以系统/机器人名义发言并绕过自身限制。
     */
    public record SendSystem(
            /** 群会话 ID（与 {@code toUserId} 二选一，群内发用这个）。 */
            Long convId,
            /** 单聊接收方（与 {@code convId} 二选一）。 */
            Long toUserId,
            /** 2=机器人，3=系统；缺省由 IM 按 3 处理。 */
            Integer senderType,
            String msgType,
            JsonNode body,
            String ext,
            String bizMsgId,
            String traceId) {
    }

    /**
     * ★DEV-291 批量发「系统通知」（IM 契约 {@code messages/system-notify}）。
     *
     * <p>★<b>仅管理员可用</b> —— 控制器在该端点上前置了管理员闸门（非管理员 {@code 4030}）。
     * 理由与会话里的 {@code send-system} <b>不同</b>：那个是「以系统名义往某个会话里发言」，
     * 这个是「往一批人的『系统通知』信箱里各投一条」——
     * 一次调用能触达的收件人<b>上限 100</b>、带未读红点、<b>进历史可查可删</b>
     * （对照 {@link PushEvent}：不落库、无痕迹）。它危险在<b>够得着谁</b>：
     * 收件人不必与调用方有任何联系人/会话关系，只要在同一个租户里报得出 userId 就投得到。
     *
     * <h2>★★发送身份<b>不由调用方决定</b>，本层也<b>给不了</b></h2>
     *
     * <p>IM 侧把发送者固定成合成账号「系统通知」（刻意落在智能体号段、
     * 且 RPC 的发送者号段闸门对该号段 fail-closed —— 调用方<b>结构上</b>发不出系统通知）。
     * 所以本 record 里<b>没有</b> {@code senderType}，与 {@link SendSystem} 恰好相反。
     *
     * <p>底层 DTO 里那个 {@code userId} 在本层同样<b>不存在</b>（本类头部铁律）：
     * 控制器用当前登录（= 管理员本人）填它，那里它只作<b>审计归属</b>与租户上下文来源。
     *
     * <h2>★失败是<b>逐条</b>的</h2>
     *
     * <p>个别收件人投不到时整体仍返回成功，明细在响应 {@code data.failed} 里 ——
     * 调用方<b>必须</b>读它，只看"有没有报错"会把"一半人没收到"读成完全成功。
     */
    public record SystemNotify(
            /**
             * ★收件人列表（必填、非空）。单个不重复的 userId；<b>整批上限 100</b>
             * （超出由 IM <b>整批拒绝</b>，不做静默截断 —— 静默截断会让调用方以为都发出去了）。
             *
             * <p>★上限<b>不在这里判</b>：4 个量级的口径保持在 IM 一处，本层抄一份就会漂移。
             * 本地只拦「空 / 缺」——空列表在 IM 侧是「发 0 条」的成功返回，
             * 与"真的发出去了"在响应上长得一模一样。
             */
            List<Long> toUserIds,
            /**
             * 消息类型：★DEV-298 起白名单四型 {@code text}（缺省，纯文本<b>气泡</b>）/
             * {@code system}（居中提示条）/ {@code card}（通知卡）/ {@code rich_text}（富文本气泡）。
             * 留空即按 {@code text} 处理。
             *
             * <p>★这是本 record 里唯一的本地缺省值。缺省从 {@code system} 改成 {@code text}
             * 是 DEV-298 的产品拍板（通知内容渲染成普通气泡而不是居中提示条），
             * 不是笔误 —— 改回去之前先读 {@link com.xspaceagi.im.sdk.rpc.dto.ImSystemNotifyRequest} 的类注释。
             *
             * <p>★取值<b>不在这里白名单校验</b>：非法值（含 {@code image} 等仍禁的单体媒体型）
             * 原样交给 IM，由它回 {@code IM_90001}（"契约只认四型"这条口径保持在服务端一处，
             * 与 {@code agentMode} 同规则）。
             */
            String msgType,
            /**
             * 消息体，结构随 {@code msgType} 而定，原样透传给 IM。必填。
             *
             * <p>★{@code msgType=text}/{@code system} 时 {@code body.text} <b>应当非空</b>：
             * 空正文的"消息"是一个未读红点、点开<b>什么都没有</b> —— 比不投更糟。
             * 本层不替调用方判（IM 会拦，见上）。
             */
            JsonNode body,
            /** 扩展字段（JSON 字符串，可空）。 */
            String ext,
            String bizMsgId,
            String traceId) {
    }

    /** 互动卡片消息。 */
    public record SendCard(
            Long convId,
            /** 卡片结构，见 IM 契约 {@code ImCardBody}（title/desc/fields/actions…）。 */
            ImCardBody card,
            String ext,
            String bizMsgId,
            String traceId) {
    }

    /** 撤回消息。 */
    public record Revoke(
            Long convId,
            Long msgId,
            String reason,
            String bizMsgId,
            String traceId) {
    }

    /** 查单条消息详情。★只读。 */
    public record GetMessage(
            Long convId,
            Long msgId,
            String traceId) {
    }

    /** 批量查已读人数。★只读；{@code msgIds} 单次上限 200。 */
    public record ReadCounts(
            Long convId,
            List<Long> msgIds,
            String traceId) {
    }

    /** 按时间范围列举会话消息。★只读。 */
    public record ListMessages(
            Long convId,
            /** 起始时间（毫秒，可空）。 */
            Long startTime,
            /** 结束时间（毫秒，可空）。 */
            Long endTime,
            /** 翻页游标：把上一次响应的 {@code data.nextSeq} 原样回填。 */
            Long afterSeq,
            /** 单页条数（可空，上限以 IM 契约为准）。 */
            Integer limit,
            String traceId) {
    }

    // ================================================================== conversations（8）

    /** 建群。 */
    public record CreateGroup(
            String name,
            String avatar,
            String notice,
            /** 初始成员（可空）。 */
            List<Long> memberUserIds,
            /** 群人数上限（可空，缺省 2000）。 */
            Integer maxMember,
            /** 业务侧群唯一键：同一个值重复调用只会建出一个群。 */
            String clientGroupId,
            String bizMsgId,
            String traceId) {
    }

    /** 加群成员。★部分成功是正常返回（没加上的在 {@code data.failedUserIds}）。 */
    public record AddMembers(
            Long convId,
            /** 单次上限 50。 */
            List<Long> userIds,
            String nickName,
            /**
             * ★DEV-271 本批<b>智能体</b>在群里的衔接模式：<b>1=唤起（默认）/ 2=监听</b>（可空）。
             * <p>只影响 userIds 里的智能体（真人忽略）；不传 ⇒ 唤起。
             * <b>2=监听</b>会让群里每条消息都发给智能体（真实模型费用），只在确实需要时才传。
             * <p>★非法值不在这里拦：原样交给 IM，由它回 {@code IM_90001}
             * （"契约只认 1/2"这条口径保持在服务端一处）。
             */
            Integer agentMode,
            String bizMsgId,
            String traceId) {
    }

    /** 移出群成员。★单次上限 50，任一失败则整体失败。 */
    public record RemoveMembers(
            Long convId,
            List<Long> userIds,
            String reason,
            String bizMsgId,
            String traceId) {
    }

    /** 群成员列表。★只读；{@code limit} 上限 100，大群会被服务端降级（{@code data.degraded=true}）。 */
    public record ListMembers(
            Long convId,
            Integer limit,
            String traceId) {
    }

    /** 取或建单聊会话（原子操作）。 */
    public record GetOrCreateP2p(
            Long toUserId,
            String bizMsgId,
            String traceId) {
    }

    /** 更新会话摘要。★尽力而为，允许丢失。 */
    public record UpdateDigest(
            Long convId,
            String digest,
            String bizMsgId,
            String traceId) {
    }

    /**
     * 列举「我」的会话列表。★只读。★DEV-277。
     *
     * <p>★翻页只有两个动作：看 {@code data.hasMore} 决定要不要继续、
     * 把 {@code data.nextCursor} <b>原样</b>回填到下一次的 {@code cursor}。
     * 游标是<b>不透明</b>快照（自己拼会报 {@code IM_40002}），且<b>不含身份</b>——
     * 把 A 的游标填给 B 不报错，只会静默返回错位的一页。
     */
    public record ListConversations(
            /** 翻页游标：首页留空，之后把上一次响应的 {@code data.nextCursor} 原样回填。 */
            String cursor,
            /** 单页条数（可空，缺省 50，服务端夹到 1~100）。 */
            Integer limit,
            String traceId) {
    }

    /**
     * 会话详情。★只读。★DEV-288。
     *
     * <p>★与「列举我的会话列表」只差一点，但那一点是<b>关键</b>：本端点能拿到
     * {@code groupNo}（群号）与 {@code ext}（扩展字段），而列表路径<b>恒为 null</b>。
     * 换句话说，要群号就<b>只能</b>走这里，翻列表翻不出来。
     *
     * <p>★★<b>查一个自己不在里面的会话是允许的</b>（内部 RPC 豁免成员闸门），
     * 此时响应里视角字段整块为 null。判读时的坑写在返回类型
     * {@link com.xspaceagi.im.sdk.rpc.dto.ImConversationDetailDto} 的类注释里：
     * <b>{@code unreadCount} 会是 {@code 0} 而不是 null</b>，
     * 判断"在不在里面"只认 {@code myRole} 是不是 null。
     */
    public record GetConversationDetail(
            Long convId,
            String traceId) {
    }

    // ================================================================== contacts（1）

    /**
     * 列举「我」的联系人。★只读。★DEV-277。
     *
     * <p>★返回是<b>三态混合</b>（已互为联系人 / 对方申请我 / 我申请对方），
     * 要「只要好友」得自己按 {@code items[].status == 1} 筛。
     * 这里<b>不提供</b>过滤参数：加了就会长出第二份筛选逻辑，而 IM 侧那条口径才是与界面同源的那份。
     */
    public record ListContacts(
            String traceId) {
    }

    // ================================================================== events（1，★闸门）

    /**
     * 推送自定义事件（IM 契约 {@code events/push}）。
     *
     * <p>★<b>仅管理员可用</b> —— 控制器在该端点上前置了管理员闸门（非管理员 {@code 4030}）。
     * 它是设计上的<b>服务间业务通道</b>：IM 的 push 链上没有任何成员/关系校验
     * （{@code CustomEventBroadcaster} 的 convId 模式是拿成员当收件人，不校验调用者是不是成员），
     * 帧体又<b>没有发送者</b>字段（{@code CustomEventDto} 只有 eventId/eventType/payload/ts）。
     * 完整依据见控制器 {@code pushEvent} 上方的注释。
     *
     * <p>★注意：本类头部"故意没有 userId / tenantId"那条铁律，在这个端点上<b>不构成保护</b> ——
     * 它防的是"伪造身份"，而这个端点的危害不在身份，在<b>够得着谁</b>与<b>能塞什么</b>。
     */
    public record PushEvent(
            /** 目标用户（与 {@code convId} 二选一或并存，以 IM 契约为准）。 */
            List<Long> userIds,
            Long convId,
            /** 事件类型标识，由业务自定义。 */
            String eventType,
            /** 事件负载。 */
            Map<String, Object> payload,
            String bizMsgId,
            String traceId) {
    }

    // ================================================================== agents（2，★DEV-285）

    /**
     * 查看「我（智能体）」在某个会话里的排队区 —— 还有哪些新消息在等我处理（★只读）。
     *
     * <p>★<b>身份口径与其余端点不同</b>：这里查的是<b>智能体主体自己</b>的队列，
     * 以「当前登录用户」的身份调用<b>不会被接受</b>（控制器上会 fail-closed 报错）。
     * 完整依据见控制器 {@code listPending} 上方的注释。
     *
     * <p>★返回<b>不保证非空</b>：空列表通常意味着「消息已被正常处理」，不是出错。
     */
    public record PendingList(
            /** 目标会话 id。 */
            Long convId,
            String traceId) {
    }

    /**
     * 向排队区声明「这批消息我（智能体）已经消费了」（★写操作，{@code bizMsgId} 幂等）。
     *
     * <p>★<b>调用纪律：先 {@link PendingList} 读走 → 确实并入上下文 → 再按同一批
     * {@code msgIds} 点名清除。</b>清掉之后平台侧不会再处理这批消息，
     * 「清了却没真消费」在用户视角就是<b>发了没回音</b>。
     *
     * <p>★必须<b>点名</b>（{@code msgIds}），不要图省事整队清空 —— 见 {@code msgIds} 字段说明。
     *
     * <p>★返回的 {@code removedCount} <b>可能小于请求条数甚至为 0</b>，都属正常，
     * 不要当失败处理；判据看响应里的 {@code removedMsgIds}。
     */
    public record PendingClear(
            /** 目标会话 id。 */
            Long convId,
            /**
             * ★要清除的消息 id（一般为上一次 {@code list} 返回的 {@code items[].msgId} 原样回传）。
             *
             * <p>★为什么不按 {@code (convId, agent)} 整队清空：读与清之间<b>可能有新消息入队</b>
             * （用户手快又发了一条），整队清空会把智能体<b>还没看过</b>的那条一起删掉 ⇒
             * 既没进上下文、行也没了 ⇒ <b>静默丢消息</b>。
             */
            List<Long> msgIds,
            String bizMsgId,
            String traceId) {
    }
}
