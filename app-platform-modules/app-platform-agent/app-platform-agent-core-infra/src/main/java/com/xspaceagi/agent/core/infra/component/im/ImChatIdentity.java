package com.xspaceagi.agent.core.infra.component.im;

import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.Map;

/**
 * chatbot（{@code HybridAgentLoop}）这一侧的「IM 身份口径」。
 *
 * <p>与 {@code NuwaxImSandboxController} 里那套 {@code *ActorUserId(...)} 是<b>同一套规则的另一个入口</b>：
 * 那边从 <b>AK 的 {@code config.ext}</b> 读身份，这边从 <b>{@link AgentContext#getImVars()}</b> 读 ——
 * 两者其实是同一份数据（IM 调 4.10 时把 variableParams 原样带过来，平台既写进 {@code imVars}、
 * 也写进为这次运行铸的 AK 的 ext），所以判据可以逐条对齐、不需要另立一套。
 *
 * <h2>为什么必须有这一层：chatbot 手里没有 AK</h2>
 *
 * <p>沙箱那条腿是「技能脚本带 AK 打 4sandbox 接口」，控制器能 {@code currentAccessKey()} 拿到 ext；
 * chatbot 这条腿是模型在进程内直接 function-call，凭据面只有 {@code AgentContext}。
 * 所以身份解析必须能在 {@code imVars} 上独立完成，否则工具要么发不出、要么以错的身份发出。
 *
 * <h2>四种 actor 口径（与控制器一一对应，别合并）</h2>
 * <ol>
 *   <li>{@link #p2pActor}：私聊发送。<b>收件人＝自己 ⇒ 智能体虚拟身份</b>（这是「智能体回他」），
 *       否则本人（这是「用户自己发」）。用户口径：私聊场景一律以用户身份调其他接口。</li>
 *   <li>{@link #groupActor}：群发送。群场景 ⇒ 智能体虚拟身份；<b>读不到虚拟身份时返回 {@code null}</b>
 *       而不是回退本人 —— 回退会把契约断裂伪装成一次正常发送（消息以群代理身份发出）。</li>
 *   <li>{@link #scenarioActor}：查询/会话操作。群场景 ⇒ 虚拟身份（读不到回退本人，让 IM 判
 *       {@code NOT_CONV_MEMBER} 大声失败并指向真实病因）；私聊 ⇒ 本人。</li>
 *   <li>{@link #agentSubject}：排队区。只能是智能体主体，<b>读不到就本地 fail-closed</b> ——
 *       那个契约不接受真人主体，静默回退等于换个身份继续跑。</li>
 * </ol>
 *
 * <h2>两道「混乱代理」闸门照搬</h2>
 *
 * <p>智能体在 IM 里的可达范围远大于驱动这一次运行的人（它可能同时在很多个群里）。所以：
 * <ul>
 *   <li>{@link #requireGroupSendTargetIsTriggerGroup}：群场景下只能发回<b>触发本次运行的那个群</b>；</li>
 *   <li>{@link #requireP2pSendIsAllowed}：群场景下<b>不允许</b>发私聊（否则以群代理身份落进任意人的单聊）。</li>
 * </ul>
 * 两道闸门的判据都是 {@link #groupScenario}（群虚拟键 <b>或</b> convId 键），它是
 * {@link #groupActorKeyPresent}（actor 选择的判据）的<b>超集</b>：少判一档就留一个缺口。
 *
 * <p>★刻意<b>不</b>用 {@code IS_GROUP_MESSAGE} 当判据：那是个由上游写入的布尔值，可被伪造；
 * 「键在不在」才是 IM 群聊分支自己留下的痕迹。
 */
@Slf4j
@Getter
public final class ImChatIdentity {

    /** 与 nuwax-im {@code ImAgentChatService#variableParams} 的键名逐字一致（硬编码键）。 */
    public static final String KEY_IS_GROUP_MESSAGE = "IS_GROUP_MESSAGE";
    public static final String KEY_AGENT_VIRTUAL_USER_ID = "AGENT_VIRTUAL_USER_ID";
    public static final String KEY_GROUP_VIRTUAL_USER_ID = "GROUP_VIRTUAL_USER_ID";
    public static final String KEY_GROUP_CONV_ID = "GROUP_CONV_ID";
    public static final String KEY_GROUP_SENDER_ID = "GROUP_SENDER_ID";
    public static final String KEY_SPACE_ID = "SPACE_ID";
    /** 可配置键（IM 侧 im.agent-chat.* 可改名，但平台按缺省名读，与 SandboxAgentClient 一致）。 */
    public static final String KEY_IM_MODE = "imMode";
    public static final String KEY_IM_CONV_ID = "imConvId";
    public static final String KEY_IM_MSG_ID = "imMsgId";
    public static final String KEY_IM_SENDER_NAME = "imSenderName";

