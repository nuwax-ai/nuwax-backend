package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★单条消息详情请求（契约 {@code MessageGetRequest} → {@code POST /internal/im/rpc/messages/get}）。
 *
 * <h2>★这是<b>读操作</b>：不过幂等闸门</h2>
 *
 * <p>读操作跳过第⑥道（幂等）、其余六道照过。⇒
 * <b>即使带了 {@code bizMsgId} 也不会被用作幂等键</b>；
 * 反过来，{@code bizMsgId} 缺失也<b>不会</b>得到 {@code IM_90001}（写操作的规则不适用于它）。
 * 契约里 {@code RpcRequestBase} 把 {@code bizMsgId} 标为必填是"写操作的统一形态"，
 * 读操作的实际闸门序列见 {@code ImRpcAuthFilter.READ_ONLY_PATHS}。
 *
 * <h2>★{@code convId} 必填（虽然只用 msgId 也能查到）</h2>
 *
 * <p>因为 {@code im_message} 按 {@code conv_id} 分成 64 张表：<b>没有 convId 就无法路由分片</b>，
 * 只能全表扫描 64 张表。所以契约把 convId 定为必填，而不是"可选优化项"。
 *
 * @param convId 会话 ID（★分片键，必填）
 * @param msgId  消息 ID
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImMessageGetRequest(Long userId,
                                  Long tenantId,
                                  String traceId,
                                  String bizMsgId,
                                  Long convId,
                                  Long msgId)
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

        public ImMessageGetRequest build() {
            return new ImMessageGetRequest(userId, tenantId, traceId, bizMsgId, convId, msgId);
        }
    }
}
