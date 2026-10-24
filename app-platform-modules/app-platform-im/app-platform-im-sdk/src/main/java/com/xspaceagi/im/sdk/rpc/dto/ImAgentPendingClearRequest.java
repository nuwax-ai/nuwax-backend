package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ★DEV-285 排队区「声明已消费」请求（契约 {@code AgentPendingClearRequest}
 * → {@code POST /internal/im/rpc/agents/pending/clear}）。
 *
 * <h2>★★语义是<b>声明已消费</b>，不是「丢弃」</h2>
 *
 * <p>清掉之后 IM 侧<b>不会再处理</b>这批消息（下一轮 drain 不到 ⇒ 不再为它们发起 4.10）。
 * 这正是需求本意 —— 智能体在本轮工具循环里已经把它们并入了上下文。
 * 但若「清了却没真消费」，用户视角就是<b>发了没回音</b>。
 * ⇒ 纪律：<b>先 list 读走 → 确已并入上下文 → 再按同一批 msgId clear</b>。
 *
 * <h2>★★为什么必须按 {@code msgIds} 点名清除，不能整队清空</h2>
 *
 * <p>list 与 clear 之间<b>可能有新消息入队</b>（用户手快又发了一条）。
 * 按 {@code (convId, agent)} 整队清空会把那条智能体<b>还没看过</b>的消息一起删掉 ⇒
 * 它既没进本轮上下文、行也没了 ⇒ <b>静默丢消息</b>。
 * 点名清除 = 只清「我确实读过的那批」，新入队的行留给下一轮
 * （IM 侧 drain 或智能体下一次 list+clear 都会带走它，不丢）。
 *
 * <p>★{@code msgIds} 通常就是上一次 {@code list} 返回的 {@code items[].msgId} 原样回传。
 * 其中已被 IM 侧 drain 走 / 本就不在队里的 id <b>不报错</b>，
 * 判据看 {@link ImAgentPendingClearDto#removedMsgIds()}。
 *
 * <h2>★被清消息的状态行会同步落「完成」</h2>
 *
 * <p>IM 侧把 {@code im_agent_msg_status} 从 UNREAD settle 为 DONE（推终态帧 + 代读）。
 * 不落终态的话，1 天后前端会把这些消息投影成「挂起」—— 一个错误的诊断。
 *
 * <p>★写操作 ⇒ {@code bizMsgId} <b>必填</b>（缺失 IM 侧 IM_90001）；
 * 超时重发同键回放首次结果（不会把两次 clear 叠加执行）。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImAgentPendingClearRequest(Long userId,
                                         Long tenantId,
                                         String traceId,
                                         String bizMsgId,
                                         Long convId,
                                         List<Long> msgIds)
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
        private List<Long> msgIds;

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

        public Builder msgIds(List<Long> msgIds) {
            this.msgIds = msgIds;
            return this;
        }

        public ImAgentPendingClearRequest build() {
            return new ImAgentPendingClearRequest(userId, tenantId, traceId, bizMsgId, convId, msgIds);
        }
    }
}
