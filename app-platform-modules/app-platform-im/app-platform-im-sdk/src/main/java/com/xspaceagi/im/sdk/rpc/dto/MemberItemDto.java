package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★成员条目（契约 {@code MemberRpcItem}，{@code ConversationMemberDto} 的 RPC 投影）。
 *
 * @param userId       用户 ID
 * @param name         用户全局名（平台侧资料）
 * @param avatar       头像
 * @param nickName     ★群内昵称（与 {@code name} 不同：同一个人在不同群可以有不同昵称）
 * @param role         1=普通成员 2=管理员 3=群主
 * @param memberStatus 成员状态（1=正常，其余见 {@code ImMemberStatus}）
 * @param joinTime     入群时间（epoch millis，可空）
 * @param muteEndTime  ★禁言到期时间（epoch millis，可空 = 未被禁言）。
 *                     ★判"当前是否被禁言"要用 {@code muteEndTime > now} 而不是 {@code != null}
 *                     —— 过期时间留在库里比"到点清理"更省事，这是刻意的设计。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MemberItemDto(Long userId,
                            String name,
                            String avatar,
                            String nickName,
                            Integer role,
                            Integer memberStatus,
                            Long joinTime,
                            Long muteEndTime) {

    /** ★群主判定（{@code role == 3}），空安全。 */
    public boolean owner() {
        return role != null && role == 3;
    }

    /** ★按给定时刻判定是否处于禁言中（空安全：{@code muteEndTime} 为 null ⇒ 未禁言）。 */
    public boolean mutedAt(long epochMilli) {
        return muteEndTime != null && muteEndTime > epochMilli;
    }

    /** ★展示名：优先群内昵称，其次全局名，都没有则返回 {@code null}（由调用方决定兜底文案）。 */
    public String displayName() {
        if (nickName != null && !nickName.isBlank()) {
            return nickName;
        }
        return name != null && !name.isBlank() ? name : null;
    }
}
