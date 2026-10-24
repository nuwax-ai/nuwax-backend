package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★单条消息的已读人数（契约 {@code ReadCountRpcItem}，与 core-sdk 的 {@code ReadStatusItem} 对齐）。
 *
 * @param msgId               消息 ID
 * @param seq                 会话内序号
 * @param readCount           已读人数
 * @param eligibleCount       应读人数（活跃成员数，<b>不含发送者本人</b>）——
 *                            "全部已读"要拿它比，不能拿 {@code memberCount} 比
 * @param readAll             服务端判定的"已全部已读"
 * @param level               档位 {@code L1/L2/L3}：L2 = 群 >500 人、L3 = 冷会话（>7 天未活跃）
 * @param readerListAvailable ★false 表示<b>拿不到</b>已读名单（>500 人的群只提供计数，
 *                            02 §7.3.1 明令禁止静默降级查 DB）。
 *                            ★调用方必须先看这个字段再决定要不要展示"查看已读的人"入口 ——
 *                            直接去调名单接口会拿到 {@code IM_30201}。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReadCountItemDto(Long msgId,
                               Long seq,
                               Integer readCount,
                               Integer eligibleCount,
                               Boolean readAll,
                               String level,
                               Boolean readerListAvailable) {

    /** ★空安全：未返回视为 false（保守取值 —— 宁可少一个入口，不要给用户一个点了报错的入口）。 */
    public boolean readerListAvailableOrFalse() {
        return Boolean.TRUE.equals(readerListAvailable);
    }

    /** ★空安全：{@code readCount >= eligibleCount} 兜底判定（服务端 readAll 缺失时用）。 */
    public boolean readAllOrCompute() {
        if (Boolean.TRUE.equals(readAll)) {
            return true;
        }
        return readCount != null && eligibleCount != null && eligibleCount > 0
                && readCount >= eligibleCount;
    }
}
