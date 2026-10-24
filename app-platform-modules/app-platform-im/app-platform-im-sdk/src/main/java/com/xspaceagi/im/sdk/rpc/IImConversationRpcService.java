package com.xspaceagi.im.sdk.rpc;

import com.xspaceagi.im.sdk.rpc.dto.ImConversationDetailDto;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationDetailRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationDto;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImConversationPageDto;
import com.xspaceagi.im.sdk.rpc.dto.ImDigestUpdateRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImGroupCreateRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMemberAddRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMemberListRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImMemberRemoveRequest;
import com.xspaceagi.im.sdk.rpc.dto.ImP2pConversationRequest;
import com.xspaceagi.im.sdk.rpc.dto.MemberAddResultDto;
import com.xspaceagi.im.sdk.rpc.dto.MemberPageDto;

/**
 * Nuwax IM 会话 RPC 服务（契约 {@code /internal/im/rpc/conversations/**}）。
 *
 * <p>★端点数是 <b>8</b>：DEV-277 起 7 个，DEV-288 补上最后缺的 {@code conversations/detail}
 * —— 至此 IM 侧 {@code conversations/**} 的 8 个端点<b>全部</b>对接完毕。
 *
 * <p>★与 {@link IImMessageRpcService} 同一套约定：方法名 = 契约 {@code operationId}；
 * {@code userId} 是「以谁的身份执行」；写操作必带 {@code bizMsgId}；失败抛 {@link ImRpcException}。
 */
public interface IImConversationRpcService {

    /** {@code POST /internal/im/rpc/conversations/group} —— 建群（{@code clientGroupId} 是业务幂等键）。 */
    ImConversationDto createGroup(ImGroupCreateRequest req);

    /**
     * {@code POST /internal/im/rpc/conversations/members/add} —— 加成员（{@code userIds} ≤50）。
     *
     * <p>★<b>部分成功是正常返回</b>：加到几个算几个，没加上的在
     * {@link MemberAddResultDto#failedUserIdsOrEmpty()} 里。调用方应当据此提示或补偿，
     * 而不是把「有失败项」当成整体失败。
     */
    MemberAddResultDto addMembers(ImMemberAddRequest req);

    /** {@code POST /internal/im/rpc/conversations/members/remove} —— 移成员（{@code userIds} ≤50，★fail-fast）。 */
    void removeMembers(ImMemberRemoveRequest req);

    /**
     * {@code POST /internal/im/rpc/conversations/members/list} —— 成员列表（≤100）。
     *
     * <p>★超 500 人的群服务端会自动降级，此时 {@link MemberPageDto#degradedOrTrue()} 为 true ——
     * <b>别把被截断的名单当完整名单展示</b>。
     */
    MemberPageDto listMembers(ImMemberListRequest req);

    /** {@code POST /internal/im/rpc/conversations/p2p} —— 原子地「取或建」单聊。 */
    ImConversationDto getOrCreateP2p(ImP2pConversationRequest req);

    /** {@code POST /internal/im/rpc/conversations/digest} —— 更新会话摘要。★尽力而为，可丢失。 */
    void updateDigest(ImDigestUpdateRequest req);

    /**
     * {@code POST /internal/im/rpc/conversations/list} —— ★DEV-277：<b>代某个用户</b>查他的会话列表。
     *
     * <p>★这是「平台代用户看会话列表」<b>唯一</b>的合规通道：用户态 REST 的身份完全取自登录上下文，
     * 平台拿不到、也不该拿别人的登录态去调。
     *
     * <p>★★翻页只认两件事：用 {@link ImConversationPageDto#hasMoreOrFalse()} 判停、
     * 把 {@code nextCursor} <b>原样</b>回填到下一次请求的 {@code cursor}。
     * 游标<b>不透明</b>（不能自己拼）且<b>与用户绑定</b>（串用不报错，只会静默返回错位的一页）——
     * 按用户隔离存储游标是调用方的责任。
     */
    ImConversationPageDto listConversations(ImConversationListRequest req);

    /**
     * {@code POST /internal/im/rpc/conversations/detail} —— ★DEV-288：按 {@code convId} 查<b>单个</b>会话详情。
     *
     * <p>★与用户态 REST {@code GET /api/instant-message/conversations/{convId}} <b>逐字同语义</b>
     * （同一个应用服务方法 + 同一个装配器）。
     *
     * <p>★★它相对 {@link #listConversations} 的<b>全部</b>增量是
     * {@link ImConversationDetailDto#groupNo()} 与 {@link ImConversationDetailDto#ext()} ——
     * 这两个字段列表路径一律拿不到（列表一次 50 条，逐个查群主键 / 塞大字段都不划算）。
     * ★其余字段在列表里也有，所以「为了拿名字/成员数」来调本端点是不划算的。
     *
     * <p>★★<b>内部 RPC 豁免成员闸门</b>（判据是服务端 {@code RequestContext.internalRpc}，
     * 不是请求参数）⇒ 可以查一个自己<b>不在里面</b>的会话。
     * 但豁免只放开成员校验，下列三条仍会报错：{@code convId} 非法 / 会话不存在
     * （{@code IM_40001} / {@code IM_40400}）、团队群对应 space 已删（会话已解散）、
     * {@code userId} 缺省或非正数（{@code IM_40001}）。
     *
     * <p>★★<b>非成员时怎么读响应</b>：视角字段整块为 null，而
     * <b>{@code unreadCount} 是 {@code 0} 而不是 null</b> ⇒
     * 判断"他是不是成员"只认 {@code myRole != null}，
     * 用 {@code unreadCount == 0} 会把"看不见"读成"没有未读"。
     * 中立字段（名称 / 群号 / ext / 成员数 / 最后摘要等）照常返回。
     *
     * <p>★读操作（{@code bizMsgId} 免填）。
     */
    ImConversationDetailDto getConversationDetail(ImConversationDetailRequest req);
}
