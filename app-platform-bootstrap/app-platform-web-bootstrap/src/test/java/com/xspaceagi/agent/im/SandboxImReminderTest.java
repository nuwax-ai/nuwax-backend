package com.xspaceagi.agent.im;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.agent.SandboxAgentClient;
import com.xspaceagi.system.application.dto.UserDto;

/**
 * 技能腿（沙箱跑 nuwax-im 技能）那两段用户提示词提醒的形状测试：
 * {@code SandboxAgentClient#buildP2pReminder} / {@code #buildGroupReminder}。
 *
 * <p>★为什么单独钉「形状」：这两个方法只往 {@code StringBuilder} 里拼字符串，
 * 少一个 {@code "\n"} <b>不会报错、也不会让任何调用失败</b>，只会让模型读到黏成一行的怪值。
 * 实际发生过：{@code P2P_MESSAGE_SENDER_ID} 那行漏了换行，产物是
 * <pre>P2P_MESSAGE_SENDER_ID: 1P2P_CONV_ID: 225619407848607744</pre>
 * 于是 SENDER_ID 的值成了 {@code 1P2P_CONV_ID: 2256...}，而 {@code P2P_CONV_ID} 根本不在行首、
 * 读不出来。<b>逐个子串断言（{@code contains("P2P_CONV_ID: 225")}）照样全绿</b> ——
 * 只有「每个键必须独占一行」这条能抓住它，所以下面用的是<b>整行相等</b>。
 *
 * <p>方法是 private 实例方法，这里走反射：刻意<b>不</b>为了可测把它们放开成 public ——
 * 提示词拼装是内部实现，对外只该暴露 {@code buildSystemPrompt} 那种有契约的入口。
 * 反射按名字取，方法被改名会以 {@code NoSuchMethodException} 大声失败，不会静默跳过。
 *
 * <p>键名与 IM 侧 {@code ImAgentChatService#variableParams} 逐字对应；其中
 * {@code GROUP_SENDER_ID} / {@code GROUP_CONV_ID} / {@code GROUP_VIRTUAL_USER_ID} 是<b>群聊专属</b>，
 * 私聊分支<b>不送</b>（IM 侧注释「私聊【不送】：拍板口径是群向智能体发消息时才加」）。
 */
class SandboxImReminderTest {

