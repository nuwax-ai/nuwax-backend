package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ★★DEV-291 {@code messages/system-notify} 的回执（契约 {@code SystemNotifySendResult}）——
 * <b>一张对账单，不是"一条消息"</b>。
 *
 * <h2>★★{@link #failed()} 非空不等于失败</h2>
 *
 * <p>本端点一次调用给多个收件人各发一条，结果天然是逐条的。个别收件人不存在 /
 * 查不到资料时，整体信封<b>仍然是</b> {@code code=0000} —— 它更不能拖垮整批
 * （一个离职账号不该让另外 99 个人的通知发不出去）。
 * ⇒ 调用方<b>必须</b>读 {@link #failed()} 才知道有没有人要补发；只看
 * "有没有抛异常"会把"一半人没收到"读成完全成功。
 *
 * <h2>★★补发纪律（两条相反，别记混）</h2>
 *
 * <ul>
 *   <li>{@code code=0000}（哪怕 {@link #failed()} 非空）⇒ 本次结果已被幂等闸门缓存 24h，
 *       用<b>同一</b> {@code bizMsgId} 重试只会回放本对象（{@code IM_20005}），
 *       <b>既不重发也不补发</b>。要补发 {@link #failed()} 里的人，必须<b>换新的
 *       {@code bizMsgId} 且只传那些 toUserIds</b>（把已成功的混进去会真的再发一条）。</li>
 *   <li>{@code code≠0000}（系统异常，如 DB / Redis 抖动）⇒ 幂等占位已<b>释放</b>，
 *       用同一 {@code bizMsgId} 重试<b>整批</b>是安全的：已成功的那几条会被消息域的
 *       {@code clientMsgId} 唯一键挡住（那个值由 {@code bizMsgId + toUserId} 决定、可复现）
 *       ⇒ 不重复落库、不重复扇出，只有没发出去的会被补上。</li>
 * </ul>
 *
 * @param targetCount 去重后的目标数（= {@link #list()} + {@link #failed()} 的条数之和）
 * @param sentCount   实际投递给消息域的条数（= {@link #list()} 的条数）
 * @param list        成功明细（顺序与请求里去重后的顺序一致）
 * @param failed      业务性跳过的收件人（见类注释的补发纪律）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImSystemNotifyResultDto(Integer targetCount,
                                      Integer sentCount,
                                      List<ImSystemNotifyItemDto> list,
                                      List<ImSystemNotifyFailedDto> failed) {

    /** ★空安全：未返回视为空表 —— 调用方拿它做循环时不必再判 null。 */
    public List<ImSystemNotifyItemDto> listOrEmpty() {
        return list == null ? List.of() : list;
    }

    /** ★空安全：未返回视为空表（⇒ 不会因为字段缺失走到"补发"分支）。 */
    public List<ImSystemNotifyFailedDto> failedOrEmpty() {
        return failed == null ? List.of() : failed;
    }

    /** ★是否<b>逐条</b>全成功（★不是"调用成功"，调用成功但部分跳过时这里为 false）。 */
    public boolean allDelivered() {
        return failedOrEmpty().isEmpty();
    }
}
