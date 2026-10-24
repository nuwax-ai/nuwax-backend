package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * ★消息详情（契约 {@code ImMessageDetailRpcDto}，{@code getMessage} 的返回）。
 *
 * <p>{@code body} 保持为 {@link JsonNode} 而不强类型化：
 * 消息体形态由 {@code msgType} 决定（text/image/file/card…），
 * 契约里它是 {@code additionalProperties: true} 的自由对象。
 * SDK 若把它拆成强类型，就等于<b>替服务端定义了消息体 schema</b> ——
 * 而新增一种 msgType 会变成一次 SDK 发版，这与"零依赖轻客户端"的定位相悖。
 *
 * @param msgId          雪花 ID（★JS 侧按 string 解析，见 {@link ImMessageDto}）
 * @param senderType     1=真人 2=机器人/Agent 3=系统
 * @param msgStatus      1=正常 2=已撤回 3=审核拦截
 * @param replyToMsgId   引用消息 ID（可空）
 * @param mentionUserIds 被 @ 的 userId 列表（不校验是否群成员，与 /api 同纪律）
 * @param readCount      已读人数（可空：冷会话/未刷新时为 null，<b>不要把 null 当 0</b>）
 * @param ext            业务扩展字段（服务端不解析、原样透传的 JSON 原文）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImMessageDetailDto(Long msgId,
                                 Long convId,
                                 Long seq,
                                 Long senderId,
                                 Integer senderType,
                                 String msgType,
                                 JsonNode body,
                                 String digest,
                                 Integer msgStatus,
                                 Long replyToMsgId,
                                 List<Long> mentionUserIds,
                                 Boolean mentionAll,
                                 Long sendTime,
                                 Long revokeTime,
                                 Long editTime,
                                 Integer readCount,
                                 String ext) {

    /** ★消息是否处于"已撤回"态（{@code msgStatus == 2}），空安全。 */
    public boolean revoked() {
        return msgStatus != null && msgStatus == 2;
    }

    /** ★消息是否被审核拦截（{@code msgStatus == 3}）。 */
    public boolean blocked() {
        return msgStatus != null && msgStatus == 3;
    }
}
