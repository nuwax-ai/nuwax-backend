package com.xspaceagi.im.sdk.rpc;

import com.xspaceagi.im.sdk.rpc.dto.ImCardMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImGroupMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageDetailDto;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageGetRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMessagePageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImMessageRevokeRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImP2pMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImReadCountsRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemMessageRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemNotifyRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImSystemNotifyResultDto;
import com.xspaceagi.im.sdk.rpc.dto.ReadCountItemDto;

import java.util.List;

/**
 * Nuwax IM 消息 RPC 服务（契约 {@code /internal/im/rpc/messages/**}，9 个端点）。
 *
 * <h2>★方法名与契约 {@code operationId} 逐字符一致</h2>
 *
 * <p>{@code sendP2pMessage} / {@code getOrCreateP2p} / … 不是审美问题：
 * 契约改了而实现没跟上时，「方法名能按 operationId 反查」是自动化发现漂移的**唯一**手段。
 *
 * <h2>★★调用前必须想清楚 {@code userId} 是谁</h2>
 *
 * <p>所有请求 DTO 的第一个字段 {@code userId} 是「**以谁的身份执行**」（服务端 {@code runAs} 切换后
 * 走与 {@code /api} 完全相同的应用服务，权限规则只有一套）。⇒ 传错 {@code userId}
 * 等于<b>以那个人的身份发消息</b>，且不会有任何报错。
 *
 * <p>★<b>唯一例外</b>是 {@link #sendSystemNotify}：它的 {@code userId} 只作
 * <b>审计归属</b>与租户上下文来源（发送身份由 IM 固定为「系统通知」合成账号），
 * 传谁都改变不了消息的展示发送者 —— 但它<b>仍必须为正数</b>、{@code tenantId} 也必填，
 * 否则 {@code IM_90001}。
 *
 * <h2>★写操作必须带 {@code bizMsgId}</h2>
 *
 * <p>写操作（发消息 / 撤回 / 建群 / 加退成员 / 改摘要 / 推事件）缺 {@code bizMsgId} → {@code IM_90001}。
 * 重试时必须<b>复用同一个值</b>，服务端命中幂等返 {@code IM_20005}（契约规定视为成功，
 * 因此不会抛异常，只是 {@code data} 里的 {@code idempotentHit=true}）。
 *
 * <h2>★失败一律抛 {@link ImRpcException}，本层不自动重试</h2>
 *
 * <p>发消息是<b>用户可感知</b>的操作，慢失败比「偷偷重试 3 次」更好。
 * 要不要重试看 {@link ImRpcException#isRetryable()}。
 */
public interface IImMessageRpcService {

    /** {@code POST /internal/im/rpc/messages/p2p} —— 单聊发消息（会话不存在则自动创建）。 */
    ImMessageDto sendP2pMessage(ImP2pMessageRequest req);

    /** {@code POST /internal/im/rpc/messages/group} —— 群消息。 */
    ImMessageDto sendGroupMessage(ImGroupMessageRequest req);

    /** {@code POST /internal/im/rpc/messages/system} —— 系统 / Agent 消息（{@code senderType=2|3}）。 */
    ImMessageDto sendSystemMessage(ImSystemMessageRequest req);

    /**
     * {@code POST /internal/im/rpc/messages/system-notify} —— ★DEV-291 批量发「系统通知」。
     *
     * <p>与 {@link #sendSystemMessage} 的分工：后者是"以系统名义往某个会话 / 某个用户的
     * 私聊里发一条消息"，{@code userId} 是<b>执行身份</b>；本方法<b>给不了发送身份</b>
     * （请求体里没有 {@code senderType}），消息由 IM 固定以合成账号投进每个收件人
     * <b>自己</b>的「系统通知」——带未读红点、进历史、可删除。
     *
     * <p>★失败是<b>逐条</b>的：个别收件人查不到时整体仍 {@code 0000}，明细在
     * {@link ImSystemNotifyResultDto#failed()} —— 调用方<b>必须</b>读它，
     * 只看"有没有抛异常"会把"一半人没收到"读成完全成功。
     *
     * <p>★{@code 0000} 之后用同一 {@code bizMsgId} 重试只会<b>回放</b>上次结果
     * （既不重发也不补发）；补发要换新键且只传 failed 里的 toUserIds。
     * 两条相反的纪律见 {@link ImSystemNotifyRequest} 与 {@link ImSystemNotifyResultDto}。
     */
    ImSystemNotifyResultDto sendSystemNotify(ImSystemNotifyRequest req);

    /** {@code POST /internal/im/rpc/messages/card} —— 互动卡片。 */
    ImMessageDto sendCardMessage(ImCardMessageRequest req);

    /** {@code POST /internal/im/rpc/messages/revoke} —— 撤回。★{@code data} 恒为 null（幂等命中也是 null）。 */
    void revokeMessage(ImMessageRevokeRequest req);

    /** {@code POST /internal/im/rpc/messages/get} —— 单条详情。★读操作，不过幂等闸门。 */
    ImMessageDetailDto getMessage(ImMessageGetRequest req);

    /** {@code POST /internal/im/rpc/messages/read-counts} —— 批量已读人数（{@code msgIds} ≤200）。 */
    List<ReadCountItemDto> getReadCounts(ImReadCountsRequest req);

    /**
     * {@code POST /internal/im/rpc/messages/list} —— 按时间范围列举会话消息。★读操作，不过幂等闸门。
     *
     * <p>★翻页<b>只认</b> {@link ImMessagePageDto#hasMoreOrFalse()}，
     * 把 {@link ImMessagePageDto#nextSeq()} 原样回填到下一次请求的 {@code afterSeq}。
     */
    ImMessagePageDto listMessagesByTimeRange(ImMessageListRequest req);
}
