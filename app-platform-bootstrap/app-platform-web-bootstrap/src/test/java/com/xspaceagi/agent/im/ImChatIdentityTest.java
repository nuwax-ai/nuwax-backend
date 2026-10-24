package com.xspaceagi.agent.im;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.im.ImChatIdentity;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;

/**
 * {@link ImChatIdentity} 的单元测试——纯内存，不起容器、不连库、不发 RPC。
 * <p>
 * 这一层是 chatbot 侧 IM 工具的<b>唯一安全边界</b>：它决定「以谁的身份、往哪个会话、发不发得出去」。
 * 判错了不会报错，只会<b>把消息以错的身份发出去</b>（用户在群里看到「张三说：…」其实是智能体说的），
 * 或者<b>让驱动这一次运行的人借智能体之手往自己进不去的群投言</b>。两种都是静默的，所以逐条钉死。
 * <p>
 * 与 {@code NuwaxImSandboxController} 里那套 {@code *ActorUserId(...)} 是同一套规则的另一个入口，
 * 断言的口径必须与那边一致。
 */
class ImChatIdentityTest {

    private static final Long SELF = 1001L;
    private static final Long TENANT = 7L;
    /** 10^12 + agentId：★必须用 long 装，int 会溢出成另一个人的 id。 */
    private static final Long AGENT_VIRTUAL = 1_000_000_000_123L;
    private static final Long GROUP_PROXY = 555L;
    private static final Long TRIGGER_CONV = 90001L;
    private static final Long OTHER_CONV = 90002L;

    // ============================== 构造辅助 ==============================

    private static AgentContext ctx(Map<String, Object> imVars, Long userId, User.Role role) {
        AgentContext ctx = new AgentContext();
        ctx.setFrom("nuwax_im");
        ctx.setImVars(imVars);
        ctx.setUserId(userId);
        UserDto user = new UserDto();
        user.setId(userId);
        user.setTenantId(TENANT);
        user.setRole(role);
        ctx.setUser(user);
        return ctx;
    }

    /** 群场景：IM 群聊分支写进来的那一组键（含群代理 userId）。 */
    private static Map<String, Object> groupVars() {
        Map<String, Object> vars = new HashMap<>();
        vars.put(ImChatIdentity.KEY_IS_GROUP_MESSAGE, true);
        vars.put(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID, AGENT_VIRTUAL);
        vars.put(ImChatIdentity.KEY_GROUP_VIRTUAL_USER_ID, GROUP_PROXY);
        vars.put(ImChatIdentity.KEY_GROUP_CONV_ID, TRIGGER_CONV);
        vars.put(ImChatIdentity.KEY_GROUP_SENDER_ID, 2002L);
        vars.put(ImChatIdentity.KEY_IM_MODE, ImChatIdentity.MODE_MENTION);
        vars.put(ImChatIdentity.KEY_IM_CONV_ID, TRIGGER_CONV);
        return vars;
    }

    /** 私聊场景：只有可配置键，没有群键。 */
    private static Map<String, Object> p2pVars() {
        Map<String, Object> vars = new HashMap<>();
        vars.put(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID, AGENT_VIRTUAL);
        vars.put(ImChatIdentity.KEY_IM_MODE, ImChatIdentity.MODE_P2P);
        vars.put(ImChatIdentity.KEY_IM_CONV_ID, 80001L);
        vars.put(ImChatIdentity.KEY_IM_MSG_ID, 70001L);
        return vars;
    }

    private static ImChatIdentity group() {
        return ImChatIdentity.of(ctx(groupVars(), GROUP_PROXY, User.Role.User));
    }

    private static ImChatIdentity p2p() {
        return ImChatIdentity.of(ctx(p2pVars(), SELF, User.Role.User));
    }

    // ============================== 四种 actor 口径 ==============================

    @Test
    @DisplayName("私聊：收件人不是自己 ⇒ actor 是本人（用户自己在发）")
    void p2pActorIsSelfWhenSendingToSomeoneElse() {
        assertEquals(SELF, p2p().p2pActor(2002L));
    }

    @Test
    @DisplayName("私聊：收件人就是自己 ⇒ actor 是智能体虚拟身份（这是智能体在回话）")
    void p2pActorIsVirtualWhenRecipientIsSelf() {
        assertEquals(AGENT_VIRTUAL, p2p().p2pActor(SELF));
    }

    @Test
    @DisplayName("私聊：收件人=自己但没有虚拟身份 ⇒ 回落本人交给 IM 判拒，不在本地静默换成别的身份")
    void p2pActorFallsBackToSelfWhenVirtualMissing() {
        Map<String, Object> vars = p2pVars();
        vars.remove(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID);
        ImChatIdentity id = ImChatIdentity.of(ctx(vars, SELF, User.Role.User));

        assertNull(id.getAgentVirtualUserId());
        // ★断言的是「回落成谁」：必须是本人（一定会被 IM 以 IM_20106 拒掉），
        //   而不是虚拟身份的某个猜测值 —— 猜出来的身份会真的把消息发出去。
        assertEquals(SELF, id.p2pActor(SELF));
    }

