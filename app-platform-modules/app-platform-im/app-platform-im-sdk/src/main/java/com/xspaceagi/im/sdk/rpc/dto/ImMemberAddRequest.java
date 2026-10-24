package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * ★加群成员请求（契约 {@code MemberAddRequest} → {@code POST /internal/im/rpc/conversations/members/add}）。
 *
 * <h2>★单次 ≤50，且"部分失败不回滚"</h2>
 *
 * <p>超 50 → {@code IM_90001}；≤50 时若有成员加不进去（不存在/已停用/群已满），
 * 服务端<b>不会</b>整体失败，而是把失败的放在 {@code failedUserIds} 里返回。
 * ⇒ 调用方必须检查 {@link MemberAddResultDto#hasFailures()}，见那里的注释。
 *
 * <p>★为什么不做成"事务全成功或全失败"：批量拉人时"3 个成功 1 个失败"比
 * "一个失败全都不加"更符合业务预期（也避免了"因一个脏数据导致整批拉人失败"）。
 *
 * @param convId    目标群（分片键）
 * @param userIds   待加入的用户（1~50 个）
 * @param nickName  ★统一设置的<b>群内昵称</b>（可空；同一批用同一个昵称，
 *                  若要每人不同昵称需逐次调用 —— 契约不提供"每人不·同昵称"的批量形态）。
 *                  ★服务端当前<b>刻意忽略</b>它，见契约。
 * @param agentMode ★DEV-271 本批<b>智能体</b>的衔接模式：<b>1=唤起（默认）/ 2=监听</b>（可空）。
 *                  ★只影响 userIds 里的智能体，真人成员忽略；null ⇒ 1；
 *                  其它值服务端报 {@code IM_90001}（★不静默取默认）。
 *                  ★它<b>没有</b>客户端侧校验：非法值原样发给服务端拿那个错误码，
 *                  好让"契约只认 1/2"这条口径只有服务端一处。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImMemberAddRequest(Long userId,
                                 Long tenantId,
                                 String traceId,
                                 String bizMsgId,
                                 Long convId,
                                 List<Long> userIds,
                                 String nickName,
                                 Integer agentMode)
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
        private String nickName;
        private Integer agentMode;

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

        public Builder nickName(String nickName) {
            this.nickName = nickName;
            return this;
        }

        /** ★DEV-271：1=唤起 / 2=监听；不设（null）⇒ 服务端按 1 处理。 */
        public Builder agentMode(Integer agentMode) {
            this.agentMode = agentMode;
            return this;
        }

        public ImMemberAddRequest build() {
            return new ImMemberAddRequest(userId, tenantId, traceId, bizMsgId, convId, userIds,
                    nickName, agentMode);
        }
    }
}
