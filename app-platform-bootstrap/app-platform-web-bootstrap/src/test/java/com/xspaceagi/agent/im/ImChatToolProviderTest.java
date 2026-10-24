package com.xspaceagi.agent.im;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.im.ImChatIdentity;
import com.xspaceagi.agent.core.infra.component.im.ImChatToolProvider;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentConfig;
import com.xspaceagi.agent.core.spec.constant.Prompts;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.agent.core.spec.utils.ToolInfoExtractor;
import com.xspaceagi.im.sdk.rpc.IImRpcAvailability;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.infra.dao.entity.User;

/**
 * {@link ImChatToolProvider}（清单侧 + 挂载闸门 + IM 提醒）的单元测试。
 *
 * <p>清单侧的错误同样是<b>静默</b>的：工具名不是纯 ASCII 就会被 {@code ToolInfoExtractor} 改名，
 * 参数类型写成 {@code Number} 会让 Jackson 交出 {@code Double}（而执行侧刻意拒收浮点 ⇒「明明填了却说没填」），
 * 写成 {@code Object} 会被 {@code ArgExtractUtil} 塞进一个空容器（模型没填和填了空对象分不出来）。
 * 这三种都不会在运行时报错，只会让工具「看起来挂着但用不了」，所以逐条钉死。
 */
class ImChatToolProviderTest {

    private static final Long SELF = 1001L;
    private static final Long TENANT = 7L;
    private static final Long AGENT_VIRTUAL = 1_000_000_000_123L;
    private static final Long GROUP_PROXY = 555L;
    private static final Long TRIGGER_CONV = 90001L;
    private static final Long SPACE_ID = 4242L;

    /** ★技能/工具文案里不许出现三方 IM 的名字（产品口径：只讲「消息」，不讲哪个 IM）。 */
    private static final List<String> FORBIDDEN_WORDS = List.of(
            "飞书", "企业微信", "钉钉", "微信", "短信", "邮件",
            "Feishu", "Lark", "WeCom", "DingTalk", "Slack", "Telegram", "WhatsApp");

    // ============================== 构造辅助 ==============================

    private static AgentContext ctx(String from, Map<String, Object> imVars, User.Role role) {
        AgentContext ctx = new AgentContext();
        ctx.setFrom(from);
        ctx.setImVars(imVars);
        ctx.setUserId(SELF);
        UserDto user = new UserDto();
        user.setId(SELF);
        user.setTenantId(TENANT);
        user.setRole(role);
        ctx.setUser(user);
        return ctx;
    }

    private static Map<String, Object> groupVars(String mode) {
        Map<String, Object> vars = new HashMap<>();
        vars.put(ImChatIdentity.KEY_IS_GROUP_MESSAGE, true);
        vars.put(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID, AGENT_VIRTUAL);
        vars.put(ImChatIdentity.KEY_GROUP_VIRTUAL_USER_ID, GROUP_PROXY);
        vars.put(ImChatIdentity.KEY_GROUP_CONV_ID, TRIGGER_CONV);
        vars.put(ImChatIdentity.KEY_GROUP_SENDER_ID, 2002L);
        vars.put(ImChatIdentity.KEY_IM_SENDER_NAME, "张三");
        vars.put(ImChatIdentity.KEY_SPACE_ID, SPACE_ID);
        vars.put(ImChatIdentity.KEY_IM_MODE, mode);
        vars.put(ImChatIdentity.KEY_IM_CONV_ID, TRIGGER_CONV);
        return vars;
    }

    private static Map<String, Object> p2pVars() {
        Map<String, Object> vars = new HashMap<>();
        vars.put(ImChatIdentity.KEY_AGENT_VIRTUAL_USER_ID, AGENT_VIRTUAL);
        vars.put(ImChatIdentity.KEY_IM_MODE, ImChatIdentity.MODE_P2P);
        vars.put(ImChatIdentity.KEY_IM_CONV_ID, 80001L);
        return vars;
    }

    /** 带 {@code agentConfig} / {@code message} 的上下文（{@link #ctx} 刻意不设 agentConfig，用来验判空）。 */
    private static AgentContext ctxWithPrompt(String from, String systemPrompt, String userPrompt, String message) {
        AgentContext ctx = ctx(from, p2pVars(), User.Role.User);
        AgentConfigDto config = new AgentConfigDto();
        config.setSystemPrompt(systemPrompt);
        config.setUserPrompt(userPrompt);
        ctx.setAgentConfig(config);
        ctx.setMessage(message);
        return ctx;
    }

    private static ImChatToolProvider provider(boolean enabled) {
        ImChatToolProvider p = new ImChatToolProvider();
        IImRpcAvailability availability = mock(IImRpcAvailability.class);
        when(availability.enabled()).thenReturn(enabled);
        p.setImRpcAvailability(availability);
        return p;
    }

    /** 非管理员看到的清单（群 mention 触发）—— 管理员端点被摘掉，见 {@link #adminTools()}。 */
    private static List<ComponentConfig> tools() {
        return build(User.Role.User, groupVars(ImChatIdentity.MODE_MENTION));
    }

