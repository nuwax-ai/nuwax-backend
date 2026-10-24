package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★DEV-291 系统通知的<b>单条成功明细</b>（契约 {@code SystemNotifySendItem}）。
 *
 * <p>它是 {@link ImSystemNotifyResultDto#list()} 的元素 ——
 * 一次调用给多个收件人各发一条，每发出去一条就产生一个本对象。
 *
 * <h2>★为什么要回执而不是只回一个 msgId</h2>
 *
 * <p>批量端点只回一个 {@code msgId}，调用方就<b>无法知道到底谁收到了</b>。
 * 于是结果拆成 {@link ImSystemNotifyResultDto#list()}（成功）
 * 与 {@link ImSystemNotifyResultDto#failed()}（业务性跳过）两堆，
 * {@link #toUserId()} 就是"把结果与请求对上"的唯一依据（回执里的主键）。
 *
 * @param toUserId ★收件人（把结果与请求对上的唯一依据）
 * @param convId   该收件人与「系统通知」的单聊（★同一用户每次都是同一个）
 * @param msgId    ★雪花 ID（消费方是 TS 时按 string 解析，JS 精度只到 2^53）
 * @param seq      会话内序号（★排序用它，不用 {@code sendTime}）
 * @param sendTime epoch millis
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImSystemNotifyItemDto(Long toUserId,
                                    Long convId,
                                    Long msgId,
                                    Long seq,
                                    Long sendTime) {
}
