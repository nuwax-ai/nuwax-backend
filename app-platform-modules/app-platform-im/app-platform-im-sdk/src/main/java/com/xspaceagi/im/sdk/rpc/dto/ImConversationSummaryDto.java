package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★会话列表项（契约 {@code ConversationSummaryDto} —— 用户态 REST
 * {@code GET /api/instant-message/conversations} 与 RPC {@code conversations/list} <b>共用同一份</b>）。
 *
 * <h2>★★这是「以某个用户为观察者」的投影，<b>不是</b>中立数据</h2>
 *
 * <p>IM 侧刻意<b>不</b>另造一个「内部版会话摘要」：界面上看到的会话与平台查到的会话
 * 必须永远同源，否则两处字段迟到会分叉，而这种漂移没有任何测试能在事前发现。
 * 代价是本 DTO 里有相当一部分是 <b>viewer 视角</b>字段：
 * {@code unreadCount}（<b>那个人</b>的未读数）、{@code topFlag}/{@code topTime}（<b>那个人</b>的置顶）、
 * {@code myNickName}（<b>那个人</b>在群里的名片）、{@code dnd}、{@code visible}。
 *
 * <p>⇒ 用它做「会话本身长什么样」的判断是安全的；把 {@code unreadCount} 之类
 * 当成「所有人都这样」就错了。需要中立投影是另一个端点的事。
 *
 * <h2>★字段裁剪是刻意的</h2>
 *
 * <p>上游 {@code ConversationSummaryDto} 有 42 个字段，本投影保留 28 个。被裁掉的与理由：
 * <ul>
 *   <li>{@code readSeq}/{@code ackSeq}/{@code clearBeforeSeq}/{@code joinSeq} ——
 *       客户端<b>多端同步</b>用的水位线，对平台调用方没有语义；</li>
 *   <li>{@code draft} —— 未发送的草稿，属用户私人编辑态；</li>
 *   <li>{@code groupNo} —— ★<b>列表路径恒为 {@code null}</b>（填它要给首屏加 50 次往返），
 *       留着只会让调用方以为「这个群没有群号」；要群号请走群详情；</li>
 *   <li>{@code ext} —— ★<b>列表路径恒为 {@code null}</b>（字段可能很大，列表一次 50 条）；</li>
 *   <li>{@code created} —— ★列表路径恒为 {@code null}（只在 createP2p / createGroup 的返回上填）；</li>
 *   <li>{@code muteAll}/{@code mentionAllPerm}/{@code joinApprove}/{@code readReceiptOn}/
 *       {@code allowMemberPin}/{@code allowMemberInvite} —— 群设置，是<b>前端群管理页</b>的事，
 *       平台调用方要判「能不能发」时应看 {@code status} 与 {@code myRole}。</li>
 * </ul>
 *
 * <p>★本 DTO 开了 {@code ignoreUnknown}：上游加字段不该让平台硬故障。
 * 反过来说，<b>上游删字段本层是感知不到的</b> —— 需要「字段必须存在」的断言时，
 * 在调用方写，别指望这里报错。
 *
 * @param convId         会话 ID（雪花）。★它同时是消息表的分片键，调发消息接口时要原样带上
 * @param convType       ★1=单聊 2=群聊 3=系统会话 <b>4=团队群（平台 space 镜像群）</b>
 *                       —— 4 是真机上<b>存在且常见</b>的值（38 条里 6 条），见 {@link #CONV_TYPE_TEAM}
 * @param name           ★会话名。单聊时库里是 NULL，由<b>服务端填对端昵称</b>后返回
 *                       ⇒ 调用方不需要再按 {@code convType} 判断该显示什么
 * @param avatar         ★会话头像，单聊时同样由服务端填对端头像
 * @param notice         群公告（单聊恒 null）
 * @param ownerUserId    群主（单聊 / 系统会话为 null）
 * @param memberCount    当前成员数
 * @param maxMember      成员上限（默认 2000）
 * @param status         ★1=正常 2=已解散 3=封禁。★非 1 时应禁止发言
 * @param spaceId        ★该会话在平台侧对应的 space（会话内「资料库」面板的依据）。
 *                       ★<b>私聊也可能有</b>（懒建：没建时为 null，前端会去 ensure 一次），
 *                       不要用「spaceId != null」判断是不是空间镜像群
 * @param lastMsgId      最后一条消息 ID（null = 这个会话还没有任何消息）
 * @param lastMsgSeq     最后一条消息 seq
 * @param lastMsgAt      ★最后一条消息时间（epoch millis）——<b>列表排序键</b>，服务端已按它倒序
 * @param lastMsgDigest  ★最后一条消息摘要（如 {@code "[图片]"}、{@code "[消息已撤回]"}）。
 *                       由服务端生成并冗余存储，<b>不是</b>前端从 body 截的 —— 撤回后它才变得对
 * @param lastSenderId   最后一条消息的发送者 userId
 * @param lastSenderName 最后一条消息的发送者显示名（★冗余存储，避免列表页 N 次查用户资料）
 * @param peerUserId     单聊对端 userId（群聊为 null）
 * @param peerName       单聊对端昵称
 * @param peerAvatar     单聊对端头像
 * @param myRole         ★<b>那个人</b>在群里的角色：1=普通 2=管理员 3=群主。
 *                       ★<b>不能</b>当权限判定的唯一依据 —— 服务端每次都重新校验，
 *                       它只够用来决定「要不要显示管理入口」
 * @param myNickName     <b>那个人</b>在群里的名片（null = 用平台昵称）
 * @param unreadCount    ★<b>那个人</b>的未读数。★这是<b>快照值</b>（实时值在 IM 的 Redis 里），
 *                       列表场景够用，但<b>不要</b>拿它做「是否有未读」的强判定
 * @param topFlag        <b>那个人</b>是否置顶
 * @param topTime        置顶时间（epoch millis）；多个置顶会话之间按它倒序
 * @param dnd            ★免打扰。★免打扰<b>仍然计未读数</b>，只是不计入总未读、不弹通知
 *                       ⇒ 渲染成「有数字但没红点」，不是「没有未读」
 * @param visible        ★<b>那个人</b>是否可见此会话。false = 用户「删除了会话」
 *                       （只是从自己列表里隐藏，<b>不是</b>退群、也不是删消息）
 * @param createTime     会话创建时间（epoch millis）
 * @param joinTime       <b>那个人</b>入群时间（epoch millis）
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImConversationSummaryDto(Long convId,
                                       Integer convType,
                                       String name,
                                       String avatar,
                                       String notice,
                                       Long ownerUserId,
                                       Integer memberCount,
                                       Integer maxMember,
                                       Integer status,
                                       Long spaceId,
                                       Long lastMsgId,
                                       Long lastMsgSeq,
                                       Long lastMsgAt,
                                       String lastMsgDigest,
                                       Long lastSenderId,
                                       String lastSenderName,
                                       Long peerUserId,
                                       String peerName,
                                       String peerAvatar,
                                       Integer myRole,
                                       String myNickName,
                                       Integer unreadCount,
                                       Boolean topFlag,
                                       Long topTime,
                                       Boolean dnd,
                                       Boolean visible,
                                       Long createTime,
                                       Long joinTime) {

    /** 会话类型：单聊。 */
    public static final int CONV_TYPE_P2P = 1;

    /** 会话类型：群聊。 */
    public static final int CONV_TYPE_GROUP = 2;

    /** 会话类型：系统会话。 */
    public static final int CONV_TYPE_SYSTEM = 3;

    /**
     * ★会话类型：<b>团队群</b>（平台 space 的镜像群，IM 侧 {@code ImConvType.TEAM}）。
     *
     * <p>★<b>真机实测不可忽略</b>：2026-09-23 对 user 1 的 38 条会话取分布 =
     * {@code {1:17, 2:15, 4:6}}，即 <b>16% 是团队群</b>，不是边角值。
     * 早期文档只列 1/2/3 是漏的。
     *
     * <p>它的资料跟随平台 space（{@code name}/{@code notice}/{@code avatar} 由 space 同步），
     * <b>不是</b> IM 自建群 —— 所以 {@code groupNo} 一类"IM 自己的群"才有的字段对它恒为 null。
     */
    public static final int CONV_TYPE_TEAM = 4;

    /** ★会话状态：正常。★非此值的会话应禁止发言。 */
    public static final int STATUS_NORMAL = 1;

    /** ★会话状态：<b>已解散</b>（IM 侧 {@code ImConvStatus.DISBANDED}）。★禁止发言。 */
    public static final int STATUS_DISBANDED = 2;

    /** ★会话状态：<b>已被封禁</b>（IM 侧 {@code ImConvStatus.BANNED}）。★禁止发言。 */
    public static final int STATUS_BANNED = 3;

    /**
     * ★是否群聊 —— 唯一正确的判断方式是看 {@code convType}。
     *
     * <p>★<b>不要</b>用 {@code peerUserId == null} 或 {@code spaceId != null} 代替：
     * 前者在系统会话上也会成立，后者会把有资料库空间<b>的私聊</b>误判成群。
     *
     * <p>★<b>{@link #CONV_TYPE_TEAM} 也算群聊</b> —— 与 IM 侧 {@code ImConvType.isGroup()}
     * （{@code this == GROUP || this == TEAM}）保持一致。只认 {@code == 2} 会把真机上
     * 16% 的会话误判成"不是群"。
     */
    public boolean isGroup() {
        return convType != null && (convType == CONV_TYPE_GROUP || convType == CONV_TYPE_TEAM);
    }

    /**
     * ★是否平台 space 的镜像群（{@code convType == 4}，IM 侧 {@code ImConvType.isSpaceMirror()}）。
     *
     * <p>★判据是 {@code convType == 4} 而<b>不是</b> {@code spaceId != null}：
     * 平台改版后<b>私聊</b>也可能带 {@code spaceId}。
     */
    public boolean isSpaceMirror() {
        return convType != null && convType == CONV_TYPE_TEAM;
    }

    /** ★是否 IM 自建群（{@code convType == 2}）—— 与上一条区分：只有这类群 IM 才能改资料 / 解散。 */
    public boolean isImOwnedGroup() {
        return convType != null && convType == CONV_TYPE_GROUP;
    }

    /** ★是否单聊。 */
    public boolean isP2p() {
        return convType != null && convType == CONV_TYPE_P2P;
    }

    /** ★是否可发言（{@code status} 未下发时<b>按可发言处理</b> —— 保守拦截会让老服务端下所有会话都发不出消息）。 */
    public boolean canSpeak() {
        return status == null || status == STATUS_NORMAL;
    }

    /** ★空安全：未下发视为 0。 */
    public int unreadOrZero() {
        return unreadCount == null ? 0 : unreadCount;
    }
}
