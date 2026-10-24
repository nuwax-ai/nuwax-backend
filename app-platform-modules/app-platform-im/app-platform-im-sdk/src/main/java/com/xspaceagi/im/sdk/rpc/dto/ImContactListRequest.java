package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★DEV-277 联系人列表请求（契约 {@code ContactListRequest}
 * → {@code POST /internal/im/rpc/contacts/list}）。
 *
 * <h2>★为什么这个类<b>一个业务字段都不加</b></h2>
 *
 * <p>需要的东西全在基类里：{@code userId} = 「查谁的联系人」、
 * {@code tenantId} = 用哪个租户的档案（头像 / 昵称走平台档案，租户不同则展示不同）。
 * ★两者<b>都必填</b> —— IM 侧一期已收紧：{@code tenantId} 缺失返回
 * {@code PARAM_ERROR}（不是「留空就由 IM 反查」，反查本身要租户上下文）。
 *
 * <p><b>不加分页参数</b>不是偷懒：用户态 {@code GET /contacts} 本身就没有分页
 * （内部硬上限 500，超出用 {@code truncated=true} 表达，<b>不是</b>错误码）。
 * 单方面在这里加 {@code limit}/{@code cursor} 会长出<b>第二份过滤/分页逻辑</b>,
 * 而「平台查到的条数」与「界面上看到的条数」从此可能不一致。需要分页时两边一起改。
 *
 * <p>★读操作（{@code bizMsgId} 免填），但仍过签名 / nonce / 限流。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImContactListRequest(Long userId,
                                   Long tenantId,
                                   String traceId,
                                   String bizMsgId)
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

        public ImContactListRequest build() {
            return new ImContactListRequest(userId, tenantId, traceId, bizMsgId);
        }
    }
}