    /**
     * 管理员看到的清单 —— <b>全量超集</b>。
     *
     * <p>★结构类断言（数量、名字唯一、参数类型、三方 IM 措辞）一律扫这份：
     * 非管理员那份少了 3 个工具，用它扫就等于<b>管理员端点永远不被检查</b> ——
     * 而且这种「覆盖悄悄缩了」不会有任何失败信号，只会让 3 个最敏感的工具脱离护栏。
     */
    private static List<ComponentConfig> adminTools() {
        return build(User.Role.Admin, groupVars(ImChatIdentity.MODE_MENTION));
    }

    private static List<ComponentConfig> build(User.Role role, Map<String, Object> imVars) {
        return new ImChatToolProvider().buildTools(ImChatIdentity.of(ctx("nuwax_im", imVars, role)));
    }

    private static List<String> names(List<ComponentConfig> tools) {
        List<String> out = new ArrayList<>();
        for (ComponentConfig t : tools) {
            out.add(t.getFunctionName());
        }
        return out;
    }

    // ============================== 挂载闸门 ==============================

    @Test
    @DisplayName("★挂载闸门：只有「IM 触发」且「IM 适配已启用」两个条件同时成立才挂")
    void shouldMountRequiresBothImOriginAndEnabledAdapter() {
        assertTrue(provider(true).shouldMount(ctx("nuwax_im", p2pVars(), User.Role.User)));

        assertFalse(provider(true).shouldMount(ctx("web", p2pVars(), User.Role.User)), "非 IM 触发不挂");
        assertFalse(provider(true).shouldMount(ctx(null, p2pVars(), User.Role.User)), "from 缺失不挂");
        assertFalse(provider(false).shouldMount(ctx("nuwax_im", p2pVars(), User.Role.User)),
                "★适配没开就不挂：挂 21 个必然 3612 失败的工具只会让模型把「环境没配」误读成「我做错了」");
        assertFalse(provider(true).shouldMount(null), "ctx 为 null 不能炸 NPE");

        // ★上面这几个 ctx 都【没有 agentConfig】（ctx() 刻意不设），先钉住这个前置，否则判空根本没被走到。
        //   钉的语义是「读不到就退化成不挂，绝不抛」：shouldMount 在每轮对话的主链路上（HybridAgentLoop），
        //   这里 NPE 等于把整轮对话炸掉 —— 一旦抛了，这条测试会以 error 收场，不需要额外包一层。
        AgentContext noConfig = ctx("nuwax_im", p2pVars(), User.Role.User);
        assertNull(noConfig.getAgentConfig(), "前置：这个 ctx 刻意不带 agentConfig");
        assertTrue(provider(true).shouldMount(noConfig), "agentConfig 为 null 时仍要能按 from 判出该挂");
        assertFalse(provider(true).shouldMount(ctxWithPrompt("web", null, null, null)),
                "agentConfig 在、两个提示词都是 null：不许炸，也不该挂");
    }

    @Test
    @DisplayName("★<ENABLE_NUWAX_IM/> 标记：系统提示词/用户提示词/本轮消息任一处命中即挂，且仍要过适配闸门")
    void enableMarkerMountsEvenWithoutImOrigin() {
        ImChatToolProvider p = provider(true);
        String marker = ImChatToolProvider.ENABLE_MARKER;
        assertEquals("<ENABLE_NUWAX_IM/>", marker, "标记字面量变了要同步改提示词模板与技能文档");

        assertTrue(p.shouldMount(ctxWithPrompt("web", "你是助手 " + marker, null, null)), "系统提示词命中");
        assertTrue(p.shouldMount(ctxWithPrompt("web", null, marker + " {{AGENT_USER_MSG}}", null)), "用户提示词命中");
        assertTrue(p.shouldMount(ctxWithPrompt("web", null, null, "帮我发条消息 " + marker)), "本轮消息命中");
        assertTrue(p.shouldMount(ctxWithPrompt("nuwax_im", null, null, null)), "IM 触发不需要标记");

        assertFalse(p.shouldMount(ctxWithPrompt("web", "你是助手", null, "帮我发条消息")), "没标记又不是 IM 触发 ⇒ 不挂");
        assertFalse(provider(false).shouldMount(ctxWithPrompt("web", marker, null, null)),
                "★标记不能绕过 available()：适配没开时挂了照样是 21 个必然失败的工具");
        // 残缺 / 大小写不同的写法一律不算命中：这是个「打开 21 个工具」的开关，宁可漏认不可误认
        for (String almost : List.of("<enable_nuwax_im/>", "<ENABLE_NUWAX_IM>", "ENABLE_NUWAX_IM")) {
            assertFalse(p.shouldMount(ctxWithPrompt("web", almost, null, null)), "不该被当成标记：" + almost);
        }
    }

