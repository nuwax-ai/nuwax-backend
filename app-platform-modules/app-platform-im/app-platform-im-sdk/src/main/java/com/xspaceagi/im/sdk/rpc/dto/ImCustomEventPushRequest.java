package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;
import java.util.Map;

/**
 * ★DEV-276 推送一条用户自定义事件（契约 {@code CustomEventPushRequest} →
 * {@code POST /internal/im/rpc/events/push}）。
 *
 * <h2>★写操作 ⇒ {@code bizMsgId} <b>必填</b></h2>
 *
 * <p>本端点虽然<b>不落库</b>，但"同一事件被投递两次"与"重复落库"同样不可接受
 * （调用方重试一次 = 前端弹两次）⇒ 它走完整的幂等闸门，<b>不</b>在
 * {@code ImRpcAuthFilter.READ_ONLY_PATHS} 里。
 *
 * <h2>★★没有 {@code eventId} 字段，{@code bizMsgId} 就是它</h2>
 *
 * <p>帧体里的 {@code eventId}（去重键 {@code "3007:{eventId}"}）由服务端
 * <b>用 {@code bizMsgId} 填</b>。理由：同一件事有两个身份字段，
 * 调用方迟早会写出"重试时换了 eventId 但 bizMsgId 没换"（或反过来）的代码，
 * 结果是<b>同一事件投递两次</b>或<b>两次不同事件被当成一次</b>，两种都难查。
 * ⇒ 身份只留一个。
 *
 * <h2>★★收件人<b>二选一</b>，不是并集</h2>
 *
 * <ul>
 *   <li>{@link #userIds()} —— 这些人的<b>全部在线端</b>（与任何会话无关）；</li>
 *   <li>{@link #convId()} —— 该会话<b>全体成员</b>的在线端（含智能体成员）。</li>
 * </ul>
 * <p>★<b>都填或都不填都会被拒</b>（{@code IM_90001}）：都填时"到底投给谁"不可推理，
 * 猜错的后果是事件投给了<b>不该收到的人</b>。要并集就<b>调两次</b>，代价只是一次额外的签名请求。
 *
 * <h2>★★本通道<b>尽力而为、不承诺送达</b>（缺口 G-28）</h2>
 *
 * <p>帧取 P1 背压优先级（通道不可写时<b>直接丢弃</b>）、收件人离线即收不到、
 * 旧前端静默忽略未知 opcode（⇒ <b>本帧必须与前端同批上线</b>），
 * 且不落库 ⇒ 丢了<b>没有任何读路径能补</b>。
 * ★需要"一定送达"的业务必须<b>自建对账</b>。
 *
 * @param userIds   收件人（与 {@code convId} 二选一；≤1000，每个必须为正数）
 * @param convId    收件会话（与 {@code userIds} 二选一）
 * @param eventType 事件类型（非空、≤64；★IM 不做枚举校验，取值由调用方定义）
 * @param payload   事件载荷（原样透传；★序列化后 ≤16KB）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImCustomEventPushRequest(Long userId,
                                       Long tenantId,
                                       String traceId,
                                       String bizMsgId,
                                       List<Long> userIds,
                                       Long convId,
                                       String eventType,
                                       Map<String, Object> payload)
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
        private List<Long> userIds;
        private Long convId;
        private String eventType;
        private Map<String, Object> payload;

        /** ★{@code bizMsgId} 必填（写操作；它同时就是帧体的 {@code eventId}）。 */
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

        /** ★与 {@link #convId(Long)} 二选一。 */
        public Builder userIds(List<Long> userIds) {
            this.userIds = userIds;
            return this;
        }

        /** ★与 {@link #userIds(List)} 二选一。 */
        public Builder convId(Long convId) {
            this.convId = convId;
            return this;
        }

        public Builder eventType(String eventType) {
            this.eventType = eventType;
            return this;
        }

        public Builder payload(Map<String, Object> payload) {
            this.payload = payload;
            return this;
        }

        public ImCustomEventPushRequest build() {
            return new ImCustomEventPushRequest(userId, tenantId, traceId, bizMsgId,
                    userIds, convId, eventType, payload);
        }
    }
}
