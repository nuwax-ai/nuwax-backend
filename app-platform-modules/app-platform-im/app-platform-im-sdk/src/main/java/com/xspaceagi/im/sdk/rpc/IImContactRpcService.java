package com.xspaceagi.im.sdk.rpc;

import com.xspaceagi.im.sdk.rpc.dto.ImContactListDto;
import com.xspaceagi.im.sdk.rpc.dto.ImContactListRequest;

/**
 * Nuwax IM 联系人 RPC 服务（契约 {@code /internal/im/rpc/contacts/**}，★DEV-277 起 1 个端点）。
 *
 * <p>★与 {@link IImConversationRpcService} 同一套约定：方法名 = 契约 {@code operationId}；
 * {@code userId} 是「查谁的联系人」；失败抛 {@link ImRpcException}。
 *
 * <h2>★为什么单独一个接口，而不是塞进会话服务</h2>
 *
 * <p>契约 tag 就是 {@code contacts}，与 {@code conversations} 平级。
 * 接口与 tag 对齐后，「按 tag 找实现」是机械动作；塞进会话服务则要靠记忆，
 * 下一个加端点的人会先翻一遍会话服务才能确认「联系人到底在哪」。
 */
public interface IImContactRpcService {

    /**
     * {@code POST /internal/im/rpc/contacts/list} —— <b>代某个用户</b>查他的联系人列表。
     *
     * <p>语义与用户态 {@code GET /api/instant-message/contacts} <b>逐字相同</b>
     * （同一个应用服务方法）⇒ 平台查到的与界面上看到的永远同源。
     *
     * <p>★★两个注意点（细节见 {@link ImContactListDto}）：
     * <ul>
     *   <li>返回是<b>三态混合</b>列表，要好友请用 {@code acceptedOnly()}；</li>
     *   <li>{@code degraded} 与 {@code truncated} 语义完全不同，
     *       前者结果集完整、后者结果集缩水。</li>
     * </ul>
     *
     * <p>★{@code tenantId} 本端点<b>必填</b>（IM 侧一期已收紧，缺失返回 {@code PARAM_ERROR}）——
     * 与那些「tenantId 可空、由 IM 自己反查」的端点不同。
     */
    ImContactListDto listContacts(ImContactListRequest req);
}
