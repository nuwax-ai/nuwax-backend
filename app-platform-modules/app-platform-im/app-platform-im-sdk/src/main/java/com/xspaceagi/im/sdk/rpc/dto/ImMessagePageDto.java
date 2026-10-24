package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * ★DEV-276 {@code messages/list} 的响应（契约 {@code MessagePageRpcDto}）。
 *
 * <h2>★★翻页判据只有一个：{@link #hasMore()}</h2>
 *
 * <p>三条看起来都能用来判停，只有一条是对的：
 * <table border="1">
 *   <caption>判停</caption>
 *   <tr><th>写法</th><th>后果</th></tr>
 *   <tr><td>★{@code hasMore()}</td><td><b>正确</b></td></tr>
 *   <tr><td>{@code nextSeq() != null}</td><td>本契约下与 hasMore 等价（见下），但别依赖这个巧合</td></tr>
 *   <tr><td>{@code list().isEmpty()}</td><td>★★<b>错</b>：正好取完最后一页时 list 非空、
 *       hasMore 为 false，照这条写会拿最后一页的 seq <b>再翻一次</b>取回空页</td></tr>
 * </table>
 *
 * <p>★{@code nextSeq} 的语义是 <b>"hasMore=false 时恒为 null"</b>（含"最后一页正好取满"
 * 与"该时段一条都没有"两种）——<b>不是</b>"list 为空才为 null"。
 * 这是 SERVER 侧刻意收的口径：让"有没有下一页"与"游标存不存在"是同一个事实，
 * 调用方少一个可以理解错的量。
 *
 * <h2>★★刻意<b>没有</b> {@code total}</h2>
 *
 * <p>"这段时间一共有多少条"要 {@code COUNT(*)} 扫完整个时段，而本查询的价值恰恰在于
 * "时段可能很宽"。⇒ 想知道总数只能自己一直翻到 {@code hasMore=false} 并累加
 * （★注意：那样得到的是"本次 limit 下能翻到的条数"，与真实总数只有在你翻完时才相等）。
 *
 * <h2>★元素是 {@link ImMessageDetailDto}，但 viewer 相关字段为空</h2>
 *
 * <p>本端点返回<b>原始消息投影</b>：{@code replyTo} / 卡片态 / 智能体态等
 * "给某个人渲染"的字段<b>一律为 null</b>（内部查询没有 viewer）。
 * ★不是"忘了填"——要那些字段请用 {@code getMessage}（它按某个身份渲染）。
 *
 * @param list    本页消息（★{@code seq ASC} = 时间正序；空 = 该时段没有消息，不是错误）
 * @param hasMore 是否还有下一页（服务端"多取一条"得出，不是估算）
 * @param nextSeq 下一页游标（原样回填 {@code ImMessageListRequest.afterSeq}）；
 *                ★{@code hasMore=false} 时为 {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImMessagePageDto(List<ImMessageDetailDto> list,
                               Boolean hasMore,
                               Long nextSeq) {

    /** ★空安全列表。 */
    public List<ImMessageDetailDto> listOrEmpty() {
        return list == null ? Collections.emptyList() : list;
    }

    /** ★空安全：未返回视为"没有下一页" —— <b>不</b>保守（保守会让调用方空转翻页）。 */
    public boolean hasMoreOrFalse() {
        return hasMore != null && hasMore;
    }

    /** ★本页条数（不是总数）。 */
    public int size() {
        return listOrEmpty().size();
    }
}
