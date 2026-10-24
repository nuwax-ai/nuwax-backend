package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * ★DEV-277 会话列表响应（契约 {@code ConversationPageRpcDto}）。
 *
 * <h2>★★翻页判据只有 {@link #hasMore()} 一个，且 {@code nextCursor} 恒与它同向</h2>
 *
 * <p>{@code nextCursor} 的语义是「{@code hasMore=false} 时<b>恒为 null</b>」——
 * 含「最后一页正好取满」与「该用户一个会话都没有」两种情形。
 * ★<b>不是</b>「list 为空才为 null」。照后者写调用方的话，
 * 会拿最后一页的游标再翻一次、取回一个空页。
 *
 * <p>★{@code hasMore} 是服务端<b>真取过 {@code limit+1} 条</b>得出的，不是估算
 * （猜边界会给调用方多一次空翻页）。
 *
 * <h2>★★刻意<b>没有</b> {@code total}</h2>
 *
 * <p>「一共有多少个会话」要独立扫一遍计数，而本端点的价值恰恰是用游标把
 * 「取一页」保持成「取一页」。⇒ 想知道总数只能一直翻到 {@code hasMore=false} 并累加。
 *
 * <h2>★顺序<b>不要</b>重排</h2>
 *
 * <p>{@code list} 已按会话活跃度倒序（置顶优先，再按最后消息时间）。
 * 与游标里的排序键同源 —— 本地重排过的列表配上游标翻页，会出现「翻页时某条记录被跳过」。
 *
 * @param list       本页会话（★已按活跃度倒序；空列表 = 该用户没有会话，不是错误）
 * @param hasMore    是否还有下一页（★判停<b>只看它</b>）
 * @param nextCursor 下一页游标（原样回填 {@code ImConversationListRequest.cursor}）；
 *                   ★{@code hasMore=false} 时为 {@code null}；
 *                   ★<b>不透明</b>，不要解析、不要自己拼、★<b>不要跨用户复用</b>（串用不报错，只会静默返回错位的一页）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImConversationPageDto(List<ImConversationSummaryDto> list,
                                    Boolean hasMore,
                                    String nextCursor) {

    /** ★空安全列表。 */
    public List<ImConversationSummaryDto> listOrEmpty() {
        return list == null ? Collections.emptyList() : list;
    }

    /** ★空安全：未返回视为「没有下一页」—— <b>不</b>保守（保守会让调用方空转翻页）。 */
    public boolean hasMoreOrFalse() {
        return hasMore != null && hasMore;
    }

    /**
     * ★本页条数（<b>不是</b>总数）。
     *
     * <p>★注意它只是「这一页有多少条」：调用方若拿它当「这个用户有几个会话」，
     * 在 {@link #hasMoreOrFalse()} 为 true 时会少算。
     */
    public int size() {
        return listOrEmpty().size();
    }
}
