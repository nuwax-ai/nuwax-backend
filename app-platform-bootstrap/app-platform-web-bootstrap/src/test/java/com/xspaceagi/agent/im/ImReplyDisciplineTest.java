package com.xspaceagi.agent.im;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.agent.SandboxAgentClient;
import com.xspaceagi.agent.core.infra.component.im.ImChatIdentity;
import com.xspaceagi.agent.core.infra.component.im.ImChatToolProvider;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentConfig;
import com.xspaceagi.agent.core.spec.constant.Prompts;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.infra.dao.entity.User;

/**
 * 「回复纪律」（{@link Prompts#IM_REPLY_DISCIPLINE_PROMPT}）的单元测试。
 *
 * <p>为什么单独一个测试类：这段提示词<b>两条腿都要有</b> ——
 * 原生工具腿（{@code ImChatToolProvider#buildReminder}，chatbot 直接 function-call {@code im_} 工具）
 * 与技能腿（{@code SandboxAgentClient#imChatSystemPromptSuffix()}，沙箱里跑 nuwax-im 技能）。
 * 少了任何一条，那条腿上的智能体就会继续把 {@code msgId=xxx}、错误码抄给用户；
 * 而提示词漏一句<b>不会报错</b>，只会让毛病悄悄回来 —— 典型的静默失败，必须钉住。
 *
 * <p>三方 IM 名称的检查在 {@code ImChatToolProviderTest#noThirdPartyImIsNamed} 里已覆盖
 * （它检查的 reminder 现在就内嵌这份常量），此处不重复。
 */
class ImReplyDisciplineTest {

    private static final Long SELF = 1001L;
    private static final Long TENANT = 7L;
    private static final Long AGENT_VIRTUAL = 1_000_000_000_123L;

    private static final String DISCIPLINE = Prompts.IM_REPLY_DISCIPLINE_PROMPT;

    // ============================== 构造辅助 ==============================

    private static AgentContext ctx(Map<String, Object> imVars) {
        AgentContext ctx = new AgentContext();
        ctx.setFrom("nuwax_im");
        ctx.setImVars(imVars);
        ctx.setUserId(SELF);
        UserDto user = new UserDto();
        user.setId(SELF);
        user.setTenantId(TENANT);
        user.setRole(User.Role.User);
        ctx.setUser(user);
        return ctx;
    }

