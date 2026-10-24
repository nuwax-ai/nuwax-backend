package com.xspaceagi.im.sdk.rpc;

import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingClearDto;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingClearRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingListDto;
import com.xspaceagi.im.sdk.rpc.dto.ImAgentPendingListRequest;

/**
 * Nuwax IM 智能体域 RPC 服务（契约 {@code /internal/im/rpc/agents/**}，★DEV-285 起 2 个端点）。
 *
 * <p>★与 {@link IImContactRpcService} 同一套约定：方法名 = 契约 {@code operationId}；
 * 失败抛 {@link ImRpcException}。
 *
 * <h2>★为什么单独一个接口，而不是塞进消息/会话服务</h2>
 *
 * <p>契约 tag 就是 {@code agents}，与 {@code messages} / {@code conversations} /
 * {@code contacts} / {@code events} 平级 —— 接口与 tag 对齐后，「按 tag 找实现」是机械动作。
 * 更重要的是<b>身份口径不同</b>：本组两个端点要求 {@code userId} 是<b>已登记的智能体主体</b>
 * （IM 侧 {@code isAgentStrict} 三态 fail-closed），
 * 而其余四组的 {@code userId} 是「以谁的身份办事」（真人或智能体都行）。
 * 混在一个接口里，调用方很容易拿「当前登录用户」去填 —— 那在别的端点能跑通，在这里必然被拒。
 *
 * <h2>★★这两个端点解决的是什么问题</h2>
 *
 * <p>DEV-284 起，用户连发的消息先进 IM 侧的<b>排队区</b>（{@code im_agent_pending}），
 * 由 IM 执行者在当前 4.10 结束后 drain 合并、<b>再发起一轮 4.10</b>。
 * 而智能体在<b>本轮工具循环里</b>其实就能自己把这些新消息消费掉 ——
 * 于是 IM 侧那份「兜底的再次投喂」就成了重复劳动（多一轮 4.10 = 多一次 token 消耗，
 * 且同一批消息可能被处理两遍）。
 *
 * <pre>
 *   智能体每轮工具调用间隙：
 *     ① {@link #listPending}   → 这个会话里还有哪些新消息在等我（含完整正文）
 *     ② 并入自己的上下文
 *     ③ {@link #clearPending}  → 声明「这批我消费了」
 *   ⇒ IM 侧下一轮 drain 到空 ⇒ 不再多发起一次 4.10
 * </pre>
 *
 * <p>★这是 DEV-284 排队合并的<b>对侧通道</b>：IM 侧 {@code runLoop} 是「智能体不自理时」的兜底，
 * 本接口是「智能体自理」的正门。两者殊途同归，不会双投喂。
 *
 * <h2>★★{@code clearPending} 的语义是「声明已消费」，不是「丢弃」</h2>
 *
 * <p>清掉之后 IM 侧<b>不会再处理</b>这批消息。所以顺序必须是
 * <b>先 list 读走 → 确已并入上下文 → 再按同一批 msgId clear</b>。
 * 「清了却没真消费」= 用户视角的「发了没回音」，那是静默丢消息。
 * 细节与竞态分析见 {@link ImAgentPendingClearRequest}。
 */
public interface IImAgentRpcService {

    /**
     * {@code POST /internal/im/rpc/agents/pending/list} —— 查这个会话的排队区里
     * 还有哪些新消息在等本智能体处理（入队顺序）。
     *
     * <p>★返回的 {@code items[].text} 是<b>回查 {@code im_message} 后的完整正文</b>
     * （与 IM 侧合并投喂同一把尺子），拿到即可直接消费；上限 500 行且<b>没有分页</b> ——
     * 触顶 = 异常堆积现场，不是「还有下一页」（理由见 {@link ImAgentPendingListDto}）。
     *
     * <p>★已撤回 / 已删除 / 查无的消息<b>整条不返回</b> ⇒ 出现的每一条都可安全消费。
     *
     * <p>★读操作（{@code bizMsgId} 免填），但仍过签名 / nonce / 限流。
     *
     * @param req {@code userId}（★智能体主体 id，必填；传本人会被 IM 拒）
     *            + {@code tenantId} + {@code convId}（必填）
     */
    ImAgentPendingListDto listPending(ImAgentPendingListRequest req);

    /**
     * {@code POST /internal/im/rpc/agents/pending/clear} —— 按 {@code msgIds}
     * <b>点名</b>清除排队行，并把被清消息的状态行 settle 为「完成」。
     *
     * <p>★★必须<b>逐个回传上一次 list 拿到的 msgId</b>，不要「整队清空」：
     * list 与 clear 之间用户可能又发了一条，整队清会把那条智能体<b>还没看过</b>的
     * 消息一起删掉 ⇒ 既没进上下文、行也没了 ⇒ 静默丢消息。
     *
     * <p>★实删可能少于请求条数甚至为 0（行已被 IM 侧 drain 走 / 本就不在队 / 幂等重放），
     * <b>都不报错</b>。判「清干净没有」看 {@link ImAgentPendingClearDto#removedMsgIds()}，
     * <b>不要</b>拿「请求条数 == removedCount」当成功判据。
     *
     * <p>★写操作 ⇒ {@code bizMsgId} 必填（缺失 IM 侧 IM_90001），超时重发同键回放首次结果。
     *
     * @param req {@code userId}（★智能体主体 id，必填）+ {@code tenantId}
     *            + {@code convId}（必填）+ {@code msgIds}（必填非空，≤500）+ {@code bizMsgId}（必填）
     */
    ImAgentPendingClearDto clearPending(ImAgentPendingClearRequest req);
}
