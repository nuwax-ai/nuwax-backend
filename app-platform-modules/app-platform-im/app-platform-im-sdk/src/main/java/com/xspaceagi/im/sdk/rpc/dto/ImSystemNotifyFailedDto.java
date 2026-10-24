package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★DEV-291 系统通知的<b>单条业务性失败明细</b>（契约 {@code SystemNotifySendFailed}）。
 *
 * <p>它是 {@link ImSystemNotifyResultDto#failed()} 的元素。
 *
 * <h2>★★"业务性跳过"与"调用失败"是两件事</h2>
 *
 * <p>出现本对象时，整体信封<b>仍然是</b> {@code code=0000}："某个收件人在平台上
 * 查不到"是<b>业务结论</b>，不是这次调用出错。⇒ 调用方<b>必须</b>逐条读
 * {@link #reason()}，只看 {@code failed} 是否为空是不够的。
 * 补发纪律见 {@link ImSystemNotifyResultDto} 类注释。
 *
 * @param toUserId 收件人
 * @param reason   ★失败原因（可直接展示给排查者 / 记进调用方日志），
 *                 形如 {@code IM_20103: 会话不存在}，由错误码与文案拼成
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImSystemNotifyFailedDto(Long toUserId,
                                      String reason) {
}
