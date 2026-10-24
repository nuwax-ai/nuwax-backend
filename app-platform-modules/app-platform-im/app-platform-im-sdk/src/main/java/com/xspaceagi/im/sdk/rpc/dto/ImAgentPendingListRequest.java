package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★DEV-285 排队区自查请求（契约 {@code AgentPendingListRequest}
 * → {@code POST /internal/im/rpc/agents/pending/list}）。
 *
 * <h2>★★{@code userId} 的形状与别处不同：这里是「我是哪个智能体」</h2>
 *
 * <p>其余四组 RPC 的 {@code userId} 是「以谁的身份办事」，真人与智能体都能填；
 * 本端点的 {@code userId} <b>必须是智能体主体 id</b> —— IM 侧拿它去注册表做
 * {@code isAgentStrict} 三态校验（查不到 ⇒ {@code PARAM_ERROR} 拒，<b>不是</b>放行）。
 * 原因很直接：排队区的主键就是 {@code (conv_id, agent_subject_id)}，
 * 而这个 {@code agent_subject_id} 与智能体虚拟 id <b>是同一个数值</b>
 * （DEV-283「把智能体当人」）⇒ 不另设字段，避免「两个字段不一致时听谁的」。
 *
 * <p>★所以拿「当前登录用户」去填会稳定失败。传本人 ⇒ IM 判它不是已登记智能体 ⇒ 拒。
 *
 * <h2>★为什么只查一个会话，没有「一次查全部」</h2>
 *
 * <p>排队区按 {@code (conv_id, agent_subject_id)} 分队，本端点只查 {@code convId} 这一个。
 * 智能体在哪个会话的 4.10 里跑就查哪个会话；跨会话自查是另一次调用。
 * 不提供「查我所有会话的排队区」，是因为那会让「这条消息属于哪次对话」在智能体侧失去锚点。
 *
 * <p>★读操作（{@code bizMsgId} 免填），但仍过签名 / nonce / 限流。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImAgentPendingListRequest(Long userId,
                                        Long tenantId,
                                        String traceId,
                                        String bizMsgId,
                                        Long convId)
        implements ImRpcRequest {

    public static Builder builder() {
        return new Builder();
    }

    /** ★链式构造；{@code actor} 一次给定身份三元组（与同族请求同形）。 */
    public static final class Builder {
        private Long userId;
        private Long tenantId;
        private String traceId;
        private String bizMsgId;
        private Long convId;

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

        public ImAgentPendingListRequest build() {
            return new ImAgentPendingListRequest(userId, tenantId, traceId, bizMsgId, convId);
        }
    }
}
