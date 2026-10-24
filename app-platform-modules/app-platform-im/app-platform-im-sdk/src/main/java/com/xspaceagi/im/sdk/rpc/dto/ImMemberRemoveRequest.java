package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ★移出群成员请求（契约 {@code MemberRemoveRequest} → {@code POST /internal/im/rpc/conversations/members/remove}）。
 *
 * <p>★本端点 <b>void</b>：成功时 {@code data=null}（幂等命中时也是 null，见契约幂等段）。
 *
 * <h2>★★与"加成员"语义相反：这里是 <b>fail-fast</b>，不是"部分失败不回滚"</h2>
 *
 * <p>加成员允许部分成功（多拉一个人失败不该拖累其他人）；但<b>移出</b>是
 * 权限与安全相关的操作，"我让你把这三个人移出去"里的任何一个没移成功，
 * 都意味着<b>某个不该在群里的人还在群里</b>。若这里也返回"部分成功"，
 * 调用方很容易只看到"没抛异常"就认为清理完成（见 {@code 02 §12.2.8} 裁定 #5）。
 * ⇒ 只要有一个 userId 移出失败，服务端<b>整体返回失败</b>，由调用方决定重试整批。
 *
 * <h2>★级联效果由服务端保证</h2>
 *
 * <p>成员被移出会级联：清 {@code im:convauth:{userId}} 缓存、发 {@code im-member-change}
 * 事件（客户端侧同步退群、搜索白名单同步失效）。⇒ 调用方<b>不需要</b>再补一刀"清缓存"，
 * 重复清理只会加大 Redis 压力。
 *
 * @param convId  目标群
 * @param userIds 待移出的用户（1~50 个）
 * @param reason  移出原因（可空，≤256）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImMemberRemoveRequest(Long userId,
                                    Long tenantId,
                                    String traceId,
                                    String bizMsgId,
                                    Long convId,
                                    List<Long> userIds,
                                    String reason)
        implements ImRpcRequest {

    /** ★契约上限：单次 50 人。 */
    public static final int MAX_BATCH = 50;

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
        private List<Long> userIds;
        private String reason;

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

        public Builder userIds(List<Long> userIds) {
            this.userIds = userIds;
            return this;
        }

        public Builder reason(String reason) {
            this.reason = reason;
            return this;
        }

        public ImMemberRemoveRequest build() {
            return new ImMemberRemoveRequest(userId, tenantId, traceId, bizMsgId, convId, userIds, reason);
        }
    }
}
