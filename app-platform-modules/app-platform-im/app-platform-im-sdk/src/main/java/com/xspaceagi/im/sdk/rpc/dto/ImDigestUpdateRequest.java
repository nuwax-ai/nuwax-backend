package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★更新会话摘要请求（契约 {@code DigestUpdateRequest} → {@code POST /internal/im/rpc/conversations/digest}）。
 *
 * <p>★本端点 <b>void</b>：成功时 {@code data=null}（幂等命中时也是 null）。
 *
 * <h2>★★"尽力而为"语义（02 §12.2.8 裁定 #6）</h2>
 *
 * <p>这个接口用于平台侧把<b>自己算出来的摘要</b>覆盖到会话列表（典型场景：
 * 平台有一条"审批通过"的系统通知走了别的通道，希望会话列表也显示它）。
 * 服务端对它的处理是<b>不做成员校验、尽力而为</b>：
 *
 * <ul>
 *   <li>不校验 {@code userId} 是否在该会话里 —— 它写的是"会话的展示属性"，
 *       不是"以某人身份发言"，因此不构成越权读取；</li>
 *   <li>失败（会话不存在 / DB 抖动）<b>不重试、不报错到调用方</b>，最多在服务端记一条日志。</li>
 * </ul>
 *
 * <p>⇒ 调用方<b>不得</b>把"摘要写成功"当成业务成功的前置条件；它天生是可丢失的展示层数据。
 * 真正重要的通知请走 {@code messages/system}（那里有完整的落库与扇出保证）。
 *
 * @param convId 目标会话
 * @param digest 会话列表展示的最后消息摘要（必填，≤256）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImDigestUpdateRequest(Long userId,
                                    Long tenantId,
                                    String traceId,
                                    String bizMsgId,
                                    Long convId,
                                    String digest)
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
        private String digest;

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

        public Builder digest(String digest) {
            this.digest = digest;
            return this;
        }

        public ImDigestUpdateRequest build() {
            return new ImDigestUpdateRequest(userId, tenantId, traceId, bizMsgId, convId, digest);
        }
    }
}
