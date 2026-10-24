package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★DEV-288 会话<b>详情</b>（契约 {@code ConversationSummaryDto} —— 用户态 REST
 * {@code GET /api/instant-message/conversations/{convId}} 与 RPC {@code conversations/detail}
 * <b>共用同一份</b>，本投影对应用户态那条 REST 的返回）。
 *
 * <h2>★★与 {@link ImConversationSummaryDto} 的关系：重叠 28 个字段，增量在 {@code groupNo} 与 {@code ext}</h2>
 *
 * <p>上游把「列表项」和「详情」做成了<b>同一个</b> DTO（差别只有 {@code ext} 与 {@code groupNo}），
 * 理由是不想"每次加字段改两处、漏改一处就变成『列表里有、点进详情就没了』"。
 *
 * <p>★平台侧<b>却刻意分成两个 record</b>，因为两边的裁剪结论不同：
 * {@link ImConversationSummaryDto} 把 {@code groupNo} / {@code ext} 裁掉的原文理由是
 * 「★<b>列表路径恒为 null</b>」—— 那句话对列表成立，对<b>详情恰恰相反</b>：
 * {@code groupNo} 只查 {@code im_subject}、只在详情路径填，{@code ext} 列表接口根本不返回。
 * ⇒ 这两个字段<b>就是</b>「详情」相对「列表项」的全部增量；
 * 若详情也复用那份 28 字段的投影，本端点就只剩"按 convId 再查一遍列表里已有的东西"，等于空壳。
 *
 * <p>★为什么不用组合 / 继承：{@code record} 是 final（继承不了），
 * 而嵌套字段与契约的<b>平铺</b> JSON 对不上。所以是老实重复声明一遍 ——
 * 代价是上游加字段时要动两处，★这笔账记在这里：<b>加字段时两个 DTO 一起看</b>。
 *
 * <h2>★★非成员时会发生什么（调用方必读）</h2>
 *
 * <p>本端点豁免成员闸门（判据是服务端 {@code RequestContext.internalRpc}），
 * 所以平台<b>可以</b>查一个自己不在里面的会话。此时：
 * <ul>
 *   <li>★<b>视角字段整块为 null</b>：{@code myRole} / {@code myNickName} / {@code draft} /
 *       {@code topFlag} / {@code topTime} / {@code dnd} / {@code muteEndTime} / {@code joinTime}
 *       以及各水位线；</li>
 *   <li>★★<b>{@code unreadCount} 是 {@code 0}，<span>不是</span> {@code null}</b> ——
 *       它是计数类字段，装配器把"取不到视角"落成了 0。
 *       ⇒ <b>不要</b>用 {@code unreadCount == 0} 判断"这个人已读完"，
 *       那在非成员场景下会把"看不见"读成"没未读";</li>
 *   <li>★<b>判定"这个人是不是成员"的唯一可靠判据是 {@code myRole != null}</b>；</li>
 *   <li>单聊（{@code convType=1}）在非成员时 {@code peerUserId} 与对端昵称/头像也为 null；</li>
 *   <li>★<b>中立字段照常返回</b>：{@code convId} / {@code convType} / 群号 / 名称 / 头像 /
 *       公告 / {@code ext} / 成员数 / 最后一条摘要等 —— 它们来自 {@code im_conversation}
 *       本行，与"谁在看"无关。</li>
 * </ul>
 *
 * <p>⇒ 一句话：<b>中立字段可以信，视角字段要先看 {@code myRole} 是不是 null。</b>
 *
 * <h2>★与 {@link ImConversationSummaryDto} 相同的三条纪律（照抄，别各自发明）</h2>
 *
 * <ul>
 *   <li>★这是「以某个用户为观察者」的投影，<b>不是</b>中立数据；
 *       {@code unreadCount} 是<b>那个人</b>的未读数，不是"所有人都这样"；</li>
 *   <li>★{@code spaceId != null} <b>不能</b>用来判断"是不是空间镜像群"——
 *       判据永远是 {@code convType == 4}（私聊也可能有 spaceId，见 {@link #CONV_TYPE_TEAM}）；</li>
 *   <li>★本 record 开了 {@code ignoreUnknown}：上游加字段不该让平台硬故障。
 *       反过来说<b>上游删字段本层感知不到</b> —— 需要"字段必须存在"的断言就写在调用方。</li>
 * </ul>
 *
 * @param convId         会话 ID（雪花）。★它同时是消息表的分片键，调发消息接口时要原样带上
 * @param convType       ★1=单聊 2=群聊 3=系统会话 <b>4=团队群（平台 space 镜像群）</b>
 *                       —— 4 是真机上<b>存在且常见</b>的值，见 {@link #CONV_TYPE_TEAM}
 * @param name           ★会话名。★单聊时库里是 NULL（存进去就会在对方改昵称后变成旧值），
 *                       由<b>服务端填对端昵称</b>后返回 ⇒ 调用方不需要再按 {@code convType} 判断该显示什么。
 *                       ★非成员时单聊仍可能为 null（拿不到对端资料）
 * @param avatar         会话头像，单聊时同样由服务端填对端头像
 * @param notice         群公告（单聊恒 null）
 * @param ownerUserId    群主（单聊 / 系统会话为 null）
 * @param memberCount    当前成员数（★由服务端维护，对账任务用 COUNT(*) 校正）
 * @param maxMember      成员上限（默认 2000，硬上限 5000）
 * @param status         ★1=正常 2=已解散 3=封禁。★非 1 时应禁止发言
 * @param spaceId        ★该会话在平台侧对应的 space（会话内「资料库」面板的依据）。
 *                       ★<b>私聊也可能有</b>（懒建：没建时为 null）；★存量群（空间落地之前建的）也可能是 null
 * @param groupNo        ★<b>群号</b>，也是本端点相对列表的主要价值之一。
 *                       ★类型是 {@code String} <b>而不是</b> {@code Long}（契约级决定）：
 *                       它是 19 位雪花，超出 JS {@code Number.MAX_SAFE_INTEGER}（2^53-1，16 位），
 *                       前端 {@code JSON.parse} 会把 {@code ...789} 变成 {@code ...800} ——
 *                       用户复制走一个<b>查不到的群号</b>，界面上却看不出任何异常。
 *                       ★<b>只有群详情路径会填</b>：单聊 / 系统会话恒 null，
 *                       <b>老群</b>（群主体概念之前创建的）也为 null ⇒ 前端遇到 null 应<b>整行不显示</b>，
 *                       而不是显示空白或 "null"（一个复制不出东西的群号输入框比看不到这一行更糟）
 * @param lastMsgId      最后一条消息 ID（null = 这个会话还没有任何消息）
 * @param lastMsgSeq     最后一条消息 seq（★会话高度，已读上报的校验上界）
 * @param lastMsgAt      最后一条消息时间（epoch millis）
 * @param lastMsgDigest  ★最后一条消息摘要（如 {@code "[图片]"}、{@code "[消息已撤回]"}）。
 *                       由<b>发送方服务端</b>生成并冗余存储，<b>不是</b>前端从 body 截的 ——
 *                       撤回后摘要要变成 {@code "[消息已撤回]"}，而撤回发生时只有服务端知道
 * @param lastSenderId   最后一条消息的发送者 userId
 * @param lastSenderName 最后一条消息的发送者显示名（★冗余存储，避免列表页 N 次查用户资料）
 * @param peerUserId     单聊对端 userId（群聊为 null；★单聊非成员时也为 null）
 * @param peerName       单聊对端昵称
 * @param peerAvatar     单聊对端头像
 * @param myRole         ★<b>那个人</b>在群里的角色：1=普通 2=管理员 3=群主。
 *                       ★★<b>本字段是不是 null 是"他在不在这个会话里"的唯一可靠判据</b>（见类注释）。
 *                       ★它<b>不能</b>当权限判定的唯一依据 —— 服务端每次都重新校验，
 *                       它只够用来决定「要不要显示管理入口」
 * @param myNickName     <b>那个人</b>在群里的名片（null = 用平台昵称）
 * @param unreadCount    ★<b>那个人</b>的未读数。★这是<b>快照值</b>（实时值在 IM 的 Redis 里）。
 *                       ★★<b>非成员时是 {@code 0} 而不是 null</b> —— 别拿 0 当"没有未读"的判据
 * @param topFlag        <b>那个人</b>是否置顶
 * @param topTime        置顶时间（epoch millis）；多个置顶会话之间按它倒序
 * @param dnd            ★免打扰。★免打扰<b>仍然计未读数</b>，只是不计入总未读、不弹通知
 *                       ⇒ 渲染成「有数字但没红点」，不是「没有未读」
 * @param visible        ★<b>那个人</b>是否可见此会话。false = 用户「删除了会话」
 *                       （只是从自己列表里隐藏，<b>不是</b>退群、也不是删消息）
 * @param createTime     会话创建时间（epoch millis）
 * @param joinTime       <b>那个人</b>入群时间（epoch millis）
 * @param ext            ★扩展字段（JSON 文本，<b>原样透传</b>）。★★<b>不可</b>对它做字符串相等比较：
 *                       MySQL 的 {@code json} 列会重新序列化（键按长度+字典序重排、补空格），
 *                       两次读回的同一个对象可能<b>不是逐字相同</b>的字符串 ⇒
 *                       要比就解析成结构比，别比字符串
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImConversationDetailDto(Long convId,
                                      Integer convType,
                                      String name,
                                      String avatar,
                                      String notice,
                                      Long ownerUserId,
                                      Integer memberCount,
                                      Integer maxMember,
                                      Integer status,
                                      Long spaceId,
                                      String groupNo,
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
                                      Long joinTime,
                                      String ext) {

    /** 会话类型：单聊。 */
    public static final int CONV_TYPE_P2P = 1;

    /** 会话类型：群聊。 */
    public static final int CONV_TYPE_GROUP = 2;

    /** 会话类型：系统会话。 */
    public static final int CONV_TYPE_SYSTEM = 3;

    /**
     * 会话类型：团队群（平台 space 的镜像群）。
     *
     * <p>★它不是"预留枚举"—— 真机上存在且常见。
     */
    public static final int CONV_TYPE_TEAM = 4;

    /** 角色：普通成员。 */
    public static final int ROLE_MEMBER = 1;

    /** 角色：管理员。 */
    public static final int ROLE_ADMIN = 2;

    /** 角色：群主。 */
    public static final int ROLE_OWNER = 3;

    /**
     * ★<b>是否是群聊</b>（含 {@link #CONV_TYPE_TEAM}）。
     *
     * <p>★{@link #CONV_TYPE_TEAM} <b>也算群聊</b> —— 与 IM 侧 {@code ImConvType.isGroup()} 一致。
     * 写成 {@code convType == 2} 会把团队群判成非群，那正是"团队群里发不出消息"的经典成因。
     */
    public boolean isGroup() {
        return convType != null && (convType == CONV_TYPE_GROUP || convType == CONV_TYPE_TEAM);
    }

    /** ★是否是团队群（平台 space 的镜像群）。★判断镜像群的判据<b>只</b>是这个，不是 {@code spaceId != null}。 */
    public boolean isTeam() {
        return convType != null && convType == CONV_TYPE_TEAM;
    }

    /** ★是否是单聊。 */
    public boolean isP2p() {
        return convType != null && convType == CONV_TYPE_P2P;
    }

    /**
     * ★<b>执行本次查询的人是不是这个会话的成员</b>（{@code myRole != null}）。
     *
     * <p>★★这是非成员场景下唯一可靠的判据 —— {@code unreadCount} 会以 {@code 0}
     * 的形态出现，用它判断会把"看不见"读成"没有未读"。
     */
    public boolean viewerIsMember() {
        return myRole != null;
    }

    /** ★空安全：{@code status} 未返回时<b>不</b>视为可发言（只在明确为 1 时才允许）。 */
    public boolean okOrFalse() {
        return status != null && status == 1;
    }
}
