package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * ★群消息请求（契约 {@code GroupMessageSendRequest} → {@code POST /internal/im/rpc/messages/group}）。
 *
 * <p>基础四件套（{@code userId/tenantId/traceId/bizMsgId}）语义见 {@link ImP2pMessageRequest}。
 *
 * @param convId         目标群会话 —— ★同时是 {@code im_message} 64 路分表的<b>分片键</b>，
 *                       传错会写进另一张分表（消息"发出去但查不到"）
 * @param replyToMsgId   引用消息 ID（可空）
 * @param mentionUserIds ★被 @ 的 userId，<b>不校验是否群成员</b>（与 /api 同纪律）——
 *                       服务端只负责把列表透传给客户端，是否可读由客户端自行判定
 * @param mentionAll     {@code @所有人}。★注意它与鉴权的关系：<b>本契约不限制谁能 @所有人</b>，
 *                       普通成员能否 @ 全员是平台侧的业务规则（IM 不重复实现第二套权限）
 * @param ext            业务扩展（原样透传，≤2048）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImGroupMessageRequest(Long userId,
                                    Long tenantId,
                                    String traceId,
                                    String bizMsgId,
                                    Long convId,
                                    String msgType,
                                    JsonNode body,
                                    Long replyToMsgId,
                                    List<Long> mentionUserIds,
                                    Boolean mentionAll,
                                    String ext)
        implements ImRpcRequest {

    public static Builder builder() {
        return new Builder();
    }

    /** ★链式构造（11 个字段里 5 个是 {@code Long}，位置参数写反编译器不会报错）。 */
    public static final class Builder {
        private Long userId;
        private Long tenantId;
        private String traceId;
        private String bizMsgId;
        private Long convId;
        private String msgType;
        private JsonNode body;
        private Long replyToMsgId;
        private List<Long> mentionUserIds;
        private Boolean mentionAll;
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

        public Builder msgType(String msgType) {
            this.msgType = msgType;
            return this;
        }

        public Builder body(JsonNode body) {
            this.body = body;
            return this;
        }

        public Builder replyToMsgId(Long replyToMsgId) {
            this.replyToMsgId = replyToMsgId;
            return this;
        }

        public Builder mentionUserIds(List<Long> mentionUserIds) {
            this.mentionUserIds = mentionUserIds;
            return this;
        }

        public Builder mentionAll(Boolean mentionAll) {
            this.mentionAll = mentionAll;
            return this;
        }

        public Builder ext(String ext) {
            this.ext = ext;
            return this;
        }

        public ImGroupMessageRequest build() {
            return new ImGroupMessageRequest(userId, tenantId, traceId, bizMsgId,
                    convId, msgType, body, replyToMsgId, mentionUserIds, mentionAll, ext);
        }
    }
}