    private static Map<String, Object> groupVars(String mode) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(ImChatIdentity.KEY_IS_GROUP_MESSAGE, true);
        vars.put(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID, AGENT_VIRTUAL);
        vars.put(ImChatIdentity.KEY_GROUP_VIRTUAL_USER_ID, 555L);
        vars.put(ImChatIdentity.KEY_GROUP_CONV_ID, 90001L);
        vars.put(ImChatIdentity.KEY_GROUP_SENDER_ID, 2002L);
        vars.put(ImChatIdentity.KEY_IM_MODE, mode);
        vars.put(ImChatIdentity.KEY_IM_CONV_ID, 90001L);
        return vars;
    }

    private static Map<String, Object> p2pVars() {
        Map<String, Object> vars = new HashMap<>();
        vars.put(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID, AGENT_VIRTUAL);
        vars.put(ImChatIdentity.KEY_IM_MODE, ImChatIdentity.MODE_P2P);
        vars.put(ImChatIdentity.KEY_IM_CONV_ID, 80001L);
        return vars;
    }

    private static String reminder(Map<String, Object> vars) {
        return new ImChatToolProvider().buildReminder(ImChatIdentity.of(ctx(vars)));
    }

    // ============================== 纪律正文 ==============================

    @Test
    @DisplayName("★纪律正文：逐类点名执行过程数据，并留下「用户明确索要就给」的口子")
    void disciplineNamesEveryLeakAndKeepsTheEscapeHatch() {
        // 逐个点名：模型漏抄的正是这些「看起来像结论」的裸 id，只写「不要返回技术数据」拦不住
        for (String leak : List.of("msgId", "convId", "seq", "bizMsgId", "traceId",
                "错误码", "工具名", "入参 JSON", "内部字段名")) {
            assertTrue(DISCIPLINE.contains(leak), "纪律要点名 " + leak + "：" + DISCIPLINE);
        }
        assertTrue(DISCIPLINE.contains("除非用户明确索要"), "必须留「用户要就给」的口子：" + DISCIPLINE);
        // ★防过界：只写「别出现 msgId」会被读成连参数都不许用，撤回/按 id 查消息会直接瘫掉
        assertTrue(DISCIPLINE.contains("照常用"), "必须说明这些值仍要用于调工具/技能：" + DISCIPLINE);
        // ★纪律不是「报喜不报忧」的许可证：失败照样要说，只是用自然语言说
        assertTrue(DISCIPLINE.contains("失败要如实说"), "必须要求如实报失败：" + DISCIPLINE);
        assertFalse(DISCIPLINE.contains("null"), "纪律正文里不该出现 null 字样：" + DISCIPLINE);
    }

    @Test
    @DisplayName("★纪律正文自带完整标签且以换行收尾：随便追加到哪一段提示词后面都是合法结构")
    void disciplineIsSelfContainedMarkup() {
        assertTrue(DISCIPLINE.startsWith("<reply-discipline>"), DISCIPLINE);
        assertTrue(DISCIPLINE.endsWith("</reply-discipline>\n"),
                "必须以闭合标签 + 换行收尾，否则拼进 StringBuilder 时会和下一段黏在一行：" + DISCIPLINE);
    }

    // ============================== 两条腿都要带上 ==============================

    @Test
    @DisplayName("★原生工具腿：三种模式的 reminder 都内嵌【同一份】纪律常量（不是各自抄一份）")
    void nativeToolLegEmbedsTheSharedDiscipline() {
        Map<String, Map<String, Object>> cases = new HashMap<>();
        cases.put(ImChatIdentity.MODE_LISTEN, groupVars(ImChatIdentity.MODE_LISTEN));
        cases.put(ImChatIdentity.MODE_MENTION, groupVars(ImChatIdentity.MODE_MENTION));
        cases.put(ImChatIdentity.MODE_P2P, p2pVars());
        for (Map.Entry<String, Map<String, Object>> e : cases.entrySet()) {
            assertTrue(reminder(e.getValue()).contains(DISCIPLINE),
                    e.getKey() + " 的提醒必须内嵌共享常量（自己抄一份就会漂移）");
        }
        // 提醒为 null imVars 时也要带上：这条纪律与身份解析无关，缺键不该把它一起丢掉
        assertTrue(reminder(null).contains(DISCIPLINE), "imVars 缺失时纪律仍要在");
    }

    @Test
    @DisplayName("★技能腿：沙箱系统提示词后缀 = 方案正文（逐字未改）+ 同一份纪律常量")
    void sandboxLegAppendsTheSameDisciplineAfterTheVerbatimDoc() {
        String suffix = SandboxAgentClient.imChatSystemPromptSuffix();

        assertTrue(suffix.contains(DISCIPLINE), "技能腿必须带上共享常量，否则沙箱里跑的智能体照旧泄漏：" + suffix);
        // 方案正文是资料库文档的逐字副本，纪律只能【追加在后面】，不许插进正文里改动它
        assertTrue(suffix.startsWith(Prompts.AGENT_IM_CHAT_SYSTEM_PROMPT),
                "《智能体参与聊天方案》正文必须原样在前（它的 javadoc 承诺与文档逐字一致）");
        assertEquals(Prompts.AGENT_IM_CHAT_SYSTEM_PROMPT + "\n" + DISCIPLINE, suffix,
                "后缀只由这两段组成，多出来的内容要在这里被看见");
        assertTrue(suffix.indexOf(DISCIPLINE) > suffix.indexOf("</im-system-instruction>"),
                "纪律要落在方案正文之后（离生成位置更近，权重更高）");
    }

    @Test
    @DisplayName("★两条腿各只嵌一次：出现第二份副本 = 有人又内联了一段会漂移的纪律")
    void disciplineAppearsExactlyOncePerLeg() {
        assertEquals(1, countOccurrences(reminder(p2pVars()), DISCIPLINE),
                "原生工具腿只该嵌一次（嵌两遍等于同一条约束在提示词里复读，白烧预算）");
        assertEquals(1, countOccurrences(SandboxAgentClient.imChatSystemPromptSuffix(), DISCIPLINE),
                "技能腿只该嵌一次");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
    }

    // ============================== 技能腿：闸门与场景段 ==============================

    /** 带 {@code agentConfig} 的上下文（{@link SandboxAgentClient#buildSystemPrompt} 要读智能体系统提示词）。 */
    private static AgentContext sandboxCtx(Map<String, Object> imVars) {
        AgentContext ctx = ctx(imVars);
        AgentConfigDto config = new AgentConfigDto();
        config.setSystemPrompt("你是助手");
        ctx.setAgentConfig(config);
        return ctx;
    }

    /** {@code slugId} 为 null 表示会话没带资料库文档变量。 */
    private static ConversationDto conversation(String slugId) {
        ConversationDto c = new ConversationDto();
        if (slugId != null) {
            Map<String, Object> vars = new HashMap<>();
            vars.put("SLUG_ID", slugId);
            c.setVariables(vars);
        }
        return c;
    }

    @Test
    @DisplayName("★技能腿闸门：只有 IM 聊天才追加，且方案正文/纪律各只追加一次")
    void sandboxGateAppendsOnceForImChatAndNothingOtherwise() {
        for (Map<String, Object> vars : List.of(
                groupVars(ImChatIdentity.MODE_LISTEN),
                groupVars(ImChatIdentity.MODE_MENTION),
                p2pVars())) {
            String tail = SandboxAgentClient.imSystemPromptTail(ctx(vars));
            assertEquals("\n" + SandboxAgentClient.imChatSystemPromptSuffix(), tail,
                    "追加内容只由「换行 + 方案正文 + 纪律」组成，多出或少掉一段都要在这里被看见");
            // ★这条正是「同一段方案正文被追加两遍」的探针：调用点少删一个 if 就会变成 2
            assertEquals(1, countOccurrences(tail, Prompts.AGENT_IM_CHAT_SYSTEM_PROMPT),
                    "方案正文只该追加一次（复读白烧上下文预算，且同一条约束重复会互相稀释）");
            assertEquals(1, countOccurrences(tail, DISCIPLINE), "纪律只该追加一次");
        }

        AgentContext web = ctx(null);
        web.setFrom("web");
        assertEquals("", SandboxAgentClient.imSystemPromptTail(web),
                "没有 imVars ⇒ 不是 IM 聊天 ⇒ 空串（普通任务不该被塞进 IM 方案正文）");
        assertEquals("", SandboxAgentClient.imSystemPromptTail(null), "ctx 为 null 也退化成空串，绝不抛");
    }

    @Test
    @DisplayName("★场景段互斥：IM 聊天带方案正文+纪律，任务会话带输出规范，两者绝不同时出现")
    void imChatSegmentAndTaskOutputSegmentAreMutuallyExclusive() {
        // ① IM 聊天（群聊/私聊都算）：带方案正文 + 纪律，不带任务输出规范
        for (Map<String, Object> vars : List.of(groupVars(ImChatIdentity.MODE_MENTION), p2pVars())) {
            String sp = SandboxAgentClient.buildSystemPrompt(sandboxCtx(vars), conversation(null));
            assertEquals(1, countOccurrences(sp, Prompts.AGENT_IM_CHAT_SYSTEM_PROMPT), "方案正文一次");
            assertEquals(1, countOccurrences(sp, DISCIPLINE), "纪律一次");
            assertFalse(sp.contains(Prompts.TASK_AGENT_OUTPUT_PROMPT),
                    "★IM 聊天不该再带任务输出规范：两段都是「怎么回话」的指令，同时给等于两套冲突口径");
        }

        // ② 普通任务会话：带任务输出规范，不带 IM 那两段
        AgentContext web = sandboxCtx(null);
        web.setFrom("web");
        String taskSp = SandboxAgentClient.buildSystemPrompt(web, conversation(null));
        assertEquals(1, countOccurrences(taskSp, Prompts.TASK_AGENT_OUTPUT_PROMPT), "任务输出规范一次");
        assertFalse(taskSp.contains("reply-discipline"), "非 IM 聊天的系统提示词里不该出现回复纪律段");
        assertFalse(taskSp.contains(Prompts.AGENT_IM_CHAT_SYSTEM_PROMPT), "非 IM 聊天不该带方案正文");

        // ③ 资料库文档编辑（SLUG_ID 非空）：两段都不带 —— 这是原有语义，重构不能把它改掉
        String repoSp = SandboxAgentClient.buildSystemPrompt(web, conversation("uJszfxZFdHE6weUJ"));
        assertFalse(repoSp.contains(Prompts.TASK_AGENT_OUTPUT_PROMPT), "文档编辑场景不带任务输出规范");
        assertFalse(repoSp.contains("reply-discipline"), "文档编辑场景不带回复纪律");

        // 智能体自己的系统提示词必须原样在最前面：追加场景段不许把它挤掉或改写
        for (String sp : List.of(taskSp, repoSp)) {
            assertTrue(sp.startsWith("你是助手\n"), sp);
        }
    }

    // ============================== 顺带钉住清单侧不受影响 ==============================

    @Test
    @DisplayName("纪律走的是提醒、不是工具描述：21 个工具的名字与描述一个都没被改动")
    void disciplineLivesInTheReminderNotInTheToolList() {
        // ★刻意翻成管理员口径：清单按身份收窄后，非管理员那份只有 18 个 ——
        //   拿它扫等于 3 个管理端点的描述永久脱离检查，而这种「覆盖悄悄缩了」不会有任何失败信号。
        AgentContext adminCtx = ctx(groupVars(ImChatIdentity.MODE_MENTION));
        adminCtx.getUser().setRole(User.Role.Admin);
        List<ComponentConfig> tools = new ImChatToolProvider().buildTools(ImChatIdentity.of(adminCtx));
        assertEquals(21, tools.size(), "工具数量变了要同步改技能文档与 ImChatToolExecutorTest");
        for (ComponentConfig t : tools) {
            assertFalse(t.getDescription().contains("<reply-discipline>"),
                    t.getFunctionName() + " 的描述不该塞纪律正文（那是提醒的职责，塞进描述会挤掉参数语义）");
            for (Arg arg : t.getInputArgs()) {
                if (arg.getDescription() == null) {
                    continue;
                }
                assertFalse(arg.getDescription().contains("reply-discipline"),
                        t.getFunctionName() + "." + arg.getName() + " 的参数描述不该塞纪律正文");
            }
        }
    }
}
