package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★DEV-276 按时间范围列举会话消息（契约 {@code MessageListRequest} →
 * {@code POST /internal/im/rpc/messages/list}）。
 *
 * <h2>★这是<b>读操作</b>：不过幂等闸门</h2>
 *
 * <p>与 {@link ImMessageGetRequest} 同族 —— {@code bizMsgId} 可传 {@code null}
 * （服务端 {@code READ_ONLY_PATHS} 豁免），带了也不会被用作幂等键。
 *
 * <h2>★★时间语义：epoch millis + <b>闭区间</b> + 可缺省</h2>
 *
 * <p>{@code startTime} / {@code endTime} 是<b>毫秒</b>，不是字符串、不是秒。
 * 两侧都可以为 {@code null}（= 该侧不限），服务端会把 null 归一成下界/上界
 * —— ★调用方<b>不要</b>自己传 {@code 0} 或 {@code Long.MAX_VALUE} 来表达"不限"：
 * 那在语义上等价，但会让人误以为这两个字段有默认值。
 *
 * <p>★时区由<b>服务端</b>按 {@code ZoneId.systemDefault()} 归一
 * （与响应里 {@code sendTime} 的毫秒换算<b>同一个常量点</b>）⇒
 * <b>调用方不要自己换算时区</b>，否则会出现"查 10:00~11:00 拿回 02:00~03:00"。
 *
 * <h2>★翻页只有一个方向（{@code afterSeq}）</h2>
 *
 * <p>返回的是 {@code seq ASC}（= 时间正序），游标 {@code afterSeq} 表示
 * "只返回 {@code seq > afterSeq} 的消息"。★刻意<b>没有</b> {@code asc/desc} 开关：
 * "倒序 + 起点游标"会立刻引出"游标是闭还是开、边界那条算不算"的歧义，
 * 而本端点的用途是<b>对账</b>，不是"聊天记录往上翻"（那是 {@code messages/history} 的活）。
 *
 * @param convId    会话 ID（★必填：分片键，没有它无法路由到 {@code im_message_XX}）
 * @param startTime 区间起点（epoch millis，闭区间，可空 = 不限）
 * @param endTime   区间终点（epoch millis，闭区间，可空 = 不限）
 * @param afterSeq  单向游标：只返回 {@code seq > afterSeq}（可空 = 从头）
 * @param limit     本页条数，缺省 50、上限 200（★超限服务端<b>直接报错</b>，不静默夹取）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImMessageListRequest(Long userId,
                                   Long tenantId,
                                   String traceId,
                                   String bizMsgId,
                                   Long convId,
                                   Long startTime,
                                   Long endTime,
                                   Long afterSeq,
                                   Integer limit)
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
        private Long startTime;
        private Long endTime;
        private Long afterSeq;
        private Integer limit;

        /** ★{@code bizMsgId} 对读操作可空（见类注释）。 */
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

        /** ★闭区间起点（epoch millis）。 */
        public Builder startTime(Long startTime) {
            this.startTime = startTime;
            return this;
        }

        /** ★闭区间终点（epoch millis）。 */
        public Builder endTime(Long endTime) {
            this.endTime = endTime;
            return this;
        }

        /** ★下一页原样回填 {@code ImMessagePageDto.nextSeq()}。 */
        public Builder afterSeq(Long afterSeq) {
            this.afterSeq = afterSeq;
            return this;
        }

        public Builder limit(Integer limit) {
            this.limit = limit;
            return this;
        }

        public ImMessageListRequest build() {
            return new ImMessageListRequest(userId, tenantId, traceId, bizMsgId, convId,
                    startTime, endTime, afterSeq, limit);
        }
    }
}
