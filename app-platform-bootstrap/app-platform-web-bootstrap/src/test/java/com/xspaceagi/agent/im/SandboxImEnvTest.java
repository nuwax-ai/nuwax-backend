package com.xspaceagi.agent.im;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.agent.SandboxAgentClient;
import com.xspaceagi.system.application.dto.UserDto;

/**
 * 沙箱进程环境变量里那批 IM 键（{@code SandboxAgentClient#buildImEnv}）的形状测试。
 *
 * <p>★键名在这里写<b>字面量</b>，不从常量引用：这批名字是给沙箱里的技能脚本用的
 * <b>对外契约</b>（脚本按 {@code os.environ["IM_GROUP_CONV_ID"]} 取值），改一次名字就废掉
 * 所有已发布的技能文档。用常量引用的话，改名会测试照样全绿、只在线上把脚本打死。
 *
 * <p>★三条必须钉住、且都<b>不会报错</b>的静默失败：
 * <ol>
 *   <li><b>非 IM 运行也被写进 IM_* 键</b> —— 技能脚本据此以为自己在群里，于是去调 IM 接口发消息；</li>
 *   <li><b>群场景缺 {@code GROUP_SENDER_ID} 时回落到智能体自己的身份</b> —— 脚本会认为
 *       「群里说话的就是我自己」，把别人的提问当成自己的发言；</li>
 *   <li><b>缺失值写成字面量 {@code "null"}</b> —— 脚本拿着四个字母当 id 用。</li>
 * </ol>
 *
 * <p>场景判据与提示词侧提醒（{@code buildGroupReminder}/{@code buildP2pReminder}）同源，
 * 都用 {@code ImChatIdentity}；本测试同时钉住「两条腿口径一致」这件事。
 */
class SandboxImEnvTest {

    private static final Long SELF = 1L;
    /** 群场景下 4.10 的 userId（群代理），与 {@code GROUP_SENDER_ID}（真人）必须区分开。 */
    private static final Long GROUP_PROXY = 1_000_000_001_596L;
    private static final Long P2P_CONV = 225619407848607744L;
    private static final Long GROUP_CONV = 227009028956360704L;
    private static final Long SPACE_ID = 57L;

    // ============================== 构造辅助 ==============================

    private static AgentContext ctx(Map<String, Object> imVars, Long userId) {
        AgentContext ctx = new AgentContext();
        ctx.setFrom("nuwax_im");
        ctx.setImVars(imVars);
        ctx.setUserId(userId);
        UserDto user = new UserDto();
        user.setId(userId);
        ctx.setUser(user);
        return ctx;
    }

