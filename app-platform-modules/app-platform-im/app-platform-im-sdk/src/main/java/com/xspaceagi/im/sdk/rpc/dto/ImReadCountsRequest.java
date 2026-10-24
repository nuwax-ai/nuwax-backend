package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ★批量已读人数请求（契约 {@code MessageReadCountRequest} → {@code POST /internal/im/rpc/messages/read-counts}）。
 *
 * <h2>★两条硬约束（服务端会拒，SDK 不替它判）</h2>
 *
 * <ol>
 *   <li>{@code msgIds} 单次 <b>≤200</b>（超过 → {@code IM_90001} 参数错误）；
 *       ⇒ 需要查更多时由调用方<b>分批</b>，而不是期待服务端截断。</li>
 *   <li>★<b>只能查 {@code userId} 自己发的消息</b>。查别人的消息不会报错，
 *       而是被过滤掉 —— 这是刻意的信息保护：
 *       "某条别人发的消息被谁读过"不该由平台侧通过这个口子拿到。</li>
 * </ol>
 *
 * <p>★读操作（不过幂等闸门），语义同 {@link ImMessageGetRequest}。
 *
 * @param convId 会话 ID（分片键）
 * @param msgIds 消息 ID 列表（1~200 条；★服务端按 {@code convId} 分片后一次性查）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImReadCountsRequest(Long userId,
                                  Long tenantId,
                                  String traceId,
                                  String bizMsgId,
                                  Long convId,
                                  List<Long> msgIds)
        implements ImRpcRequest {

    /** ★契约上限：单次 200 条。 */
    public static final int MAX_MSG_IDS = 200;

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

        public ImReadCountsRequest build() {
            return new ImReadCountsRequest(userId, tenantId, traceId, bizMsgId, convId, msgIds);
        }
    }
}
