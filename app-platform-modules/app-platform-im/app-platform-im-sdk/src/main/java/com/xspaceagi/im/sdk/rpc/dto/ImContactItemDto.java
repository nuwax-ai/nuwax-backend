package com.xspaceagi.im.sdk.rpc.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * ★联系人条目（契约 {@code ContactItem} —— 用户态 REST {@code GET /contacts}
 * 与 RPC {@code contacts/list} <b>共用同一份</b>）。
 *
 * <h2>★★{@code userId} 是<b>不透明标识</b>，且「人和智能体在这里长得一样」</h2>
 *
 * <p>真人就是平台 {@code userId}（恒等）；智能体是平台虚拟身份接口发的 {@code userId}
 * ——★<b>同样是普通十位数，只看 {@code userId} 分不出人和智能体</b>。
 * 类型判断看 {@link #subjectType}，<b>不要</b>按 id 段位猜。
 *
 * <p>唯一的历史例外：平台 key 未配时（fail-open）智能体回落成
 * {@code AGENT_OFFSET + agentId} 的 13 位引用令牌。★<b>不要依赖这条</b> ——
 * 它是降级形态而不是约定，平台一旦通就会变。
 *
 * <h2>★展示名用服务端算好的那份</h2>
 *
 * <p>{@link #displayName} 的优先级（备注 &gt; 昵称 &gt; 账号名 &gt; {@code 用户{id}}）
 * 由服务端统一实现。★调用方<b>不要自己重算</b>：重算出来的结果与界面上显示的不一致时，
 * 用户会以为「平台查错了人」。
 *
 * @param userId      ★对方的主体 id。★不透明标识，见类注释；
 *                    可直接用于建会话 / 发消息，但<b>不要</b>拿它做算术或大小比较
 * @param subjectType ★1=人 2=智能体。★类型判断只看它，不要按 id 段位猜
 * @param userName    平台账号名。★智能体<b>恒为 null</b>（智能体只有一个名字，放在 {@code nickName} 里）
 * @param nickName    平台昵称。★智能体时这里放的是<b>智能体名称</b>；
 *                    ★平台不可用时为 null（见 {@link ImContactListDto#degraded()}）
 * @param avatar      平台头像 URL。★平台不可用时为 null；智能体的空串已由服务端归一成 null
 * @param remark      我给对方起的备注名。★只存在于 IM（平台没有备注概念）
 * @param displayName ★★服务端算好的展示名（备注 &gt; 昵称 &gt; 账号名 &gt; {@code 用户{id}}），
 *                    <b>直接渲染</b>，不要自己重算
 * @param status      ★关系状态：1=已互为联系人 2=对方申请我 3=我申请对方。
 *                    ★<b>待同意（2/3）的不要渲染成正常联系人</b>
 * @param source      来源标识。★两侧口径不一致（IM SDK 注释：1=搜索添加 2=发消息自动建立 3=群聊成员；
 *                    openapi 里写作 1=平台用户 4=智能体）—— 本层<b>原样透传、不做解释</b>，
 *                    需要按它分支时请先与 IM 侧确认
 * @param applyMsg    申请附言（只有 {@code status=2/3} 有意义）
 * @param time        该行的建立 / 申请时间（epoch millis）。
 *                    ★用 long 而不是格式化字符串：列表右侧要显示「11:20 / 昨天 / 8月21日」
 *                    这类<b>相对时间</b>，必须由客户端按本地时区算
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ImContactItemDto(Long userId,
                               Integer subjectType,
                               String userName,
                               String nickName,
                               String avatar,
                               String remark,
                               String displayName,
                               Integer status,
                               Integer source,
                               String applyMsg,
                               Long time) {

    /** 主体类型：人。 */
    public static final int SUBJECT_TYPE_USER = 1;

    /** 主体类型：智能体。 */
    public static final int SUBJECT_TYPE_AGENT = 2;

    /** 关系状态：已互为联系人。 */
    public static final int STATUS_ACCEPTED = 1;

    /** 关系状态：对方申请我（待我同意）。 */
    public static final int STATUS_PENDING_IN = 2;

    /** 关系状态：我申请对方（待对方同意）。 */
    public static final int STATUS_PENDING_OUT = 3;

    /** ★是否已是好友（{@code status=1}）。★未下发按「不是」处理 —— 把待同意的当成好友会让调用方直接发消息过去。 */
    public boolean accepted() {
        return status != null && status == STATUS_ACCEPTED;
    }

    /** ★是否智能体。 */
    public boolean agent() {
        return subjectType != null && subjectType == SUBJECT_TYPE_AGENT;
    }

    /**
     * ★展示名兜底：{@code displayName} 为空时依次退回昵称 / 账号名 / {@code 用户名{id}}。
     *
     * <p>★这<b>不是</b>在平台侧重算一遍优先级（那会与界面上显示的漂移），
     * 只是在服务端没给（理论不该发生）时不至于渲染出一个 null。
     */
    public String displayNameOrFallback() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        if (nickName != null && !nickName.isBlank()) {
            return nickName;
        }
        if (userName != null && !userName.isBlank()) {
            return userName;
        }
        return "用户" + userId;
    }
}
