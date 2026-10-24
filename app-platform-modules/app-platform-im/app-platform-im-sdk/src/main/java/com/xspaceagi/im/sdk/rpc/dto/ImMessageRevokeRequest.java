package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★撤回消息请求（契约 {@code MessageRevokeRequest} → {@code POST /internal/im/rpc/messages/revoke}）。
 *
 * <p>★本端点 <b>void</b>：成功时 {@code data=null}，幂等命中时 {@code code=IM_20005} 而 data 仍为 null。
 *
 * <h2>★权限与时限不由调用方决定</h2>
 *
 * <p>服务端按与 {@code /api} <b>相同</b>的规则校验：只有发送者本人（或群主/管理员）能撤回，
 * 且超过时限（{@code IM_20002}）。SDK 侧<b>不</b>做前置判断 ——
 * "我是不是能撤回这条"的答案只有服务端知道（成员角色、禁言、时限都在它那边）。
 *
 * @param convId 会话 ID（分片键，用于定位消息所在分表）
 * @param msgId  消息 ID
 * @param reason 撤回原因（可空，≤256；会进入审计/系统提示）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImMessageRevokeRequest(Long userId,
                                     Long tenantId,
                                     String traceId,
                                     String bizMsgId,
                                     Long convId,
                                     Long msgId,
                                     String reason)
        implements ImRpcRequest {

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
        private Long msgId;
        private String reason;

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

        public Builder convId(Long convId) {
            this.convId = convId;
            return this;
        }

        public Builder msgId(Long msgId) {
            this.msgId = msgId;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public ImMessageRevokeRequest build() {
            return new ImMessageRevokeRequest(userId, tenantId, traceId, bizMsgId, convId, msgId, reason);
        }
    }
}