    @Test
    @DisplayName("available：bean 缺失或查询本身抛异常都按未启用处理（绝不因缺 bean 让 chatbot 起不来）")
    void availableFailsClosed() {
        assertFalse(new ImChatToolProvider().available(), "bean 没装配 ⇒ 未启用");

        ImChatToolProvider p = new ImChatToolProvider();
        IImRpcAvailability boom = mock(IImRpcAvailability.class);
        when(boom.enabled()).thenThrow(new IllegalStateException("boom"));
        p.setImRpcAvailability(boom);
        assertFalse(p.available(), "查询抛异常 ⇒ 按未启用处理，不把异常甩给调用链");
    }

    // ============================== 清单结构 ==============================

    @Test
    @DisplayName("21 个工具（管理员口径=全量）：类型是 Im、名字唯一、模型看到的函数名就是 im_xxx")
    void toolListIsWellFormed() {
        List<ComponentConfig> tools = adminTools();
        assertEquals(21, tools.size(), "工具数量变了要同步改技能文档与 ImChatToolExecutorTest");

        Set<String> names = new HashSet<>();
        Set<Long> targetIds = new HashSet<>();
        for (ComponentConfig t : tools) {
            assertEquals(ComponentTypeEnum.Im, t.getType(), t.getFunctionName());
            assertTrue(names.add(t.getFunctionName()), "functionName 重复：" + t.getFunctionName());
            assertTrue(targetIds.add(t.getTargetId()), "targetId 重复，日志里对不上是哪一个：" + t.getTargetId());
            assertTrue(t.getFunctionName().matches("im_[a-z0-9_]+"), "函数名要能直接给模型：" + t.getFunctionName());
            assertFalse(t.getDescription() == null || t.getDescription().isBlank(),
                    t.getFunctionName() + " 没有描述，模型没法判断该不该调它");

            // ★name 刻意用中文：toolNameExchange 遇到非纯 ASCII 会原样回落到 functionName。
            //   若哪天有人把 name 改成英文，模型看到的工具名就会变，而提示词/技能文档里写的是 im_xxx。
            assertEquals(t.getFunctionName(),
                    ToolInfoExtractor.toolNameExchange(t.getName(), t.getFunctionName()),
                    "模型看到的工具名必须稳定是 " + t.getFunctionName());
            assertFalse(t.getName().matches("[a-zA-Z0-9_]+"),
                    "name 必须不是纯 ASCII，否则 toolNameExchange 会把它当工具名：" + t.getName());

            // ★失败要走 FAILED 分支把真实原因喂回模型；一旦设了这两个，失败会被兜底文案盖成「成功」
            assertNull(t.getExceptionOut(), t.getFunctionName() + " 不该设 exceptionOut");
            assertNull(t.getFallbackMsg(), t.getFunctionName() + " 不该设 fallbackMsg");
        }
    }

    @Test
    @DisplayName("★参数类型不许用 Number / Object / Array_Object（框架会在缺省时塞空容器、或让 Jackson 交出 Double）")
    void argTypesAvoidTheFrameworkTraps() {
        Set<DataTypeEnum> forbidden = Set.of(DataTypeEnum.Number, DataTypeEnum.Object, DataTypeEnum.Array_Object);
        // ★扫全量（管理员口径）：非管理员清单少了 3 个管理端点，用它扫等于这 3 个不再被检查
        for (ComponentConfig t : adminTools()) {
            assertFalse(t.getInputArgs() == null, t.getFunctionName() + " 的 inputArgs 不能是 null");
            Set<String> argNames = new HashSet<>();
            for (Arg arg : t.getInputArgs()) {
                assertFalse(forbidden.contains(arg.getDataType()),
                        t.getFunctionName() + "." + arg.getName() + " 用了被禁的类型 " + arg.getDataType());
                assertTrue(argNames.add(arg.getName()),
                        t.getFunctionName() + " 的参数名重复：" + arg.getName());
                assertFalse(arg.getDescription() == null || arg.getDescription().isBlank(),
                        t.getFunctionName() + "." + arg.getName() + " 没有描述");
                // ★getEnable() 是 null 安全的（null 视作启用），但显式写 false 就等于把参数藏起来
                assertTrue(arg.getEnable(), t.getFunctionName() + "." + arg.getName() + " 被禁用了");
            }
            // 幂等键必须是可选的：要求模型「自己想一个 bizMsgId」等于要求它编一个随机串，
            // 而重试时它并不会回填同一个值 —— 可选 + 执行侧自动生成才是能用的语义。
            for (Arg arg : t.getInputArgs()) {
                if ("bizMsgId".equals(arg.getName())) {
                    assertFalse(arg.isRequire(), t.getFunctionName() + " 的 bizMsgId 必须是可选的");
                }
            }
        }
    }

