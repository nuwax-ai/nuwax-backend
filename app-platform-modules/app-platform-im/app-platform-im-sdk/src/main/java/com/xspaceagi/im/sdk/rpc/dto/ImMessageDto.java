package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★发送类 RPC 的精简回执（契约 {@code ImMessageRpcDto}，与 core-sdk 的 {@code SendMessageResult} 对齐）。
 *
 * @param msgId         ★雪花 ID（int64）。★JS/TS 侧一律按 <b>string</b> 解析 ——
 *                      雪花 ID 是 19 位十进制，超出 IEEE-754 双精度的安全整数范围（2^53），
 *                      {@code JSON.parse} 会静默丢精度且<b>不报错</b>（相邻消息变成同一个 ID）。
 * @param convId        会话 ID（单聊时由服务端创建/复用后回填）
 * @param seq           会话内单调递增序号 —— <b>排序用它，不要用 sendTime</b>
 *                      （同毫秒多条、时钟回拨都会让 sendTime 排序出错）
 * @param sendTime      epoch millis
 * @param idempotentHit ★true = 本次是 {@code IM_20005} 幂等命中、其余字段是<b>首次执行</b>的结果。
 *                      调用方据此避免重复触发下游动作（例如重复发通知）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImMessageDto(Long msgId,
                           Long convId,
                           Long seq,
                           Long sendTime,
                           Boolean idempotentHit) {

    /** ★空安全判定：服务端未返回该字段时视为 false（首次执行）。 */
    public boolean idempotentHitOrFalse() {
        return Boolean.TRUE.equals(idempotentHit);
    }
}
