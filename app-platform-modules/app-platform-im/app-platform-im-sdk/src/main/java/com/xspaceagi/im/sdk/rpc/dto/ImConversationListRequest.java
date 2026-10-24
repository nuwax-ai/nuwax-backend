package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★DEV-277 会话列表请求（契约 {@code ConversationListRequest}
 * → {@code POST /internal/im/rpc/conversations/list}）。
 *
 * <h2>★它补的是什么缺口</h2>
 *
 * <p>会话列表此前<b>只有</b>用户态 REST（{@code GET /api/instant-message/conversations}），
 * 而那条端点的身份<b>完全取自登录上下文</b>（客户端传不了 {@code userId}）
 * ⇒ 平台想「代某个用户看他的会话列表」在 HMAC 平面上没有合规通道。
 * 本端点就是那条通道，且与 REST <b>逐字同语义</b>（同一个应用服务方法）
 * ⇒ 界面上看到的与平台查到的永远是同一份。
 *
 * <h2>★★{@code cursor} 是不透明的，只能原样回传</h2>
 *
 * <p>它内部是四个排序键的快照（{@code t{0|1}|topTime|lastMsgAt|convId}，
 * 上限 128 字符）。三种误用里只有一种会报错：
 *
 * <table border="1">
 *   <caption>游标误用的三种形态</caption>
 *   <tr><th>形态</th><th>服务端行为</th></tr>
 *   <tr><td>首页不传 / 传空串</td><td><b>正常</b>（空串按「无游标」处理，不是非法游标）</td></tr>
 *   <tr><td>自己拼 / 格式非法</td><td>报 {@code IM_40002}（<b>不猜</b> —— 猜错会静默返回一份错序的列表）</td></tr>
 *   <tr><td>★串用别人的游标</td><td>★★<b>不报错</b>：游标里不含用户身份，服务端在结构上就无法校验归属</td></tr>
 * </table>
 *
 * <p>⇒ 把游标当黑盒字符串，并且<b>按用户隔离存储</b>是调用方的责任。
 *
 * <h2>★{@code limit} 的归一不在本层</h2>
 *
 * <p>缺省 {@value #DEFAULT_LIMIT}，服务端 clamp 到 {@code [1, }{@value #MAX_LIMIT}{@code ]}
 * ——★<b>clamp 而不是拒绝</b>：传 0 / 负数 / 超大值都不报错，
 * 「忘了传 limit」应当退化成一个能用的首页，而不是一个 400。
 *
 * <p>★读操作（{@code bizMsgId} 免填，服务端登记在 {@code ImRpcAuthFilter.READ_ONLY_PATHS}），
 * 但仍过签名 / nonce / 限流。
 *
 * @param cursor 上一页的 {@code nextCursor}（可空 = 第一页）；★不透明，见类注释
 * @param limit  单页条数（可空 ⇒ 服务端取 {@value #DEFAULT_LIMIT}；服务端 clamp 到 {@code [1,}{@value #MAX_LIMIT}{@code ]}）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImConversationListRequest(Long userId,
                                        Long tenantId,
                                        String traceId,
                                        String bizMsgId,
                                        String cursor,
                                        Integer limit)
        implements ImRpcRequest {

    /** ★与服务端一致：缺省 50（与用户态 REST 的 {@code defaultValue} 同值）。 */
    public static final int DEFAULT_LIMIT = 50;

    /** ★契约上限：单次 100 条。 */
    public static final int MAX_LIMIT = 100;

    /** ★契约上限：游标 128 字符。本层在发出去之前先拦，避免上游回一个语焉不详的 {@code IM_40002}。 */
    public static final int MAX_CURSOR_LEN = 128;

    public static Builder builder() {
        return new Builder();
    }

    /** ★链式构造；{@code actor} 一次给定身份三元组（与同族请求同形）。 */
    public static final class Builder {
        private Long userId;
        private Long tenantId;
        private String traceId;
        private String bizMsgId;
        private String cursor;
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

        public Builder cursor(String cursor) {
            this.cursor = cursor;
            return this;
        }

        public Builder limit(Integer limit) {
            this.limit = limit;
            return this;
        }

        public ImConversationListRequest build() {
            return new ImConversationListRequest(userId, tenantId, traceId, bizMsgId, cursor, limit);
        }
    }
}