    public static final String MODE_LISTEN = "listen";
    public static final String MODE_MENTION = "mention";
    public static final String MODE_P2P = "p2p";

    /**
     * 4.10 的 {@code userId}：<b>群 = 群代理 subjectId / 私聊 = 真人 userId</b>。
     * ★群场景下它<b>不是</b>智能体的虚拟身份，拿它调任何 convId 作用域的接口都会被 IM 判
     * {@code NOT_CONV_MEMBER} —— 这正是「群里必须用 agent 虚拟身份」的由来。
     */
    private final Long selfUserId;
    private final Long tenantId;
    /** 智能体拟人化虚拟身份（{@code AGENT_OFFSET(10^12) + agentId}）；imVars 没带则为 null。 */
    private final Long agentVirtualUserId;
    /** actor 选择用判据：{@code containsKey(GROUP_VIRTUAL_USER_ID)}（判键不判值）。 */
    private final boolean groupActorKeyPresent;
    /** 闸门用判据：上面那个 <b>或</b> {@code containsKey(GROUP_CONV_ID)}（超集）。 */
    private final boolean groupScenario;
    /** {@code GROUP_CONV_ID}：触发本次运行的那个群。 */
    private final Long groupConvId;
    /** {@code imConvId}：本次触发的会话（群/私聊都有）。 */
    private final Long convId;
    /** {@code imMsgId}：触发本次运行的那条消息。 */
    private final Long triggerMsgId;
    /** {@code GROUP_SENDER_ID}：群里说话的那个人（真人时即平台原生 userId）。 */
    private final Long senderId;
    private final String senderName;
    /** {@code imMode}：listen / mention / p2p。 */
    private final String mode;
    private final Long spaceId;
    private final boolean admin;
    /** {@code imVars} 是否非空：即「本次运行是不是 IM 带进来的」。 */
    private final boolean imVarsPresent;

    private ImChatIdentity(Long selfUserId, Long tenantId, Long agentVirtualUserId,
                           boolean groupActorKeyPresent, boolean groupScenario,
                           Long groupConvId, Long convId, Long triggerMsgId,
                           Long senderId, String senderName, String mode, Long spaceId, boolean admin,
                           boolean imVarsPresent) {
        this.selfUserId = selfUserId;
        this.tenantId = tenantId;
        this.agentVirtualUserId = agentVirtualUserId;
        this.groupActorKeyPresent = groupActorKeyPresent;
        this.groupScenario = groupScenario;
        this.groupConvId = groupConvId;
        this.convId = convId;
        this.triggerMsgId = triggerMsgId;
        this.senderId = senderId;
        this.senderName = senderName;
        this.mode = mode;
        this.spaceId = spaceId;
        this.admin = admin;
        this.imVarsPresent = imVarsPresent;
    }

    public static ImChatIdentity of(AgentContext ctx) {
        Map<String, Object> vars = ctx == null ? null : ctx.getImVars();
        UserDto user = ctx == null ? null : ctx.getUser();
        // userId：getUser().getId() 优先（SandboxAgentClient.buildP2pReminder 用的就是它），退回 ctx.getUserId()
        Long self = user != null && user.getId() != null ? user.getId() : (ctx == null ? null : ctx.getUserId());
        boolean groupKey = vars != null && vars.containsKey(KEY_GROUP_VIRTUAL_USER_ID);
        boolean groupConvKey = vars != null && vars.containsKey(KEY_GROUP_CONV_ID);
        return new ImChatIdentity(
                self,
                user != null ? user.getTenantId() : null,
                vars == null ? null : toLong(vars.get(KEY_AGENT_VIRTUAL_USER_ID)),
                groupKey,
                groupKey || groupConvKey,
                vars == null ? null : toLong(vars.get(KEY_GROUP_CONV_ID)),
                vars == null ? null : toLong(vars.get(KEY_IM_CONV_ID)),
                vars == null ? null : toLong(vars.get(KEY_IM_MSG_ID)),
                vars == null ? null : toLong(vars.get(KEY_GROUP_SENDER_ID)),
                vars == null ? null : asText(vars.get(KEY_IM_SENDER_NAME)),
                vars == null ? null : asText(vars.get(KEY_IM_MODE)),
                vars == null ? null : toLong(vars.get(KEY_SPACE_ID)),
                user != null && user.getRole() == User.Role.Admin,
                vars != null && !vars.isEmpty());
    }