    @Test
    @DisplayName("★每次 buildTools 都新建实例：ArgExtractUtil 会就地改写 Arg，共享实例会把上一轮的改写渗到下一轮")
    void toolsAreFreshInstancesEveryCall() {
        ImChatToolProvider p = new ImChatToolProvider();
        ImChatIdentity id = ImChatIdentity.of(ctx("nuwax_im", p2pVars(), User.Role.User));
        List<ComponentConfig> first = p.buildTools(id);
        List<ComponentConfig> second = p.buildTools(id);

        assertNotSame(first, second);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            assertNotSame(first.get(i), second.get(i), first.get(i).getFunctionName() + " 的 config 被复用了");
            List<Arg> a = first.get(i).getInputArgs();
            List<Arg> b = second.get(i).getInputArgs();
            assertEquals(a.size(), b.size());
            for (int j = 0; j < a.size(); j++) {
                assertNotSame(a.get(j), b.get(j),
                        first.get(i).getFunctionName() + "." + a.get(j).getName() + " 的 Arg 被复用了");
            }
        }
    }

    @Test
    @DisplayName("★文案里不许出现三方 IM 的名字（工具描述与提醒都要查）")
    void noThirdPartyImIsNamed() {
        // ★工具扫【全量】（管理员口径）：非管理员清单少 3 个管理端点，用它扫等于那 3 个的文案脱离检查。
        //   提醒按身份分叉，所以两种身份的提醒都扫一遍。
        ImChatToolProvider p = new ImChatToolProvider();
        ImChatIdentity adminListen = ImChatIdentity.of(
                ctx("nuwax_im", groupVars(ImChatIdentity.MODE_LISTEN), User.Role.Admin));
        ImChatIdentity plainListen = ImChatIdentity.of(
                ctx("nuwax_im", groupVars(ImChatIdentity.MODE_LISTEN), User.Role.User));
        List<String> offenders = new ArrayList<>();
        for (ComponentConfig t : p.buildTools(adminListen)) {
            check(offenders, t.getFunctionName() + ".name", t.getName());
            check(offenders, t.getFunctionName() + ".description", t.getDescription());
            for (Arg arg : t.getInputArgs()) {
                check(offenders, t.getFunctionName() + "." + arg.getName(), arg.getDescription());
            }
        }
        check(offenders, "reminder(admin)", p.buildReminder(adminListen));
        check(offenders, "reminder(user)", p.buildReminder(plainListen));
        assertTrue(offenders.isEmpty(), "出现了三方 IM 名称：" + offenders);
    }

    // ============================== ★暴露面按身份收窄 ==============================

    /**
     * ★管理端点必须<b>管理员才暴露</b>：非管理员的清单里<b>根本没有</b>这 3 个工具。
     *
     * <p>为什么「挂上去、调用时返 4030」不够：清单是<b>能力广告</b>，模型看见 {@code im_system_notify}
     * 就认为「我能批量给一百人发通知」，于是把它当可用选项去规划 —— 每次换回一个 4030，
     * 它要么反复重试、要么对用户说「我没有权限」这种用户听不懂的话。
     * 更根本的是：把「能不能做」只放在执行侧，等于<b>先让模型学会这个动作的存在</b>。
     *
     * <p>★而且「不暴露」在这条链路上<b>真的关掉了执行路径</b>，不是纯提示词优化：
     * {@code ModelInvoker} 的 {@code FunctionToolCallback} 是逐个 componentConfig 闭包绑定注册的
     * （{@code .builder(name, (in, c) -> componentExecute(modelContext, componentConfig, in, c))}），
     * 本轮 componentConfigs 是唯一注册面 —— 不在清单里的函数名<b>没有回调</b>，
     * 模型即使凭历史轨迹记住名字硬调也落不到 {@code ImChatToolExecutor}。
     *
     * <p>★必须同时钉「少了哪 3 个」和「<b>只</b>少了那 3 个」：只断言 size==18 抓不住
     * 「误删了 im_send_group、又把 im_system_notify 留下」这种组合。
     */
    @Test
    @DisplayName("★管理端点非管理员看不到：私聊/群聊两种触发下都恰好少那 3 个，其余 18 个一个不少")
    void adminEndpointsAreNotAdvertisedToNonAdmins() {
        List<String> adminOnly = ImChatToolProvider.ADMIN_ONLY_TOOLS;
        assertEquals(List.of("im_send_system", "im_system_notify", "im_push_event"), adminOnly,
                "★管理员端点名单变了要同步改：技能文档 SKILL.md（21 − 3 = 18）、"
                        + "ImChatToolExecutorTest.adminGatedToolsRejectNonAdmin、以及 NuwaxImSandboxController 的 requireAdmin");

        Set<String> full = new HashSet<>(names(adminTools()));
        assertEquals(21, full.size(), "管理员口径是全量 21 个");

        // 私聊与群聊两种触发都要收窄：判据是「调用者是不是管理员」，不是「在哪个会话里」
        List<Map<String, Object>> triggers = List.of(p2pVars(), groupVars(ImChatIdentity.MODE_MENTION),
                groupVars(ImChatIdentity.MODE_LISTEN));
        for (Map<String, Object> vars : triggers) {
            List<ComponentConfig> seen = build(User.Role.User, vars);
            List<String> seenNames = names(seen);
            assertEquals(18, seen.size(), "非管理员应看到 21 − 3 = 18 个：" + seenNames);
            for (String admin : adminOnly) {
                assertFalse(seenNames.contains(admin),
                        "★管理端点 " + admin + " 不该出现在非管理员清单里（清单是模型唯一的注册面，"
                                + "「不暴露」同时意味着「调不到」）：" + seenNames);
            }
            // ★反向钉：除那 3 个以外一个都不许少（过滤写成「按名字前缀删」或「删多了」在这里炸）
            for (String name : full) {
                if (!adminOnly.contains(name)) {
                    assertTrue(seenNames.contains(name), "非管理员清单误删了普通工具 " + name + "：" + seenNames);
                }
            }
        }
    }

    @Test
    @DisplayName("管理员身份：21 个全在，3 个管理端点照常暴露（收窄不能把管理员也一起挡住）")
    void adminSeesEveryToolIncludingTheAdminEndpoints() {
        List<String> seen = names(build(User.Role.Admin, p2pVars()));
        assertEquals(21, seen.size(), "管理员应看到全量 21 个：" + seen);
        for (String admin : ImChatToolProvider.ADMIN_ONLY_TOOLS) {
            assertTrue(seen.contains(admin), "管理员必须看得到 " + admin + "：" + seen);
        }
    }

    /**
     * ★「读不到」必须等于「不给」，而且<b>绝不许 NPE</b>：{@code buildTools} 在每轮对话的主链路上
     * （{@code HybridAgentLoop#invokeModel → buildIMTools}），这里抛出去就是炸掉整轮对话。
     *
     * <p>三种「读不到」：identity 本身是 null、ctx 在但 {@code user} 是 null（内部调用/未登录链路）、
     * 以及 {@code from=web} 的标记路径（有登录用户但角色是普通 User）。
     * 全部退化成「非管理员」而不是抛 —— 收窄方向<b>只能往少了收</b>。
     */
    @Test
    @DisplayName("★identity 为 null / user 为 null：退化成非管理员（少给 3 个），绝不抛")
    void unreadableIdentityDegradesToNonAdminInsteadOfThrowing() {
        List<String> nullIdentity = names(assertDoesNotThrow(() -> new ImChatToolProvider().buildTools(null)));
        assertEquals(18, nullIdentity.size(), "identity=null ⇒ 按非管理员处理：" + nullIdentity);
        assertMissing(nullIdentity);

        AgentContext noUser = ctx("nuwax_im", groupVars(ImChatIdentity.MODE_MENTION), User.Role.User);
        noUser.setUser(null);
        List<String> fromNullUser = names(new ImChatToolProvider().buildTools(ImChatIdentity.of(noUser)));
        assertEquals(18, fromNullUser.size(), "读不到 user ⇒ 判不出管理员 ⇒ 不给：" + fromNullUser);
        assertMissing(fromNullUser);
    }

    private static void assertMissing(List<String> seenNames) {
        for (String admin : ImChatToolProvider.ADMIN_ONLY_TOOLS) {
            assertFalse(seenNames.contains(admin), "退化成非管理员时不该带 " + admin + "：" + seenNames);
        }
    }

    /**
     * ★过滤不许改 {@code targetId}：编号由<b>全量清单里的位置</b>决定，管理员与非管理员看到的是同一套。
     *
     * <p>为什么值得单独钉：编号漂移<b>不报错、也不影响调用</b>，只会让日志里的 {@code targetId}
     * 跨轮、跨用户对不上是哪一个工具 —— 排查时拿着一个编号去翻历史，翻到的是别的工具。
     * 「先造全量再按名字摘」天然满足这条；改成「{@code if (admin) add(...)}」就会漂，所以钉住。
     */
    @Test
    @DisplayName("★过滤只摘不改：同名工具的 targetId 在管理员与非管理员清单里完全一致")
    void filteringDoesNotRenumberTargetIds() {
        Map<String, Long> adminIds = new HashMap<>();
        for (ComponentConfig t : adminTools()) {
            adminIds.put(t.getFunctionName(), t.getTargetId());
        }
        for (ComponentConfig t : tools()) {
            assertEquals(adminIds.get(t.getFunctionName()), t.getTargetId(),
                    "★" + t.getFunctionName() + " 的 targetId 随身份漂了：日志里对不上是哪一个工具");
        }
        assertEquals(3, adminIds.size() - tools().size(), "只差那 3 个管理端点");
    }

    private static void check(List<String> offenders, String where, String text) {
        if (text == null) {
            return;
        }
        for (String word : FORBIDDEN_WORDS) {
            if (text.contains(word)) {
                offenders.add(where + " 含「" + word + "」");
            }
        }
    }

    // ============================== IM 提醒 ==============================

    @Test
    @DisplayName("★监听模式必须写明「直接输出的内容用户看不到」并点名 im_send_group（且只提「不必每条都回」，不提「直接输出可见」）")
    void listenModeReminderTellsTheModelItsTextIsInvisible() {
        String reminder = new ImChatToolProvider().buildReminder(
                ImChatIdentity.of(ctx("nuwax_im", groupVars(ImChatIdentity.MODE_LISTEN), User.Role.User)));

        assertTrue(reminder.contains("<im-reminder>"), reminder);
        assertTrue(reminder.contains("IM_MODE: listen"), reminder);
        assertTrue(reminder.contains("im_send_group"), "监听模式必须点名发送工具：" + reminder);
        assertTrue(reminder.contains("不会看到"), "必须说清直接输出的文本到不了用户手上：" + reminder);
        assertFalse(reminder.contains("自动发到") || reminder.contains("会被代发"),
                "★任何模式下都不能说「直接输出会被自动发到会话」——IM 侧 replyForwardEnabled 默认 false，"
                        + "收集到的回复文本被丢弃，只有工具发送的内容用户才看得到：" + reminder);
    }

    @Test
    @DisplayName("★mention / p2p 同样「直接输出用户看不到」，并各点名自己的发送工具")
    void mentionAndP2pReminderTellTheModelItsTextIsInvisible() {
        // 判据：IM 派发的三种模式在这件事上没有区别 —— 直接输出都到不了用户。
        // 这条用例的前身断言的是反面（"自动发到本次会话"），前提写错，已被用户裁定推翻。
        String group = new ImChatToolProvider().buildReminder(
                ImChatIdentity.of(ctx("nuwax_im", groupVars(ImChatIdentity.MODE_MENTION), User.Role.User)));
        assertTrue(group.contains("用户看不到"), "群 mention 必须说清直接输出到不了群里：" + group);
        assertTrue(group.contains("必须用 im_send_group"), "群 mention 必须点名群里唯一能用的发送工具：" + group);
        assertFalse(group.contains("im_send_p2p"), "★群场景不能提私聊工具（执行侧一律拒）：" + group);
        assertFalse(group.contains("自动发到") || group.contains("看得到 ——"),
                "★群 mention 绝不能说「直接输出会被自动发进群」：" + group);

        String p2p = new ImChatToolProvider().buildReminder(
                ImChatIdentity.of(ctx("nuwax_im", p2pVars(), User.Role.User)));
        assertTrue(p2p.contains("对方看不到"), "私聊必须说清直接输出对方收不到：" + p2p);
        assertTrue(p2p.contains("必须用 im_send_p2p"), "私聊必须点名 im_send_p2p：" + p2p);
        assertFalse(p2p.contains("自动发到") || p2p.contains("看得到 ——"),
                "★私聊绝不能说「直接输出会被自动发给对方」：" + p2p);
    }

    @Test
    @DisplayName("★两个发送工具的 description 必须同样说「直接输出用户看不到」——描述是模型决定调不调的唯一文案面")
    void sendToolDescriptionsTellTheModelItsTextIsInvisible() {
        Map<String, String> desc = new HashMap<>();
        for (ComponentConfig t : tools()) {
            desc.put(t.getFunctionName(), t.getDescription());
        }
        String p2p = desc.get("im_send_p2p");
        assertTrue(p2p.contains("对方看不到"), "im_send_p2p 必须说清直接输出对方收不到：" + p2p);
        assertFalse(p2p.contains("会被自动发到") || p2p.contains("不需要再调本工具"),
                "★im_send_p2p 绝不能写「直接输出会被自动发到会话、日常不必再调」：" + p2p);

        String group = desc.get("im_send_group");
        assertTrue(group.contains("用户看不到"), "im_send_group 必须说清直接输出用户看不到：" + group);
        assertFalse(group.contains("会被自动发到") || group.contains("不需要再调本工具"),
                "★im_send_group 绝不能写「直接输出会被自动发到会话」：" + group);
    }

    @Test
    @DisplayName("★回复纪律：执行过程数据（msgId/错误码/工具名…）不许抄给用户，但工具参数照常用")
    void replyDisciplineKeepsExecutionDetailsOutOfTheAnswer() {
        for (String mode : List.of(ImChatIdentity.MODE_LISTEN, ImChatIdentity.MODE_MENTION,
                ImChatIdentity.MODE_P2P)) {
            Map<String, Object> vars = ImChatIdentity.MODE_P2P.equals(mode) ? p2pVars() : groupVars(mode);
            String reminder = new ImChatToolProvider().buildReminder(
                    ImChatIdentity.of(ctx("nuwax_im", vars, User.Role.User)));

            assertTrue(reminder.contains("<reply-discipline>"), reminder);
            // 逐个点名：模型漏抄的正是这些「看起来像结论」的裸 id
            for (String leak : List.of("msgId", "convId", "seq", "bizMsgId", "traceId", "错误码")) {
                assertTrue(reminder.contains(leak), mode + " 的纪律要点名 " + leak + "：" + reminder);
            }
            assertTrue(reminder.contains("除非用户明确索要"), mode + " 必须留「用户要就给」的口子：" + reminder);
            // ★防过界：只写「别出现 msgId」会被读成连参数都不许用，撤回/按 id 查消息会直接瘫掉
            assertTrue(reminder.contains("照常用"), mode + " 必须说明这些值仍要用于调工具/技能：" + reminder);
            // ★正文取自共享常量（技能腿 SandboxAgentClient 用同一份），这里只钉「确实嵌进来了」，
            //   逐条内容的断言在 ImReplyDisciplineTest 里，避免两处各维护一份期望文案。
            assertTrue(reminder.contains(Prompts.IM_REPLY_DISCIPLINE_PROMPT),
                    mode + " 必须内嵌 Prompts.IM_REPLY_DISCIPLINE_PROMPT 本体：" + reminder);
            // ★纪律不是「报喜不报忧」的许可证：失败照样要说，只是用自然语言说
            assertTrue(reminder.contains("失败要如实说"), mode + " 必须要求如实报失败：" + reminder);
        }
    }

    @Test
    @DisplayName("群场景的提醒要带上群 id、发言人、资料库 spaceId，并说明两道闸门")
    void groupReminderCarriesTheContext() {
        String reminder = new ImChatToolProvider().buildReminder(
                ImChatIdentity.of(ctx("nuwax_im", groupVars(ImChatIdentity.MODE_MENTION), User.Role.User)));

        assertTrue(reminder.contains("GROUP_CONV_ID: " + TRIGGER_CONV), reminder);
        assertTrue(reminder.contains("GROUP_MESSAGE_SENDER_ID: 2002"), reminder);
        assertTrue(reminder.contains("GROUP_MESSAGE_SENDER_NAME: 张三"), reminder);
        assertTrue(reminder.contains("spaceId: " + SPACE_ID), reminder);
        assertTrue(reminder.contains("虚拟身份"), reminder);
        assertTrue(reminder.contains("不能在群场景下发私聊"), reminder);
    }

    @Test
    @DisplayName("私聊场景的提醒不带群键，缺键时也不许打出 null 字样")
    void p2pReminderHasNoGroupSection() {
        Map<String, Object> vars = p2pVars();
        // 不放进 imSenderName / SPACE_ID
        String reminder = new ImChatToolProvider().buildReminder(
                ImChatIdentity.of(ctx("nuwax_im", vars, User.Role.User)));

        assertFalse(reminder.contains("GROUP_CONV_ID"), reminder);
        assertFalse(reminder.contains("GROUP_"), "私聊提醒不该出现任何群聊专属键：" + reminder);
        assertFalse(reminder.contains("spaceId"), reminder);
        assertFalse(reminder.contains("null"), "缺键时不能把 null 拼进提示词：" + reminder);
        assertTrue(reminder.contains("IM_CONV_ID: 80001"), reminder);
    }

    /**
     * ★私聊提醒要带「在跟谁说话」：对端 id + 对端名字，且<b>各占一行</b>。
     *
     * <p>为什么钉「各占一行」而不是 contains：技能腿的 {@code buildP2pReminder} 曾经漏了一个
     * {@code "\n"}，产物是「P2P_MESSAGE_SENDER_ID: 1P2P_CONV_ID: 2256…」—— 两个键黏成一行，
     * 而 {@code contains("P2P_MESSAGE_SENDER_ID: 1")} 照样为真、测试全绿。黏行<b>不报错</b>，
     * 只会让模型拿着怪值去调工具，所以断言用整行相等。
     *
     * <p>对端 id 只能取本次运行的登录用户：IM 的 {@code GROUP_SENDER_ID} 是<b>群聊专属</b>键
     * （nuwax-im {@code ImAgentChatService#variableParams} 注释「私聊【不送】」），
     * 私聊派发时 {@code .userId(真人)} 就是发消息的那个人本人 —— 与 {@code ImChatIdentity#selfUserId} 同源。
     */
    @Test
    @DisplayName("★私聊场景的提醒要带对端 id 与对端名字，各占一行，且键名与技能腿一致")
    void p2pReminderCarriesTheCounterpartOneKeyPerLine() {
        Map<String, Object> vars = p2pVars();
        vars.put(ImChatIdentity.KEY_IM_SENDER_NAME, "李四");
        String reminder = new ImChatToolProvider().buildReminder(
                ImChatIdentity.of(ctx("nuwax_im", vars, User.Role.User)));
        List<String> lines = List.of(reminder.split("\n", -1));

        assertTrue(lines.contains("P2P_MESSAGE_SENDER_ID: " + SELF),
                "对端 id = 本次运行的登录用户；黏到别的键后面也算失败。实际提醒：\n" + reminder);
        assertTrue(lines.contains("P2P_MESSAGE_SENDER_NAME: 李四"), "对端名字必须独占一行：" + reminder);
        // ★键名与 SandboxAgentClient#buildP2pReminder 逐字相同：两条腿是同一个智能体的两种跑法，
        //   同一个概念换两个名字只会让模型多一层映射（技能腿的形状在 SandboxImReminderTest 里钉）
        assertTrue(lines.contains("IM_CONV_ID: 80001"), reminder);
    }

    @Test
    @DisplayName("★私聊缺 imSenderName：名字留空，但 SENDER_ID 仍必须在，且不许漏成 null")
    void p2pReminderDegradesSenderNameToEmpty() {
        String reminder = new ImChatToolProvider().buildReminder(
                ImChatIdentity.of(ctx("nuwax_im", p2pVars(), User.Role.User)));
        List<String> lines = List.of(reminder.split("\n", -1));

        assertTrue(lines.contains("P2P_MESSAGE_SENDER_NAME: "), "名字缺失 ⇒ 留空，但键不能整个消失：" + reminder);
        assertTrue(lines.contains("P2P_MESSAGE_SENDER_ID: " + SELF), "id 不依赖名字，缺名字也要在：" + reminder);
        assertFalse(reminder.contains("null"), reminder);
    }

    /**
     * ★没有触发上下文（{@code <ENABLE_NUWAX_IM/>} 标记路径 / imVars 为空）时，提醒<b>不许谎称来源</b>。
     *
     * <p>原来这条路照样输出「本次对话由女娲消息触发」+ {@code IM_MODE} / {@code IM_CONV_ID} /
     * {@code P2P_MESSAGE_SENDER_ID}（填成登录用户本人）—— 四样全是假的。假前提<b>不会报错</b>，
     * 只会让模型以为自己在 IM 会话里、把 web 上的对话当成 IM 消息往外发，
     * 还会以为「本轮输出会被自动代发」而不去调发送工具。
     */
    @Test
    @DisplayName("★imVars 缺失（标记路径）：提醒不许谎称由 IM 触发，也不许给出假的模式/会话/发言人")
    void reminderWithoutImVarsDoesNotFakeAnImTrigger() {
        AgentContext bare = new AgentContext();
        bare.setUserId(SELF);
        String reminder = assertDoesNotThrow(
                () -> new ImChatToolProvider().buildReminder(ImChatIdentity.of(bare)));

        assertTrue(reminder.contains("<im-reminder>"), reminder);
        assertTrue(reminder.contains("</im-reminder>"), "块必须闭合：" + reminder);
        assertFalse(reminder.contains("null"), reminder);
        // ★四样假信息一个都不许出现
        assertFalse(reminder.contains("本次对话由女娲消息"), "不许谎称 IM 触发：" + reminder);
        assertFalse(reminder.contains("IM_MODE"), "没有触发模式：" + reminder);
        assertFalse(reminder.contains("IM_CONV_ID"), "没有触发会话：" + reminder);
        assertFalse(reminder.contains("P2P_MESSAGE_SENDER_ID"), "没有发言人，别把登录用户填进去：" + reminder);
        assertFalse(reminder.contains("自动发到本次会话"), "标记路径下输出走 web 通道，不是代发：" + reminder);
        // 该有的还得有：工具确实挂着，且要求显式确认目标
        assertTrue(reminder.contains("im_ 开头的工具已挂载"), reminder);
        assertTrue(reminder.contains("必须先确认目标会话或收件人"), "没有触发会话 ⇒ 必须要求先问清发给谁：" + reminder);
        assertTrue(reminder.contains(Prompts.IM_REPLY_DISCIPLINE_PROMPT), "回复纪律两条分支都要带：" + reminder);
    }

    @Test
    @DisplayName("★imVars 是空 Map 时同样走「无触发上下文」分支（判据是键值有没有，不是 map 在不在）")
    void reminderTreatsEmptyImVarsAsNoTriggerContext() {
        String reminder = new ImChatToolProvider().buildReminder(
                ImChatIdentity.of(ctx("nuwax_im", new HashMap<>(), User.Role.User)));
        assertFalse(reminder.contains("本次对话由女娲消息"), reminder);
        assertFalse(reminder.contains("IM_CONV_ID"), reminder);
    }

    /**
     * ★缺 {@code GROUP_SENDER_ID} 时不许回落到 {@code AGENT_VIRTUAL_USER_ID}。
     *
     * <p>回落等于告诉模型「在群里说话的人是你自己」：它会把别人的提问当成自己的发言，
     * 回复对象、称呼、以及「该不该回」全跟着错。而这个错<b>不报错</b>。
     * 留空的语义是「不知道谁说的」—— 不知道远好过知道错的。
     */
    @Test
    @DisplayName("★群提醒缺 GROUP_SENDER_ID：留空，绝不回落到智能体自己的虚拟身份")
    void groupReminderLeavesSenderIdEmptyInsteadOfFallingBackToTheAgent() {
        Map<String, Object> vars = groupVars(ImChatIdentity.MODE_MENTION);
        vars.remove(ImChatIdentity.KEY_GROUP_SENDER_ID);
        String reminder = new ImChatToolProvider().buildReminder(
                ImChatIdentity.of(ctx("nuwax_im", vars, User.Role.User)));
        List<String> lines = List.of(reminder.split("\n", -1));

        assertTrue(lines.contains("GROUP_MESSAGE_SENDER_ID: "), "留空，但键要在：" + reminder);
        assertFalse(reminder.contains(String.valueOf(AGENT_VIRTUAL)),
                "不许把智能体自己的虚拟身份当成发言人：" + reminder);
    }
}