    private static final Long SELF = 1L;
    private static final Long AGENT_VIRTUAL = 1_000_000_000_123L;
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
        vars.put("AGENT_VIRTUAL_USER_ID", AGENT_VIRTUAL);
        vars.put("imMode", "p2p");
        vars.put("imConvId", P2P_CONV);
        vars.put("imMsgId", 330011L);
        vars.put("imSenderName", "冯飞1");
        return vars;
    }

    private static Map<String, Object> groupVars(String mode) {
        Map<String, Object> vars = new HashMap<>();
        vars.put("IS_GROUP_MESSAGE", true);
        vars.put("AGENT_VIRTUAL_USER_ID", AGENT_VIRTUAL);
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

    /**
     * 反射调 private 提醒方法（形状见类注释）。
     *
     * <p>★两种失败必须分开报：{@link java.lang.reflect.InvocationTargetException} 是<b>方法体自己抛了</b>
     * （这段拼在每轮对话主链路上，抛出去就是炸掉整轮），而 {@code NoSuchMethodException} 是方法被改名了。
     * 一律报成「取不到方法」会把排查引到错误的方向上去。
     */
    private static String reminder(String method, Map<String, Object> vars, Long userId) {
        try {
            Method m = SandboxAgentClient.class.getDeclaredMethod(method, AgentContext.class);
            m.setAccessible(true);
            return (String) m.invoke(new SandboxAgentClient(), ctx(vars, userId));
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new AssertionError(method + " 自己抛了 " + cause.getClass().getSimpleName()
                    + "（这段在每轮对话的主链路上，抛出去=炸掉整轮）", cause);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("取不到 " + method + "（改名了要同步改这里）", e);
        }
    }

    private static String p2p(Map<String, Object> vars) {
        return reminder("buildP2pReminder", vars, SELF);
    }

    private static String group(String mode) {
        return reminder("buildGroupReminder", groupVars(mode), GROUP_PROXY);
    }

    /**
     * ★承重断言：每个键值对都必须<b>独占一行</b>（整行相等，不是 contains）。
     * 黏行只有这条抓得住，见类注释里那个真实案例。
     */
    private static void assertKeyPerLine(String text, Map<String, String> expected) {
        List<String> lines = List.of(text.split("\n", -1));
        for (Map.Entry<String, String> e : expected.entrySet()) {
            String want = e.getKey() + ": " + e.getValue();
            assertTrue(lines.contains(want),
                    "「" + want + "」必须独占一行；黏到别的键后面 = 模型读不出这个字段。实际提醒：\n" + text);
        }
    }

    // ============================== 私聊提醒 ==============================

    @Test
    @DisplayName("★私聊提醒：发送者 id / 发送者名字 / 会话 id 三个键各占一行，值都取对")
    void p2pReminderCarriesSenderAndConvOneKeyPerLine() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("P2P_MESSAGE_SENDER_ID", String.valueOf(SELF));
        expected.put("P2P_MESSAGE_SENDER_NAME", "冯飞1");
        expected.put("P2P_CONV_ID", String.valueOf(P2P_CONV));
        assertKeyPerLine(p2p(p2pVars()), expected);
    }

    @Test
    @DisplayName("★私聊提醒：发送者 id 取本次运行的登录用户（IM 私聊不送 GROUP_SENDER_ID），不许留空也不许写成群代理")
    void p2pSenderIdComesFromTheLoggedInUserBecauseImDoesNotSendGroupSenderId() {
        String r = p2p(p2pVars());
        assertTrue(r.contains("P2P_MESSAGE_SENDER_ID: " + SELF), r);
        // 私聊分支拿不到 GROUP_SENDER_ID / GROUP_VIRTUAL_USER_ID：一旦哪天有人改成从 imVars 取，
        // 这里就会变成空值或群代理 id —— 「以谁的身份说话」写错是不报错的
        assertFalse(r.contains(String.valueOf(GROUP_PROXY)), "私聊提醒里不该出现群代理 id：" + r);
        assertFalse(r.contains("GROUP_"), "私聊提醒不该出现任何群聊专属键：" + r);
    }

    @Test
    @DisplayName("imSenderName 缺失或为 null：留空，绝不把「null」四个字拼进提示词")
    void p2pSenderNameDegradesToEmptyNotNull() {
        Map<String, Object> missing = p2pVars();
        missing.remove("imSenderName");
        assertTrue(p2p(missing).contains("P2P_MESSAGE_SENDER_NAME: \n"), "键缺失 ⇒ 值留空");

        Map<String, Object> explicitNull = p2pVars();
        explicitNull.put("imSenderName", null);
        String r = p2p(explicitNull);
        assertFalse(r.contains("null"), "★键在、值为 null 时 getOrDefault 照样交出 null：" + r);
        assertTrue(r.contains("P2P_MESSAGE_SENDER_NAME: \n"), r);
    }

    @Test
    @DisplayName("私聊提醒仍要带上「用技能回复、直接输出用户看不到」与资料库 spaceId")
    void p2pReminderKeepsSkillDirectiveAndSpaceId() {
        String r = p2p(p2pVars());
        assertTrue(r.contains("nuwax-im 技能"), "必须指名用技能发消息：" + r);
        assertTrue(r.contains("会话直接输出的内容用户不会看到"), "必须说清直接输出到不了用户手上：" + r);

        Map<String, Object> withSpace = p2pVars();
        withSpace.put("SPACE_ID", SPACE_ID);
        assertTrue(p2p(withSpace).contains("优先使用的资料库spaceId: " + SPACE_ID), "带 SPACE_ID 时要给出资料库空间");
        assertFalse(r.contains("spaceId"), "没带 SPACE_ID 时不该出现这一行：" + r);
    }

    // ============================== 群提醒（回归护栏） ==============================

    @Test
    @DisplayName("群提醒：三个键各占一行（私聊那次黏行不能在这里重演）")
    void groupReminderCarriesSenderAndConvOneKeyPerLine() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("GROUP_MESSAGE_SENDER_ID", "9");
        expected.put("GROUP_MESSAGE_SENDER_NAME", "赵宇");
        expected.put("GROUP_CONV_ID", String.valueOf(GROUP_CONV));
        expected.put("群资料库spaceId", String.valueOf(SPACE_ID));
        assertKeyPerLine(group("mention"), expected);
    }

    @Test
    @DisplayName("群提醒：mention 走「用技能回复」，listen 走「监听模式、无关消息不必回」")
    void groupReminderSplitsMentionAndListen() {
        String mention = group("mention");
        assertTrue(mention.contains("nuwax-im 技能"), mention);
        assertFalse(mention.contains("监听模式"), "mention 是代发的，不该说自己在监听：" + mention);

        String listen = group("listen");
        assertTrue(listen.contains("监听模式"), listen);
        assertTrue(listen.contains("不需要回复"), "监听模式必须给出「可以不回」的许可：" + listen);
        assertTrue(listen.contains("nuwax-im 技能"), "要回时仍然只能走技能：" + listen);
    }

    @Test
    @DisplayName("imVars 为 null：两段提醒都退化成空串，绝不抛（这段拼在每轮对话的主链路上）")
    void remindersDegradeToEmptyWithoutImVars() {
        assertTrue(reminder("buildP2pReminder", null, SELF).isEmpty());
        assertTrue(reminder("buildGroupReminder", null, GROUP_PROXY).isEmpty());
    }

    // ============================== 标签闭合 ==============================

    /**
     * ★两段提醒都必须真正闭合。原来收尾写的是 {@code <p2p-reminder>} / {@code <group-reminder>}
     * （少一个 {@code /}），等于开了个永不闭合的块 —— 而它<b>不报错</b>，只会让后面追加的整段
     * 提示词（{@code <system-reminder>}、技能使用说明）都被读成这个块的内部约束。
     *
     * <p>★必须数「开始标签 == 结束标签」，不能只断言 {@code contains("</p2p-reminder>")}：
     * 少了闭合标签时那条断言为假能抓住，但<b>多写一个</b>闭合标签同样是坏的，只有计数抓得住。
     */
    @Test
    @DisplayName("★标签闭合：开合标签各一个，且闭合在键值行之后")
    void reminderBlocksAreProperlyClosed() {
        Map<String, String> tags = Map.of(
                "buildP2pReminder", "p2p-reminder",
                "buildGroupReminder", "group-reminder");
        for (Map.Entry<String, String> e : tags.entrySet()) {
            String text = "buildP2pReminder".equals(e.getKey())
                    ? p2p(p2pVars()) : group("mention");
            String open = "<" + e.getValue() + ">";
            String close = "</" + e.getValue() + ">";
            assertEquals(1, count(text, open), e.getKey() + " 只该有一个开始标签：" + text);
            assertEquals(1, count(text, close), e.getKey() + " 只该有一个闭合标签（原来一个都没有）：" + text);
            assertTrue(text.indexOf(close) > text.indexOf(open), "闭合必须在开始之后：" + text);
        }
    }

    private static int count(String haystack, String needle) {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            n++;
        }
        return n;
    }

    // ============================== 缺键退化 ==============================

    /**
     * ★群提醒缺 {@code GROUP_SENDER_ID} 时不许回落到 {@code AGENT_VIRTUAL_USER_ID}。
     * 回落等于告诉模型「在群里说话的人是你自己」，而 {@code AGENT_VIRTUAL_USER_ID} 在群分支<b>一定会送</b>，
     * 所以这个回落<b>必然生效</b>、不是理论风险。错的身份<b>不报错</b>，只会让回复对象整个搞反。
     */
    @Test
    @DisplayName("★群提醒缺 GROUP_SENDER_ID：留空，绝不回落到智能体自己的虚拟身份")
    void groupReminderLeavesSenderIdEmptyInsteadOfFallingBackToTheAgent() {
        Map<String, Object> vars = groupVars("mention");
        vars.remove("GROUP_SENDER_ID");
        String r = reminder("buildGroupReminder", vars, GROUP_PROXY);

        assertTrue(List.of(r.split("\n", -1)).contains("GROUP_MESSAGE_SENDER_ID: "),
                "留空，但键要在（模型据此知道「不知道是谁说的」）：" + r);
        assertFalse(r.contains(String.valueOf(AGENT_VIRTUAL)),
                "不许把智能体自己的虚拟身份当成发言人：" + r);
    }

    /**
     * ★值为 null（键在、值缺）时不许把「null」四个字拼进提示词，也不许 NPE。
     *
     * <p>{@code getOrDefault} 在<b>键存在、值为 null</b> 时照样交出 null，所以它挡不住这个；
     * {@code imMode} 那一处更狠 —— 原来是 {@code getOrDefault("imMode","").equals("mention")}，
     * 值为 null 会直接 NPE，而这段拼在<b>每轮群聊对话的主链路上</b>（炸掉整轮对话）。
     */
    @Test
    @DisplayName("★键在值为 null：不拼「null」字样、不 NPE，且 imMode=null 走监听分支而不是抛")
    void nullValuedKeysDegradeQuietly() {
        Map<String, Object> p2p = p2pVars();
        p2p.put("imSenderName", null);
        p2p.put("imConvId", null);
        p2p.put("SPACE_ID", null);
        String p = p2p(p2p);
        assertFalse(p.contains("null"), p);
        assertTrue(List.of(p.split("\n", -1)).contains("P2P_CONV_ID: "), p);

        Map<String, Object> group = groupVars("mention");
        group.put("imMode", null);
        group.put("imSenderName", null);
        group.put("GROUP_CONV_ID", null);
        group.put("GROUP_SENDER_ID", null);
        String g = reminder("buildGroupReminder", group, GROUP_PROXY);
        assertFalse(g.contains("null"), g);
        assertTrue(g.contains("监听模式"), "★imMode=null 原来会 NPE；现在按「不是 mention」走监听分支：" + g);
    }
}