    /**
     * 是否由 IM 触发（有 imVars 且能解析出身份）。
     *
     * <p>★必须带上 {@code imVarsPresent} 这一档：{@link #selfUserId} 会回落到 {@code ctx.getUserId()}，
     * 所以「能解析出身份」这一条对<b>任何</b>有登录态的上下文都成立（web 请求也一样）。少了这一档，
     * 一个名字读起来像闸门的方法会对全部流量放行 —— 而这类判断错了不报错，只会让身份选错，
     * 是本类唯一「静默且方向朝危险侧」的失败模式。
     */
    public boolean resolved() {
        return imVarsPresent && (selfUserId != null || agentVirtualUserId != null);
    }

    public boolean listenMode() {
        return MODE_LISTEN.equals(mode);
    }

    public boolean mentionMode() {
        return MODE_MENTION.equals(mode);
    }

    public boolean p2pMode() {
        return MODE_P2P.equals(mode);
    }

    // ============================== actor 解析 ==============================

    /**
     * 私聊发送的 actor：收件人＝自己 ⇒ 智能体虚拟身份（智能体在回话），否则本人（用户自己在发）。
     *
     * <p>虚拟身份读不到时<b>原样交给 IM 判拒</b>（预期 IM_20106）：这条回退是安全的，
     * 因为它一定会被拒，不会产出一条身份可疑的消息。
     */
    public Long p2pActor(Long toUserId) {
        if (toUserId == null || !toUserId.equals(selfUserId)) {
            return selfUserId;
        }
        if (agentVirtualUserId == null) {
            log.warn("★[IM-TOOL] 私聊收件人=自己，但 imVars 未带 {}，原样交给 IM 判拒（预期 IM_20106）"
                            + " userId={} toUserId={} — 若这是 IM 私聊回话链路，先查 4.10 的 variableParams",
                    KEY_AGENT_VIRTUAL_USER_ID, selfUserId, toUserId);
            return selfUserId;
        }
        return agentVirtualUserId;
    }

    /**
     * 群发送的 actor：群场景 ⇒ 智能体虚拟身份；<b>虚拟身份读不到时返回 null</b>（让 IM 以 IM_90001 大声拒），
     * 私聊场景 ⇒ 本人（用户自己在单聊里要求往某个群发）。
     */
    public Long groupActor() {
        if (!groupActorKeyPresent) {
            return selfUserId;
        }
        if (agentVirtualUserId == null) {
            log.warn("★[IM-TOOL] 群发起（imVars 带 {}）但未带智能体虚拟身份，返回 null 让 IM 大声拒掉"
                            + "（预期 IM_90001）：回退成 {} 会把契约断裂伪装成一次正常发送 userId={}",
                    KEY_GROUP_VIRTUAL_USER_ID, selfUserId, selfUserId);
            return null;
        }
        return agentVirtualUserId;
    }

    /** 查询/会话操作类端点的 actor：群场景 ⇒ 虚拟身份（读不到回退本人，由 IM 判 NOT_CONV_MEMBER）；私聊 ⇒ 本人。 */
    public Long scenarioActor() {
        if (!groupActorKeyPresent) {
            return selfUserId;
        }
        if (agentVirtualUserId == null) {
            log.warn("★[IM-TOOL] 群场景（imVars 带 {}）但未带智能体虚拟身份，回退本人"
                            + "（本人若不在群里 → IM 判 NOT_CONV_MEMBER，大声失败且指向真实病因）userId={}",
                    KEY_GROUP_VIRTUAL_USER_ID, selfUserId);
            return selfUserId;
        }
        return agentVirtualUserId;
    }

    /** 发送类端点（send-card / send-system）的 actor：有虚拟身份用它，缺失回退本人（不按场景分流）。 */
    public Long agentActor() {
        return agentVirtualUserId != null ? agentVirtualUserId : selfUserId;
    }

