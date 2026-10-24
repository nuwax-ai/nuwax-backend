package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * ★DEV-285 排队区「声明已消费」响应（契约 {@code AgentPendingClearRpcDto}）。
 *
 * <h2>★★{@code removedCount} 可能<b>小于</b>请求条数，甚至为 0 —— 都正常</h2>
 *
 * <p>三种「删少了」的成因，全都<b>不报错</b>：
 *
 * <ol>
 *   <li>行已被 IM 侧执行者 drain 走（「流结束后才来 clear」的竞态窗口）
 *       ⇒ 那批消息由 IM 侧合并处理，<b>不丢</b>；</li>
 *   <li>msgId 本就不在队里（传错 / 重复 clear 同一批 —— 第二次删 0）；</li>
 *   <li>幂等重放（同 {@code bizMsgId} 超时重发）⇒ 回放的是<b>首次</b>的删除结果，
 *       不是又删了一遍。</li>
 * </ol>
 *
 * <p>★★所以<b>不要</b>拿「请求条数 == removedCount」当成功判据 ——
 * 那会把这三种正常情形都误报成失败。判「清干净没有」看 {@link #removedMsgIds()}。
 *
 * <p>★每一条实删的消息，其状态行在 IM 侧已同步 settle 为「完成」（推终态帧 + 代读）
 * ⇒ 前端显示「完成」，1 天后不会被投影成「挂起」。
 *
 * @param removedCount ★实删行数（= {@code removedMsgIds.size()}；删 0 正常，见类注释）
 * @param removedMsgIds ★实删的消息 id（请求 msgIds 的子集）。
 *                      ★顺序 = 队列序，<b>不是</b>请求序 —— 两个顺序对账没有意义，集合相等才是判据
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImAgentPendingClearDto(Integer removedCount,
                                     List<Long> removedMsgIds) {

    /** ★空安全列表。 */
    public List<Long> removedMsgIdsOrEmpty() {
        return removedMsgIds == null ? Collections.emptyList() : removedMsgIds;
    }

    /** ★实删条数（以列表实际长度为准，不信冗余的 {@code removedCount}）。 */
    public int removedSize() {
        return removedMsgIdsOrEmpty().size();
    }

    /** ★一条都没删掉（正常竞态的一种，见类注释 —— 不是失败）。 */
    public boolean removedNothing() {
        return removedMsgIdsOrEmpty().isEmpty();
    }

    /** ★指定这条是否真的被删掉了（用于「我读过的这批到底清了没」的逐条核对）。 */
    public boolean removed(Long msgId) {
        return msgId != null && removedMsgIdsOrEmpty().contains(msgId);
    }
}
