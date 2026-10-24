package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Collections;
import java.util.List;

/**
 * ★加成员结果（契约 {@code MemberAddRpcResult}，与 core-sdk 的 {@code AddMembersResult} 对齐）。
 *
 * <h2>★"部分成功"是<b>正常返回</b>，不是异常</h2>
 *
 * <p>契约明确"部分成员失败不整体回滚"：调用方拿到的可能是
 * {@code inserted=3, failedUserIds=[9004]} 这样的混合结果，而 HTTP 状态与 {@code code}
 * 都是成功的。⇒ <b>只判断"没抛异常"是不够的</b>，必须检查 {@link #failedUserIdsOrEmpty()}，
 * 否则"以为 4 个人都拉进群了，实际只有 3 个"这类问题会一直漂到用户投诉。
 *
 * <h2>★★"一个也没进来"有<b>四种</b>，只看 inserted 会把它们混成一种</h2>
 *
 * <p>一次「加 5 个人」在服务端可能落到六种结局上，其中<b>四种</b>在
 * {@code inserted}/{@code revived} 上都读作 {@code 0}：
 *
 * <ul>
 *   <li>{@link #alreadyIn()} &gt; 0 —— 人<b>本来就在群里</b>（不是失败，也不该说"已邀请"）；</li>
 *   <li>{@link #applied()} &gt; 0 —— 转成<b>入群申请单</b>，等<b>群主/管理员</b>审批（DEV-197-c）；</li>
 *   <li>{@link #pendingConfirm()} &gt; 0 —— 已发邀请卡片，等<b>被邀请人本人</b>点同意（DEV-256）；</li>
 *   <li>以上都为 0 且 {@link #failedUserIdsOrEmpty()} 也空 —— 「冷却期内被跳过」
 *       （{@link #skippedUserIdsOrEmpty()}）才可能是那个"什么都没发生"的答案。</li>
 * </ul>
 *
 * <p>★少了这四个字段，上面四种结局在调用方眼里<b>逐字相同</b>（{@code 0/0/0 + 空 failedUserIds}），
 * 于是"转审批"会被读成"被静默吞掉" —— 这不是读错了，是信息在这一层<b>不存在</b>。
 *
 * <h2>★两种粒度：{@code applied} 是整批，{@code pendingConfirm} 是逐人</h2>
 *
 * <ul>
 *   <li>{@link #applied()} 非零 ⇒ 一次调用里所有人一起转申请，必然 {@code inserted == revived == 0}，
 *       且与 {@link #hasPendingConfirm()} 互斥；</li>
 *   <li>{@link #pendingConfirm()} ★<b>可以与 inserted/revived 同时非零</b>
 *       （同 Team 的直接进群、不同 Team 的等他确认）⇒ 判"这次到底发生了什么"不能只看一个字段。</li>
 * </ul>
 *
 * @param inserted              本次新增的成员数
 * @param revived               曾在群、本次复活的成员数（退群后又被拉回来）
 * @param alreadyIn             本来就在群里（幂等命中，不算失败）
 * @param failedUserIds         ★失败的用户 ID（可能为空列表，不会是 null —— 见 {@link #failedUserIdsOrEmpty()}）
 * @param applied               ★DEV-197-c：本次转为入群申请单的人数（没有真的加入）
 * @param appliedUserIds        ★DEV-197-c：转为申请的人（见 {@link #appliedUserIdsOrEmpty()}）
 * @param pendingConfirm        ★DEV-256：已发出邀请、等被邀请人本人同意的人数
 * @param pendingConfirmUserIds ★DEV-256：等被邀请人确认的人（见 {@link #pendingConfirmUserIdsOrEmpty()}）
 * @param skippedUserIds        ★DEV-197-c：被跳过的人 —— "暂时"（冷却期），与 {@code failedUserIds} 的"永久"不同
 * @param memberCount           操作后的群成员总数
 * @param idempotentHit         ★true = 本次是 {@code IM_20005} 幂等命中，以上计数都是首次执行的结果
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberAddResultDto(Integer inserted,
                                 Integer revived,
                                 Integer alreadyIn,
                                 List<Long> failedUserIds,
                                 Integer applied,
                                 List<Long> appliedUserIds,
                                 Integer pendingConfirm,
                                 List<Long> pendingConfirmUserIds,
                                 List<Long> skippedUserIds,
                                 Integer memberCount,
                                 Boolean idempotentHit) {

    /** ★空安全列表（服务端返 null 或空数组时都得到空列表，调用方无需判空）。 */
    public List<Long> failedUserIdsOrEmpty() {
        return failedUserIds == null ? Collections.emptyList() : failedUserIds;
    }

    /** ★DEV-197-c：转申请的人。★空安全（旧版 IM 不带该字段时为 null ⇒ 空列表）。 */
    public List<Long> appliedUserIdsOrEmpty() {
        return appliedUserIds == null ? Collections.emptyList() : appliedUserIds;
    }

    /** ★DEV-256：等被邀请人确认的人。★空安全。 */
    public List<Long> pendingConfirmUserIdsOrEmpty() {
        return pendingConfirmUserIds == null ? Collections.emptyList() : pendingConfirmUserIds;
    }

    /** ★DEV-197-c：被跳过的人（冷却期）。★空安全。 */
    public List<Long> skippedUserIdsOrEmpty() {
        return skippedUserIds == null ? Collections.emptyList() : skippedUserIds;
    }

    /** ★是否存在"想加但没加成"的成员 —— 调用方应当据此提示/补偿。 */
    public boolean hasFailures() {
        return !failedUserIdsOrEmpty().isEmpty();
    }

    /**
     * ★★本次是否<b>走了"转成入群申请"那条路径</b>（DEV-197-c）。
     *
     * <p>★调用方<b>必须</b>先问这一句再决定怎么提示：两条路径的"成功"长得完全不一样 ——
     * 一条是"人已经进来了"，另一条是"申请已提交、等管理员审"。
     *
     * <p>★判据用 {@code applied > 0} 而<b>不是</b> {@code inserted == 0}：
     * 后者在"人全都在群里了"时也成立，那不是转申请路径。
     */
    public boolean convertedToRequests() {
        return applied != null && applied > 0;
    }

    /**
     * ★★本次是否<b>有人的入群在等被邀请人本人同意</b>（DEV-256）。
     *
     * <p>★它<b>与</b> {@link #convertedToRequests()} 不会同时为真（服务端同一次调用的不同分支），
     * 但<b>与</b>"真的有人进来了"可以同时为真。
     */
    public boolean hasPendingConfirm() {
        return pendingConfirm != null && pendingConfirm > 0;
    }

    /** ★空安全：未返回视为 false。 */
    public boolean idempotentHitOrFalse() {
        return Boolean.TRUE.equals(idempotentHit);
    }
}
