package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ★建群请求（契约 {@code GroupCreateRequest} → {@code POST /internal/im/rpc/conversations/group}）。
 *
 * <h2>★{@code memberUserIds} <b>不含 {@code userId} 本人</b></h2>
 *
 * <p>服务端会把 {@code userId} 自动加为群主。若调用方把他也放进列表，
 * 结果是"重复成员被幂等吸收"（不会报错，也不会变成两个群主），
 * 但群成员的"应读人数"等计数容易因此对不上 ⇒ 按契约<b>不要</b>把自己放进列表。
 *
 * <h2>★两级上限（都按 {@code memberUserIds.size()} 判）</h2>
 *
 * <ul>
 *   <li>&gt; 2000 → {@code IM_20101}（超出 D-05=b 的群上限）</li>
 *   <li>&gt; 5000 → {@code IM_20102}（超出硬上限）</li>
 * </ul>
 * ★注意 2000 是"默认上限"而 {@code maxMember} 可以调大 —— 但硬上限 5000 不可突破。
 *
 * <h2>★{@code clientGroupId} 是<b>另一条</b>幂等线</h2>
 *
 * <p>{@code bizMsgId} 是传输层幂等（Redis SETNX，挡"同一请求重发"），
 * {@code clientGroupId} 是<b>业务层</b>幂等（DB 侧，挡"用不同请求建了同一个业务群"）。
 * 平台侧建群时应带上自己的业务群 ID —— 否则网络重试之外的重复建群（用户连点两次、
 * 上游补偿任务重跑）挡不住。
 *
 * @param name          群名（必填，≤64）
 * @param memberUserIds 初始成员（不含自己，1~5000）
 * @param maxMember     群上限（可空 ⇒ 服务端取 2000）
 * @param clientGroupId 调用方业务群 ID（可空，≤64；IM 不解析，仅用于审计/幂等对账）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImGroupCreateRequest(Long userId,
                                   Long tenantId,
                                   String traceId,
                                   String bizMsgId,
                                   String name,
                                   String avatar,
                                   String notice,
                                   List<Long> memberUserIds,
                                   Integer maxMember,
                                   String clientGroupId)
        implements ImRpcRequest {

    /** ★默认群上限（D-05=b）。 */
    public static final int DEFAULT_MAX_MEMBER = 2000;

    /** ★软上限：超过即 {@code IM_20101}。 */
    public static final int SOFT_MEMBER_LIMIT = 2000;

    /** ★硬上限：超过即 {@code IM_20102}（不可通过 maxMember 突破）。 */
    public static final int HARD_MEMBER_LIMIT = 5000;

    public static Builder builder() {
        return new Builder();
    }

    /** ★链式构造。 */
    public static final class Builder {
        private Long userId;
        private Long tenantId;
        private String traceId;
        private String bizMsgId;
        private String name;
        private String avatar;
        private String notice;
        private List<Long> memberUserIds;
        private Integer maxMember;
        private String clientGroupId;

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

        public Builder name(String name) {
            this.name = name;
            return this;
        }

        public Builder avatar(String avatar) {
            this.avatar = avatar;
            return this;
        }

        public Builder notice(String notice) {
            this.notice = notice;
            return this;
        }

        /** ★初始成员（★<b>不要</b>包含 {@code userId} 自己，服务端会自动加为群主）。 */
        public Builder memberUserIds(List<Long> memberUserIds) {
            this.memberUserIds = memberUserIds;
            return this;
        }

        public Builder maxMember(Integer maxMember) {
            this.maxMember = maxMember;
            return this;
        }

        public Builder clientGroupId(String clientGroupId) {
            this.clientGroupId = clientGroupId;
            return this;
        }

        public ImGroupCreateRequest build() {
            return new ImGroupCreateRequest(userId, tenantId, traceId, bizMsgId,
                    name, avatar, notice, memberUserIds, maxMember, clientGroupId);
        }
    }
}
