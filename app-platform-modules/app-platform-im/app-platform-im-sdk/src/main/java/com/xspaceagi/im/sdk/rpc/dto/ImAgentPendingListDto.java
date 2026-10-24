package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * ★DEV-285 排队区自查响应（契约 {@code AgentPendingListRpcDto}）。
 *
 * <h2>★★刻意<b>没有</b>分页（无 cursor / hasMore / total）</h2>
 *
 * <p>排队区常态<b>近空</b>（drain 即删、clear 即删），一次 list 就该拿全。
 * 服务端硬上限 {@link #MAX_ITEMS} = 500，真触顶 = <b>异常堆积现场</b>
 * （执行者长期没跑），那需要的是告警而不是翻页协议 ——
 * 给一个 {@code hasMore} 反而会把异常藏起来（调用方会安心地翻下一页，
 * 于是「堆积」这件事在两边都不再显眼）。
 *
 * <p>对照 {@link ImConversationPageDto} 的游标分页：那里是「可能真的很多」，
 * 这里是「多了就是出事了」。
 *
 * <h2>★顺序 = 入队顺序，调用方不要重排</h2>
 *
 * <p>与 IM 侧 drain 的合并顺序<b>同一把尺子</b>（先 {@code create_time}，
 * 同秒时雪花 id 兜底）⇒ 按列表顺序并入上下文，时间线与用户说话顺序一致。
 *
 * <p>★空列表 = 排队区没有等待的消息。这<b>包含一种正常竞态</b>：
 * 流结束后才来查、行已被 IM 侧 drain 走 —— 那批消息由 IM 侧正常处理，<b>不丢</b>。
 *
 * @param items ★排队中的消息（入队顺序；已剔除撤回/删除/查无，每条都可安全消费）。永不为 null
 * @param count ★{@code items} 的条数（冗余字段，与 {@code items.size()} 恒等）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImAgentPendingListDto(List<ImAgentPendingItemDto> items,
                                    Integer count) {

    /** ★单次上限 —— 超过即截断（与服务端常量同值）。触顶请当异常处理，见类注释。 */
    public static final int MAX_ITEMS = 500;

    /** ★空安全列表。 */
    public List<ImAgentPendingItemDto> itemsOrEmpty() {
        return items == null ? Collections.emptyList() : items;
    }

    /** ★本批条数（以列表实际长度为准，不信冗余的 {@code count}）。 */
    public int size() {
        return itemsOrEmpty().size();
    }

    /** ★有没有等待处理的消息。★{@code false} 时<b>不要</b>再调 {@code clearPending}。 */
    public boolean isEmpty() {
        return itemsOrEmpty().isEmpty();
    }

    /**
     * ★本批是不是撞到了上限 —— 撞到说明排队区在异常堆积，值得上报而不是当成正常翻页。
     *
     * <p>判据用「条数 ≥ 上限」而不是「有下一页标志」：服务端刻意不下发那种标志
     * （见类注释），所以只能这样推断，也因此把推断收在方法里而不是散在调用点。
     */
    public boolean hitCap() {
        return size() >= MAX_ITEMS;
    }
}