    private static Map<String, Object> p2pVars() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("IS_GROUP_MESSAGE", false);
        vars.put("AGENT_VIRTUAL_USER_ID", 1_000_000_000_123L);
        vars.put("imMode", "p2p");
        vars.put("imConvId", P2P_CONV);
        vars.put("imMsgId", 330011L);
        vars.put("imSenderName", "冯飞1");
        return vars;
    }

    private static Map<String, Object> groupVars(String mode) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("IS_GROUP_MESSAGE", true);
        vars.put("AGENT_VIRTUAL_USER_ID", 1_000_000_000_123L);
        vars.put("GROUP_VIRTUAL_USER_ID", GROUP_PROXY);
        vars.put("GROUP_CONV_ID", GROUP_CONV);
        vars.put("GROUP_SENDER_ID", 9L);
        vars.put("imMode", mode);
        vars.put("imConvId", GROUP_CONV);
        vars.put("imMsgId", 330022L);
        vars.put("imSenderName", "赵宇");
        vars.put("SPACE_ID", SPACE_ID);
        return vars;
    }

    private static Map<String, String> env(Map<String, Object> vars, Long userId) {
        return SandboxAgentClient.buildImEnv(ctx(vars, userId));
    }

    /** 任何值都不许是字面量 "null"（见类注释第 3 条）。 */
    private static void assertNoLiteralNull(Map<String, String> env) {
        env.forEach((k, v) -> assertNotEquals("null", v,
                k + " 的值是字面量 \"null\" —— 脚本会把它当成真实值用，缺键比错值安全：" + env));
    }

    // ============================== 群聊 ==============================

    @Test
    @DisplayName("群消息：群/会话/发送者全套键都在，且发送者是群里那个人（不是群代理）")
    void groupEnvCarriesGroupContext() {
        Map<String, String> env = env(groupVars("mention"), GROUP_PROXY);

        assertEquals("group", env.get("IM_SCENARIO"), env.toString());
        assertEquals(String.valueOf(GROUP_CONV), env.get("IM_GROUP_CONV_ID"), env.toString());
        assertEquals(String.valueOf(GROUP_CONV), env.get("IM_CONV_ID"), env.toString());
        assertEquals("330022", env.get("IM_MSG_ID"), env.toString());
        assertEquals("mention", env.get("IM_MODE"), env.toString());
        assertEquals("赵宇", env.get("IM_SENDER_NAME"), env.toString());
        assertEquals(String.valueOf(SPACE_ID), env.get("IM_SPACE_ID"), env.toString());
        // ★发送者必须是 GROUP_SENDER_ID（9），不是 4.10 的 userId（群代理）——
        //   写成群代理等于告诉脚本「群里说话的人是你自己」。
        assertEquals("9", env.get("IM_SENDER_ID"), env.toString());
        assertNoLiteralNull(env);
    }

    @Test
    @DisplayName("群消息监听模式：IM_MODE=listen 原样透传（脚本据此决定要不要回话）")
    void groupListenModeIsPassedThrough() {
        Map<String, String> env = env(groupVars("listen"), GROUP_PROXY);
        assertEquals("listen", env.get("IM_MODE"), env.toString());
        assertEquals("group", env.get("IM_SCENARIO"), env.toString());
    }

    // ============================== 私聊 ==============================

    @Test
    @DisplayName("私聊：场景是 p2p，发送者取本次登录用户，且不出现群专属的 IM_GROUP_CONV_ID")
    void p2pEnvCarriesSenderAsLoginUser() {
        Map<String, String> env = env(p2pVars(), SELF);

        assertEquals("p2p", env.get("IM_SCENARIO"), env.toString());
        assertEquals(String.valueOf(SELF), env.get("IM_SENDER_ID"), env.toString());
        assertEquals(String.valueOf(P2P_CONV), env.get("IM_CONV_ID"), env.toString());
        assertEquals("p2p", env.get("IM_MODE"), env.toString());
        // 群专属键一个都不许出现：脚本靠「键在不在」判场景，混进来就会走错分支
        assertFalse(env.containsKey("IM_GROUP_CONV_ID"), env.toString());
        assertNoLiteralNull(env);
    }

    // ============================== 两类静默失败 ==============================

    @Test
    @DisplayName("★群场景缺 GROUP_SENDER_ID：IM_SENDER_ID 直接不写，绝不回落到群代理/智能体身份")
    void groupWithoutSenderIdWritesNoSenderKey() {
        Map<String, Object> vars = groupVars("mention");
        vars.remove("GROUP_SENDER_ID");
        Map<String, String> env = env(vars, GROUP_PROXY);

        assertFalse(env.containsKey("IM_SENDER_ID"),
                "缺发送者时必须不写这个键 —— 回落等于宣称「群里说话的是你自己」：" + env);
        // 场景本身仍要认得出（否则脚本连「我在群里」都不知道）
        assertEquals("group", env.get("IM_SCENARIO"), env.toString());
        assertEquals("赵宇", env.get("IM_SENDER_NAME"), env.toString());
        assertNoLiteralNull(env);
    }

    @Test
    @DisplayName("非 IM 触发：一个 IM_* 键都不写（否则脚本会以为自己在群里）")
    void nonImRunWritesNothing() {
        assertTrue(env(null, SELF).isEmpty(), "imVars=null 不该产出任何 IM 键");
        assertTrue(env(new HashMap<>(), SELF).isEmpty(), "imVars 为空 Map 不该产出任何 IM 键");
        // 普通网页会话：有登录用户、但没 imVars
        AgentContext plain = ctx(null, SELF);
        assertTrue(SandboxAgentClient.buildImEnv(plain).isEmpty(), "普通会话不该产出任何 IM 键");
    }

    @Test
    @DisplayName("★不注入身份 id：AGENT_VIRTUAL_USER_ID / GROUP_VIRTUAL_USER_ID 不进环境变量")
    void identityIdsAreNotExported() {
        Map<String, String> group = env(groupVars("mention"), GROUP_PROXY);
        Map<String, String> p2p = env(p2pVars(), SELF);

        // 这两个 id 是「以谁的名义发消息」，由服务端按 AK 的 ext 判；沙箱拿到也用不上
        for (Map<String, String> env : java.util.List.of(group, p2p)) {
            assertFalse(env.containsKey("IM_AGENT_VIRTUAL_USER_ID"), env.toString());
            assertFalse(env.containsKey("IM_GROUP_VIRTUAL_USER_ID"), env.toString());
            assertFalse(env.containsKey("AGENT_VIRTUAL_USER_ID"), env.toString());
            assertFalse(env.containsKey("GROUP_VIRTUAL_USER_ID"), env.toString());
        }
        // 反面：身份 id 一次都不该以任何形式出现在值里
        assertFalse(group.containsValue("1000000000123"), group.toString());
    }

    @Test
    @DisplayName("★场景判据不认 IS_GROUP_MESSAGE：那个布尔值可被伪造，群键才是痕迹")
    void scenarioFollowsGroupKeysNotFlag() {
        // 伪造：说自己是群消息，但没有任何群键
        Map<String, Object> fake = new HashMap<>(p2pVars());
        fake.put("IS_GROUP_MESSAGE", true);
        assertEquals("p2p", env(fake, SELF).get("IM_SCENARIO"),
                "只有 IS_GROUP_MESSAGE 没有群键时仍应判为私聊");

        // 反向：群键在、布尔值说不是群 —— 按群处理（与 ImChatIdentity 的 groupScenario 同口径）
        Map<String, Object> reverse = new HashMap<>(groupVars("mention"));
        reverse.put("IS_GROUP_MESSAGE", false);
        Map<String, String> env = env(reverse, GROUP_PROXY);
        assertEquals("group", env.get("IM_SCENARIO"), env.toString());
        assertEquals("9", env.get("IM_SENDER_ID"), env.toString());
    }
}
