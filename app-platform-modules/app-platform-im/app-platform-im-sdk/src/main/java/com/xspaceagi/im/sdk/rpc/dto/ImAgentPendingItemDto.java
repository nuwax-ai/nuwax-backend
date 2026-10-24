package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★DEV-285 排队区里的一条消息（契约 {@code AgentPendingItemRpcDto}
 * ，{@code agents/pending/list} 的响应元素）。
 *
 * <h2>★★{@code text} 是<b>回查后的完整正文</b>，不是队列行的摘要快照</h2>
 *
 * <p>IM 侧逐条按 {@code msgId} 回查 {@code im_message} 取全文，拿不到（非文本类型）
 * 才回退 {@link #digest()}。与 IM 侧「合并投喂」<b>同一把尺子</b> ⇒
 * 智能体从这里拿到的东西，与它被 IM 直接喂进来时看到的一致。
 * 为什么不直接发快照：{@code digest} 是 ≤512 字符的<b>截断摘要</b>（图片类只有「[图片]」占位），
 * 而且<b>快照会把撤回前的内容发出去</b> —— 「永远按 msgId 回查、不信快照」是本项目的撤回一致性纪律。
 *
 * <p>★已撤回 / 已删除 / 查无的消息<b>整条不返回</b>（调用方看不到它们，也无从 clear 它们）。
 * ⇒ 这个类型里出现的每一条都是<b>可安全消费</b>的。
 *
 * <h2>★ID 是 {@code Long}（雪花），JS 侧有 2^53 精度问题</h2>
 *
 * <p>{@code msgId} 是雪花 id，位数会超出 JS {@code Number} 的安全整数范围。
 * 这是<b>整个 RPC 平面的既有话题</b>（{@code messages/list} 的 {@code nextSeq} 同构），
 * 不在本端点单独解决。缓解办法：{@code clear} 只需要把这个值<b>原样回传</b>，
 * 用能保精度的 JSON 解析器（如 bigint-safe 模式）则无损。
 *
 * @param msgId       ★消息 id（雪花）。{@code clear} 时按它点名；也是 IM 侧回查正文的键
 * @param senderId    ★发送者的 IM subject id（真人 = 平台 userId 同域；也可能是另一个智能体）
 * @param senderName  ★发送者昵称快照（入队时登记，可能为 null；群聊里「谁发的」以它为准）
 * @param seq         消息在会话内的 seq（会话高度）
 * @param text        ★完整正文（回查 {@code im_message}；非文本类型回退 digest）
 * @param digest      ★入队时的摘要快照（≤512 字符，图片类 =「[图片]」；保留供展示/对账）
 * @param enqueueTime ★入队时间（epoch millis）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImAgentPendingItemDto(Long msgId,
                                    Long senderId,
                                    String senderName,
                                    Long seq,
                                    String text,
                                    String digest,
                                    Long enqueueTime) {

    /**
     * ★可读正文：优先完整正文，退回摘要快照，都没有就空串。
     *
     * <p>把「两个字段怎么选」收在这里，是为了让调用方不必各自写一遍
     * {@code text != null ? text : digest} —— 那种散落的三元表达式迟早会出现
     * 「这个调用点用 text、那个用 digest」的不一致，而表现在用户侧就是
     * 「同一批消息，有时完整有时被截断」。
     */
    public String readableText() {
        if (text != null && !text.isBlank()) {
            return text;
        }
        return digest == null ? "" : digest;
    }
}
