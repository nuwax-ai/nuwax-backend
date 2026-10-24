package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★互动卡片消息请求（契约 {@code CardMessageSendRequest} → {@code POST /internal/im/rpc/messages/card}）。
 *
 * <p>基础四件套语义见 {@link ImP2pMessageRequest}。
 *
 * @param convId 目标会话（分片键）
 * @param card   ★卡片体（必填；{@code title} 决定会话列表里显示的 {@code [卡片] xxx} 摘要）
 * @param ext    业务扩展（原样透传）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImCardMessageRequest(Long userId,
                                   Long tenantId,
                                   String traceId,
                                   String bizMsgId,
                                   Long convId,
                                   ImCardBody card,
                                   String ext)
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
        private ImCardBody card;
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

        public Builder convId(Long convId) {
            this.convId = convId;
            return this;
        }

        public Builder card(ImCardBody card) {
            this.card = card;
            return this;
        }

        public Builder ext(String ext) {
            this.ext = ext;
            return this;
        }

        public ImCardMessageRequest build() {
            return new ImCardMessageRequest(userId, tenantId, traceId, bizMsgId, convId, card, ext);
        }
    }
}