    @Test
    @DisplayName("群：actor 是智能体虚拟身份，绝不是群代理")
    void groupActorIsVirtualInGroupScenario() {
        ImChatIdentity id = group();

        assertEquals(AGENT_VIRTUAL, id.groupActor());
        assertEquals(AGENT_VIRTUAL, id.scenarioActor());
        // ★群代理 userId 是 4.10 的调用主体，不是「以谁的名义发言」的身份；
        //   它连群成员都不是，拿它调 convId 作用域的接口只会被判 NOT_CONV_MEMBER。
        assertEquals(GROUP_PROXY, id.getSelfUserId());
        assertFalse(GROUP_PROXY.equals(id.groupActor()), "群发送不得以群代理身份发出");
    }

    @Test
    @DisplayName("群：读不到虚拟身份 ⇒ groupActor 返回 null 让 IM 大声拒，不回退群代理")
    void groupActorIsNullWhenVirtualMissing() {
        Map<String, Object> vars = groupVars();
        vars.remove(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID);
        ImChatIdentity id = ImChatIdentity.of(ctx(vars, GROUP_PROXY, User.Role.User));

        // ★这条是「宁可不发，也不以错的身份发」：回退成群代理等于把契约断裂伪装成一次正常发送。
        assertNull(id.groupActor());
        // 查询类允许回退本人（会被 IM 判 NOT_CONV_MEMBER，大声失败且指向真实病因）
        assertEquals(GROUP_PROXY, id.scenarioActor());
    }

    @Test
    @DisplayName("私聊：查询/会话操作的 actor 是本人")
    void scenarioActorIsSelfInP2p() {
        assertEquals(SELF, p2p().scenarioActor());
    }

    @Test
    @DisplayName("agentActor：有虚拟身份用它，没有才回退本人（不按场景分流）")
    void agentActorPrefersVirtual() {
        assertEquals(AGENT_VIRTUAL, p2p().agentActor());
        assertEquals(AGENT_VIRTUAL, group().agentActor());

        Map<String, Object> vars = p2pVars();
        vars.remove(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID);
        assertEquals(SELF, ImChatIdentity.of(ctx(vars, SELF, User.Role.User)).agentActor());
    }

    @Test
    @DisplayName("排队区：没有虚拟身份就本地 fail-closed，不回退本人")
    void agentSubjectFailsClosedWithoutVirtual() {
        assertEquals(AGENT_VIRTUAL, p2p().agentSubject());

        Map<String, Object> vars = p2pVars();
        vars.remove(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID);
        ImChatIdentity id = ImChatIdentity.of(ctx(vars, SELF, User.Role.User));

        BizException e = assertThrows(BizException.class, id::agentSubject);
        assertEquals(ErrorCodeEnum.PERMISSION_DENIED.getCode(), e.getCode());
        assertTrue(e.getMessage().contains(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID),
                "报错必须点明缺的是哪个键，否则排查只能靠猜：" + e.getMessage());
    }

    // ============================== 两道混乱代理闸门 ==============================

