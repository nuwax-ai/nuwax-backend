package com.xspaceagi.agent.core.infra.component.im;

import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentConfig;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.spec.constant.Prompts;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.im.sdk.rpc.IImRpcAvailability;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * chatbot（{@code HybridAgentLoop}）的<b>原生 IM 工具清单</b>。
 *
 * <p>把 {@code NuwaxImSandboxController} 暴露给沙箱技能的那 21 个端点，用它们内部同一批 IM RPC 接口
 * 重新包成 native function-call 工具。两条腿打的是<b>同一组 RPC</b>，差别只在凭据面：
 * 沙箱那条腿是「技能脚本带 AK 打 4sandbox HTTP」，这条腿是「模型在进程内直接 function-call」。
 *
 * <h2>为什么要有这条腿</h2>
 *
 * <p>IM 触发的对话里，大量诉求是「回一句」「看下这条消息」「把某人拉进群」这种一步到位的活。
 * 走沙箱要起容器、装技能、跑脚本，为了发一句话付一整套开销；而 {@code HybridAgentLoop} 的定位
 * 正是「简单任务直接答/直接用绑定的组件工具在进程内执行」。所以这些动作应该是<b>原生工具</b>。
 *
 * <h2>★挂载前置闸门：{@link IImRpcAvailability#enabled()}</h2>
 *
 * <p>{@code IImRpcAvailability} 的 javadoc 说「显式调用的路径不必问，让 disabled() 冒出去」——
 * 那说的是<b>用户点了一下「发消息」</b>。这里不是：挂载工具是<b>能力广告</b>，
 * 在 IM 适配没开的环境里挂 21 个必然以 3612 失败的工具，只会白烧提示词预算、
 * 并让模型把「环境没配」误读成「我做错了」。所以这里按「自动通道」那一档处理：先问一次，没开就整批不挂。
 *
 * <p>★注意 {@code enabled()==true} <b>不</b>代表配置完整；缺 {@code base-url}/{@code secret} 时
 * 仍然返回 {@code true}，缺项要到真正发请求时以 {@code 3612 imRpcNotConfigured} 暴露 —— 那是<b>配错了</b>，
 * 不是<b>没开</b>，需要被看见，不该在这里悄悄吞掉。
 *
 * <h2>★为什么是新枚举 {@link ComponentTypeEnum#Im} 而不是复用 {@code ToolCall}</h2>
 *
 * <p>{@code ToolCall} 在 {@code SandboxAgentClient} 里是「沙箱侧发生了一次工具调用」的<b>展示类型</b>，
 * 由沙箱回传的事件驱动；这里的 {@code Im} 是<b>进程内真的执行了一个组件</b>。两者混用会让
 * 「执行链里出现的 ToolCall」既可能是沙箱回显、也可能是本地执行，排查时无法区分。
 *
 * <h2>身份口径</h2>
 *
 * <p>全部落在 {@link ImChatIdentity}（私聊以用户身份、群里以智能体虚拟身份、撤回按 senderId 判、
 * 排队区只能是智能体主体且 fail-closed）。本类只负责「挂哪些工具、参数长什么样」。
 *
 * <h2>★暴露面按身份收窄：管理员端点非管理员看不到</h2>
 *
 * <p>{@link #ADMIN_ONLY_TOOLS} 那 3 个（以系统名义发言 / 批量系统通知 / 自定义事件推送）
 * <b>只在管理员身份下进入清单</b>。清单是模型唯一的注册面，所以「不暴露」同时意味着「调不到」；
 * 执行侧仍按同一个常量再判一次 {@code requireAdmin()}，两道闸门共用一份名单。
 */
@Slf4j
@Component
public class ImChatToolProvider {

    /**
     * 合成 targetId 起点。工具没有真实的组件行，但 {@code ComponentExecutingDto}/{@code ComponentExecuteResult}
     * 都带 targetId 用于展示与串联，给每个工具一个稳定且互不相同的值，日志里才好对上是哪一个。
     * 取 9 亿区间是为了不与真实组件 id 撞车。
     */
    private static final long TARGET_ID_BASE = 900_000_000L;

    /**
     * 显式开启 IM 工具的标记：出现在系统提示词 / 用户提示词 / 本轮用户消息里任意一处即算开启。
     *
     * <p>用途是「不是由 IM 触发、但也要能收发 IM」的场景（例如在门户里让智能体去发消息）。
     * 匹配是<b>逐字区分大小写</b>的：这是个「一次打开 21 个工具」的开关，宁可漏认不可误认。
     */
    public static final String ENABLE_MARKER = "<ENABLE_NUWAX_IM/>";

    /**
     * ★管理员专属工具：<b>非管理员连工具面都不给</b>（不是「给了但调用时拒」）。
     *
     * <p>为什么「执行时才拒」不够：工具清单是<b>能力广告</b>，模型看到 {@code im_system_notify}
     * 就会认为「我有批量给一百人发通知的能力」，于是在规划里把它当成一个可用选项 ——
     * 每次调用换回一个 4030，它要么反复重试、要么对用户说「我没有权限」这种用户听不懂的话。
     * 而更根本的是：<b>把「能不能做」的判断只放在执行侧，等于让模型先学会这个动作的存在</b>。
     * 管理员端点的三个动作（以系统名义发言、批量通知、无法归因的事件推送）都属于
     * 「不该让非管理员场景的智能体知道它存在」的那一类。
     *
     * <p>★这不是纯提示词优化，<b>它真的关掉了执行路径</b>：{@code ModelInvoker} 的
     * {@code FunctionToolCallback} 是<b>逐个 componentConfig 闭包绑定</b>注册的
     * （{@code .builder(name, (input, context) -> componentExecute(modelContext, componentConfig, ...))}），
     * 本轮 {@code componentConfigs} 是唯一的注册面 —— 不在清单里的函数名<b>没有对应回调</b>，
     * 模型即使凭历史轨迹记住名字硬调，也落不到 {@code ImChatToolExecutor}。
     *
     * <p>★执行侧（{@link ImChatToolExecutor#dispatch}）仍然按<b>同一个常量</b>再判一次
     * {@code requireAdmin()}：两道闸门共用一份名单，所以不存在「清单说不是管理员端点、
     * 执行侧却当它是」的漂移；加第四个管理员端点时只改这个常量，两边同时生效。
     */
    public static final List<String> ADMIN_ONLY_TOOLS = List.of(
            "im_send_system", "im_system_notify", "im_push_event");

    private IImRpcAvailability imRpcAvailability;

    /**
     * ★setter 注入 + {@code required=false}：IM 适配（{@code app-platform-im-infra}）不在本模块的依赖里，
     * bean 由 bootstrap 装配。装配不上（例如某个不含 im 的启动包）时按「没开」处理，绝不让 chatbot 起不来。
     */
    @Autowired(required = false)
    public void setImRpcAvailability(IImRpcAvailability imRpcAvailability) {
        this.imRpcAvailability = imRpcAvailability;
    }

    /**
     * IM RPC 适配是否启用（bean 缺失 / 查询本身抛异常都按未启用处理）。
     */
    public boolean available() {
        if (imRpcAvailability == null) {
            return false;
        }
        try {
            return imRpcAvailability.enabled();
        } catch (Throwable t) {
            log.warn("[IM-TOOL] 查询 IM 适配启用状态失败，按未启用处理（不挂载 IM 工具）: {}", t.getMessage());
            return false;
        }
    }

    /**
     * 构建<b>本次身份能看到的</b> IM 工具。
     *
     * <p>★每次调用都<b>新建</b> {@link ComponentConfig} 与 {@link Arg} 实例（与 {@code buildSandboxTools()} 一致）：
     * {@code ArgExtractUtil.setArgDefaultValue} 会就地改写 Arg（bindValueType / bindValue），
     * 共享实例会让上一轮的改写渗到下一轮。
     *
     * <h2>★管理员专属工具：非管理员直接从清单里摘掉</h2>
     *
     * <p>{@link #ADMIN_ONLY_TOOLS} 那 3 个只在 {@code identity.isAdmin()} 为真时出现（理由见该常量的 javadoc）。
     * 实现上是<b>先造全量、再按名字过滤</b>，不是在 {@code if (admin)} 里逐个 add：
     * 这样 {@code targetId} 由全量清单里的位置决定，<b>管理员与非管理员看到的是同一套编号</b> ——
     * 日志里的 {@code targetId} 才能跨轮、跨用户对得上是哪一个工具（编号漂移不会报错，只会让排查时对不上号）。
     *
     * <p>★{@code identity} 为 null 时退化成 {@link ImChatIdentity#of} {@code (null)}（全程判空的身份）：
     * 这条链路上「读不到」必须等于「不给」，而不是 NPE —— {@code buildTools} 在每轮对话的主链路上
     * （{@code HybridAgentLoop#invokeModel}），这里抛出去就是炸掉整轮。
     *
     * @param identity 本次会话的身份口径（决定哪些工具挂得上）
     */
    public List<ComponentConfig> buildTools(ImChatIdentity identity) {
        ImChatIdentity id = identity == null ? ImChatIdentity.of(null) : identity;
        List<ComponentConfig> tools = allTools();
        if (!id.isAdmin()) {
            tools.removeIf(t -> ADMIN_ONLY_TOOLS.contains(t.getFunctionName()));
        }
        log.info("[IM-TOOL] 挂载 {} 个原生 IM 工具 groupScenario={} mode={} agentVirtualUserId={} admin={}"
                        + "（管理员专属 {} 个 → {}）",
                tools.size(), id.isGroupScenario(), id.getMode(),
                id.getAgentVirtualUserId(), id.isAdmin(), ADMIN_ONLY_TOOLS.size(),
                id.isAdmin() ? "已暴露" : "未暴露");
        return tools;
    }

    /**
     * 全量清单（21 个），<b>不做任何身份过滤</b> —— 谁看得到哪些由 {@link #buildTools} 决定。
     *
     * <p>刻意 private static：它是「有哪些工具、参数长什么样」的唯一定义处，
     * 对外只暴露按身份过滤后的那一份，避免调用方绕过闸门拿到全量清单。
     */
    private static List<ComponentConfig> allTools() {
        List<ComponentConfig> tools = new ArrayList<>();
        int seq = 0;

        // ============ 发送（身份敏感，闸门见 ImChatIdentity） ============
        tools.add(tool(++seq, "im_send_p2p", "发私聊消息",
                """
                给某个用户发一条私聊消息。默认发文本，也支持发图片、文件、语音、视频与富文本。
                【什么时候调】① IM 私聊里回话 —— 你这一轮直接输出的内容对方看不到，要说给对方听的话只能靠本工具送出去；② 用户明确要你「告诉某人某件事」；③ 长任务开始前先发一句进度说明；④ 要把一份文件或一张图发给他。
                【什么时候不用调】只有「确定不需要对方收到任何东西」时才可以不调（例如这一轮只是内部推理、或你打算只把结论留给调用方）。★日常回话必须调：漏了不会有任何报错，对方就是收不到你的回复。
                ★【什么时候调不了】本次对话由群消息触发时一律被拒（群场景的授权范围只有那个群）。要通知群里的某个人，请改用 im_send_group 并在 mentionUserIds 里 @ 他。
                【收件人怎么定】用平台用户 ID（数字，不是昵称/手机号）。私聊场景的对端就是 P2P_MESSAGE_SENDER_ID；同群的人用 im_list_members 查；查不到就问用户，不要猜一个 id。
                【msgType 怎么选】不传 = 发文本。可选值：text 纯文本 / rich_text 富文本 / image 图片 / file 文件 / audio 语音 / video 视频。★卡片与居中系统提示不在这里 —— 它们各有专门工具（im_send_card、im_send_system），不要用本工具去凑。
                【每种类型给什么】文本类（text / rich_text）：text="正文"。媒体类（image / file / audio / video）：url="可公开访问的完整地址"（这些类型必填）、fileName="9月报表.xlsx"（接收方看到的文件名，发文件时务必给）、size=字节数（可选）。地址不要自己编，用 im_list_messages 里取到的、或你自己上传后拿到的真实地址。
                【示例】P2P_MESSAGE_SENDER_ID 为 6 时 —— 发文本：toUserId=6、text="报表已经生成好了，稍后发你"。
                发文件：toUserId=6、msgType="file"、url="https://…/report.xlsx"、fileName="9月报表.xlsx"、size=20480。
                发图片：toUserId=6、msgType="image"、url="https://…/chart.png"。
                【bodyJson（一般用不到）】它让你直接给出完整消息体 JSON（例如 rich_text 要带 segments 或附件清单）。一旦给了 bodyJson，上面的 text/url/fileName/size 就都不生效 —— 不确定该类型的 body 结构时不要用它。
                【身份与返回】收件人是本次对话的用户本人时，消息以你（智能体）的身份发出（对方看到是智能体发的）；发给其他用户时以用户本人的身份发出（对方看到是用户自己发的）。返回 {ok, bizMsgId, data}，data 里是这条消息的 msgId 与发送时间；失败不会返回「发送失败」这种文案，而是直接报错。
                """,
                idArg("toUserId", "收件人的平台用户 ID（数字）。私聊场景取 P2P_MESSAGE_SENDER_ID，同群的人用 im_list_members 查", true),
                textArg(false),
                str("msgType", "消息类型：不传=text。可选 text / rich_text / image / file / audio / video；发卡片用 im_send_card、发系统提示用 im_send_system。text / rich_text 时正文放 text；其余类型正文换成 url", false),
                str("url", "媒体类型（image / file / audio / video）的文件地址，这几种类型必填。填可公开访问的完整地址", false),
                str("fileName", "媒体类型的文件名（发文件时务必给），接收方看到的就是它", false),
                num("size", "媒体类型的文件大小（字节，可选）", false),
                str("bodyJson", "一般不用。完整消息体 JSON（如 rich_text 的 segments 或附件清单），给了它以它为准", false),
                str("bizMsgId", "幂等键。同一条消息重试时必须回填上一次用的值，避免重复发送；不填则自动生成", false)));

        tools.add(tool(++seq, "im_send_group", "发群消息",
                """
                往一个群里发一条消息。默认发文本，也支持发图片、文件、语音、视频与富文本；可以 @ 人、可以引用回复某条消息。
                ★【什么时候必须调】只要你想让群里的人看到任何内容 —— 你这一轮直接输出的文本用户看不到，只有本工具发出去的消息才会进群；漏了不会有任何报错，群里就是空的。监听模式（IM_MODE: listen）同样如此，区别只是那种模式不必每条消息都回；一旦决定要回，仍然只能用本工具。
                【其他该调的时候】需要把结论、进度、执行结果主动推到群里；需要 @ 某个人让他看到；需要引用回复某条消息；需要把文件或图片发到群里 —— 后几件事只有本工具能做到。
                【目标怎么定】由群消息触发时省略 convId（默认就是触发本次对话的那个群，也是唯一允许的目标）；由私聊或门户触发时必须显式给 convId，可先用 im_list_conversations 查。
                【msgType 怎么选】不传 = 发文本。可选值：text 纯文本 / rich_text 富文本 / image 图片 / file 文件 / audio 语音 / video 视频。★卡片用 im_send_card、居中系统提示用 im_send_system，不要用本工具去凑。
                【每种类型给什么】文本类（text / rich_text）：text="正文"。媒体类（image / file / audio / video）：url="可公开访问的完整地址"（这些类型必填）、fileName="9月报表.xlsx"（接收方看到的文件名，发文件时务必给）、size=字节数（可选）。地址不要自己编，用 im_list_messages 里取到的真实地址。
                【示例】在触发的群里回复提问者（提问者 id 见 GROUP_MESSAGE_SENDER_ID）：text="这个我查一下，5 分钟后给你结果"、replyToMsgId=刚才那条提问的 msgId、mentionUserIds=[提问者 id]。
                把结论推给全群：text="部署完成，v1.2.3 已上线"、mentionAll=true。
                把文件发到群里：msgType="file"、url="https://…/report.xlsx"、fileName="9月报表.xlsx"、mentionUserIds=[提问者 id]。
                【约束】正文按纯文本渲染（不支持 Markdown 语法，链接直接把地址写出来即可）；@ 与引用回复只有本工具能做；★发到非触发群会被拒；引用回复用的 msgId 必须属于本群。
                【返回】{ok, bizMsgId, data}，data 里是这条消息的 msgId 与发送时间；失败直接报错，不会静默。
                """,
                idArg("convId", "目标群会话 ID。群消息触发时可省略（默认=本次触发的群，也是唯一允许的目标）；私聊触发时必须给，可用 im_list_conversations 查", false),
                textArg(false),
                str("msgType", "消息类型：不传=text。可选 text / rich_text / image / file / audio / video；发卡片用 im_send_card、发系统提示用 im_send_system。text / rich_text 时正文放 text；其余类型正文换成 url", false),
                str("url", "媒体类型（image / file / audio / video）的文件地址，这几种类型必填。填可公开访问的完整地址", false),
                str("fileName", "媒体类型的文件名（发文件时务必给），接收方看到的就是它", false),
                num("size", "媒体类型的文件大小（字节，可选）", false),
                str("bodyJson", "一般不用。完整消息体 JSON（如 rich_text 的 segments 或附件清单），给了它以它为准", false),
                idArg("replyToMsgId", "要引用回复的消息 ID（本群内的 msgId，如触发本次对话的那条消息）", false),
                arr("mentionUserIds", "要 @ 的平台用户 ID 列表（id 见 GROUP_MESSAGE_SENDER_ID 或 im_list_members）", false),
                bool("mentionAll", "是否 @ 所有人（true 时全体成员都会收到提醒）", false),
                str("bizMsgId", "幂等键。同一条消息重试时必须回填上一次用的值；不填则自动生成", false)));

        tools.add(tool(++seq, "im_send_card", "发互动卡片",
                """
                往一个会话里发一张卡片消息（标题 + 描述 + 可选图标 + 可选键值对明细 + 可点击的跳转链接）。卡片比普通文字气泡更醒目，适合「任务完成」「审批结果」「数据摘要」这类需要一眼看懂的结论。以你（智能体）的身份发出，收件人看到的是智能体发的。
                【什么时候调】① 结论或交付物需要一个可点击的入口（文档、报告、页面）；② 用户要一眼就能看到的结果，而不是一段普通消息；③ 结论里有几个成对的数字（单号、金额、条数）值得分行列出。
                【目标怎么定】convId 可省略（默认=本次触发的会话）。要发给不在本会话里的某个人，先用 im_get_or_create_p2p 拿到与他的会话 ID，再把那个 ID 当作 convId。
                【示例】title="日报已生成"、desc="2026-09-29 日报，含 3 条待办"、actionUrl="https://…/doc/xxxx"。
                只发结论不发链接时省略 actionUrl：title="数据核对完成"、desc="共 120 条，异常 0 条"。
                要分行列明细时加 fieldsJson：[{"label":"工单号","value":"WO-20260929-007"},{"label":"状态","value":"已完成"}]。
                要在标题旁带个图标时加 iconUrl（一个可公开访问的图片地址，如 "https://…/logo.png"）。
                【约束】title 必填，它是会话列表里唯一可见的字段（≤128 字），不要写成「通知」这种看不出内容的词；desc 建议一两句话（≤512 字）；fieldsJson 建议 2~5 条（多了卡片会很长），label/value 都写字符串、别写成数字（写成 007 会变 7）；★卡片上没有可点击按钮，唯一的点击目标是 actionUrl，需要用户做选择时请在描述里说清让他回哪一句。
                【返回】{ok, bizMsgId, data}，data 里是这条消息的 msgId 与发送时间。
                """,
                idArg("convId", "目标会话 ID，可省略（默认=本次触发的会话）；发给某个人时用 im_get_or_create_p2p 拿到的会话 ID", false),
                str("title", "卡片标题（必填，≤128 字）。它会显示在会话列表里，要能一眼看出是什么事", true),
                str("desc", "卡片描述正文（≤512 字），写结论与关键数字", false),
                str("iconUrl", "标题旁的图标地址（可省略）：一个可公开访问的图片 URL", false),
                str("fieldsJson", "键值对明细区（可省略）：JSON 数组文本，形如 [{\"label\":\"工单号\",\"value\":\"007\"}]。label 与 value 都要给，都用字符串写（写成数字会让 007 变成 7）", false),
                str("actionUrl", "点击卡片要打开的完整网址（≤512 字，需带 http/https）", false),
                str("bizMsgId", "幂等键。同一条消息重试时必须回填上一次用的值；不填则自动生成", false)));

        // ============ ★管理员专属：非管理员场景整段不出现在清单里（见 ADMIN_ONLY_TOOLS） ============
        // 新增管理员端点时【只改 ADMIN_ONLY_TOOLS 一处】：清单过滤与执行侧闸门都由那个常量驱动，
        // 两边不会漂移。描述里的「★仅管理员可用」刻意保留 —— 它如今是给管理员看的冗余提醒，
        // 但万一哪天过滤被改坏，这句仍是模型手上唯一的线索。
        tools.add(tool(++seq, "im_send_system", "发系统消息",
                """
                往一个会话或某个用户发一条系统提示 —— 它以居中提示条的样式出现，不是普通消息气泡，收件人看到的是「系统」而不是某个人发的。★仅管理员可用。
                【什么时候调】要让所有人知道一条不属于任何人的规则性提示：本群从今天起禁言、系统将在 22:00 维护、这条会话已归档。
                【不要用它来】代替你（智能体）自己的回复 —— 那种内容用 im_send_group / im_send_p2p，收件人才知道是谁在说话；也不要拿它当批量通知用（那件事是 im_system_notify）。
                【目标怎么定】convId（发到整个会话）与 toUserId（只发给某个人）二选一，两个都不给会报错。
                【示例】往本次触发的会话发一条：convId=本次触发的会话 ID、text="本群将在 22:00 进行维护，预计 10 分钟"。
                【返回】{ok, bizMsgId, data}，data 含 msgId 与发送时间。
                """,
                idArg("convId", "目标会话 ID（与 toUserId 二选一，都不给会报错）：整条会话的人都看到", false),
                idArg("toUserId", "目标用户 ID（与 convId 二选一，都不给会报错）：只发给这一个人", false),
                textArg(true),
                str("bizMsgId", "幂等键。同一条消息重试时必须回填上一次用的值；不填则自动生成", false)));

        // ★只暴露 msgType=text（★DEV-298 起白名单四型 text/system/card/rich_text，本工具固定 text
        //   纯文本气泡，与平台通知链路 NotifyMessageApplicationServiceImpl#pushImNotify 同拍板）：
        //   它的 body 形状 {"text": ...} 是已经在线上跑通的那一个。
        //   通知卡（card）/ 富文本（rich_text）的 body 契约这里没有可验证的依据，与其让模型去猜字段名，
        //   不如不挂 —— 猜错的代价是「发出去一条空卡片 / 空气泡」，而不是一个可读的报错。
        tools.add(tool(++seq, "im_system_notify", "批量系统通知",
                """
                给一批用户推送系统通知：消息写进每个人的通知中心并计入未读红点（会进历史、可删除），不是往聊天里发一条消息。★仅管理员可用。
                【什么时候调】把一条结论推给「某几个人」而不是「某个会话里说话的人」时：定时任务的产出、审批结果、任务完成提醒。
                【与相似工具的区别】往会话里看到的是 im_send_group；给单个用户发一条会出现在聊天里的是 im_send_p2p；本工具只进通知中心。
                【示例】把跑完的日报推给 3 个人：toUserIds=[1,6,9]、text="今日 AI 简报已生成，共 12 条"。
                【约束】★单次最多 100 人，超出整批拒绝（不会静默截断，人多请分批）。★失败是逐条的：个别人投不到时整体仍算成功，必须读返回里的 failed 明细再下结论，不要只说「已发送」。
                【返回】{ok, bizMsgId, data}，data 含成功人数、失败明细（failed 为空才代表全员投到）。
                """,
                arr("toUserIds", "接收人的平台用户 ID 列表，单次最多 100 人（超出整批拒绝，请分批）", true),
                textArg(true),
                str("bizMsgId", "幂等键。同一条消息重试时必须回填上一次用的值；不填则自动生成", false)));

        tools.add(tool(++seq, "im_push_event", "推送自定义事件",
                """
                给一批用户或某个会话推一个自定义事件：它本身不带可见的消息正文，由客户端自己决定怎么呈现（弹提醒、刷新列表都取决于客户端有没有实现这个事件类型）。★仅管理员可用。
                【什么时候调】只在用户明确要求「推一个事件/通知某端刷新」时用；用户要的是「让某人看到一句话」时请用 im_send_p2p / im_send_group / im_system_notify。
                【示例】通知客户端某任务已完成：eventType="agent.task.completed"、userIds=[6]、payloadJson="{"taskId":123}"。
                【约束】userIds 与 convId 必须给其中一个（要两者都覆盖就调两次）。★「成功」不等于「送达」：收件人没开客户端就收不到、事件不落库、没有补偿重发。★事件发出后无法撤回——收件人已经收到的东西撤不掉，所以只在用户真的要求时用。
                【返回】{ok, bizMsgId, data}，data 含目标用户数、在线设备数与实际发出的帧数；在线设备数为 0 是正常结果（没人在线），不代表调用失败。
                """,
                str("eventType", "事件类型标识（业务自定义，客户端按它决定怎么处理，如 agent.task.completed）", true),
                arr("userIds", "接收人的平台用户 ID 列表（与 convId 二选一；要两者都覆盖就调两次）", false),
                idArg("convId", "目标会话 ID：该会话全体成员都会收到（与 userIds 二选一）", false),
                str("payloadJson", "事件负载，JSON 对象文本，如 {\"taskId\":123}（不带可见正文）", false),
                str("bizMsgId", "幂等键。同一条消息重试时必须回填上一次用的值；不填则自动生成", false)));

        tools.add(tool(++seq, "im_revoke", "撤回消息",
                """
                撤回一条消息。
                【什么时候调】用户说「把刚才那条撤了」；或你自己发现刚发出去的内容有问题（发错群、发错人、写错了）——这种情况应当立刻撤，不要等用户来问。
                ★【只能撤自己发的】只能撤回以本次身份发出的消息（你作为智能体发的，或本次用户本人发的）；别人的消息撤不了。且受撤回时限约束（默认 24 小时）。
                【msgId 从哪来】你刚发出去那条的 ID 就在发送返回的 data.msgId 里；本次会话里其他消息用 im_list_messages 查。
                【示例】撤掉刚发错的一条：convId=本次触发的会话、msgId=上一步发送返回的 msgId、reason="内容有误，重发"。
                ★【撤回的后果】群里会留下「某某撤回了一条消息」的提示，做不到无痕；已经被人看到的内容也不会因此消失，重要更正请再补发一条说明。重复撤回同一条是幂等成功，不会报错。
                【返回】{ok, bizMsgId, data}。
                """,
                idArg("convId", "消息所在会话 ID，可省略（默认=本次触发的会话）", false),
                idArg("msgId", "要撤回的消息 ID（你刚发的那条见发送返回的 data.msgId，其他消息用 im_list_messages 查）", true),
                str("reason", "撤回原因（会记在平台上，用于事后追溯）", false),
                str("bizMsgId", "幂等键。同一条消息重试时必须回填上一次用的值；不填则自动生成", false)));

        // ============ 消息查询 ============
        tools.add(tool(++seq, "im_get_message", "查消息详情",
                "按会话 ID + 消息 ID 查一条消息的完整内容（含发送者、时间、正文、撤回状态）。",
                idArg("convId", "消息所在会话 ID，可省略（默认=本次触发的会话）", false),
                idArg("msgId", "消息 ID", true)));

        tools.add(tool(++seq, "im_read_counts", "查已读人数",
                "批量查若干条消息的已读/未读人数。单次最多 200 条。",
                idArg("convId", "消息所在会话 ID，可省略（默认=本次触发的会话）", false),
                arr("msgIds", "消息 ID 列表，单次最多 200 条", true)));

        tools.add(tool(++seq, "im_list_messages", "按时间范围查消息",
                "拉取某个会话在指定时间范围内的消息。翻页只认返回里的 hasMore，"
                        + "把上一次返回的 nextSeq 原样回填到 afterSeq。",
                idArg("convId", "会话 ID，可省略（默认=本次触发的会话）", false),
                idArg("startTime", "起始时间（毫秒时间戳，含）", false),
                idArg("endTime", "结束时间（毫秒时间戳，含）", false),
                idArg("afterSeq", "从该序号之后开始取（翻页游标）", false),
                num("limit", "单页条数", false)));

        // ============ 会话 ============
        tools.add(tool(++seq, "im_list_conversations", "查会话列表",
                "以本次身份列出其参与的会话（群 + 私聊）。翻页用返回的 nextCursor 回填 cursor。",
                str("cursor", "翻页游标，取上一次返回的 nextCursor", false),
                num("limit", "单页条数，最多 100", false)));

        tools.add(tool(++seq, "im_conversation_detail", "查会话详情",
                "查一个会话的详情（名称、公告、类型、成员数等）。",
                idArg("convId", "会话 ID，可省略（默认=本次触发的会话）", false)));

        tools.add(tool(++seq, "im_list_contacts", "查联系人",
                "以本次身份列出其联系人。", new Arg[0]));

        tools.add(tool(++seq, "im_list_members", "查群成员",
                "列出群成员。单次最多 100 条；群规模超过 500 人时只返回前 100 且 degraded=true。",
                idArg("convId", "群会话 ID，可省略（默认=本次触发的会话）", false),
                num("limit", "单页条数，最多 100", false)));

        tools.add(tool(++seq, "im_get_or_create_p2p", "获取或创建私聊会话",
                "拿到与某个用户的私聊会话（不存在则创建），返回会话 ID。",
                idArg("toUserId", "对方的平台用户 ID", true)));

        tools.add(tool(++seq, "im_create_group", "建群",
                "创建一个群并拉入初始成员。clientGroupId 是业务幂等键：同一个值重复调用只会建出一个群。",
                str("name", "群名称", true),
                arr("memberUserIds", "初始成员的平台用户 ID 列表", true),
                str("notice", "群公告", false),
                str("avatar", "群头像 URL", false),
                str("clientGroupId", "业务幂等键，重试时必须回填同一个值", false),
                num("maxMember", "群人数上限（软上限 2000，硬上限 5000）", false),
                str("bizMsgId", "幂等键。重试时必须回填上一次用的值；不填则自动生成", false)));

        tools.add(tool(++seq, "im_update_digest", "更新会话摘要",
                "更新某个会话的摘要文本（会话列表里显示的那行提示）。",
                idArg("convId", "会话 ID，可省略（默认=本次触发的会话）", false),
                str("digest", "新的摘要文本", true)));

        // ============ 群成员增删 ============
        tools.add(tool(++seq, "im_add_members", "添加群成员",
                "往群里加人。单次最多 50 人。★新增人员前应先确保其已加入关联的平台空间。",
                idArg("convId", "群会话 ID，可省略（默认=本次触发的会话）", false),
                arr("userIds", "要加入的平台用户 ID 列表，单次最多 50 人", true),
                str("nickName", "被加入者在本群的昵称", false),
                num("agentMode", "以智能体身份加入时的模式", false),
                str("bizMsgId", "幂等键。重试时必须回填上一次用的值；不填则自动生成", false)));

        tools.add(tool(++seq, "im_remove_members", "移除群成员",
                "把人从群里移出。单次最多 50 人。要移除的人不在群里时不会报错。",
                idArg("convId", "群会话 ID，可省略（默认=本次触发的会话）", false),
                arr("userIds", "要移出的平台用户 ID 列表，单次最多 50 人", true),
                str("reason", "移除原因", false),
                str("bizMsgId", "幂等键。重试时必须回填上一次用的值；不填则自动生成", false)));

        // ============ 排队区（只能是智能体主体） ============
        tools.add(tool(++seq, "im_pending_list", "查排队中的新消息",
                "查看本次运行开始之后、在同一个会话里追加进来的新消息（避免长任务期间漏掉用户的新话）。"
                        + "★取到消息后必须调用 im_pending_clear 清除，否则下一轮会重复处理。",
                idArg("convId", "会话 ID，可省略（默认=本次触发的会话）", false)));

        tools.add(tool(++seq, "im_pending_clear", "清除已处理的排队消息",
                "把已经读取处理过的排队消息从队列中清除。必须传 im_pending_list 返回的那些 msgId。",
                idArg("convId", "会话 ID，可省略（默认=本次触发的会话）", false),
                arr("msgIds", "要清除的消息 ID 列表（取自 im_pending_list 的返回）", true)));

        return tools;
    }

    /**
     * 本次会话的 IM 上下文提醒（拼进 user prompt）。
     *
     * <h2>★三种模式都要写「直接输出的内容用户看不到」</h2>
     *
     * <p>IM 派发进来的这一轮，<b>智能体直接输出的文本到不了用户</b> —— 无论 mention、p2p 还是 listen，
     * 只有经 {@code im_} 工具发出去的消息才会进会话。依据（nuwax-im）：
     * {@code ImAgentDispatchProperties#replyForwardEnabled} <b>默认 false</b>，
     * {@code ImAgentChatService#settleAndForward} 第⑦段「收集文本但<b>不</b>代发」（口径⑥：做成配置、默认丢弃）。
     * {@code onEvent = AgentReplyCollector.compose(latch, collector)} 只说明<b>文本被收集</b>，
     * 不等于<b>被代发</b> —— 别只看 collector 存在就断定「直接输出能到用户手上」。
     * 措辞与 {@code SandboxAgentClient} 两条腿的提醒一致，两条腿行为不劈叉。
     *
     * <p>★这三种模式曾经被写成「mention / p2p 的输出会被自动发到本次会话，不必再调工具」，
     * 与上面这条事实<b>恰好相反</b>：模型于是不再调发送工具，而提示词写错<b>不会报错</b> ——
     * 用户等不到任何回复，日志里也没有异常。这是本方法唯一的「静默且朝危险侧」的失败模式，勿再改回去。
     *
     * <p>★判监听用 {@code imMode}，不用 {@code IS_GROUP_MESSAGE}：后者只说「是群消息」，
     * 群里的 mention 模式同样为真。监听与 mention 的差别在「要不要每条都回」，
     * 不在「能不能直接输出」—— 两者都得调工具。
     *
     * <h2>★「怎么回话」这段为什么分三支而不是两支</h2>
     *
     * <p>三支的区别是<b>该调哪个工具</b>，不是「要不要调」：{@code listen}（群里，不必每条都回）、
     * 群 mention（要回 ⇒ {@code im_send_group}）、p2p（要回 ⇒ {@code im_send_p2p}）。
     * 合并成两支就会写错工具名：执行侧 {@code ImChatToolExecutor#sendP2p} 的
     * {@code requireP2pSendIsAllowed} 对群场景一律拒，群场景下让模型用 {@code im_send_p2p}
     * 等于让它撞一次 {@code 4030} 才学会。
     *
     * <h2>★两分支：有触发上下文 / 没有</h2>
     *
     * <p>{@link ImChatIdentity#isImVarsPresent()} 为假时（典型是 {@code <ENABLE_NUWAX_IM/>} 标记路径：
     * 工具挂上了，但本轮不是 IM 派发进来的）走一份<b>独立</b>的提醒。判据只能是 {@code imVarsPresent}，
     * 不能用 {@code selfUserId != null} —— 后者会回落到 {@code ctx.getUserId()}，任何有登录态的上下文都成立。
     *
     * <p>这条分支存在的意义是<b>不谎称来源</b>：没有 imVars 就不知道触发会话、不知道发言人，
     * 也就谈不上「要说的话会进某个 IM 会话」。照原样输出那些键，模型会拿着假前提去发消息 ——
     * 提示词写错<b>不会报错</b>，只会让行为悄悄跑偏，这是本方法唯一「静默且方向朝危险侧」的失败模式。
     */
    public String buildReminder(ImChatIdentity identity) {
        StringBuilder sb = new StringBuilder("\n<im-reminder>\n");
        if (!identity.isImVarsPresent()) {
            // ★没有触发上下文（典型是 {@code <ENABLE_NUWAX_IM/>} 标记路径：工具挂上了，但本轮不是 IM 派发进来的）。
            //   原来这条路照样输出「本次对话由女娲消息触发」+ IM_MODE / IM_CONV_ID / P2P_MESSAGE_SENDER_ID，
            //   四样全是假的（senderId 会填成登录用户本人）。假前提【不会报错】，只会让模型以为自己在 IM 会话里、
            //   把当前对话当成 IM 消息往外发。所以这里单独给一份不谎称来源的提醒：
            //   措辞刻意是「没有携带触发上下文」而不是「不是 IM 触发的」—— 后者在
            //   「from=nuwax_im 但 imVars 意外为空」这条退化路径上同样会变成假话。
            sb.append("本次对话没有携带女娲消息（nuwax-im）的触发上下文（触发会话、发言人都不明），")
                    .append("但 im_ 开头的工具已挂载，你可以直接调用它们收发消息、管理会话与群成员。\n");
            sb.append("<system-reminder>没有「本次触发的会话」可自动回复：你输出的文本走原本的会话通道，用户看得到；")
                    .append("要往消息会话里发东西必须显式调用 im_ 工具，并且必须先确认目标会话或收件人 —— ")
                    .append("拿不准就问用户，不要凭猜测发送。</system-reminder>\n");
            sb.append(Prompts.IM_REPLY_DISCIPLINE_PROMPT);
            sb.append("</im-reminder>\n\n");
            return sb.toString();
        }
        sb.append("IM_CONV_ID: ").append(identity.getConvId() == null ? "" : identity.getConvId()).append("\n");
        if (identity.isGroupScenario()) {
            sb.append("IM_MODE: ").append(nullToEmpty(identity.getMode())).append("\n");
            sb.append("GROUP_CONV_ID: ").append(identity.getGroupConvId() == null ? "" : identity.getGroupConvId()).append("\n");
            // ★缺 GROUP_SENDER_ID 时原来回落到 AGENT_VIRTUAL_USER_ID —— 那等于告诉模型
            //   「在群里说话的人是你自己」，它会把别人的提问当成自己的发言。宁可留空：
            //   留空是「不知道谁说的」，回落是「知道，是你」—— 后者是错的且不报错。
            sb.append("GROUP_MESSAGE_SENDER_ID: ")
                    .append(identity.getSenderId() == null ? "" : identity.getSenderId())
                    .append("\n");
            sb.append("GROUP_MESSAGE_SENDER_NAME: ").append(nullToEmpty(identity.getSenderName())).append("\n");
            sb.append("★群场景下所有以会话为作用域的调用都会自动以你在本群的虚拟身份进行；")
                    .append("只能把消息发回本次触发的这个群，也不能在群场景下发私聊。\n");
        } else {
            // 私聊：IM 的 GROUP_SENDER_ID 是【群聊专属】键（nuwax-im ImAgentChatService#variableParams
            // 注释「私聊【不送】」），所以对端 id 只能取本次运行的登录用户 —— 私聊派发时 4.10 的
            // .userId(真人) 就是发消息的那个人。名字走 imSenderName（两分支都送）。
            // ★键名与技能腿（SandboxAgentClient#buildP2pReminder）保持一致：两条腿是同一个智能体的
            //   两种跑法，同一个概念换两个名字只会让模型多一层映射。
            sb.append("P2P_MESSAGE_SENDER_ID: ")
                    .append(identity.getSelfUserId() == null ? "" : identity.getSelfUserId()).append("\n");
            sb.append("P2P_MESSAGE_SENDER_NAME: ").append(nullToEmpty(identity.getSenderName())).append("\n");
        }
        if (identity.getSpaceId() != null) {
            sb.append("优先使用的资料库 spaceId: ").append(identity.getSpaceId()).append("\n");
        }
        if (identity.listenMode()) {
            sb.append("<system-reminder>你现在在群里处于监听模式，无需每个请求都进行回复，")
                    .append("消息与你无关或你无法提供有价值的信息时不需要回复；")
                    .append("需要你回复时必须调用 im_send_group，会话直接输出的内容用户不会看到。</system-reminder>\n");
        } else if (identity.isGroupScenario()) {
            // ★群里的 mention 与 listen 的差别只在「要不要每条都回」：两者的直接输出都到不了群里，
            //   要说话都得调工具。★点名 im_send_group 而不是 im_send_p2p：群场景下发私聊在执行侧
            //   一律被拒（requireP2pSendIsAllowed），写错工具名等于让模型白撞一次 4030。
            sb.append("<system-reminder>本次对话由女娲消息（nuwax-im）触发：你这一轮直接输出的内容用户看不到，")
                    .append("要说给这个群里的人听，必须用 im_send_group 把内容发出去（漏了不会有任何报错，群里就是空的）。")
                    .append("群场景下不能发私聊；要 @ 某个人用 mentionUserIds。\n")
                    .append("任务较长时可先发一句进度说明，但不要刷屏。</system-reminder>\n");
        } else {
            // 私聊：同上，直接输出对方收不到，说话只能走 im_send_p2p。
            sb.append("<system-reminder>本次对话由女娲消息（nuwax-im）触发：你这一轮直接输出的内容对方看不到，")
                    .append("要说给对方听，必须用 im_send_p2p 把内容发出去（漏了不会有任何报错，对方就是收不到）。\n")
                    .append("任务较长时可先发一句进度说明，但不要刷屏。</system-reminder>\n");
        }
        // ★放在模式说明之后：两条都是「怎么回话」的约束，挨在一起；且它是提醒的收尾，离生成位置最近。
        //   正文取自 Prompts.IM_REPLY_DISCIPLINE_PROMPT —— 沙箱技能腿（SandboxAgentClient）用的是同一份常量。
        //   两处各写一份必然漂移，而提示词漏掉一句【不会报错】，只会让「msgId 抄进回复」这个毛病悄悄回来。
        sb.append(Prompts.IM_REPLY_DISCIPLINE_PROMPT);
        sb.append("</im-reminder>\n\n");
        return sb.toString();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    // ============================== 构建工具 ==============================

    private static ComponentConfig tool(int seq, String functionName, String name, String description, Arg... args) {
        ComponentConfig config = new ComponentConfig();
        config.setId(TARGET_ID_BASE + seq);
        config.setTargetId(TARGET_ID_BASE + seq);
        config.setType(ComponentTypeEnum.Im);
        config.setFunctionName(functionName);
        // ★name 刻意用中文：ModelInvoker 会拿 name 过 ToolInfoExtractor.toolNameExchange，
        //   非纯 ASCII 时它原样回落到 functionName，工具名才稳定是 im_xxx（与 buildSandboxTools 同一招）。
        config.setName(name);
        config.setDescription(description);
        config.setInputArgs(new ArrayList<>(List.of(args)));
        // 不设 exceptionOut / fallbackMsg：失败要走 ModelInvoker 的 FAILED 分支，把真实原因喂回模型，
        // 让它自己决定是换参数重试还是如实告诉用户 —— 用兜底文案盖掉病因只会让模型接着瞎猜。
        return config;
    }

    private static Arg str(String name, String description, boolean require) {
        return Arg.builder().name(name).description(description)
                .dataType(DataTypeEnum.String).require(require).build();
    }

    /**
     * id / 序号 / 时间戳类参数一律声明成 {@code Integer}（JSON Schema 的 {@code integer} 无 32 位上限）。
     *
     * <p>★<b>不能</b>声明成 {@code Number}：那会映射成 JSON Schema 的 {@code number}，
     * Jackson 解析出 {@code Double}，而 {@link ImChatIdentity#toLong} 刻意拒收浮点
     * （截断后的数字是另一个人的 id）—— 结果是「明明填了却说没填」。
     * 执行侧一律走自己的 {@code toLong}，不依赖框架转值（{@code AGENT_VIRTUAL_USER_ID = 10^12 + agentId}，int 装不下）。
     */
    private static Arg idArg(String name, String description, boolean require) {
        return Arg.builder().name(name).description(description)
                .dataType(DataTypeEnum.Integer).require(require).build();
    }

    private static Arg num(String name, String description, boolean require) {
        return Arg.builder().name(name).description(description)
                .dataType(DataTypeEnum.Integer).require(require).build();
    }

    private static Arg arr(String name, String description, boolean require) {
        return Arg.builder().name(name).description(description)
                .dataType(DataTypeEnum.Array_Integer).require(require).build();
    }

    private static Arg bool(String name, String description, boolean require) {
        return Arg.builder().name(name).description(description)
                .dataType(DataTypeEnum.Boolean).require(require).build();
    }

    private static Arg textArg(boolean require) {
        return str("text", "消息正文（纯文本，可含换行）。只写要给用户看的内容本身，"
                + "不要把内部 id、错误码、工具名写进去（用户看不懂），除非用户明确索要", require);
    }

    /**
     * 供 {@code HybridAgentLoop} 判断本轮是否要走 IM 工具挂载。
     *
     * <p>两个来源任一成立即可：① 本次运行由 IM 触发（{@code from == nuwax_im}）；
     * ② 提示词/消息里带 {@link #ENABLE_MARKER}。两者都还要再过 {@link #available()}
     * （IM 适配未启用时挂 21 个必然失败的工具，只会让模型把「环境没配」误读成「我做错了」）。
     *
     * <p>★任何一条判据读不到都退化成「不挂」，绝不抛异常：这个方法在每轮对话的主链路上，
     * 这里 NPE 等于把整轮对话炸掉。
     */
    public boolean shouldMount(AgentContext ctx) {
        if (ctx == null) {
            return false;
        }
        boolean promptSupportIM = false;
        // ★agentConfig 可能为 null（非智能体配置驱动的链路），必须逐层判空
        AgentConfigDto agentConfig = ctx.getAgentConfig();
        String systemPrompt = agentConfig == null ? null : agentConfig.getSystemPrompt();
        String userPrompt = agentConfig == null ? null : agentConfig.getUserPrompt();
        String message = ctx.getMessage();
        String enableIMCmd = ENABLE_MARKER;
        if (systemPrompt != null && systemPrompt.contains(enableIMCmd)) {
            promptSupportIM = true;
        } else if (userPrompt != null && userPrompt.contains(enableIMCmd)) {
            promptSupportIM = true;
        } else if (message != null && message.contains(enableIMCmd)) {
            promptSupportIM = true;
        }
        return ("nuwax_im".equals(ctx.getFrom()) || promptSupportIM) && available();
    }
}
