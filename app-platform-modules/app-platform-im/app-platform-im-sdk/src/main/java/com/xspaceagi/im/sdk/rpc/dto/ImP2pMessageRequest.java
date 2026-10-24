package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * ★单聊发消息请求（契约 {@code P2pMessageSendRequest} → {@code POST /internal/im/rpc/messages/p2p}）。
 *
 * <h2>★前四个组件是<b>所有</b> RPC 请求共有的（契约 {@code RpcRequestBase}）</h2>
 *
 * <ul>
 *   <li>{@code userId} —— ★"以谁的身份执行"（必填）。服务端用 {@code runAs} 切换身份后
 *       走与 {@code /api} <b>完全相同</b>的应用服务，权限规则不会有两套实现。
 *       ⇒ 平台侧传错 userId 等于<b>以那个人身份发消息</b>，这是本契约里最需要小心的一处。</li>
 *   <li>{@code tenantId} —— 租户（一期收紧为必填）。缺失会让服务端多一次 HTTP 反查（多一次 RTT）。</li>
 *   <li>{@code traceId} —— 链路追踪透传（对应 HTTP 头 {@code X-Request-Id}，可空）。</li>
 *   <li>{@code bizMsgId} —— ★<b>幂等键</b>（写操作必填，缺失 → {@code IM_90001}）。
 *       服务端 SETNX {@code im:rpc:idem:{appId}:{bizMsgId}} TTL 24h，命中返 {@code IM_20005}。
 *       ⇒ 调用方应在<b>业务事务里</b>生成并落库，重试时复用同一个值；
 *       用 {@code UUID.randomUUID()} 每次新生成等于<b>没有幂等</b>。</li>
 * </ul>
 *
 * <h2>★字段全是包装类型</h2>
 *
 * <p>{@code Long}/{@code Integer}/{@code Boolean} 而不是基本类型：null 才能表达
 * "调用方没提供这个可选字段"。用基本类型的话 {@code mentionAll=false} 与"未设置"
 * 无法区分，而序列化时两者对服务端的语义不同（前者覆盖默认值，后者走服务端默认）。
 *
 * @param toUserId 接收方（{@code userId == toUserId} → {@code IM_20106}，一期不支持与自己单聊）
 * @param msgType  text / image / file / card …（服务端按类型做结构化校验）
 * @param body     消息体 JSON（TEXT 时形如 {@code {"text":"..."}}）
 * @param ext      业务扩展（服务端不解析，原样透传给客户端）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImP2pMessageRequest(Long userId,
                                  Long tenantId,
                                  String traceId,
                                  String bizMsgId,
                                  Long toUserId,
                                  String msgType,
                                  JsonNode body,
                                  String ext)
        implements ImRpcRequest {

    /** ★便捷入口：{@code ImP2pMessageRequest.builder().actor(...).toUserId(...)...build()}。 */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * ★手写 Builder（而不是用 8 参规范构造器）。
     *
     * <p>理由不是"好看"：{@code userId/tenantId/toUserId} 全是 {@code Long}、
     * {@code msgType/ext} 全是 {@code String}，位置写反时<b>编译器毫无怨言</b>，
     * 而后果是"消息发给了错误的人"。链式命名参数把这类错误挡在编译期。
     *
     * <p>★{@code build()} <b>不做</b>必填校验：服务端才是契约的权威校验方
     * （缺 {@code bizMsgId} → {@code IM_90001}、缺 {@code toUserId} → {@code IM_90001}）。
     * 在 SDK 里复制一份校验规则，要么与服务端漂移，要么让"服务端返回 IM_90001"
     * 这条契约路径在 SDK 的单测里<b>永远测不到</b>。
     */
    public static final class Builder {
        private Long userId;
        private Long tenantId;
        private String traceId;
        private String bizMsgId;
        private Long toUserId;
        private String msgType;
        private JsonNode body;
        private String ext;

        /** ★四个基础字段一次给全（身份 + 租户 + 幂等键）。 */
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

        public Builder toUserId(Long toUserId) {
            this.toUserId = toUserId;
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

        public Builder ext(String ext) {
            this.ext = ext;
            return this;
        }

        public ImP2pMessageRequest build() {
            return new ImP2pMessageRequest(userId, tenantId, traceId, bizMsgId,
                    toUserId, msgType, body, ext);
        }
    }

    /**
     * ★调用方自检：是不是忘了带幂等键（重试时复用同一个 bizMsgId 是调用方的责任）。
     *
     * <p>★★注意 {@code @JsonIgnore} 在这里<b>不是装饰</b>：Jackson 收集 record 属性时
     * 既看 record components，<b>也</b>扫描标准 getter 形式的方法 ——
     * {@code isBlankBizMsgId()} 正好是 {@code isXxx} 形式，不加注解就会凭空多出一个
     * 请求体字段 {@code blankBizMsgId}。⇒ 本模块里任何"不入契约"的辅助方法
     * （尤其是 {@code getXxx/isXxx} 形式）都必须标 {@code @JsonIgnore}，
     * 契约测试会逐个比对请求字段，漏标即变红。
     */
    @JsonIgnore
    public boolean isBlankBizMsgId() {
        return bizMsgId == null || bizMsgId.isBlank();
    }
}