    @Test
    @DisplayName("闸门②：群场景下发私聊一律拒（授权范围是本群）")
    void groupScenarioRejectsP2pSend() {
        BizException e = assertThrows(BizException.class, group()::requireP2pSendIsAllowed);
        assertEquals(ErrorCodeEnum.PERMISSION_DENIED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("闸门②：私聊场景放行")
    void p2pScenarioAllowsP2pSend() {
        p2p().requireP2pSendIsAllowed();
    }

    @Test
    @DisplayName("闸门①：群场景只能发回触发本次运行的那个群")
    void groupSendIsLimitedToTriggerGroup() {
        ImChatIdentity id = group();

        id.requireGroupSendTargetIsTriggerGroup(TRIGGER_CONV);
        BizException e = assertThrows(BizException.class,
                () -> id.requireGroupSendTargetIsTriggerGroup(OTHER_CONV));
        assertEquals(ErrorCodeEnum.PERMISSION_DENIED.getCode(), e.getCode());
        assertTrue(e.getMessage().contains(String.valueOf(TRIGGER_CONV)), "报错应带上触发群 id：" + e.getMessage());
        assertTrue(e.getMessage().contains(String.valueOf(OTHER_CONV)), "报错应带上请求的群 id：" + e.getMessage());
    }

    @Test
    @DisplayName("闸门①：convId 缺失同样拒（不能靠「没填」绕过）")
    void groupSendRejectsNullConvId() {
        assertThrows(BizException.class, () -> group().requireGroupSendTargetIsTriggerGroup(null));
    }

    @Test
    @DisplayName("闸门①：群场景却读不到 GROUP_CONV_ID 的值 ⇒ 按不放行处理")
    void groupSendFailsClosedWhenTriggerConvUnknown() {
        Map<String, Object> vars = groupVars();
        // ★键在（⇒ 判定为群场景）但值为 null：IM 群聊分支是无条件 put 的，
        //   读不到值说明 IM 侧版本回退/漂移 —— 此时「无法确认目标是不是触发群」，只能拒。
        vars.put(ImChatIdentity.KEY_GROUP_CONV_ID, null);
        ImChatIdentity id = ImChatIdentity.of(ctx(vars, GROUP_PROXY, User.Role.User));

        assertTrue(id.isGroupScenario());
        assertNull(id.getGroupConvId());
        BizException e = assertThrows(BizException.class,
                () -> id.requireGroupSendTargetIsTriggerGroup(OTHER_CONV));
        assertEquals(ErrorCodeEnum.PERMISSION_DENIED.getCode(), e.getCode());
    }

    @Test
    @DisplayName("闸门①：私聊场景不校验目标群（用户自己要求往某个群发是允许的）")
    void p2pScenarioSkipsTriggerGroupGate() {
        p2p().requireGroupSendTargetIsTriggerGroup(OTHER_CONV);
    }

    @Test
    @DisplayName("★闸门判据是「键在不在」的超集：只有 GROUP_CONV_ID 也算群场景")
    void gateJudgementIsSupersetOfActorJudgement() {
        Map<String, Object> vars = new HashMap<>();
        // 只有 GROUP_CONV_ID，没有 GROUP_VIRTUAL_USER_ID
        vars.put(ImChatIdentity.KEY_GROUP_CONV_ID, TRIGGER_CONV);
        vars.put(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID, AGENT_VIRTUAL);
        ImChatIdentity id = ImChatIdentity.of(ctx(vars, SELF, User.Role.User));

        assertFalse(id.isGroupActorKeyPresent(), "actor 选择的判据只看群虚拟键");
        assertTrue(id.isGroupScenario(), "闸门判据必须是超集，少判一档就留一个缺口");
        // 两道闸门都要按群场景生效
        assertThrows(BizException.class, id::requireP2pSendIsAllowed);
        assertThrows(BizException.class, () -> id.requireGroupSendTargetIsTriggerGroup(OTHER_CONV));
    }

    @Test
    @DisplayName("★不用 IS_GROUP_MESSAGE 当判据：伪造的布尔值不能把私聊变成群场景，也不能把群场景变成私聊")
    void isGroupMessageFlagIsNotTheJudgementKey() {
        // 群场景却声称 false ⇒ 仍然是群场景（键在）
        Map<String, Object> lying = groupVars();
        lying.put(ImChatIdentity.KEY_IS_GROUP_MESSAGE, false);
        assertTrue(ImChatIdentity.of(ctx(lying, GROUP_PROXY, User.Role.User)).isGroupScenario());

        // 私聊却声称 true ⇒ 仍然是私聊（群键不在）
        Map<String, Object> forged = p2pVars();
        forged.put(ImChatIdentity.KEY_IS_GROUP_MESSAGE, true);
        ImChatIdentity id = ImChatIdentity.of(ctx(forged, SELF, User.Role.User));
        assertFalse(id.isGroupScenario());
        id.requireP2pSendIsAllowed();
    }

    // ============================== 管理员闸门 ==============================

    @Test
    @DisplayName("requireAdmin：管理员放行，非管理员与读不到用户都 4030（fail-closed）")
    void requireAdminFailsClosed() {
        ImChatIdentity.of(ctx(p2pVars(), SELF, User.Role.Admin)).requireAdmin();

        BizException e = assertThrows(BizException.class, p2p()::requireAdmin);
        assertEquals(ErrorCodeEnum.PERMISSION_DENIED.getCode(), e.getCode());

        // 没有 UserDto（只剩 ctx.userId）⇒ 读不到角色 ⇒ 按非管理员处理
        AgentContext anonymous = new AgentContext();
        anonymous.setUserId(SELF);
        anonymous.setImVars(p2pVars());
        assertThrows(BizException.class, ImChatIdentity.of(anonymous)::requireAdmin);

        // ctx 整个为 null 也不能炸出 NPE，而是老老实实拒掉
        assertThrows(BizException.class, ImChatIdentity.of(null)::requireAdmin);
    }

    @Test
    @DisplayName("群场景下的调用主体是群代理，几乎不可能是管理员 ⇒ 管理员工具在群里天然调不通")
    void groupProxyIsNotAdmin() {
        assertFalse(group().isAdmin());
        assertThrows(BizException.class, group()::requireAdmin);
    }

    // ============================== 模式 / toLong ==============================

    @Test
    @DisplayName("模式判定按 imMode 原文")
    void modeFlags() {
        assertTrue(p2p().p2pMode());
        assertFalse(p2p().listenMode());
        assertTrue(group().mentionMode());

        Map<String, Object> vars = groupVars();
        vars.put(ImChatIdentity.KEY_IM_MODE, ImChatIdentity.MODE_LISTEN);
        assertTrue(ImChatIdentity.of(ctx(vars, GROUP_PROXY, User.Role.User)).listenMode());
    }

    @Test
    @DisplayName("resolved：imVars 为 null / 空时不算 IM 触发，且不抛 NPE")
    void resolvedIsFalseWithoutImVars() {
        AgentContext bare = new AgentContext();
        bare.setUserId(SELF);
        ImChatIdentity id = ImChatIdentity.of(bare);

        assertFalse(id.resolved());
        assertEquals(SELF, id.getSelfUserId());
        assertNull(id.getMode());
        assertFalse(id.isGroupScenario());

        // ★空 map 同样不算：selfUserId 会回落到 ctx.getUserId()，「能解析出身份」对任何登录态都成立，
        //   所以必须靠「有没有 imVars」这一档把它挡下来 —— 少了这一档，web 请求也会被判成 IM 触发。
        AgentContext emptyVars = ctx(new HashMap<>(), SELF, User.Role.User);
        assertFalse(ImChatIdentity.of(emptyVars).resolved(), "空 imVars 不算 IM 触发");
        assertEquals(SELF, ImChatIdentity.of(emptyVars).getSelfUserId(), "但身份仍要能解析出来");
    }

    @Test
    @DisplayName("resolved：IM 触发的两种场景都为 true；带登录态的 web 上下文仍为 false")
    void resolvedIsTrueOnlyForImOrigin() {
        assertTrue(p2p().resolved());
        assertTrue(group().resolved());

        // ★这条钉的是「resolved() 不是登录态的别名」：同一个 userId，没有 imVars 就必须判 false。
        AgentContext web = ctx(null, SELF, User.Role.User);
        web.setFrom("web");
        assertFalse(ImChatIdentity.of(web).resolved());
    }

    @Test
    @DisplayName("selfUserId 优先取 user.getId()，缺失时退回 ctx.getUserId()")
    void selfUserIdPrefersUserDto() {
        AgentContext ctx = ctx(p2pVars(), SELF, User.Role.User);
        ctx.setUserId(424242L);
        assertEquals(SELF, ImChatIdentity.of(ctx).getSelfUserId());

        ctx.setUser(null);
        assertEquals(424242L, ImChatIdentity.of(ctx).getSelfUserId());
    }

    @Test
    @DisplayName("★toLong 必须认得下 10^12+agentId，且浮点一律不认、不截断")
    void toLongIsStrictAboutFloats() {
        assertEquals(AGENT_VIRTUAL, ImChatIdentity.toLong(AGENT_VIRTUAL));
        assertEquals(AGENT_VIRTUAL, ImChatIdentity.toLong(String.valueOf(AGENT_VIRTUAL)));
        assertEquals(3L, ImChatIdentity.toLong(3));
        assertEquals(3L, ImChatIdentity.toLong(3L));
        assertEquals(3L, ImChatIdentity.toLong(" 3 "));
        // ★量级自检：虚拟身份 id 已超出 int 范围（10^12 + agentId），若哪天有人把解析口径改成 int，这条先红
        assertTrue(AGENT_VIRTUAL > Integer.MAX_VALUE, "虚拟身份 id 必须超出 int 范围，否则下面那条钉不住什么");
        assertEquals(Integer.MAX_VALUE + 1L, ImChatIdentity.toLong(Integer.MAX_VALUE + 1L),
                "超出 int 的正整数必须照样认下来，不能因为「装不下」而被当成没填");

        // ★浮点拒绝：截断后的数字是【另一个人】的 id，而这条链路决定的正是「以谁的身份发」
        assertNull(ImChatIdentity.toLong(3.0d));
        assertNull(ImChatIdentity.toLong(3.7d));
        assertNull(ImChatIdentity.toLong(3.0f));
        assertNull(ImChatIdentity.toLong(new BigDecimal("3")));
        assertNull(ImChatIdentity.toLong("3.0"));

        // 非正数 / 非数字 / null / 其他类型
        assertNull(ImChatIdentity.toLong(0));
        assertNull(ImChatIdentity.toLong(-1));
        assertNull(ImChatIdentity.toLong("abc"));
        assertNull(ImChatIdentity.toLong(""));
        assertNull(ImChatIdentity.toLong("  "));
        assertNull(ImChatIdentity.toLong(null));
        assertNull(ImChatIdentity.toLong(true));
    }
}
