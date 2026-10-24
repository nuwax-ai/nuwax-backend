package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * ★系统 / Agent 消息请求（契约 {@code SystemMessageSendRequest} → {@code POST /internal/im/rpc/messages/system}）。
 *
 * <h2>★{@code convId} 与 {@code toUserId} <b>二选一</b>（至少一个，都缺 → {@code IM_90001}）</h2>
 *
 * <ul>
 *   <li>给<b>已知会话</b>发通知（如"你的审批已通过"发到既有单聊）⇒ 传 {@code convId}；</li>
 *   <li>给<b>某个用户</b>发通知但不知道会话 ⇒ 传 {@code toUserId}，服务端定位/自动创建系统单聊。</li>
 * </ul>
 * 两个都传时以 {@code convId} 为准（显式指定优先）。
 *
 * <h2>★{@code senderType} 是"这条消息以谁的名义出现"</h2>
 *
 * <p>取值 {2, 3}（缺省 3）：2=机器人/Agent、3=系统。★这里<b>没有</b> {@code senderType=1}（真人）——
 * 真人身份只能靠用户 token 在 {@code /api} 平面获得；RPC 平面用 HMAC 签名，
 * <b>不允许</b>用它伪造真人发言。这是"两条通道凭据绝不混用"的具体体现。
 *
 * @param senderType 2=机器人/Agent，3=系统（缺省）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImSystemMessageRequest(Long userId,
                                     Long tenantId,
                                     String traceId,
                                     String bizMsgId,
                                     Long convId,
                                     Long toUserId,
                                     Integer senderType,
                                     String msgType,
                                     JsonNode body,
                                     String ext)
        implements ImRpcRequest {

    /** ★机器人/Agent 发送。 */
    public static final int SENDER_TYPE_BOT = 2;

    /** ★系统发送（缺省）。 */
    public static final int SENDER_TYPE_SYSTEM = 3;

    public static Builder builder() {
        return new Builder();
    }

    /** ★链式构造。 */
    public static final class Builder {
        private Long userId;
        private Long tenantId;
        private String traceId;
        private String bizMsgId;
        private Long convId;
        private Long toUserId;
        private Integer senderType;
        private String msgType;
        private JsonNode body;
        private String ext;

        public Builder actor(Long userId, Long tenantId, String bizMsgId) {
            this.userId = userId;
            this.tenantId = tenantId;
            this.bizMsgId = bizMsgId;
            return this;
        }

        public Builder traceId(String traceId) {
            this.traceId = traceId;
            return this;
        }

        /** ★按会话发（与 {@link #toUserId(Long)} 二选一，都传时服务端以 convId 为准）。 */
        public Builder convId(Long convId) {
            this.convId = convId;
            return this;
        }

        /** ★按用户发（服务端定位/自动创建系统单聊）。 */
        public Builder toUserId(Long toUserId) {
            this.toUserId = toUserId;
            return this;
        }

        public Builder senderType(Integer senderType) {
            this.senderType = senderType;
            return this;
        }

        public Builder msgType(String msgType) {
            this.msgType = msgType;
            return this;
        }

        public Builder body(JsonNode body) {
            this.body = body;
            return this;
        }

        public Builder ext(String ext) {
            this.ext = ext;
            return this;
        }

        public ImSystemMessageRequest build() {
            return new ImSystemMessageRequest(userId, tenantId, traceId, bizMsgId,
                    convId, toUserId, senderType, msgType, body, ext);
        }
    }
}
