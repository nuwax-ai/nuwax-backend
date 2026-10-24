package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★取或建单聊请求（契约 {@code P2pConversationRequest} → {@code POST /internal/im/rpc/conversations/p2p}）。
 *
 * <h2>★"取或建"是<b>原子</b>的，调用方不需要先查后建</h2>
 *
 * <p>服务端按 {@code (userId, toUserId)} 归一化后做唯一键插入（同一对用户顺序无关），
 * 返回 {@code created} 标识本次是新建还是复用。⇒ 调用方应当<b>直接调它</b>，
 * 而不是"先 GET 列表看有没有 → 没有就建"：后者在并发下会得到两条会话
 * （两个请求同时查到"没有"）。这个接口的存在就是为了替掉那个错误模式。
 *
 * <p>★{@code userId == toUserId} → {@code IM_20106}（一期不支持与自己建会话）。
 *
 * <p>基础四件套（含 {@code bizMsgId}，写操作必填）语义见 {@link ImP2pMessageRequest}。
 *
 * @param toUserId 对手方（会话的"另一个人"）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImP2pConversationRequest(Long userId,
                                       Long tenantId,
                                       String traceId,
                                       String bizMsgId,
                                       Long toUserId)
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
        private Long toUserId;

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

        public Builder toUserId(Long toUserId) {
            this.toUserId = toUserId;
            return this;
        }

        public ImP2pConversationRequest build() {
            return new ImP2pConversationRequest(userId, tenantId, traceId, bizMsgId, toUserId);
        }
    }
}
