package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★成员列表请求（契约 {@code MemberListRequest} → {@code POST /internal/im/rpc/conversations/members/list}）。
 *
 * <p>★读操作（不过幂等闸门），语义同 {@link ImMessageGetRequest}。
 *
 * <h2>★{@code limit} 的上限是 100，而"降级"由服务端按群规模<b>自己</b>决定</h2>
 *
 * <p>{@code limit} 只表达"我最多要多少条"（1~100，缺省 100）。
 * 群成员 &gt;500 时服务端<b>无论 limit 多少</b>都会只返回前 100 并置
 * {@code degraded=true}（02 §12.2.2）—— 这是保护 DB 的硬规则，不是"可以调大的参数"。
 * ⇒ 需要完整名单请走客户端长连接侧的成员接口，不要试图在这里放大 limit。
 *
 * @param convId 会话 ID（分片键）
 * @param limit  期望条数（1~100，可空 ⇒ 服务端取 100）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImMemberListRequest(Long userId,
                                  Long tenantId,
                                  String traceId,
                                  String bizMsgId,
                                  Long convId,
                                  Integer limit)
        implements ImRpcRequest {

    /** ★契约上限：单次 100 条。 */
    public static final int MAX_LIMIT = 100;

    /** ★触发降级的群规模阈值（>500 人 ⇒ 只返前 100 且 degraded=true）。 */
    public static final int DEGRADE_THRESHOLD = 500;

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
        private Integer limit;

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

        public Builder limit(Integer limit) {
            this.limit = limit;
            return this;
        }

        public ImMemberListRequest build() {
            return new ImMemberListRequest(userId, tenantId, traceId, bizMsgId, convId, limit);
        }
    }
}
