package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★会话（契约 {@code ImConversationRpcDto}，{@code createGroup} / {@code getOrCreateP2p} 的返回）。
 *
 * @param convId      会话 ID
 * @param convType    1=单聊 2=群聊 3=系统会话
 * @param ownerUserId 群主（单聊/系统会话为 null）
 * @param memberCount 当前成员数
 * @param maxMember   群上限（缺省 2000，见 D-05=b）
 * @param created     ★本次是否<b>新建</b>。false = 复用既有会话（
 *                    单聊时是"这两个人本来就有会话"，建群时是 {@code clientGroupId} 命中已有群）
 * @param createTime  epoch millis
 * @param idempotentHit ★true = 本次是 {@code IM_20005} 幂等命中，{@code created}/{@code convId}
 *                      都是<b>首次执行</b>的结果。★注意它与 {@code created} 的区别：
 *                      {@code created=true} 且 {@code idempotentHit=true} 表示"群是上一次调用建的，
 *                      这次只是回放"，调用方<b>不应</b>据此再发一次入群通知。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImConversationDto(Long convId,
                                Integer convType,
                                String name,
                                String avatar,
                                Long ownerUserId,
                                Integer memberCount,
                                Integer maxMember,
                                Boolean created,
                                Long createTime,
                                Boolean idempotentHit) {

    /** ★空安全：未返回视为 false（不是新建）。 */
    public boolean createdOrFalse() {
        return Boolean.TRUE.equals(created);
    }

    /** ★空安全：未返回视为 false。 */
    public boolean idempotentHitOrFalse() {
        return Boolean.TRUE.equals(idempotentHit);
    }
}