    /**
     * 排队区端点（pending/list、pending/clear）的 actor：<b>恒为智能体虚拟身份，绝不返回本人</b>。
     * 读不到就本地 fail-closed —— 那个契约不接受真人主体，静默回退等于换个身份继续跑。
     */
    public Long agentSubject() {
        if (agentVirtualUserId == null) {
            log.warn("★[IM-TOOL] 排队区端点要求智能体主体身份，但 imVars 未携带 {}，已拒绝 userId={}",
                    KEY_AGENT_VIRTUAL_USER_ID, selfUserId);
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "排队区接口只能以智能体身份调用，当前会话上下文未携带智能体虚拟身份（"
                            + KEY_AGENT_VIRTUAL_USER_ID + "）");
        }
        return agentVirtualUserId;
    }

    // ============================== 闸门 ==============================

    /** 群场景专属闸门②：群场景下不允许发私聊（授权范围是本群，私聊会以群代理身份落进任意人的单聊）。 */
    public void requireP2pSendIsAllowed() {
        if (!groupScenario) {
            return;
        }
        log.warn("★[IM-TOOL] 群场景下试图发私聊，已拒绝：群场景的授权范围是本群 userId={} groupConvId={}",
                selfUserId, groupConvId);
        throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED,
                BizExceptionCodeEnum.validationFailedWithDetail,
                "由群消息触发时不能发私聊消息，只能在触发本次对话的群里发言");
    }

    /**
     * 群场景专属闸门①：群发的落点必须<b>就是</b>触发本次运行的那个群。
     *
     * <p>★判据不是「智能体在不在目标群」（那件事 IM 的 requireActiveMember 已经判了），
     * 而是「这个目标是不是本次运行的授权范围」—— 平台能判、IM 判不了的正是这一件。
     *
     * @param convId 请求里的目标群
     */
    public void requireGroupSendTargetIsTriggerGroup(Long convId) {
        if (!groupScenario) {
            return;
        }
        if (groupConvId == null) {
            log.warn("★[IM-TOOL] 群场景却读不到 {} 的值，按不放行处理 convId={} userId={} —"
                            + " 该键在 IM 群聊分支里是无条件 put 的，读不到说明 IM 侧版本回退/漂移",
                    KEY_GROUP_CONV_ID, convId, selfUserId);
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "群场景下无法确认目标群是否是本次触发的群，已拒绝发送");
        }
        if (convId == null || !groupConvId.equals(convId)) {
            log.warn("★[IM-TOOL] 群场景下试图发往【非触发群】，已拒绝：智能体是多个群的成员，"
                            + "放行等于让驱动者借它向自己进不去的群投言 triggerConvId={} targetConvId={} userId={}",
                    groupConvId, convId, selfUserId);
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED,
                    BizExceptionCodeEnum.validationFailedWithDetail,
                    "只能把消息发回触发本次对话的那个群（本次触发群 convId=" + groupConvId
                            + "，请求的 convId=" + convId + "）");
        }
    }

    /** 与控制器 {@code requireAdmin()} 同口径：非管理员一律 4030，fail-closed（读不到用户也算非管理员）。 */
    public void requireAdmin() {
        if (!admin) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
    }

    // ============================== 取值工具 ==============================

    /**
     * 宽松的 id 解析：整数型 Number 与非空白数字串都认，其余一律 null。
     *
     * <p>★浮点类型（Double/Float/BigDecimal）一律不认、也<b>不做</b> {@code longValue()} 截断：
     * 截断后的数字是<b>另一个人的</b> id，而这条链路决定的正是「以谁的身份把消息发出去」。
     * 宁可走「读不到」分支（最终由 IM 拒掉），也不产出一条身份可疑的消息。
     *
     * <p>另外这里<b>必须</b>认 {@code Long}：{@code AGENT_VIRTUAL_USER_ID = 10^12 + agentId}，
     * 用 int 装会溢出（这也是工具入参不能声明成 Integer 再由框架转值的原因）。
     */
    public static Long toLong(Object raw) {
        if (raw instanceof Number number
                && !(number instanceof Double) && !(number instanceof Float) && !(number instanceof BigDecimal)) {
            long value = number.longValue();
            return value > 0 ? value : null;
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                long value = Long.parseLong(text.trim());
                return value > 0 ? value : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String asText(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }
}
