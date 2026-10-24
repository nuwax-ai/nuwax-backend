package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★DEV-288 会话详情请求（契约 {@code ConversationDetailRequest}
 * → {@code POST /internal/im/rpc/conversations/detail}）。
 *
 * <h2>★它补的是什么缺口</h2>
 *
 * <p>在它之前，平台按 {@code convId} 拿一个会话的信息<b>只有</b>两条路，都不合用：
 * <ul>
 *   <li>{@code conversations/list}（DEV-277）—— 那是<b>整个列表</b>，
 *       为了查一个群要把这个人的会话翻完，且列表路径<b>拿不到群号与 ext</b>；</li>
 *   <li>用户态 REST {@code GET /api/instant-message/conversations/{convId}} ——
 *       身份<b>完全取自登录上下文</b>，平台传不了 {@code userId}。</li>
 * </ul>
 *
 * <p>本端点与那条 REST <b>逐字同语义</b>（同一个 {@code getDetail} + 同一个装配器）
 * ⇒ 界面上看到的与平台查到的永远是同一份。
 *
 * <h2>★★唯一业务参数就是 {@code convId}，<b>没有</b>批量形态</h2>
 *
 * <p>契约里就这一个字段。需要 N 个会话的详情就发 N 次请求 ——
 * 这是刻意的：详情要查 {@code im_subject}（群号）、要装配对端资料，
 * 单次成本远高于列表项，做成批量只是把 N 次往返挪到服务端排队，还多一个部分失败的语义。
 *
 * <h2>★读操作：{@code bizMsgId} 免填</h2>
 *
 * <p>服务端登记在 {@code ImRpcAuthFilter.READ_ONLY_PATHS}，跳过幂等闸门
 * （但仍过签名 / nonce / 限流）。本层保留该访问器只是为了"同一套封装同时处理读写"。
 *
 * <h2>★★内部 RPC 豁免成员闸门 —— 但这不是"什么都能查"</h2>
 *
 * <p>判据是服务端的 {@code RequestContext.internalRpc}（<b>不是</b>请求参数），
 * 所以平台可以查一个<b>自己不在里面</b>的会话。豁免<b>只</b>放开成员校验，
 * 下列三条不豁免：
 * <ul>
 *   <li>{@code convId} 非法 / 会话不存在 ⇒ {@code IM_40001} / {@code IM_40400}；</li>
 *   <li>团队群对应的平台 space 已删 ⇒ {@code 会话已解散}；</li>
 *   <li>{@code userId} 缺省 / 非正数 ⇒ {@code IM_40001}。</li>
 * </ul>
 *
 * <p>★<b>非成员时响应里有一大片字段是 null</b>，怎么判读见
 * {@link ImConversationDetailDto} 的类注释 —— 那里有一条会咬人的坑
 * （{@code unreadCount} 是 {@code 0} 而不是 null）。
 *
 * @param convId 会话 ID（必填、正数；★它同时是消息表的分片键）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImConversationDetailRequest(Long userId,
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

        public ImConversationDetailRequest build() {
            return new ImConversationDetailRequest(userId, tenantId, traceId, bizMsgId, convId);
        }
    }
}
