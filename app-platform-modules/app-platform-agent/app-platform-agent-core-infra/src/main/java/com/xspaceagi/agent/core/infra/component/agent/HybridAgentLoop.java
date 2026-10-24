package com.xspaceagi.agent.core.infra.component.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AgentOutputDto;
import com.xspaceagi.agent.core.adapter.dto.ChatMessageDto;
import com.xspaceagi.agent.core.adapter.dto.KnowledgeSearchConfigDto;
import com.xspaceagi.agent.core.adapter.dto.PluginExecuteResultDto;
import com.xspaceagi.agent.core.adapter.dto.config.*;
import com.xspaceagi.agent.core.adapter.dto.config.bind.*;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.EndNodeConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import com.xspaceagi.agent.core.infra.component.ArgExtractUtil;
import com.xspaceagi.agent.core.infra.component.agent.dto.AgentExecuteResult;
import com.xspaceagi.agent.core.infra.component.im.ImChatIdentity;
import com.xspaceagi.agent.core.infra.component.im.ImChatToolProvider;
import com.xspaceagi.agent.core.infra.component.knowledge.KnowledgeBaseSearcher;
import com.xspaceagi.agent.core.infra.component.knowledge.SearchContext;
import com.xspaceagi.agent.core.infra.component.mcp.McpContext;
import com.xspaceagi.agent.core.infra.component.mcp.McpExecutor;
import com.xspaceagi.agent.core.infra.component.memory.MemoryToolProvider;
import com.xspaceagi.agent.core.infra.component.model.ModelContext;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.infra.component.model.dto.*;
import com.xspaceagi.agent.core.infra.component.plugin.PluginContext;
import com.xspaceagi.agent.core.infra.component.plugin.PluginExecutor;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowExecutor;
import com.xspaceagi.agent.core.spec.enums.*;
import com.xspaceagi.agent.core.spec.utils.PlaceholderParser;
import com.xspaceagi.agent.core.spec.utils.ToolInfoExtractor;
import com.xspaceagi.agent.core.spec.utils.UrlFile;
import com.xspaceagi.knowledge.sdk.response.KnowledgeQaVo;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.dto.McpExecuteOutput;
import com.xspaceagi.mcp.sdk.dto.McpToolDto;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.utils.DateUtil;
import com.xspaceagi.system.spec.utils.I18nUtil;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.content.Media;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeType;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.xspaceagi.agent.core.spec.constant.Prompts.EXTRACT_PARAM_PROMPT;
import static com.xspaceagi.agent.core.spec.enums.GlobalVariableEnum.CHAT_CONTEXT;

/**
 * 混合编排智能体（L1：原生 Function Call + 简单任务直答 / 复杂任务沙箱执行）。
 * <p>
 * 基于 {@link MasterAgentLoop} 的总控编排思想改造：利用模型<b>原生 function calling</b> 协议实现意图路由，
 * 但<b>不建子会话、不分发</b>，所有输出统一收敛回本 loop 输出给用户：
 * <ul>
 *   <li><b>简单任务</b>：模型直接回答，或调用绑定的组件工具（插件/知识库/工作流/MCP）由 ModelInvoker 在本机执行</li>
 *   <li><b>复杂任务</b>（写代码/操作文件/执行命令/网页自动化等需要真实运行环境的任务）：
 *       模型调用 {@code sandbox} 控制工具（returnDirect）→ HybridAgentLoop <b>in-process</b> 调
 *       {@link SandboxAgentClient#chat}，沙箱 {@code Flux&lt;CallMessage&gt;} 桥接为 {@code AgentOutputDto}
 *       流式推送到<b>同一个 sink</b>，最终输出收敛回本 loop（统一输入输出）</li>
 * </ul>
 * <p>
 * 控制工具（{@link ComponentTypeEnum#Control}，returnDirect=true）：
 * <ul>
 *   <li>{@code sandbox}：在云端沙箱中执行复杂任务（唯一控制工具，沙箱触发通道）</li>
 * </ul>
 * <p>
 * 响应式架构（对齐 MasterAgentLoop）：
 * <ul>
 *   <li>顶层 {@code Flux.create(sink -> pipeline.subscribe())}，单一订阅点</li>
 *   <li>内部全部使用 {@code Mono.defer} 操作符链组合，无嵌套 subscribe</li>
 *   <li>模型调用失败自动重试（仅首 token 前可重试，避免重复输出）</li>
 *   <li>沙箱会话跨轮复用：sessionId 持久化到 conversation（sandboxSessionCreatedConsumer），
 *       "继续"时同一沙箱会话接着执行</li>
 * </ul>
 */
@Slf4j
@Component
public class HybridAgentLoop {

    // ======================== System Prompt ========================

    private static final String HYBRID_SYSTEM_PROMPT = """
            你是一个智能助手。你直接面对用户，负责回答用户的问题或完成用户的任务。
            
            ## 你的能力
            - 直接回答用户的问题（常识、解释、分析、创作、闲聊等）——这是最常见的情况，直接输出回答即可
            - 当你绑定了工具技能（插件、知识库、工作流、MCP 等）时，可以根据用户问题调用这些工具获取信息后回答
            - 当任务需要真实运行环境时（编写/运行代码、读写/处理文件、执行命令、数据分析、网页自动化、系统操作等），调用 sandbox 工具在云端沙箱中执行，沙箱执行结果会流式返回给你
            
            ## sandbox 工具（复杂任务执行通道）
            sandbox 工具会在云端沙箱环境中启动一个独立的执行代理，你的 inputMessage 就是交给它的任务描述。
            - 调用 sandbox 时，inputMessage 必须写全：任务目标、输入数据/背景、步骤要求、约束、期望输出格式。沙箱没有本会话的上下文，不要省略任何必要信息
            - 沙箱执行是"一次性提交"：它会自主完成整个任务并返回结果，执行期间你无法干预
            - 执行完成后直接向用户展示沙箱返回的结果（代码、文件、输出等），不要重复执行
            - 用户说"继续"/"接着做"/"改一下"时，基于上次沙箱的任务上下文继续调用 sandbox（沙箱会话会保持，能记住之前的操作）
            
            ## 重要约束
            - 绝大多数情况下直接回答（不调用任何工具）。
            - 简单任务（查询、解释、总结、创作、检索信息等）直接用工具或直接回答，不要调 sandbox。
            - 只有明确需要真实执行环境时才调用 sandbox。
            - 调用工具时不要同时输出文本（直接调用即可，系统会自动通知用户）。
            - 不要谎称任务已执行：只有真正调用 sandbox 工具，任务才会真正执行。
            """;

    /**
     * 控制工具名集合，用于识别 returnDirect 返回的控制 JSON。
     * <p>
     * ★这份名单同时是 {@link #dispatchControlSignal} 的<b>准入面</b>：不在里面的 action 一律按「未知」
     * 降级成普通文本，防止控制 JSON 泄漏给用户。记忆三个工具的名字取自 {@link MemoryToolProvider}，
     * 与工具清单同源（新增记忆工具时只改那边，这里跟着生效）。
     */
    private static final Set<String> CONTROL_ACTIONS = Set.of(
            "sandbox",
            MemoryToolProvider.ACTION_QUERY, MemoryToolProvider.ACTION_SAVE, MemoryToolProvider.ACTION_DELETE);

    /**
     * 结果回喂型控制工具（记忆三件套）单轮最多调用次数：达到上限后不再挂载记忆工具，
     * 强制模型基于已累积的完整上下文直接回答，防止「调工具 → 回喂 → 再调同一工具」死循环。
     * 取值与 {@code MasterAgentLoop#MAX_TOOL_CALLS} 一致。
     */
    private static final int MAX_TOOL_CALLS = 6;

    /**
     * 模型调用重试次数（仅瞬时错误、首 token 前）
     */
    private static final int MODEL_RETRY_MAX = 2;
    /**
     * 重试初始退避
     */
    private static final Duration MODEL_RETRY_BACKOFF = Duration.ofSeconds(1);

    // ======================== 执行链持久化 ========================

    /**
     * sessionState 中保存完整执行链（{@code List<ExecMsg>} 的 JSON 字符串）的 key。
     * 执行链 = user → assistant(tool_use) → tool(result) → assistant 交错序列，
     * 跨轮持久化到 conversation.variables，模型执行上下文唯一来源（roundMessage 仅前端展示）。
     */
    private static final String EXEC_CONTEXT_KEY = "hybridExecContext";
    /**
     * 执行链 JSON 字符上限，超出后裁剪最旧轮次，防止会话变量无限膨胀。
     */
    private static final int MAX_EXEC_CONTEXT_LENGTH = 64000;
    /**
     * 空 content 兜底占位符：部分模型网关要求所有消息 content 非空（否则报
     * "messages.N: all messages must have non-empty content"），执行链中任何可能为空/空白
     * 的 user / 纯文本 assistant / tool 消息在发送给模型前统一替换为该占位符。
     * 同时覆盖历史会话变量中已持久化的空文本脏数据（转换层兜底）。
     */
    private static final String EMPTY_CONTENT_PLACEHOLDER = "(empty)";
    /**
     * 沙箱结果写入执行链的截断长度（结果完整内容已流式输出给用户，执行链仅保留摘要供模型回顾）
     */
    private static final int MAX_SANDBOX_RESULT_LENGTH = 2000;
    /**
     * 会话变量中记录"上次沙箱调用时间"的 key（毫秒时间戳），随 persistSessionState 持久化，跨轮可见。
     */
    private static final String LAST_SANDBOX_TIME_KEY = "lastSandboxTime";
    /**
     * "10 分钟直连沙箱"窗口：上次走了沙箱执行后，窗口内的下一次消息直接继续沙箱执行（跳过模型意图路由）。
     * 仅对"无用户主动选择标记、模型路由进过沙箱"的会话生效（见 {@link #userSelectedSandbox}）。
     */
    private static final long SANDBOX_CONTINUE_WINDOW_MS = 10 * 60 * 1000L;
    /**
     * 会话变量中"用户主动选择沙箱"标记 key（值 "1"）：execute 入口检测到请求携带 sandboxId 时写入，
     * 随 persistSessionState 持久化。命中后该会话永久直连沙箱（区别于仅模型路由进过沙箱的 10 分钟窗口）。
     */
    private static final String USER_SANDBOX_SELECTED_KEY = "userSandboxSelected";
    /**
     * {@code <sandbox_system_prompt>…</sandbox_system_prompt>} 块：AgentConfig.systemPrompt 中沙箱智能体专用提示词。
     * 正常 LLM 请求（总控模型/变量提取/对话上下文）移除该块；沙箱智能体请求仅使用块内内容（无标签回退完整 systemPrompt）。
     */
    private static final Pattern SANDBOX_SYSTEM_PROMPT_PATTERN = Pattern.compile(
            "<sandbox_system_prompt>(.*?)</sandbox_system_prompt>", Pattern.DOTALL);

    // ======================== Dependencies ========================

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private ModelInvoker modelInvoker;

    @Resource
    private SandboxAgentClient sandboxAgentClient;

    // 工具前置调用依赖（对齐 AgentExecutor：AUTO 类型组件在模型调用前自动执行）
    @Resource
    private KnowledgeBaseSearcher knowledgeBaseSearcher;

    @Resource
    private PluginExecutor pluginExecutor;

    @Resource
    private WorkflowExecutor workflowExecutor;

    @Resource
    private McpExecutor mcpExecutor;

    /**
     * IM 原生工具的清单侧（{@link ComponentTypeEnum#Im}）。执行侧在 ModelInvoker 里
     * （{@code ImChatToolExecutor}），身份口径在 {@link ImChatIdentity}。
     */
    @Resource
    private ImChatToolProvider imChatToolProvider;

    /**
     * 长期记忆工具的<b>清单 + 执行</b>（memory_query / memory_save / memory_delete）。
     * 与 {@code MasterAgentLoop} 共用同一个 bean：开关判据、工具描述、分类体系、纪律提示词、回喂指令
     * 全平台只有一份 —— 抄两份必然漂移，而漂移的表现不是报错，是同一个智能体在总控和 chatbot 上
     * 记忆行为不一样（排查时两边都得读一遍才知道差在哪）。
     */
    @Resource
    private MemoryToolProvider memoryToolProvider;

    // ======================== Public API ========================

    public Flux<AgentOutputDto> execute(AgentContext agentContext) {
        // 对齐 MasterAgentLoop：sessionState = variableParams（共享同一 Map，持久化到 conversation.variables）
        initSessionState(agentContext);
        // 用户主动选择了具体沙箱（请求携带 sandboxId > 0）：持久化标记到 sessionState，
        // 该会话之后永久直连沙箱（跳过模型路由）。仅 sandboxId > 0（指定沙箱配置）才算，
        // -1（云端电脑/全局沙箱）与 null（未选）都不算。区别于"仅模型路由进过沙箱"
        // （无标记，走 10 分钟窗口，超窗回退模型路由）。标记随 commitExecContext → persistSessionState 跨轮落库。
        if (agentContext.getSandboxId() != null && agentContext.getSandboxId() > 0
                && agentContext.getSessionState() != null) {
            agentContext.getSessionState().put(USER_SANDBOX_SELECTED_KEY, "1");
        }
        // 确保 AgentExecuteResult 已初始化（组件执行回调需要写入结果）
        if (agentContext.getAgentExecuteResult() == null) {
            agentContext.setAgentExecuteResult(new AgentExecuteResult());
        }
        if (agentContext.getAgentExecuteResult().getStartTime() == null) {
            agentContext.getAgentExecuteResult().setStartTime(System.currentTimeMillis());
        }
        // 长期记忆加载（对齐 AgentExecutor：个人信息预加载，其他信息按需查询）
        loadLongMemory(agentContext);
        MasterLoopContext mctx = new MasterLoopContext(agentContext);
        // 从 sessionState 恢复上一轮持久化的完整执行链（跨轮连问时模型能看到历史工具调用与结果）
        loadExecContext(mctx);
        // 本 loop 自行构造完整消息链（执行链 + 本轮 user），ModelInvoker 不再追加 buildUserMessage
        agentContext.setExecutionMessagesComplete(true);
        // 入口即写入 user message（roundMessage 仅前端展示，不作为模型执行上下文）
        addUserRoundMessage(agentContext);
        // 对齐 MasterAgentLoop：顶层 Flux.create + 单一 subscribe，内部 Mono.defer 组合
        return Flux.create(sink -> {
            // 变量设置前置（对齐 AgentExecutor）：模型调用前提取 AutoRecognition 变量 + 填充默认值到
            // variableParams（驱动 buildUserPrompt / buildHybridSystemPrompt 的占位符替换）。
            // 失败不阻断主流程（用户确认），记录日志后继续模型调用。
            Disposable d = variableSet(agentContext, sink, mctx)
                    .onErrorResume(err -> {
                        log.warn("[HybridAgentLoop] variable set failed, continue: {}", err.getMessage());
                        return Mono.empty();
                    })
                    .then(streamWithNativeFC(mctx, sink))
                    // 轮次正常结束：把本轮 [user, 工具链, assistant] 提交到执行链并持久化（错误轮次不提交）
                    .doOnSuccess(v -> commitExecContext(mctx))
                    // 顶层兜底：任何泄漏到本层的错误（组件 exceptionOut=Y 主动抛出、AUTO 必填参数提取失败、
                    // 持久化 NPE、收尾写库失败等）都必须收敛为带错误输出的正常收尾。此前内层 subscribe 无错误
                    // 消费者，错误被 reactor onErrorDropped 吞掉，sink 永不 complete，外层
                    // ChatApplicationServiceImpl 收不到错误信号 → 请求静默悬挂直至 2 小时 watchdog 被动终止
                    .onErrorResume(err -> Mono.fromRunnable(() -> finishWithError(agentContext, sink, err)))
                    .subscribe(unused -> {
                    }, err -> log.error("[HybridAgentLoop] top-level fallback failed: {}", err.getMessage(), err));
            sink.onDispose(d);
        });
    }

    // ======================== Core: Native Function Call Streaming ========================

    /**
     * 单次流式调用模型（挂载 sandbox 控制工具 + 组件工具），通过原生 function call 实现简单/复杂任务路由。
     * <p>
     * 响应式组合模式（对齐 MasterAgentLoop）：
     * <pre>
     * modelInvoker.invoke(mc)
     *     .retryWhen(...)           // 瞬时错误重试（仅首 token 前）
     *     .doOnNext(emit to sink)   // 流式输出 token
     *     .then(Mono.defer(...))    // 流结束后：控制信号（沙箱）or 自答完成
     * </pre>
     *
     * @return Mono&lt;Void&gt; 表示整个流程完成（成功或已处理错误）
     */
    private Mono<Void> streamWithNativeFC(MasterLoopContext mctx, FluxSink<AgentOutputDto> sink) {
        AgentContext ctx = mctx.ctx();
        // 直连沙箱（跳过模型意图路由）：智能体配置了 skill（技能型智能体，任务天然需要真实执行环境）、
        // 用户主动选择了具体沙箱（sessionState 标记）、或上次沙箱执行结束后 10 分钟窗口内的连续消息。
        // 直接把本轮用户消息交给沙箱执行（沙箱会话经 conversation.sandboxSessionId 跨轮复用），
        // 复用 dispatchControlSignal 保证执行链（tool_use/tool 配对）与错误兜底与模型路由路径完全一致。
        // 提前到 AUTO 前置调用之前判断：直连沙箱路径下 AUTO 组件（知识库/插件/工作流）前置调用的结果
        // 不会注入沙箱上下文（buildSandboxContext 只拼 longMemory/user-reminder/attachments），
        // 若先执行则属白执行（skill 型智能体每轮都白查一次知识库），故直连时跳过。
        if (hasSkill(ctx) || userSelectedSandbox(ctx) || shouldContinueSandbox(ctx)) {
            // ★这条热路径完全不经过模型，所以本轮不会提供记忆工具（模型没机会调）—— 直连沙箱时
            //   记忆只靠 loadLongMemory 的被动加载（<user-memory> 注入沙箱上下文），主动查/写/删要等
            //   下一轮走模型路由时才有。这是刻意的取舍：热路径的意义就是省掉一次模型往返。
            // Map.of 不允许 null 值，消息可能为空（handleSandbox 内层对空 inputMessage 有兜底提示）
            Map<String, String> hotPathDecision = new HashMap<>();
            hotPathDecision.put("action", "sandbox");
            hotPathDecision.put("inputMessage", StringUtils.defaultString(ctx.getMessage()));
            String rawJson = JSON.toJSONString(hotPathDecision);
            log.info("[HybridAgentLoop] sandbox direct path (hasSkill={}, userSelected={}, continueWindow={}), skip model routing",
                    hasSkill(ctx), userSelectedSandbox(ctx), shouldContinueSandbox(ctx));
            return dispatchControlSignal(mctx, rawJson, sink);
        }
        // 工具前置调用（对齐 AgentExecutor）：模型调用前自动执行 AUTO 类型的知识库/插件/MCP/工作流组件，
        // 结果经 toolChain（assistant(tool_use) → tool(result)）注入模型上下文并随执行链持久化；
        // 工作流 directOutput=Y 时直接输出短路（不走模型，用户确认支持）。
        return Mono.fromCallable(() -> {
                    List<String> directOutputResults = new ArrayList<>();
                    invokeAndRemoveAutoComponents(ctx, sink, mctx, directOutputResults);
                    return directOutputResults;
                })
                // 前置调用内部可能触发必填参数提取（模型调用 block），移到 boundedElastic 避免阻塞订阅线程
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(directOutputResults -> {
                    if (!directOutputResults.isEmpty()) {
                        // directOutput=Y 工作流短路：直接输出结果（对齐 AgentExecutor，不走模型）
                        return emitDirectOutput(ctx, sink, mctx, directOutputResults);
                    }
                    return invokeModel(mctx, sink);
                });
    }

    /**
     * 模型调用主体（前置调用完成后进入）：挂载 sandbox 控制工具 + 记忆管理工具（开启长期记忆时）
     * + 组件工具，走原生 function call 路由。
     */
    private Mono<Void> invokeModel(MasterLoopContext mctx, FluxSink<AgentOutputDto> sink) {
        return invokeModel(mctx, sink, null, true);
    }

    /**
     * 模型调用主体（完整版，支持记忆工具「结果回喂」的二次调用）。
     *
     * @param extraPromptSection 追加到 userPrompt 末尾的额外内容（记忆工具的执行结果指令），null/空则不追加
     * @param includeMemoryTools 是否挂载记忆工具（调用次数达到 {@link #MAX_TOOL_CALLS} 上限后传 false，防循环）
     */
    private Mono<Void> invokeModel(MasterLoopContext mctx, FluxSink<AgentOutputDto> sink,
                                   String extraPromptSection, boolean includeMemoryTools) {
        AgentContext ctx = mctx.ctx();
        // ★IM 工具的挂载闸门只算一次，且同时决定「挂不挂工具」与「注不注 IM 提醒」——两者必须同源：
        //   只挂工具不注提醒：监听模式下模型会把回复当普通文本输出，而那条链路的 SSE 在 IM 侧整段丢弃
        //   （ImAgentChatService 的 listen 分支 onEvent = null），用户一个字都收不到；
        //   只注提醒不挂工具：模型会去调一个根本不存在的函数名。
        boolean imToolsMounted = imChatToolProvider.shouldMount(ctx);
        // ★记忆工具同理：闸门只算一次，同时决定「挂不挂工具」与「注不注记忆纪律提示词段」。
        //   只挂不注 → 模型不知道 memoryId 严禁编造、事实变更必须更新旧记忆；
        //   只注不挂 → 模型去调一个本轮根本没注册的函数名。
        //   判据是 MemoryToolProvider#isOpen（agentConfig.openLongMemory == Open），与 loadLongMemory 的
        //   被动加载门控同一个：被动加载决定 <user-memory> 里有没有料，工具决定模型能不能主动查/写/删。
        boolean memoryToolsMounted = includeMemoryTools && memoryToolProvider.isOpen(ctx);
        String systemPrompt = buildHybridSystemPrompt(mctx, memoryToolsMounted);
        String userPrompt = buildUserPrompt(ctx, imToolsMounted);
        if (StringUtils.isNotBlank(extraPromptSection)) {
            // 回喂轮：本轮 user 提示词末尾追加「以上是记忆查询/写入/删除结果 + 接下来怎么答」的指令。
            // 工具结果本身在 toolChain 里，以 tool(result) 消息随执行链一起交给模型（顺序见 buildExecutionMessages）
            userPrompt += extraPromptSection;
        }
        // 完整执行链由 buildModelContext 统一构造（历史 execContext + 本轮 user + 本轮 toolChain），
        // 走 spring-ai 原生 Message 序列，ModelInvoker 不再追加 user message。
        ModelContext modelContext = buildModelContext(ctx, systemPrompt, userPrompt, mctx);

        // 工具列表 = sandbox 控制工具（开关开启时）+ 记忆管理工具（开启长期记忆时）
        //          + 智能体绑定的组件工具（插件/知识库/工作流/MCP）
        //          + IM 原生工具（本次运行由 IM 触发、且 IM 适配已启用时）
        List<ComponentConfig> allTools = new ArrayList<>();
        if (isSandboxEnabled(ctx)) {
            allTools.addAll(buildSandboxTools());
        }
        if (memoryToolsMounted) {
            allTools.addAll(memoryToolProvider.buildTools());
        }
        allTools.addAll(buildComponentTools(ctx));
        if (imToolsMounted) {
            allTools.addAll(buildIMTools(ctx));
        }
        modelContext.getModelCallConfig().setComponentConfigs(allTools);
        AtomicReference<String> controlSignalRef = new AtomicReference<>();
        modelContext.setComponentExecutingConsumer((componentExecutingDto) -> {
            // ★Im 与 Control 一样整段跳过。理由不同但同样硬：
            //   Control 跳过是因为它是 returnDirect 的路由信号，不是给用户看的动作；
            if (componentExecutingDto.getType() == ComponentTypeEnum.Control) {
                return;
            }
            AgentOutputDto processingOutput = new AgentOutputDto();
            processingOutput.setEventType(AgentOutputDto.EventTypeEnum.PROCESSING);
            processingOutput.setRequestId(modelContext.getRequestId());
            processingOutput.setData(componentExecutingDto);
            sink.next(processingOutput);
            if (componentExecutingDto.getStatus() == ExecuteStatusEnum.FINISHED
                    || componentExecutingDto.getStatus() == ExecuteStatusEnum.FAILED) {
                // 组件执行完成：记录结果 + 追加过程标记到文本（前端渲染执行状态卡片）
                if (ctx.getAgentExecuteResult() != null) {
                    ctx.getAgentExecuteResult().getComponentExecuteResults().add(componentExecutingDto.getResult());
                }
                mctx.textAccum.append(AgentExecutor.buildProcessingText(componentExecutingDto));
                mctx.componentExecutedList.add(componentExecutingDto);
            }
        });

        return modelInvoker.invoke(modelContext)
                // 重试：仅当尚未输出任何 token 时才重试（避免重复输出）
                .retryWhen(Retry.backoff(MODEL_RETRY_MAX, MODEL_RETRY_BACKOFF)
                        .filter(this::isTransientError)
                        .doBeforeRetry(signal -> log.warn("[HybridAgentLoop] model call retry #{}: {}",
                                signal.totalRetries() + 1, signal.failure().getMessage())))
                .doOnNext(msg -> {
                    if (msg.getType() == MessageTypeEnum.THINK) {
                        mctx.thinkAccum.append(msg.getText() == null ? "" : msg.getText());
                        sink.next(buildCallMessageOutput(ctx, msg));
                        return;
                    }
                    // CHAT token：先检测控制信号，再决定是否输出（避免 returnDirect JSON 泄漏给用户）
                    if (modelContext.getModelCallResult().getData() instanceof Map<?, ?> && ((Map<?, ?>) modelContext.getModelCallResult().getData()).containsKey("action")) {
                        // 控制信号：记录但不输出给用户
                        log.info("[HybridAgentLoop] control signal: {}", modelContext.getModelCallResult().getData());
                        controlSignalRef.compareAndSet(null, JSON.toJSONString(modelContext.getModelCallResult().getData()));
                        return;
                    }
                    // 普通文本：自答直通
                    String text = msg.getText() == null ? "" : msg.getText();
                    mctx.textAccum.append(text);
                    sink.next(buildCallMessageOutput(ctx, msg));
                })
                .then(Mono.defer(() -> {
                    // 收集组件工具轨迹（ModelInvoker 经 spring-ai 内部执行的插件/知识库/工作流等）并入 toolChain，
                    // 保证非 Control 工具调用也在执行链中（随 commitExecContext 持久化，跨轮可见）
                    collectToolTrace(mctx);
                    String controlJson = controlSignalRef.get();
                    if (controlJson != null) {
                        // 控制信号 → 沙箱执行（自答，本轮结束）或记忆工具（结果回喂，再调一次模型）
                        return dispatchControlSignal(mctx, controlJson, sink);
                    } else {
                        // 自答完成
                        return finalizeSelfAnswer(ctx, sink, mctx.textAccum, mctx.thinkAccum, mctx.componentExecutedList);
                    }
                }))
                .onErrorResume(err -> {
                    log.error("[HybridAgentLoop] model error: {}", err.getMessage(), err);
                    mctx.getErrorOccurred().set(true);
                    return handleModelError(mctx, sink, err);
                });
    }

    /**
     * IM 原生工具清单（{@link ComponentTypeEnum#Im}）：把 {@code NuwaxImSandboxController} 那套
     * 4sandbox 技能端点，用<b>它们底下同一组 IM RPC 接口</b>包装成 chatbot 的原生 function-call 工具。
     *
     * <p>★「实现不一定全部写在这个类里」——所以这里只做一件事：委托。实现分三个类，各管一段：
     * <ul>
     *   <li>{@link ImChatToolProvider}：21 个工具的清单（名称/描述/入参 schema）+ 挂载闸门 + IM 提醒文案；</li>
     *   <li>{@link ImChatIdentity}：<b>身份口径</b>（私聊=本人、群=智能体虚拟身份、撤回按 senderId 选）
     *       与两道「混乱代理」闸门。这是安全边界，单独成类是为了能被单测逐条钉住；</li>
     *   <li>{@code ImChatToolExecutor}：分派 + 调 RPC + 错误码翻译，由 {@code ModelInvoker} 的
     *       {@code case Im} 调起（本类看不到执行，与 Plugin/Workflow/Mcp 一致）。</li>
     * </ul>
     *
     * <p>★与沙箱那条腿的分工：沙箱走「技能脚本带 AK 打 4sandbox HTTP 接口」，chatbot 走这里
     * 「进程内 function call 直连 IM RPC」。两条腿打的是<b>同一组接口、同一套身份规则</b>，
     * 差别只在凭据面（AK 的 ext vs {@link AgentContext#getImVars()}）。
     *
     * <p>调用前已由 {@link ImChatToolProvider#shouldMount(AgentContext)} 把过关
     * （{@code from == nuwax_im} 且 IM 适配已启用），所以这里不再重复判。
     */
    private Collection<? extends ComponentConfig> buildIMTools(AgentContext ctx) {
        return imChatToolProvider.buildTools(ImChatIdentity.of(ctx));
    }

    /**
     * 记录一次控制工具调用（assistant(tool_use)），追加到 {@link MasterLoopContext#getToolChain()}。
     * 与后续 {@link #appendToolResult} 追加的 tool(result) 通过 toolCallId 配对，
     * 形成标准 function-call 语义的 "assistant(tool_use) → tool(result)" 序列，
     * 轮次结束时随执行链一并持久化，模型执行上下文唯一来源。
     */
    private void recordToolCall(MasterLoopContext mctx, String action, String rawJson) {
        ExecMsg msg = new ExecMsg();
        msg.setRole("assistant");
        msg.setToolName(action);
        msg.setToolArgs(rawJson);
        msg.setToolCallId(UUID.randomUUID().toString().replace("-", ""));
        mctx.getToolChain().add(msg);
    }

    /**
     * 追加一次工具执行结果（tool(result)）到 {@link MasterLoopContext#getToolChain()}。
     * 调用方保证在 {@link #recordToolCall} 之后调用；复用最后一条同 action 的 tool_use 的 toolCallId 保证配对。
     */
    private void appendToolResult(MasterLoopContext mctx, String action, String resultText) {
        // 取工具链中最后一条同 action 的 assistant(tool_use)，复用其 toolCallId 保证调用/结果配对
        String toolCallId = null;
        for (int i = mctx.getToolChain().size() - 1; i >= 0; i--) {
            ExecMsg m = mctx.getToolChain().get(i);
            if ("assistant".equals(m.getRole()) && action.equals(m.getToolName())) {
                toolCallId = m.getToolCallId();
                break;
            }
        }
        ExecMsg msg = new ExecMsg();
        msg.setRole("tool");
        msg.setToolName(action);
        msg.setText(resultText);
        msg.setToolCallId(toolCallId);
        mctx.getToolChain().add(msg);
    }

    /**
     * 组件的规范工具名：{@link #recordToolCall} / {@link #appendToolResult} / 异常兜底三处<b>必须使用同一名字</b>，
     * 否则 appendToolResult 按名倒查不到同名 tool_use、复用不到 toolCallId → 配对断裂，
     * {@link #toSpringAiMessage} 会为 toolCallId 为空的 tool 结果生成随机 id → tool_use/tool_result id 不一致，
     * Anthropic/OpenAI 协议校验直接失败（本轮模型调用报错），孤儿消息还会污染跨轮执行链。
     * 命名规则与 {@link #buildComponentTools} 挂载工具一致（functionName 优先，回退绑定名），
     * 保证执行链中的工具名与模型实际看到的工具名相同。
     */
    private static String resolveToolName(AgentComponentConfigDto dto) {
        String name = null;
        Object targetConfig = dto.getTargetConfig();
        if (dto.getType() == AgentComponentConfig.Type.Plugin
                && targetConfig instanceof PluginDto pluginDto
                && StringUtils.isNotBlank(pluginDto.getFunctionName())) {
            name = pluginDto.getFunctionName();
        }
        if (dto.getType() == AgentComponentConfig.Type.Workflow
                && targetConfig instanceof WorkflowConfigDto workflowConfigDto
                && StringUtils.isNotBlank(workflowConfigDto.getFunctionName())) {
            name = workflowConfigDto.getFunctionName();
        }
        if (dto.getType() == AgentComponentConfig.Type.Mcp
                && dto.getBindConfig() instanceof McpBindConfigDto mcpBind
                && StringUtils.isNotBlank(mcpBind.getToolName())) {
            return mcpBind.getToolName();
        }
        if (dto.getTargetId() != null) {
            name = dto.getType().name().toLowerCase() + dto.getTargetId();
        }
        return ToolInfoExtractor.toolNameExchange(dto.getName(), name);
    }

    /**
     * 收集组件工具轨迹（ModelInvoker.componentExecute 写入 AgentContext.toolTraceAccum）并入本轮 toolChain。
     * <p>
     * 非 Control 组件工具（插件/知识库/工作流/MCP/Table）由 spring-ai 内部执行，不经过
     * {@link #recordToolCall}/{@link #appendToolResult}（那是 Control 工具专用）。其调用与结果
     * 由 ModelInvoker 统一采集为 {@link ToolExecRecord}（assistant(tool_use) → tool(result) 按 executeId 配对），
     * 每次流式调用结束后在此并入 toolChain，随 {@link #commitExecContext} 持久化到执行链。
     * Control 工具不写 toolTraceAccum（componentExecute 已过滤），无重复风险。
     * </p>
     */
    private void collectToolTrace(MasterLoopContext mctx) {
        AgentContext ctx = mctx.ctx();
        List<ToolExecRecord> accum = ctx.getToolTraceAccum();
        if (CollectionUtils.isEmpty(accum)) {
            return;
        }
        for (ToolExecRecord t : accum) {
            ExecMsg msg = new ExecMsg();
            msg.setRole(t.getRole());
            msg.setToolName(t.getToolName());
            msg.setToolArgs(t.getToolArgs());
            msg.setText(t.getText());
            msg.setToolCallId(t.getToolCallId());
            mctx.getToolChain().add(msg);
        }
        accum.clear();
    }

    /**
     * 自答流完成：写 assistant 消息 + final result + complete。
     *
     * @param componentExecutedList 本轮组件（插件/知识库/工作流等）执行记录，写入 assistant 消息供前端渲染
     */
    private Mono<Void> finalizeSelfAnswer(AgentContext ctx, FluxSink<AgentOutputDto> sink,
                                          StringBuilder textAccum, StringBuilder thinkAccum,
                                          List<ComponentExecutingDto> componentExecutedList) {
        return Mono.<Void>fromRunnable(() -> {
            // 空响应保护
            if (textAccum.isEmpty() && thinkAccum.isEmpty()) {
                log.warn("[HybridAgentLoop] model returned empty response");
                setErrorResult(ctx, "模型未返回有效内容");
                sink.next(buildTextMessage(ctx, "抱歉，暂时无法回答，请稍后重试。"));
                sink.next(AgentExecutor.buildFinalResultOutput(ctx));
                sink.complete();
                return;
            }
            completeSuccess(ctx, sink, textAccum.toString(), thinkAccum.toString(), componentExecutedList, false);
        });
    }

    /**
     * 模型调用错误处理（重试已耗尽后到达此处）。
     * <p>
     * 对齐 AgentExecutor.chatError 的数据保全：已流式输出的半截内容（如有）连同失败说明写入
     * roundMessage（用户刷新后仍可见"回答到一半失败"）。错误轮次<b>不提交执行链</b>
     * （调用方已置 errorOccurred，commitExecContext 跳过），模型上下文保持干净。
     */
    private Mono<Void> handleModelError(MasterLoopContext mctx, FluxSink<AgentOutputDto> sink,
                                        Throwable err) {
        return Mono.<Void>fromRunnable(() -> {
            AgentContext ctx = mctx.ctx();
            setErrorResult(ctx, err.getMessage());
            // 错误提示优先展示真实错误信息（err.getMessage），为空时才用兜底文案
            String errText = StringUtils.defaultIfBlank(err.getMessage(), "回答生成失败，请稍后重试。");
            // 已有半截输出也保存（对齐 AgentExecutor.chatError），避免用户刷新后丢失
            if (mctx.textAccum.length() > 0) {
                addAssistantRoundMessage(ctx, mctx.textAccum.toString() + "\n\n" + errText,
                        mctx.thinkAccum.toString(), mctx.componentExecutedList);
            }
            sink.next(buildTextMessage(ctx, errText));
            sink.next(AgentExecutor.buildFinalResultOutput(ctx));
            sink.complete();
        });
    }

    // ======================== 变量设置（对齐 AgentExecutor） ========================

    /**
     * 变量组件设置（模型调用前执行，execute 入口前置）：
     * <ol>
     *   <li>系统变量（CHAT_CONTEXT 等）填充到 variableParams</li>
     *   <li>自定义变量默认值（前端未传时按 bindValue 填充）</li>
     *   <li>AutoRecognition 自定义变量：模型 JSON 提取，结果写回 variableParams
     *       （驱动 buildUserPrompt 的 user-reminder / buildHybridSystemPrompt 占位符替换）</li>
     * </ol>
     * 失败策略由调用方决定（execute 中 onErrorResume 不阻断主流程，用户确认）。
     */
    private Mono<Void> variableSet(AgentContext ctx, FluxSink<AgentOutputDto> sink, MasterLoopContext mctx) {
        List<AgentComponentConfigDto> componentList = ctx.getAgentConfig().getAgentComponentConfigList();
        AgentComponentConfigDto variableComponent = componentList.stream()
                .filter(c -> c.getType() == AgentComponentConfig.Type.Variable).findFirst().orElse(null);
        if (variableComponent == null) {
            return Mono.empty();
        }
        VariableConfigDto variableConfigDto = (VariableConfigDto) variableComponent.getBindConfig();
        if (variableConfigDto == null || CollectionUtils.isEmpty(variableConfigDto.getVariables())) {
            return Mono.empty();
        }
        // 过滤 AutoRecognition 自定义变量（前端已传的由 variableParams 预置，不重复提取）
        List<Arg> userArgs = variableConfigDto.getVariables().stream()
                .filter(var -> !var.isSystemVariable()
                        && var.getInputType() != null && var.getInputType() == Arg.InputTypeEnum.AutoRecognition)
                .collect(Collectors.toList());
        variableConfigDto.getVariables().forEach(var -> {
            if (var.isSystemVariable() && var.getEnable()) {
                if (CHAT_CONTEXT.name().equals(var.getName())) {
                    // 对话上下文变量：systemPrompt + 历史执行链消息 + 本轮用户消息
                    // （<sandbox_system_prompt> 块是沙箱专用，对话上下文走正常 LLM，先移除）
                    List<Message> messages = new ArrayList<>();
                    if (ctx.getContextMessages() != null) {
                        messages.addAll(ctx.getContextMessages());
                    }
                    String chatContextSystemPrompt = removeSandboxSystemPrompt(ctx.getAgentConfig().getSystemPrompt());
                    messages.add(0, new SystemMessage(chatContextSystemPrompt == null ? "" : chatContextSystemPrompt));
                    messages.add(new UserMessage(ctx.getMessage()));
                    ctx.getVariableParams().put(var.getName(), messages);
                }
            }
            // 自定义变量默认值（前端未传且配置了默认值时填充）
            if (!var.isSystemVariable() && !ctx.getVariableParams().containsKey(var.getName())
                    && StringUtils.isNotBlank(var.getBindValue())) {
                ctx.getVariableParams().put(var.getName(), var.getBindValue());
            }
        });
        if (userArgs.isEmpty()) {
            return Mono.empty();
        }
        // 模型提取 AutoRecognition 变量（JSON 结构化输出，对齐 AgentExecutor:1266）
        userArgs.forEach(arg -> arg.setDataType(DataTypeEnum.String));
        ModelContext modelContext = buildExtractContext(ctx, mctx, userArgs);
        // 过程输出：变量设置组件 EXECUTING
        ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
        componentExecuteResult.setStartTime(System.currentTimeMillis());
        componentExecuteResult.setName(I18nUtil.systemMessage(ctx.getUser().getLangMap(), "Backend.Agent.Component.VariableSetting"));
        componentExecuteResult.setType(ComponentTypeEnum.Variable);
        componentExecuteResult.setSuccess(true);
        if (ctx.getAgentExecuteResult() != null) {
            ctx.getAgentExecuteResult().getComponentExecuteResults().add(componentExecuteResult);
        }
        sink.next(buildProcessOutput(variableComponent, ExecuteStatusEnum.EXECUTING, ComponentTypeEnum.Variable, componentExecuteResult));
        return modelInvoker.invoke(modelContext)
                .doOnComplete(() -> {
                    Object res = modelContext.getModelCallResult().getData();
                    if (res instanceof Map) {
                        ((Map<?, ?>) res).forEach((k, v) -> ctx.getVariableParams().put(String.valueOf(k), v));
                    }
                    componentExecuteResult.setEndTime(System.currentTimeMillis());
                    componentExecuteResult.setData(res);
                    sink.next(buildProcessOutput(variableComponent, ExecuteStatusEnum.FINISHED, ComponentTypeEnum.Variable, componentExecuteResult));
                })
                .doOnError(err -> {
                    // doOnError 不吞错：仅标记过程输出，错误仍沿链传播给 execute 的 onErrorResume
                    log.warn("[HybridAgentLoop] variable extraction failed: {}", err.getMessage());
                    componentExecuteResult.setEndTime(System.currentTimeMillis());
                    componentExecuteResult.setSuccess(false);
                    componentExecuteResult.setError(err.getMessage());
                    sink.next(buildProcessOutput(variableComponent, ExecuteStatusEnum.FAILED, ComponentTypeEnum.Variable, componentExecuteResult));
                })
                .then();
    }

    /**
     * 构造变量/参数提取专用 ModelContext（JSON 结构化输出，上下文与正常 LLM 请求一致）。
     */
    private ModelContext buildExtractContext(AgentContext ctx, MasterLoopContext mctx, List<Arg> outputArgs) {
        ModelContext modelContext = new ModelContext();
        modelContext.setRequestId(ctx.getRequestId());
        modelContext.setConversationId(ctx.getConversationId());
        modelContext.setModelConfig((ModelConfigDto) ctx.getAgentConfig().getModelComponentConfig().getTargetConfig());
        // 变量提取与正常 LLM 请求使用相同的上下文（仅输出格式 JSON，用户要求）：
        // system = 总控提示词 + 提取指令（前缀与正常请求一致，长上下文缓存可复用）；
        // user = buildUserPrompt（与正常请求完全一致，含用户最后一条消息/记忆/提醒/附件）；
        // 历史执行链 = buildHistoryMessages（与正常请求一致，完整上下文）。
        // 提取请求必须让 ModelInvoker 追加 user 消息（buildUserMessage 会携带 JSON schema 与附件媒体）：
        // 主 ctx executionMessagesComplete=true 会跳过追加（导致 user/schema/media 全部丢失），
        // 故用副本置 false，contextMessages 注入历史执行链（user 由 ModelInvoker 追加在历史之后，顺序合规）。
        AgentContext extractCtx = new AgentContext();
        BeanUtils.copyProperties(ctx, extractCtx);
        extractCtx.setContextMessages(buildHistoryMessages(mctx));
        extractCtx.setExecutionMessagesComplete(false);
        modelContext.setAgentContext(extractCtx);
        ModelBindConfigDto modelBindConfigDto = (ModelBindConfigDto) ctx.getAgentConfig().getModelComponentConfig().getBindConfig();
        ModelCallConfigDto callConfig = new ModelCallConfigDto();
        callConfig.setSystemPrompt(buildHybridSystemPrompt(ctx) + "\n\n" + EXTRACT_PARAM_PROMPT);
        callConfig.setUserPrompt(buildUserPrompt(ctx));
        callConfig.setChatRound(0);
        callConfig.setStreamCall(true);
        callConfig.setOutputType(OutputTypeEnum.JSON);
        callConfig.setOutputArgs(outputArgs);
        if (modelBindConfigDto != null) {
            callConfig.setMaxTokens(modelBindConfigDto.getMaxTokens());
            callConfig.setTemperature(modelBindConfigDto.getTemperature());
            callConfig.setTopP(modelBindConfigDto.getTopP());
        }
        modelContext.setModelCallConfig(callConfig);
        modelContext.setTraceContext(ctx.getTraceContext().next(TraceContext.TraceTargetType.Model,
                modelContext.getModelConfig().getId().toString(), modelContext.getModelConfig().getModel(),
                modelContext.getModelConfig().getName(), null, ctx.isDefaultModelChanged() ? ctx.getUserId() : null));
        return modelContext;
    }

    /**
     * 模型提取参数（AUTO 组件必填参数提取，blockLast 同步等待，对齐 AgentExecutor:1348）。
     * 仅在 boundedElastic 线程执行（streamWithNativeFC 已 subscribeOn）。
     */
    private Map<String, Object> extractParam(AgentContext ctx, List<Arg> args, MasterLoopContext mctx) {
        ModelContext modelContext = buildExtractContext(ctx, mctx, args);
        modelInvoker.invoke(modelContext).blockLast();
        Object res = modelContext.getModelCallResult().getData();
        if (!(res instanceof Map)) {
            return new HashMap<>();
        }
        return (Map<String, Object>) res;
    }

    /**
     * directOutput=Y 工作流短路输出：结果直接呈现给用户，不调用模型（对齐 AgentExecutor）。
     * 前置调用记录已入 toolChain，随 commitExecContext 持久化执行链。
     */
    private Mono<Void> emitDirectOutput(AgentContext ctx, FluxSink<AgentOutputDto> sink, MasterLoopContext mctx,
                                        List<String> directOutputResults) {
        return Mono.<Void>fromRunnable(() -> {
            String directText = String.join("\n\n", directOutputResults);
            mctx.textAccum.append(directText);
            completeSuccess(ctx, sink, directText, mctx.thinkAccum.toString(), mctx.componentExecutedList, true);
        });
    }

    // ======================== Control Signal Detection & Dispatch ========================

    /**
     * 判断是否为可重试的瞬时错误（网络超时、连接重置、限流、5xx）。
     */
    private boolean isTransientError(Throwable e) {
        String msg = e.getMessage();
        if (msg == null) {
            return true; // 未知错误默认可重试
        }
        String lower = msg.toLowerCase();
        return lower.contains("timeout") || lower.contains("timed out")
                || lower.contains("connection reset") || lower.contains("connection refused")
                || lower.contains("429") || lower.contains("rate limit")
                || lower.contains("502") || lower.contains("503") || lower.contains("504")
                || lower.contains("service unavailable") || lower.contains("bad gateway");
    }

    /**
     * 解析控制信号并分发：{@code sandbox}（自答型，输出直接给用户）与
     * {@code memory_query} / {@code memory_save} / {@code memory_delete}（结果回喂型，执行后再调一次模型）。
     * 准入面是 {@link #CONTROL_ACTIONS}，不在名单里的 action 降级成普通文本（防控制 JSON 泄漏）。
     * 返回 Mono&lt;Void&gt;，handler 输出通过 doOnNext(sink::next) 桥接（统一输入输出）。
     */
    private Mono<Void> dispatchControlSignal(MasterLoopContext mctx, String rawJson,
                                             FluxSink<AgentOutputDto> sink) {
        AgentContext ctx = mctx.ctx();
        return Mono.defer(() -> {
            JSONObject control;
            try {
                control = JSON.parseObject(rawJson.trim());
            } catch (Exception e) {
                log.warn("[HybridAgentLoop] control signal parse failed, emit as text. raw={}", rawJson);
                return emitAsSelfAnswer(ctx, rawJson, sink);
            }

            String action = control.getString("action");
            log.info("[HybridAgentLoop] control action={}, params={}", action, rawJson);

            if (!CONTROL_ACTIONS.contains(action)) {
                log.warn("[HybridAgentLoop] unknown action [{}], emit as text", action);
                return emitAsSelfAnswer(ctx, rawJson, sink);
            }

            // 记忆管理工具（结果回喂型，与 MasterAgentLoop 共用 MemoryToolProvider）：执行 → 结果与 tool_use
            // 按 toolCallId 配对入 toolChain → 二次调模型，由模型基于结果最终回答用户。
            // ★Control 型工具被 ModelInvoker 强制 returnDirect=true，模型调它【不产生任何用户可见输出】，
            //   所以这条腿必须走完回喂（handleMemory → continueWithContext）；照抄 sandbox 那条自答腿的写法
            //   会让用户一个字都收不到、sink 也永不 complete。
            if (memoryToolProvider.isMemoryAction(action)) {
                if (!memoryToolProvider.isOpen(ctx)) {
                    // 防御：未开启长期记忆时本轮不会挂记忆工具（正常不产生），兜底防控制 JSON 泄漏给用户。
                    // 不能返回 Mono.empty()——那样 sink 无任何输出也永不 complete，请求会静默悬挂；
                    // 与 sandbox 未启用同样降级成友好提示的自答收尾
                    log.warn("[HybridAgentLoop] long memory disabled, ignore memory control signal: {}", action);
                    return emitAsSelfAnswer(ctx, "长期记忆未开启，我无法查询或修改你的记忆。", sink);
                }
                // assistant(tool_use) 入 toolChain（与 handleMemory 内的 appendToolResult 配对）
                recordToolCall(mctx, action, rawJson);
                return handleMemory(mctx, action, control, sink);
            }

            if (!isSandboxEnabled(ctx) && !userSelectedSandbox(ctx) && !hasSkill(ctx)) {
                // 防御：sandbox 开关关闭、用户未选择沙箱、且智能体未配置 skill 时忽略 sandbox 控制信号
                // （正常不挂工具不会产生，此处兜底防 JSON 泄漏；用户已选择沙箱 / 配置了 skill 视为强制启用，放行）。
                // 不能返回 Mono.empty()——那样 sink 无任何输出也永不 complete，请求会静默悬挂；
                // 降级为友好提示的自答收尾（与未知 action 的降级一致）
                log.warn("[HybridAgentLoop] sandbox disabled, ignore sandbox control signal: {}", action);
                return emitAsSelfAnswer(ctx, "沙箱执行未启用，请直接描述您的问题，我来为您解答。", sink);
            }

            IntentDecision decision = control.to(IntentDecision.class);
            // assistant(tool_use) 入 toolChain（与 handleSandbox 内的 appendToolResult 配对）
            recordToolCall(mctx, action, rawJson);

            // 桥接 handler 输出到 sink（对齐 MasterAgentLoop 的 doOnNext 模式，无嵌套 subscribe）。
            // 沙箱执行输出（CallMessage → AgentOutputDto）与总控自答输出走同一个 sink，统一输入输出。
            return handleSandbox(mctx, decision, sink)
                    .doOnNext(sink::next)
                    .doOnComplete(sink::complete)
                    .then()
                    .onErrorResume(err -> {
                        log.error("[HybridAgentLoop] handler error: {}", err.getMessage(), err);
                        return Mono.<Void>fromRunnable(() -> {
                            // 兜底路径（handleSandbox 内层未拦截的异常）：此时 tool_use 已入 toolChain
                            // 但可能没有配对 tool(result)，保守置 errorOccurred 跳过执行链提交，避免孤儿
                            // tool_use 污染执行链；错误信息仍写入 roundMessage，用户刷新后可见
                            // （对齐 AgentExecutor.chatError）。
                            mctx.getErrorOccurred().set(true);
                            // 错误提示优先展示真实错误信息（err.getMessage），为空时才用兜底文案
                            String errText = StringUtils.defaultIfBlank(err.getMessage(), "处理过程中出现异常，请稍后重试。");
                            addAssistantRoundMessage(ctx, errText, "");
                            setErrorResult(ctx, err.getMessage());
                            sink.next(buildTextMessage(ctx, errText));
                            sink.next(AgentExecutor.buildFinalResultOutput(ctx));
                            sink.complete();
                        });
                    });
        });
    }

    /**
     * 控制信号解析失败/未知 action 时的降级：作为普通文本输出
     */
    private Mono<Void> emitAsSelfAnswer(AgentContext ctx, String text,
                                        FluxSink<AgentOutputDto> sink) {
        return Mono.<Void>fromRunnable(() -> completeSuccess(ctx, sink, text, "", null, true));
    }

    // ==================== Branch: MEMORY（记忆管理：查询/写入更新/删除，结果回喂型） ====================

    /**
     * 记忆工具执行：清单与 RPC 都在 {@link MemoryToolProvider}（与 {@code MasterAgentLoop} 共用同一个 bean），
     * 这里只做 loop 侧的「配对 + 回喂」。
     * <p>
     * tool_use 已由 {@link #dispatchControlSignal} 记录；本方法把结果按<b>同一个 action 名</b>追加成
     * tool(result)（名字不一致就复用不到 toolCallId → 配对断裂 → 协议校验直接失败，见 {@link #appendToolResult}），
     * 再带着 {@link MemoryToolProvider.Op#instruction()} 二次调模型，由模型基于结果回答用户。
     * <p>
     * ★provider 的三个方法<b>不抛异常</b>（失败也返回可回喂的结果文本），所以这里不需要额外兜底；
     * 真抛出去也会落到 {@link #invokeModel} 的 onErrorResume → handleModelError，不会静默悬挂。
     */
    private Mono<Void> handleMemory(MasterLoopContext mctx, String action, JSONObject control,
                                    FluxSink<AgentOutputDto> sink) {
        AgentContext ctx = mctx.ctx();
        MemoryToolProvider.Op op = switch (action) {
            case MemoryToolProvider.ACTION_QUERY -> memoryToolProvider.query(ctx, control);
            case MemoryToolProvider.ACTION_SAVE -> memoryToolProvider.save(ctx, control);
            case MemoryToolProvider.ACTION_DELETE -> memoryToolProvider.delete(ctx, control);
            // 进不来：调用点已用 isMemoryAction 收口。留这一支只为 switch 穷尽 ——
            // 真进了说明 CONTROL_ACTIONS 与 MemoryToolProvider.ACTIONS 两份名单漂移了，必须炸出来
            default -> throw new IllegalStateException("not a memory action: " + action);
        };
        appendToolResult(mctx, op.action(), op.result());
        return continueWithContext(mctx, sink, op.instruction());
    }

    /**
     * 结果回喂型控制工具的统一续跑入口（memory_query / memory_save / memory_delete）。
     * <p>
     * 工具结果已由调用方经 {@link #appendToolResult} 追加到 {@code toolChain}（与 {@link #recordToolCall} 的
     * {@code assistant(tool_use)} 形成 "tool_use → tool_result" 配对），本方法只负责把当轮指令作为追加提示
     * 再调一次模型，由模型基于完整轨迹最终回答用户。
     * <p>
     * 防循环：每次递增 {@link MasterLoopContext#getToolCallCount()}，超过 {@link #MAX_TOOL_CALLS} 后
     * <b>不再挂载记忆工具</b>，强制模型基于已累积上下文直接回答。
     * <p>
     * ★这里与 {@code MasterAgentLoop} 有一处<b>刻意不同</b>：Master 超限后摘掉的是 search_tasks
     * （它的回喂型工具有一堆，摘一个并不能真正终止循环）；本 loop 的回喂型工具<b>只有记忆这三个</b>，
     * 摘掉它们才真的断掉「调工具 → 回喂 → 再调同一工具」的环。sandbox 仍照常挂载 ——
     * 它是自答型、输出直接给用户、不会再回到模型，构不成回喂环。
     */
    private Mono<Void> continueWithContext(MasterLoopContext mctx, FluxSink<AgentOutputDto> sink,
                                           String instruction) {
        boolean overLimit = mctx.getToolCallCount().incrementAndGet() > MAX_TOOL_CALLS;
        if (overLimit) {
            log.warn("[HybridAgentLoop] 工具调用已达上限 {}，停止挂载记忆工具，基于已累积上下文直接回答", MAX_TOOL_CALLS);
        }
        return invokeModel(mctx, sink, instruction, !overLimit);
    }


    // ======================== Branch: SANDBOX（复杂任务沙箱执行，统一输入输出核心） ========================

    /**
     * 沙箱执行分支：in-process 调 {@link SandboxAgentClient#chat} 执行复杂任务。
     * <p>
     * 与 MasterAgentLoop 派发（fire-and-forget 建子会话）的本质区别：<b>不新建会话、不分发</b>，
     * conversationId 保持总控自己的，沙箱 {@code Flux<CallMessage>} 流式映射为 {@code AgentOutputDto}
     * 推送到总控的同一个 sink，最终输出收敛回本 loop 统一输出给用户。
     * <p>
     * 流程：
     * <ol>
     *   <li>inputMessage 为空 → {@link #emitSimpleResponse} 提示</li>
     *   <li>构造沙箱上下文（复制总控上下文，userPrompt=inputMessage，useSate=false 走 conversation 跨轮复用）</li>
     *   <li>流式转发：THINK → thinkAccum + 输出；CHAT → textAccum + 输出（含沙箱 &lt;task-result&gt; 直通）</li>
     *   <li>完成 → 结果摘要作为 tool(result) 入执行链 + assistant 轮消息 + FINAL_RESULT + complete</li>
     *   <li>错误 → 错误文本 + FINAL_RESULT + complete</li>
     * </ol>
     *
     * @param sink 总控输出通道（供沙箱组件执行回调同步转发 PROCESSING）
     */
    private Flux<AgentOutputDto> handleSandbox(MasterLoopContext mctx, IntentDecision decision,
                                               FluxSink<AgentOutputDto> sink) {
        AgentContext ctx = mctx.ctx();
        String inputMessage = decision.getInputMessage();
        if (StringUtils.isBlank(inputMessage)) {
            return emitSimpleResponse(ctx, "请描述需要沙箱执行的任务。");
        }

        // 构造沙箱上下文（重置组件占位符 / 注入执行回调，会话复用逻辑见 buildSandboxContext）
        AgentContext sandboxCtx;
        try {
            sandboxCtx = buildSandboxContext(mctx, inputMessage, sink);
            if (mctx.getAgentContext().getSandboxSkillPushConsumer() != null) {
                mctx.getAgentContext().getSandboxSkillPushConsumer().accept(mctx.agentContext);
                mctx.getAgentContext().setSandboxSkillPushConsumer(null);
            }
        } catch (Exception e) {
            log.error("[HybridAgentLoop] build sandbox context failed", e);
            return emitSimpleResponse(ctx, "沙箱环境初始化失败：" + e.getMessage());
        }
        // 流式桥接：沙箱 CallMessage 流 → AgentOutputDto 流，全部走总控 sink（统一输入输出）
        return sandboxAgentClient.chat(sandboxCtx)
                .doOnNext(msg -> {
                    // 沙箱输出累积到总控文本（跨轮执行链可见，commitExecContext 时作为 assistant 收尾）
                    if (msg.getType() == MessageTypeEnum.THINK) {
                        mctx.thinkAccum.append(msg.getText() == null ? "" : msg.getText());
                    } else {
                        mctx.textAccum.append(msg.getText() == null ? "" : msg.getText());
                    }
                })
                .map(msg -> buildCallMessageOutput(ctx, msg))
                .doOnComplete(() -> {
                    // 沙箱执行完成：结果写入执行链 + 会话消息 + 成功结果
                    String finalText = mctx.textAccum.toString();
                    appendToolResult(mctx, "sandbox", buildSandboxResultSummary(finalText));
                    addAssistantRoundMessage(ctx, finalText, mctx.thinkAccum.toString(), mctx.componentExecutedList);
                    setSuccessResult(ctx, finalText);
                    // 沙箱执行正常结束：刷新"上次沙箱执行结束时间"，10 分钟内下一条消息直连沙箱
                    recordLastSandboxTime(ctx);
                    // 长期记忆总结入队（沙箱成功也是正常完成轮次，推送摘要）
                    pushMemorySummary(ctx);
                })
                // 正常完成后追加 FINAL_RESULT 收尾（外层 doOnComplete(sink::complete) 转发完成信号）
                .concatWith(Flux.defer(() -> Flux.just(AgentExecutor.buildFinalResultOutput(ctx))))
                .onErrorResume(err -> {
                    log.error("[HybridAgentLoop] sandbox execution error", err);
                    // 对齐 AgentExecutor.chatError 的数据保全：
                    // ① 已执行部分 + 错误说明统一收进 textAccum，随 commitExecContext 作为 assistant 收尾
                    //    提交执行链，下一轮模型能看到"上次沙箱执行失败"，可精准继续
                    String partialText = mctx.textAccum.toString();
                    // 错误提示优先展示真实错误信息（err.getMessage），为空时才用兜底文案
                    String errText = StringUtils.defaultIfBlank(err.getMessage(), "沙箱执行过程中出现异常，请稍后重试。");
                    mctx.textAccum.append(StringUtils.isNotBlank(partialText) ? "\n\n" + errText : errText);
                    // ② tool_use 配对：失败也记录 tool(result)（已执行部分摘要 + 错误），避免孤儿 tool_use
                    appendToolResult(mctx, "sandbox",
                            (StringUtils.isNotBlank(partialText) ? "已执行部分：\n" + buildSandboxResultSummary(partialText) + "\n\n" : "")
                                    + "执行失败：" + StringUtils.defaultIfBlank(err.getMessage(), "未知异常"));
                    // ③ 已有输出 + 错误说明写入 roundMessage（用户刷新后可见）
                    addAssistantRoundMessage(ctx, mctx.textAccum.toString(), mctx.thinkAccum.toString(),
                            mctx.componentExecutedList);
                    setErrorResult(ctx, err.getMessage());
                    // 沙箱执行失败也刷新"上次沙箱执行结束时间"：沙箱会话可能仍存活，
                    // 失败后用户紧接的下一条消息应直连沙箱继续尝试（而非让模型重新判断）
                    recordLastSandboxTime(ctx);
                    // 已流式输出的部分不重复推送，只推送错误说明 + 最终结果
                    return Flux.just(buildTextMessage(ctx, errText), AgentExecutor.buildFinalResultOutput(ctx));
                });
    }

    /**
     * 记录"上次沙箱执行结束时间"（毫秒时间戳）到 sessionState → conversation.variables 跨轮持久化
     * （随 commitExecContext → persistSessionState 落库）。
     * 由 {@link #handleSandbox} 在沙箱执行结束（成功 doOnComplete / 失败 onErrorResume）时调用；
     * 10 分钟内下一条用户消息经 {@link #shouldContinueSandbox} 直连沙箱（跳过模型路由）。
     */
    private void recordLastSandboxTime(AgentContext ctx) {
        try {
            ctx.getSessionState().put(LAST_SANDBOX_TIME_KEY, String.valueOf(System.currentTimeMillis()));
        } catch (Exception e) {
            log.warn("[HybridAgentLoop] record last sandbox time failed: {}", e.getMessage());
        }
    }

    /**
     * 构造沙箱执行上下文（对齐 AgentExecutor 的 TaskAgent 沙箱路径）。
     * <p>
     * 关键点：
     * <ul>
     *   <li>{@code BeanUtils.copyProperties} 复制总控上下文：conversation（同会话，不新建）、user、
     *       traceContext、sandboxSessionCreatedConsumer（上层注入，随复制带到沙箱）等</li>
     *   <li>{@code userPrompt = inputMessage}（沙箱看到的任务描述），{@code useSate=false}：
     *       SandboxAgentClient 走 conversation.sandboxSessionId 跨轮复用沙箱会话</li>
     *   <li>contextMessages 保留总控执行链（沙箱 buildContextPrompt 会带上历史，fresh 会话时可见）</li>
     *   <li>组件工具占位符替换（resetToolBlock），沙箱 systemPrompt/message 里能引用绑定组件</li>
     *   <li>componentExecutingConsumer 转发沙箱组件执行进度 PROCESSING → 总控 sink，结果累积入执行结果</li>
     * </ul>
     *
     * @param sink 总控输出通道（组件执行回调同步转发）
     */
    private AgentContext buildSandboxContext(MasterLoopContext mctx, String inputMessage,
                                             FluxSink<AgentOutputDto> sink) {
        AgentContext ctx = mctx.ctx();
        AgentContext sandboxCtx = new AgentContext();
        BeanUtils.copyProperties(ctx, sandboxCtx);
        // BeanUtils.copyProperties 是浅拷贝，sandboxCtx 与 ctx 共享同一个 AgentConfig 对象；
        // 下方会 setSystemPrompt 改写沙箱提示词，若不隔离会污染总控 ctx 的 systemPrompt。
        // 复制一个独立 AgentConfig 副本（仅 systemPrompt 字段会被改写，其余字段沙箱只读，嵌套引用共享无碍）。
        AgentConfigDto sandboxAgentConfig = new AgentConfigDto();
        BeanUtils.copyProperties(ctx.getAgentConfig(), sandboxAgentConfig);
        sandboxCtx.setAgentConfig(sandboxAgentConfig);
        sandboxCtx.setMessage(inputMessage);
        sandboxCtx.setOriginalMessage(inputMessage);
        sandboxCtx.setUseSate(false);
        sandboxCtx.setExecutionMessagesComplete(false);
        sandboxCtx.setFinished(new AtomicBoolean(false));
        // 中断标志共享：用户在总控层停止时沙箱同步感知（SandboxAgentClient 检测 ifInterrupted 会取消会话）
        sandboxCtx.setIfInterrupted(ctx.getIfInterrupted());

        // 组件工具（与总控模型看到的同一份），替换工具技能占位符（对齐 AgentExecutor:322）
        List<ComponentConfig> componentTools = buildComponentTools(ctx);
        sandboxCtx.setMessage(ModelInvoker.resetToolBlock(componentTools, sandboxCtx.getMessage()));
        // 沙箱智能体请求：仅使用 <sandbox_system_prompt> 块内内容（无标签时回退完整 systemPrompt，兼容现有配置）
        String sandboxSystemPrompt = extractSandboxSystemPrompt(ctx.getAgentConfig().getSystemPrompt());
        if (sandboxSystemPrompt == null) {
            sandboxSystemPrompt = ctx.getAgentConfig().getSystemPrompt();
        }
        sandboxCtx.getAgentConfig().setSystemPrompt(
                ModelInvoker.resetToolBlock(componentTools, sandboxSystemPrompt));

        // 上下文块拼接进沙箱任务描述（对齐 buildUserPrompt ①③④ 的块格式）：
        // 沙箱 userPrompt = contextPromptBuilder + message，直接携带长期记忆/自定义指令/附件，
        // 不依赖 contextMessages 的时序/内容（buildContextPrompt 会剥离 user-reminder，附件仅存在于本轮 user prompt）
        StringBuilder sandboxPrompt = new StringBuilder();
        if (StringUtils.isNotBlank(ctx.getLongMemory())) {
            sandboxPrompt.append("<user-memory>\n").append(ctx.getLongMemory()).append("\n</user-memory>\n\n");
        }
        if (StringUtils.isNotBlank(ctx.getAgentConfig().getUserPrompt())) {
            sandboxPrompt.append("<user-reminder>\n")
                    .append(PlaceholderParser.resoleAndReplacePlaceholder(ctx.getVariableParams(),
                            ctx.getAgentConfig().getUserPrompt()))
                    .append("\n</user-reminder>\n\n");
        }
        if (CollectionUtils.isNotEmpty(ctx.getAttachments())) {
            sandboxPrompt.append("<attachments>\n").append(JSON.toJSONString(ctx.getAttachments()))
                    .append("\n</attachments>\n\n");
        }
        if (!sandboxPrompt.toString().isEmpty()) {
            sandboxCtx.setMessage(sandboxPrompt + sandboxCtx.getMessage());
        }

        // 组件执行进度 → PROCESSING 转发总控 sink + 结果累积（对齐 streamWithNativeFC 的 consumer）
        sandboxCtx.setComponentExecutingConsumer((componentExecutingDto) -> {
            AgentOutputDto processingOutput = new AgentOutputDto();
            processingOutput.setEventType(AgentOutputDto.EventTypeEnum.PROCESSING);
            processingOutput.setRequestId(ctx.getRequestId());
            processingOutput.setData(componentExecutingDto);
            sink.next(processingOutput);
            if (componentExecutingDto.getStatus() == ExecuteStatusEnum.FINISHED
                    || componentExecutingDto.getStatus() == ExecuteStatusEnum.FAILED) {
                if (ctx.getAgentExecuteResult() != null) {
                    ctx.getAgentExecuteResult().getComponentExecuteResults().add(componentExecutingDto.getResult());
                }
                mctx.textAccum.append(AgentExecutor.buildProcessingText(componentExecutingDto));
                mctx.componentExecutedList.add(componentExecutingDto);
            }
        });
        // sandboxSessionCreatedConsumer 已随 copyProperties 复制（上层 ChatApplicationServiceImpl 注入）：
        // SandboxAgentClient 创建会话后回调它，把 sandboxServerId + sandboxSessionId 持久化到 conversation，
        // 实现"继续"时同一沙箱会话跨轮复用。

        log.info("[HybridAgentLoop] sandbox ctx ready, convId={}, useSate=false, sandboxSessionId={}",
                ctx.getConversationId(),
                ctx.getConversation() != null ? ctx.getConversation().getSandboxSessionId() : null);
        return sandboxCtx;
    }

    /**
     * 沙箱最终结果摘要（写入执行链供模型回顾，完整内容已流式输出给用户）。
     * 超长时保留末尾 MAX_SANDBOX_RESULT_LENGTH 字（结论/报错通常在末尾，模型回顾更有效）。
     */
    private String buildSandboxResultSummary(String finalText) {
        String result = StringUtils.defaultString(finalText);
        if (result.length() > MAX_SANDBOX_RESULT_LENGTH) {
            return result.substring(result.length() - MAX_SANDBOX_RESULT_LENGTH) + "\n…（结果过长已截断，完整结果见上方消息）";
        }
        return result;
    }

    // ======================== Helpers ========================

    private Flux<AgentOutputDto> emitSimpleResponse(AgentContext ctx, String msg) {
        return Flux.create(sink -> completeSuccess(ctx, sink, msg, "", null, true));
    }

    /**
     * null-safe 设置错误结果
     */
    private void setErrorResult(AgentContext ctx, String error) {
        if (ctx.getAgentExecuteResult() != null) {
            ctx.getAgentExecuteResult().setSuccess(false);
            ctx.getAgentExecuteResult().setError(error);
            ctx.getAgentExecuteResult().setEndTime(System.currentTimeMillis());
        }
    }

    /**
     * null-safe 设置成功结果（对齐 AgentExecutor.chatComplete：设置 endTime + outputText）
     */
    private void setSuccessResult(AgentContext ctx, String outputText) {
        if (ctx.getAgentExecuteResult() != null) {
            ctx.getAgentExecuteResult().setOutputText(outputText);
            ctx.getAgentExecuteResult().setSuccess(true);
            ctx.getAgentExecuteResult().setEndTime(System.currentTimeMillis());
        }
    }

    /**
     * 正常完成轮次统一收尾：写 assistant 轮消息 + 成功结果 + 最终结果 + complete + 记忆摘要。
     * 自答 / directOutput / 降级文本 / 提示响应四个正常完成路径复用，避免后续新增收尾逻辑时漏改某一路径。
     *
     * @param emitText 是否先输出文本消息（流式已逐 token 输出过的路径传 false，避免重复输出全文）
     */
    private void completeSuccess(AgentContext ctx, FluxSink<AgentOutputDto> sink, String text,
                                 String think, List<ComponentExecutingDto> componentExecutedList, boolean emitText) {
        addAssistantRoundMessage(ctx, text, think, componentExecutedList);
        if (emitText) {
            sink.next(buildTextMessage(ctx, text));
        }
        setSuccessResult(ctx, text);
        sink.next(AgentExecutor.buildFinalResultOutput(ctx));
        sink.complete();
        // 长期记忆总结入队（对齐 AgentExecutor：对话完成后异步摘要）
        pushMemorySummary(ctx);
    }

    /**
     * 顶层兜底收尾：泄漏到 execute 最外层的任何错误统一收敛为「错误文案 + FINAL_RESULT + complete」。
     * <p>
     * sink 此前可能已有输出甚至已 complete（如正常收尾后执行链持久化才抛错）：Reactor FluxSink 在
     * complete 之后对 next/complete 静默忽略，不会重复输出或抛异常。此处<b>不写 roundMessage/执行链</b>——
     * 写库操作本身可能就是错误源，兜底只保证请求一定以终结输出收敛，绝不悬挂。
     */
    private void finishWithError(AgentContext ctx, FluxSink<AgentOutputDto> sink, Throwable err) {
        log.error("[HybridAgentLoop] unexpected error escaped to top level: {}", err.getMessage(), err);
        setErrorResult(ctx, err.getMessage());
        // 错误提示优先展示真实错误信息（err.getMessage），为空时才用兜底文案
        String errText = StringUtils.defaultIfBlank(err.getMessage(), "处理过程中出现异常，请稍后重试。");
        try {
            sink.next(buildTextMessage(ctx, errText));
            sink.next(AgentExecutor.buildFinalResultOutput(ctx));
        } finally {
            sink.complete();
        }
    }

    // ======================== Control Tools Builder ========================

    /**
     * 构建控制工具列表（{@link ComponentTypeEnum#Control}，returnDirect=true）。
     * <p>
     * 仅一个 {@code sandbox} 工具：复杂任务执行通道。模型通过原生 function call 调用，
     * returnDirect 终止模型循环 → 控制信号 {@code {"action":"sandbox","inputMessage":"..."}}
     * 被 streamWithNativeFC 的 doOnNext 拦截 → dispatchControlSignal → handleSandbox。
     */
    private List<ComponentConfig> buildSandboxTools() {
        ComponentConfig sandbox = new ComponentConfig();
        sandbox.setType(ComponentTypeEnum.Control);
        sandbox.setFunctionName("sandbox");
        sandbox.setName("沙箱执行");
        sandbox.setDescription("在云端沙箱环境中执行需要真实运行环境的复杂任务（编写/运行代码、读写/处理文件、执行命令、数据分析、网页自动化等）。沙箱会自主完成整个任务，执行结果直接返回。");
        sandbox.setTargetId(0L);
        sandbox.setInputArgs(List.of(
                Arg.builder().name("inputMessage")
                        .description("交给沙箱的完整自包含任务描述，包含任务目标、输入数据/背景、步骤要求、约束与期望输出格式（沙箱没有本会话之外的上下文，必须写全）")
                        .dataType(DataTypeEnum.String).require(true).build()
        ));
        return List.of(sandbox);
    }

    // ======================== 工具前置调用（对齐 AgentExecutor） ========================

    /**
     * 模型调用前自动执行 AUTO 类型的组件（知识库/插件/MCP/工作流），结果注入模型上下文。
     * <p>
     * 对齐 AgentExecutor.invokeAndRemoveAutoComponents：
     * <ol>
     *   <li>收集 AUTO 插件/工作流/MCP 的必填参数（有必填时经模型提取 componentRequireParams）</li>
     *   <li>自动调用 AUTO 知识库（批量检索）</li>
     *   <li>自动调用 AUTO 插件/MCP/工作流，过程输出（EXECUTING/FINISHED/FAILED）推送 sink</li>
     *   <li>每次自动执行以 recordToolCall + appendToolResult 写入 toolChain
     *       （assistant(tool_use) → tool(result) 配对），随 buildExecutionMessages 注入模型并持久化执行链</li>
     *   <li>工作流 directOutput=Y → 结果进 directOutputResults（短路直接输出，用户确认支持）</li>
     *   <li>AUTO 组件执行后从配置列表移除（用户确认），避免再挂载为 function calling 工具导致重复调用</li>
     * </ol>
     */
    private void invokeAndRemoveAutoComponents(AgentContext ctx, FluxSink<AgentOutputDto> sink, MasterLoopContext mctx,
                                               List<String> directOutputResults) {
        // 提取 AUTO 自动执行工具的必要参数
        Map<String, Object> componentRequireParams = extractAutoInvokeComponentRequireParamValues(ctx, mctx);
        // 自动调用知识库（AUTO 类型批量检索）
        Iterator<AgentComponentConfigDto> ite = ctx.getAgentConfig().getAgentComponentConfigList().iterator();
        List<Long> knowledgeBaseIds = new ArrayList<>();
        KnowledgeBaseBindConfigDto knowledgeBaseBindConfig = null;
        AgentComponentConfigDto agentComponentConfig = null;
        while (ite.hasNext()) {
            AgentComponentConfigDto dto = ite.next();
            if (dto.getType() == AgentComponentConfig.Type.Knowledge) {
                KnowledgeBaseBindConfigDto bind = (KnowledgeBaseBindConfigDto) dto.getBindConfig();
                if (bind != null && bind.getInvokeType() == KnowledgeBaseBindConfigDto.InvokeTypeEnum.AUTO) {
                    if (knowledgeBaseBindConfig == null) {
                        knowledgeBaseBindConfig = bind;
                    }
                    if (agentComponentConfig == null) {
                        agentComponentConfig = dto;
                    }
                    knowledgeBaseIds.add(dto.getTargetId());
                    ite.remove(); // AUTO 前置执行后从挂载移除（用户确认）
                }
            }
        }
        if (!knowledgeBaseIds.isEmpty()) {
            ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
            componentExecuteResult.setName(agentComponentConfig.getName());
            componentExecuteResult.setIcon(agentComponentConfig.getIcon());
            componentExecuteResult.setId(knowledgeBaseIds.get(0));
            componentExecuteResult.setStartTime(System.currentTimeMillis());
            componentExecuteResult.setExecuteId(UUID.randomUUID().toString().replace("-", ""));
            componentExecuteResult.setType(ComponentTypeEnum.Knowledge);
            // 规范工具名：recordToolCall / appendToolResult 成功 / 异常兜底三处必须同名，否则 appendToolResult
            // 按名倒查不到 tool_use → toolCallId 配对断裂 → tool_result 随机 id 与 tool_use 不一致 → 协议 400。
            // （旧实现 record 侧用 "knowledge"+id、append 侧用 getName() 显示名，两边必然对不上）
            String knowledgeToolName = resolveToolName(agentComponentConfig);
            try {
                if (ctx.getAgentExecuteResult() != null) {
                    ctx.getAgentExecuteResult().getComponentExecuteResults().add(componentExecuteResult);
                }
                sink.next(buildProcessOutput(agentComponentConfig, ExecuteStatusEnum.EXECUTING,
                        ComponentTypeEnum.Knowledge, componentExecuteResult));
                // assistant(tool_use) 入 toolChain（与 appendToolResult 配对）
                recordToolCall(mctx, knowledgeToolName, JSON.toJSONString(Map.of("query", ctx.getMessage())));
                String res = queryKnowledgeBase(ctx, knowledgeBaseIds, knowledgeBaseBindConfig, componentExecuteResult);
                appendToolResult(mctx, knowledgeToolName, res);
                componentExecuteResult.setEndTime(System.currentTimeMillis());
                sink.next(buildProcessOutput(agentComponentConfig, ExecuteStatusEnum.FINISHED,
                        ComponentTypeEnum.Knowledge, componentExecuteResult));
            } catch (Exception e) {
                componentExecuteResult.setSuccess(false);
                componentExecuteResult.setError(e.getMessage());
                componentExecuteResult.setEndTime(System.currentTimeMillis());
                sink.next(buildProcessOutput(agentComponentConfig, ExecuteStatusEnum.FAILED,
                        ComponentTypeEnum.Knowledge, componentExecuteResult));
                // 与 recordToolCall 同名（knowledgeToolName），否则 toolCallId 配对断裂
                appendToolResult(mctx, knowledgeToolName,
                        "Knowledge base search failed: " + e.getMessage());
            }
        }
        // 自动调用插件、MCP、工作流
        ite = ctx.getAgentConfig().getAgentComponentConfigList().iterator();
        while (ite.hasNext()) {
            AgentComponentConfigDto dto = ite.next();
            ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
            componentExecuteResult.setStartTime(System.currentTimeMillis());
            componentExecuteResult.setId(dto.getTargetId());
            componentExecuteResult.setExecuteId(UUID.randomUUID().toString().replace("-", ""));
            componentExecuteResult.setName(dto.getName());
            componentExecuteResult.setIcon(dto.getIcon());
            ComponentTypeEnum componentType = null;
            // 规范工具名：recordToolCall/appendToolResult/异常兜底必须同名，保证 toolCallId 配对（见 resolveToolName）
            String toolName = resolveToolName(dto);
            try {
                if (dto.getType() == AgentComponentConfig.Type.Plugin) {
                    PluginBindConfigDto bind = (PluginBindConfigDto) dto.getBindConfig();
                    if (bind != null && bind.getInvokeType() == PluginBindConfigDto.PluginInvokeTypeEnum.AUTO) {
                        componentType = ComponentTypeEnum.Plugin;
                        Map<String, Object> input = extractInput(bind.getInputArgBindConfigs(), componentRequireParams, dto.getId());
                        componentExecuteResult.setInput(JsonSerializeUtil.deepCopy(input));
                        componentExecuteResult.setType(componentType);
                        if (ctx.getAgentExecuteResult() != null) {
                            ctx.getAgentExecuteResult().getComponentExecuteResults().add(componentExecuteResult);
                        }
                        sink.next(buildProcessOutput(dto, ExecuteStatusEnum.EXECUTING, componentType, componentExecuteResult));
                        recordToolCall(mctx, toolName, JSON.toJSONString(input));
                        String res = invokePlugin(ctx, dto, input, componentExecuteResult);
                        appendToolResult(mctx, toolName, res);
                    }
                }
                if (dto.getType() == AgentComponentConfig.Type.Mcp) {
                    McpBindConfigDto bind = (McpBindConfigDto) dto.getBindConfig();
                    if (bind != null && bind.getInvokeType() == McpBindConfigDto.McpInvokeTypeEnum.AUTO) {
                        componentType = ComponentTypeEnum.Mcp;
                        Map<String, Object> input = extractInput(bind.getInputArgBindConfigs(), componentRequireParams, dto.getId());
                        componentExecuteResult.setInput(JsonSerializeUtil.deepCopy(input));
                        componentExecuteResult.setType(componentType);
                        if (ctx.getAgentExecuteResult() != null) {
                            ctx.getAgentExecuteResult().getComponentExecuteResults().add(componentExecuteResult);
                        }
                        sink.next(buildProcessOutput(dto, ExecuteStatusEnum.EXECUTING, componentType, componentExecuteResult));
                        recordToolCall(mctx, toolName, JSON.toJSONString(input));
                        String res = invokeMcp(ctx, dto, input, componentExecuteResult);
                        appendToolResult(mctx, toolName, res);
                    }
                }
                if (dto.getType() == AgentComponentConfig.Type.Workflow) {
                    WorkflowBindConfigDto bind = (WorkflowBindConfigDto) dto.getBindConfig();
                    if (bind != null && bind.getInvokeType() == WorkflowBindConfigDto.WorkflowInvokeTypeEnum.AUTO) {
                        componentType = ComponentTypeEnum.Workflow;
                        Map<String, Object> input = extractInput(bind.getArgBindConfigs(), componentRequireParams, dto.getId());
                        componentExecuteResult.setInput(JsonSerializeUtil.deepCopy(input));
                        componentExecuteResult.setType(componentType);
                        if (ctx.getAgentExecuteResult() != null) {
                            ctx.getAgentExecuteResult().getComponentExecuteResults().add(componentExecuteResult);
                        }
                        sink.next(buildProcessOutput(dto, ExecuteStatusEnum.EXECUTING, componentType, componentExecuteResult));
                        recordToolCall(mctx, toolName, JSON.toJSONString(input));
                        String res = invokeWorkflow(ctx, dto, input, componentExecuteResult);
                        if (bind.getDirectOutput() != null && bind.getDirectOutput().equals(YesOrNoEnum.Y.getKey())) {
                            // 直接输出短路（对齐 AgentExecutor）：结果直出，不再交给模型
                            if (!componentExecuteResult.getSuccess()) {
                                directOutputResults.clear();
                                directOutputResults.add(componentExecuteResult.getError());
                            } else {
                                directOutputResults.add(componentExecuteResult.getData() instanceof String
                                        ? (String) componentExecuteResult.getData()
                                        : JSON.toJSONString(componentExecuteResult.getData()));
                            }
                            res = ""; // 直接输出了，不再交给大模型
                        }
                        appendToolResult(mctx, toolName, res);
                    }
                }
                if (componentType != null) {
                    componentExecuteResult.setEndTime(System.currentTimeMillis());
                    sink.next(buildProcessOutput(dto, ExecuteStatusEnum.FINISHED, componentType, componentExecuteResult));
                }
            } catch (Exception e) {
                componentExecuteResult.setSuccess(false);
                componentExecuteResult.setError(e.getMessage());
                componentExecuteResult.setEndTime(System.currentTimeMillis());
                sink.next(buildProcessOutput(dto, ExecuteStatusEnum.FAILED, componentType, componentExecuteResult));
                if (dto.getExceptionOut() != null && dto.getExceptionOut().equals(YesOrNoEnum.Y.getKey())) {
                    throw e;
                } else if (StringUtils.isNotBlank(dto.getFallbackMsg())) {
                    appendToolResult(mctx, toolName, dto.getFallbackMsg());
                } else {
                    appendToolResult(mctx, toolName,
                            "Tool `" + toolName + "` invocation failed: " + e.getMessage());
                }
            }
        }
    }

    /**
     * 收集 AUTO 自动执行插件/工作流/MCP 的必填参数，有必填时经模型提取（对齐 AgentExecutor:519）。
     */
    private Map<String, Object> extractAutoInvokeComponentRequireParamValues(AgentContext ctx, MasterLoopContext mctx) {
        List<Arg> requireArgs = new ArrayList<>();
        List<Arg> allArgs = new ArrayList<>();
        ctx.getAgentConfig().getAgentComponentConfigList().forEach(dto -> {
            if (dto.getType() == AgentComponentConfig.Type.Plugin) {
                PluginBindConfigDto bind = (PluginBindConfigDto) dto.getBindConfig();
                if (bind != null && bind.getInvokeType() == PluginBindConfigDto.PluginInvokeTypeEnum.AUTO
                        && CollectionUtils.isNotEmpty(bind.getInputArgBindConfigs())) {
                    checkAndSetRequireArgs(bind.getInputArgBindConfigs(), requireArgs, allArgs, dto.getId());
                }
            }
            if (dto.getType() == AgentComponentConfig.Type.Workflow) {
                WorkflowBindConfigDto bind = (WorkflowBindConfigDto) dto.getBindConfig();
                if (bind != null && bind.getInvokeType() == WorkflowBindConfigDto.WorkflowInvokeTypeEnum.AUTO
                        && CollectionUtils.isNotEmpty(bind.getArgBindConfigs())) {
                    checkAndSetRequireArgs(bind.getArgBindConfigs(), requireArgs, allArgs, dto.getId());
                }
            }
            if (dto.getType() == AgentComponentConfig.Type.Mcp) {
                McpBindConfigDto bind = (McpBindConfigDto) dto.getBindConfig();
                if (bind != null && bind.getInvokeType() == McpBindConfigDto.McpInvokeTypeEnum.AUTO
                        && CollectionUtils.isNotEmpty(bind.getInputArgBindConfigs())) {
                    checkAndSetRequireArgs(bind.getInputArgBindConfigs(), requireArgs, allArgs, dto.getId());
                }
            }
        });
        // 没有必填参数时直接过
        if (requireArgs.isEmpty()) {
            return new HashMap<>();
        }
        return extractParam(ctx, allArgs, mctx);
    }

    private static void checkAndSetRequireArgs(List<Arg> argBindConfigs, List<Arg> requireArgs,
                                               List<Arg> allArgs, Long id) {
        if (argBindConfigs == null) {
            return;
        }
        argBindConfigs.forEach(argBindConfig -> {
            Arg arg = new Arg();
            BeanUtils.copyProperties(argBindConfig, arg);
            arg.setName(arg.getName() + "_" + id);
            allArgs.add(arg);
            if (argBindConfig.isRequire() && argBindConfig.getEnable()) {
                requireArgs.add(arg);
            }
        });
    }

    private static Map<String, Object> extractInput(List<Arg> inputArgBindConfigs,
                                                    Map<String, Object> componentRequireParams, Long id) {
        Map<String, Object> input = new HashMap<>();
        if (CollectionUtils.isNotEmpty(inputArgBindConfigs)) {
            for (Arg arg : inputArgBindConfigs) {
                if (!arg.getEnable()) {
                    continue;
                }
                Object value = componentRequireParams.get(arg.getName() + "_" + id);
                if (value != null) {
                    input.put(arg.getName(), value);
                }
            }
        }
        return input;
    }

    private static void checkAndCompleteInput(AgentContext ctx, List<Arg> argBindConfigs,
                                              Map<String, Object> input, List<String> errorList) {
        if (CollectionUtils.isEmpty(argBindConfigs)) {
            return;
        }
        ArgExtractUtil.setArgDefaultValue(ctx, argBindConfigs, input, null, errorList);
    }

    private String invokeMcp(AgentContext ctx, AgentComponentConfigDto dto, Map<String, Object> input,
                             ComponentExecuteResult componentExecuteResult) {
        McpBindConfigDto mcpBindConfigDto = (McpBindConfigDto) dto.getBindConfig();
        McpDto mcpDto = (McpDto) dto.getTargetConfig();
        McpContext mcpContext = McpContext.builder()
                .requestId(ctx.getRequestId())
                .conversationId(ctx.getConversationId())
                .user(ctx.getUser())
                .mcpDto((McpDto) dto.getTargetConfig())
                .params(input)
                .name(mcpBindConfigDto.getToolName())
                .traceContext(ctx.getTraceContext().next(TraceContext.TraceTargetType.Mcp,
                        mcpDto.getId().toString(), mcpDto.getName(), mcpDto.getDescription(), mcpDto.getIcon()))
                .build();
        try {
            McpExecuteOutput mcpExecuteOutput = mcpExecutor.execute(mcpContext).blockLast();
            if (mcpExecuteOutput == null || !mcpExecuteOutput.isSuccess()) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.validationFailedWithDetail,
                        mcpExecuteOutput == null ? "" : mcpExecuteOutput.getMessage());
            }
            componentExecuteResult.setSuccess(true);
            componentExecuteResult.setData(mcpExecuteOutput.getResult());
            return buildToolCallResult(mcpContext.getName(), JSON.toJSONString(mcpExecuteOutput.getResult()), null);
        } catch (Exception e) {
            log.error("MCP execution failed: {}", e.getMessage(), e);
            componentExecuteResult.setSuccess(false);
            componentExecuteResult.setError(e.getMessage());
            return buildToolCallResult(mcpContext.getName(), null, e.getMessage());
        }
    }

    private String invokeWorkflow(AgentContext ctx, AgentComponentConfigDto dto, Map<String, Object> input,
                                  ComponentExecuteResult componentExecuteResult) {
        try {
            WorkflowConfigDto workflowConfigDto = (WorkflowConfigDto) dto.getTargetConfig();
            if (workflowConfigDto == null) {
                throw new RuntimeException("tool execution failed");
            }
            List<String> errorList = new ArrayList<>();
            WorkflowBindConfigDto workflowBindConfigDto = (WorkflowBindConfigDto) dto.getBindConfig();
            checkAndCompleteInput(ctx, workflowBindConfigDto.getArgBindConfigs(), input, errorList);
            org.springframework.util.Assert.isTrue(errorList.isEmpty(), String.join(",", errorList));
            WorkflowContext workflowContext1 = new WorkflowContext();
            workflowContext1.setOriginalWorkflowId(workflowConfigDto.getId());
            workflowContext1.setAgentContext(ctx);
            workflowContext1.setRequestId(ctx.getRequestId());
            workflowContext1.setWorkflowConfig(workflowConfigDto);
            workflowContext1.setUseResultCache(workflowBindConfigDto.isUseResultCache());
            workflowContext1.setAsyncExecute(workflowBindConfigDto.getAsync() != null && workflowBindConfigDto.getAsync() == 1);
            workflowContext1.setAsyncReplyContent(workflowBindConfigDto.getAsyncReplyContent());
            workflowContext1.setNodeExecutingConsumer(nodeExecutingDto -> {
            });
            workflowContext1.setParams(input);
            workflowContext1.setTraceContext(ctx.getTraceContext().next(TraceContext.TraceTargetType.Workflow,
                    workflowConfigDto.getId().toString(), workflowConfigDto.getName(), workflowConfigDto.getDescription(), workflowConfigDto.getIcon()));
            Object res = workflowExecutor.execute(workflowContext1).block();
            EndNodeConfigDto endNodeConfigDto = (EndNodeConfigDto) workflowConfigDto.getEndNode().getNodeConfig();
            if (endNodeConfigDto.getReturnType() == EndNodeConfigDto.ReturnType.TEXT
                    && StringUtils.isNotBlank(workflowContext1.getEndNodeContent())) {
                res = workflowContext1.getEndNodeContent();
            }
            componentExecuteResult.setSuccess(true);
            if (workflowContext1.getWorkflowConfig().getSpaceId().equals(ctx.getAgentConfig().getSpaceId())) {
                // 工作流与智能体同属一个空间时才记录内部执行日志
                componentExecuteResult.setInnerExecuteInfo(workflowContext1.getNodeExecuteResultMap().values());
            }
            componentExecuteResult.setData(res);
            return buildToolCallResult(dto.getName(), res == null ? "" : JSON.toJSONString(res), null);
        } catch (Exception e) {
            log.error("Workflow invocation failed", e);
            componentExecuteResult.setSuccess(false);
            componentExecuteResult.setError(e.getMessage());
            return buildToolCallResult(dto.getName(), null, e.getMessage());
        }
    }

    private String invokePlugin(AgentContext ctx, AgentComponentConfigDto dto, Map<String, Object> input,
                                ComponentExecuteResult componentExecuteResult) {
        try {
            List<String> errorList = new ArrayList<>();
            PluginBindConfigDto pluginBindConfigDto = (PluginBindConfigDto) dto.getBindConfig();
            checkAndCompleteInput(ctx, pluginBindConfigDto.getInputArgBindConfigs(), input, errorList);
            org.springframework.util.Assert.isTrue(errorList.isEmpty(), String.join(",", errorList));
            PluginDto pluginDto = (PluginDto) dto.getTargetConfig();
            PluginContext pluginContext = PluginContext.builder()
                    .requestId(ctx.getRequestId())
                    .agentContext(ctx)
                    .pluginConfig((PluginConfigDto) pluginDto.getConfig())
                    .pluginDto(pluginDto)
                    .params(input)
                    .userId(ctx.getUserId())
                    .asyncExecute(pluginBindConfigDto.getAsync() != null && pluginBindConfigDto.getAsync() == 1)
                    .asyncReplyContent(pluginBindConfigDto.getAsyncReplyContent())
                    .traceContext(ctx.getTraceContext().next(TraceContext.TraceTargetType.Plugin,
                            pluginDto.getId().toString(), pluginDto.getName(), pluginDto.getDescription(), pluginDto.getIcon()))
                    .build();
            PluginExecuteResultDto pluginExecuteResultDto = pluginExecutor.execute(pluginContext).block();
            Object res = null;
            if (pluginExecuteResultDto != null) {
                componentExecuteResult.setSuccess(pluginExecuteResultDto.isSuccess());
                componentExecuteResult.setError(pluginExecuteResultDto.getError());
                componentExecuteResult.setData(pluginExecuteResultDto.getResult());
                if (!pluginExecuteResultDto.isSuccess()) {
                    throw new RuntimeException(pluginExecuteResultDto.getError());
                }
                res = pluginExecuteResultDto.getResult();
                componentExecuteResult.setInnerExecuteInfo(pluginExecuteResultDto.getLogs());
            }
            componentExecuteResult.setSuccess(true);
            componentExecuteResult.setData(res);
            return buildToolCallResult(dto.getName(), res == null ? "" : JSON.toJSONString(res), null);
        } catch (Exception e) {
            log.error("Plugin invocation failed", e);
            componentExecuteResult.setSuccess(false);
            componentExecuteResult.setError(e.getMessage());
            return buildToolCallResult(dto.getName(), null, e.getMessage());
        }
    }

    private String queryKnowledgeBase(AgentContext ctx, List<Long> knowledgeBaseIds,
                                      KnowledgeBaseBindConfigDto bind, ComponentExecuteResult componentExecuteResult) {
        SearchContext searchContext = new SearchContext();
        // 移除 agentContext.getMessage() 中 <attachment> 标签内容
        searchContext.setAgentContext(ctx);
        searchContext.setQuery(ctx.getMessage().replaceAll("<attachment>[\\s\\S]*?</attachment>", ""));
        searchContext.setSearchStrategy(bind.getSearchStrategy());
        searchContext.setMatchingDegree(bind.getMatchingDegree());
        searchContext.setMaxRecallCount(bind.getMaxRecallCount());
        searchContext.setKnowledgeBaseIds(knowledgeBaseIds);
        searchContext.setRequestId(ctx.getRequestId());
        componentExecuteResult.setInput(Map.of("query", searchContext.getQuery()));
        List<KnowledgeQaVo> qaVoList = knowledgeBaseSearcher.search(searchContext).block();
        componentExecuteResult.setSuccess(true);
        componentExecuteResult.setData(CollectionUtils.isEmpty(qaVoList) ? "No relevant information found" : qaVoList);
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("The following are the search results in the knowledge base:\n");
        if (!CollectionUtils.isEmpty(qaVoList)) {
            stringBuilder.append(JSON.toJSONString(qaVoList));
        } else {
            if (bind.getNoneRecallReplyType() == KnowledgeBaseBindConfigDto.NoneRecallReplyTypeEnum.CUSTOM
                    && StringUtils.isNotBlank(bind.getNoneRecallReply())) {
                stringBuilder.append(bind.getNoneRecallReply());
            } else {
                stringBuilder.append("No relevant information found");
            }
        }
        return stringBuilder.append("\n\n").toString();
    }

    private static String buildToolCallResult(String toolName, String result, String errorMessage) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("The following is the tool `").append(toolName).append("` invocation result:\n");
        if (result != null) {
            stringBuilder.append(result);
        }
        if (errorMessage != null) {
            stringBuilder.append("<error_message>").append(errorMessage).append("</error_message>");
        }
        return stringBuilder.append("\n\n").toString();
    }

    /**
     * 构造组件执行过程输出（EXECUTING/FINISHED/FAILED 状态卡片，对齐 AgentExecutor:1402）。
     */
    private static AgentOutputDto buildProcessOutput(AgentComponentConfigDto componentConfig, ExecuteStatusEnum executeStatus,
                                                     ComponentTypeEnum type, ComponentExecuteResult componentExecuteResult) {
        CardBindConfigDto cardBindDto = null;
        if (componentConfig.getType() == AgentComponentConfig.Type.Workflow) {
            WorkflowBindConfigDto workflowBindConfigDto = (WorkflowBindConfigDto) componentConfig.getBindConfig();
            cardBindDto = workflowBindConfigDto.getCardBindConfig();
        }
        if (componentConfig.getType() == AgentComponentConfig.Type.Plugin) {
            PluginBindConfigDto pluginBindConfigDto = (PluginBindConfigDto) componentConfig.getBindConfig();
            cardBindDto = pluginBindConfigDto.getCardBindConfig();
        }
        ComponentExecutingDto componentExecutingDto = new ComponentExecutingDto();
        componentExecutingDto.setTargetId(componentConfig.getTargetId());
        componentExecutingDto.setName(componentConfig.getName());
        componentExecutingDto.setType(type);
        componentExecutingDto.setStatus(executeStatus);
        componentExecutingDto.setResult(componentExecuteResult);
        componentExecutingDto.setCardBindConfig(cardBindDto);
        AgentOutputDto agentOutputDto = new AgentOutputDto();
        agentOutputDto.setData(componentExecutingDto);
        agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.PROCESSING);
        return agentOutputDto;
    }

    // ======================== Component Tools (插件/知识库/工作流/MCP) ========================

    /**
     * 将智能体绑定的组件（插件/知识库/工作流/MCP）转换为模型可调用的工具。
     * <p>
     * 对齐 ：简单任务由 ModelInvoker 在本机直接执行这些工具，
     * 无需沙箱。排除 {@code Type.Agent}（子智能体在本 loop 不派发）、{@code Type.Table}/{@code Type.Page}（不支持）。
     */
    private List<ComponentConfig> buildComponentTools(AgentContext ctx) {
        List<AgentComponentConfigDto> componentDtos = ctx.getAgentConfig().getAgentComponentConfigList();
        if (CollectionUtils.isEmpty(componentDtos)) {
            return List.of();
        }
        List<ComponentConfig> tools = new ArrayList<>();
        for (AgentComponentConfigDto dto : componentDtos) {
            // 子智能体不在本 loop 派发，不暴露为直接工具
            if (dto.getType() == AgentComponentConfig.Type.Agent) {
                continue;
            }
            try {
                ComponentConfig config = new ComponentConfig();
                config.setId(dto.getId());
                config.setName(dto.getName());
                config.setIcon(dto.getIcon());
                config.setTargetId(dto.getTargetId());
                config.setDescription(dto.getDescription());
                config.setExceptionOut(dto.getExceptionOut());
                config.setFallbackMsg(dto.getFallbackMsg());
                List<Arg> inputArgBindConfigs = null;
                switch (dto.getType()) {
                    case Knowledge:
                        KnowledgeBaseBindConfigDto kbBind = (KnowledgeBaseBindConfigDto) dto.getBindConfig();
                        config.setType(ComponentTypeEnum.Knowledge);
                        KnowledgeSearchConfigDto searchConfig = new KnowledgeSearchConfigDto();
                        searchConfig.setSearchStrategy(kbBind.getSearchStrategy());
                        searchConfig.setMatchingDegree(kbBind.getMatchingDegree());
                        searchConfig.setMaxRecallCount(kbBind.getMaxRecallCount());
                        searchConfig.setNoneRecallReplyType(kbBind.getNoneRecallReplyType());
                        searchConfig.setNoneRecallReply(kbBind.getNoneRecallReply());
                        config.setTargetConfig(searchConfig);
                        break;
                    case Plugin:
                        config.setType(ComponentTypeEnum.Plugin);
                        PluginDto pluginDto = (PluginDto) dto.getTargetConfig();
                        config.setTargetConfig(pluginDto);
                        config.setName(pluginDto.getName());
                        config.setFunctionName(pluginDto.getFunctionName());
                        config.setDescription(pluginDto.getDescription());
                        PluginBindConfigDto pluginBind = (PluginBindConfigDto) dto.getBindConfig();
                        config.setAsyncExecute(pluginBind.getAsync() != null && pluginBind.getAsync() == 1);
                        config.setAsyncReplyContent(pluginBind.getAsyncReplyContent());
                        inputArgBindConfigs = pluginBind.getInputArgBindConfigs();
                        config.setBindConfig(pluginBind);
                        break;
                    case Workflow:
                        config.setType(ComponentTypeEnum.Workflow);
                        WorkflowBindConfigDto wfBind = (WorkflowBindConfigDto) dto.getBindConfig();
                        WorkflowConfigDto wfConfig = (WorkflowConfigDto) dto.getTargetConfig();
                        config.setTargetConfig(wfConfig);
                        config.setName(wfConfig.getName());
                        config.setFunctionName(wfConfig.getFunctionName());
                        config.setDescription(wfConfig.getDescription());
                        config.setAsyncExecute(wfBind.getAsync() != null && wfBind.getAsync() == 1);
                        config.setAsyncReplyContent(wfBind.getAsyncReplyContent());
                        inputArgBindConfigs = wfBind.getArgBindConfigs();
                        config.setBindConfig(wfBind);
                        break;
                    case Mcp:
                        config.setType(ComponentTypeEnum.Mcp);
                        McpBindConfigDto mcpBind = (McpBindConfigDto) dto.getBindConfig();
                        config.setTargetConfig(dto.getTargetConfig());
                        config.setName(dto.getName());
                        config.setFunctionName(mcpBind.getToolName());
                        config.setDescription(getMcpToolDescription((McpDto) dto.getTargetConfig(), mcpBind.getToolName()));
                        config.setAsyncExecute(mcpBind.getAsync() != null && mcpBind.getAsync() == 1);
                        config.setAsyncReplyContent(mcpBind.getAsyncReplyContent());
                        inputArgBindConfigs = mcpBind.getInputArgBindConfigs();
                        config.setBindConfig(mcpBind);
                        break;
                    default:
                        // Table, Page 等：本 loop 暂不支持，跳过
                        continue;
                }
                if (!CollectionUtils.isEmpty(inputArgBindConfigs)) {
                    config.setInputArgs(inputArgBindConfigs.stream().map(arg -> (Arg) arg).toList());
                }
                tools.add(config);
            } catch (Exception e) {
                log.warn("[HybridAgentLoop] skip component [{}] type={}: {}", dto.getName(), dto.getType(), e.getMessage());
            }
        }
        if (!tools.isEmpty()) {
            log.info("[HybridAgentLoop] loaded {} component tools: {}", tools.size(),
                    tools.stream().map(ComponentConfig::getName).toList());
        }
        return tools;
    }

    /**
     * 获取 MCP 工具的精确描述（从已部署的工具列表中按 toolName 查找）。
     */
    private static String getMcpToolDescription(McpDto targetConfig, String toolName) {
        if (targetConfig.getDeployedConfig() == null || targetConfig.getDeployedConfig().getTools() == null) {
            return targetConfig.getDescription();
        }
        for (McpToolDto tool : targetConfig.getDeployedConfig().getTools()) {
            if (tool.getName().equals(toolName)) {
                return tool.getDescription();
            }
        }
        return targetConfig.getDescription();
    }

    // ======================== Context / List Builders ========================

    /**
     * 初始化 sessionState（对齐 MasterAgentLoop 模式）。
     * <p>
     * 从 conversation.variables 加载上一轮持久化的状态（含执行链），
     * 合并到 variableParams 后令 sessionState = variableParams（共享引用），
     * 通过 {@link #persistSessionState} 保存到 conversation.variables。
     */
    private void initSessionState(AgentContext ctx) {
        Map<String, Object> variableParams = ctx.getVariableParams();
        // 从会话变量中恢复上一轮的状态（执行链等）
        if (ctx.getConversation() != null && ctx.getConversation().getVariables() != null) {
            ctx.getConversation().getVariables().forEach((key, value) -> {
                if (!variableParams.containsKey(key)) {
                    variableParams.put(key, value);
                }
            });
        }
        ctx.setSessionState(variableParams);
    }

    /**
     * 持久化 sessionState 到会话变量（跨轮可见）。
     * 对齐 MasterAgentLoop 的 {@code conversationApplicationService.updateConversationVariables} 调用。
     */
    private void persistSessionState(AgentContext ctx) {
        try {
            if (ctx.getSessionState() != null && StringUtils.isNotBlank(ctx.getConversationId())) {
                conversationApplicationService.updateConversationVariables(
                        Long.parseLong(ctx.getConversationId()), ctx.getSessionState());
            }
        } catch (Exception e) {
            log.warn("[HybridAgentLoop] persist session state failed: {}", e.getMessage());
        }
    }

    /**
     * 加载长期记忆（对齐 AgentExecutor）。
     * 仅当智能体配置开启了 openLongMemory 时加载，异常不阻断主流程。
     */
    private void loadLongMemory(AgentContext ctx) {
        if (ctx.getAgentConfig().getOpenLongMemory() != AgentConfig.OpenStatus.Open) {
            return;
        }
        try {
            boolean justKeywordMatch = true;
            ctx.setLongMemory(conversationApplicationService.queryMemory(
                    ctx.getUser().getTenantId(), ctx.getUser().getId(),
                    ctx.getAgentConfig().getId(), ctx.getOriginalMessage(),
                    "", justKeywordMatch, ctx.isFilterSensitive()));
        } catch (Exception e) {
            log.warn("[HybridAgentLoop] failed to query long-term memory", e);
        }
    }

    /**
     * 长期记忆总结入队（对齐 AgentExecutor：对话完成后推入摘要队列）。
     */
    private void pushMemorySummary(AgentContext ctx) {
        if (ctx.getAgentConfig().getOpenLongMemory() != AgentConfig.OpenStatus.Open) {
            return;
        }
        try {
            if (!ctx.getConversationId().startsWith("agent:")) {
                conversationApplicationService.pushToSummaryQueue(Long.parseLong(ctx.getConversationId()));
            }
        } catch (Exception e) {
            log.warn("[HybridAgentLoop] failed to push memory summary: {}", e.getMessage());
        }
    }

    // ======================== Prompt Builders ========================

    /**
     * sandbox 启用开关：默认禁用，extra["enableSandbox"]="1" 时启用。
     * 禁用时：不挂载 sandbox 控制工具，系统提示词直接用智能体配置的（不追加总控提示词）；
     * 启用时：挂载 sandbox 控制工具，系统提示词追加总控提示词（HYBRID_SYSTEM_PROMPT + 身份设定）。
     */
    private boolean isSandboxEnabled(AgentContext ctx) {
        if (ctx.getAgentConfig() == null || ctx.getAgentConfig().getExtra() == null) {
            return false;
        }
        if (ctx.getAgentConfig().getSystemPrompt() != null && ctx.getAgentConfig().getSystemPrompt().contains("<EnableSandbox/>")) {
            return true;
        }
        Object enableSandbox = ctx.getAgentConfig().getExtra().get("enableSandbox");
        return enableSandbox != null && "1".equals(String.valueOf(enableSandbox));
    }

    /**
     * "10 分钟直连沙箱"快速通道：上次沙箱执行（handleSandbox 入口记录时间）后的连续消息，
     * 直接继续沙箱执行、跳过模型意图路由。命中条件：
     * <ol>
     *   <li>sandbox 开关开启（关闭时永不直连，正常模型路由）</li>
     *   <li>sessionState 记录了上次沙箱调用时间（毫秒时间戳）</li>
     *   <li>距上次沙箱调用 ≤ {@link #SANDBOX_CONTINUE_WINDOW_MS}</li>
     * </ol>
     * 时间戳随 persistSessionState 持久化到 conversation.variables，跨轮（重启/多实例）依然有效。
     */
    private boolean shouldContinueSandbox(AgentContext ctx) {
        if (!isSandboxEnabled(ctx) || ctx.getSessionState() == null) {
            return false;
        }
        Object lastTime = ctx.getSessionState().get(LAST_SANDBOX_TIME_KEY);
        if (lastTime == null) {
            return false;
        }
        try {
            long last = Long.parseLong(String.valueOf(lastTime));
            return System.currentTimeMillis() - last <= SANDBOX_CONTINUE_WINDOW_MS;
        } catch (NumberFormatException e) {
            // 脏数据防御：值非法视为未命中，走正常模型路由
            return false;
        }
    }

    /**
     * 用户是否主动选择了具体沙箱：sessionState 存在 {@link #USER_SANDBOX_SELECTED_KEY} 标记
     * （execute 入口检测到请求携带 sandboxId &gt; 0 时写入，随 conversation.variables 跨轮持久化；
     * 仅指定沙箱配置算数，-1 云端电脑/全局沙箱与 null 未选都不写标记）。
     * <p>
     * 命中后该会话所有消息永久直连沙箱（跳过模型意图路由），且优先于 enableSandbox 开关
     * （用户显式选了沙箱不应被配置开关静默忽略）。
     * <p>
     * 注意：不依赖 conversation.sandboxServerId——该字段一旦绑定沙箱就永不消失
     * （SandboxAgentClient 创建沙箱会话时经 consumer 持久化），无法区分"用户主动选择"与
     * "模型路由进沙箱"；用请求级 sandboxId 写入的显式标记才能区分两者。
     */
    private boolean userSelectedSandbox(AgentContext ctx) {
        return ctx.getSessionState() != null
                && "1".equals(String.valueOf(ctx.getSessionState().get(USER_SANDBOX_SELECTED_KEY)));
    }

    /**
     * 智能体配置了技能（skillIds 非空）时永久直连沙箱：技能型智能体的任务天然需要真实执行环境
     * （技能在沙箱内执行），跳过模型意图路由，所有消息直接把用户输入交给沙箱（与
     * userSelectedSandbox 一样独立于 enableSandbox 开关——配置了 skill 即视为沙箱语义授权）。
     * 沙箱会话经 conversation.sandboxSessionId 跨轮复用，执行链/记忆推送与模型路由路径完全一致
     * （走同一 dispatchControlSignal → handleSandbox）。
     */
    private boolean hasSkill(AgentContext ctx) {
        return CollectionUtils.isNotEmpty(ctx.getSkillIds());
    }

    /**
     * 提取 {@code <sandbox_system_prompt>} 块内内容（沙箱智能体专用提示词）。
     * 无标签、标签为空、或入参为 null/空白时返回 null（由调用方决定回退策略）。
     */
    private static String extractSandboxSystemPrompt(String systemPrompt) {
        if (StringUtils.isBlank(systemPrompt)) {
            return null;
        }
        Matcher matcher = SANDBOX_SYSTEM_PROMPT_PATTERN.matcher(systemPrompt);
        if (matcher.find()) {
            String content = matcher.group(1).trim();
            return StringUtils.isBlank(content) ? null : content;
        }
        return null;
    }

    /**
     * 移除 {@code <sandbox_system_prompt>} 块及其周边空白（正常 LLM 请求用）。
     * 无标签时原样返回（可能为 null），保证既有配置行为不变。
     */
    private static String removeSandboxSystemPrompt(String systemPrompt) {
        if (StringUtils.isBlank(systemPrompt)) {
            return systemPrompt;
        }
        return SANDBOX_SYSTEM_PROMPT_PATTERN.matcher(systemPrompt).replaceAll("").trim();
    }

    /**
     * @param memoryToolsMounted 本轮是否<b>真的挂载了</b>记忆工具。★必须与实际挂载情况同源
     *                           （见 {@link #invokeModel}），否则提示词里会出现本轮没注册的工具名。
     */
    private String buildHybridSystemPrompt(MasterLoopContext mctx, boolean memoryToolsMounted) {
        return buildHybridSystemPrompt(mctx.ctx(), memoryToolsMounted);
    }

    /**
     * 变量提取等无 MasterLoopContext 的场景复用：提示词内容与正常 LLM 请求完全一致（缓存前缀可复用）。
     * <p>
     * ★记忆段这里按 {@link MemoryToolProvider#isOpen} 注入 —— 与正常轮次「工具真的挂上了」时的判据同一个，
     * 所以两条路径产出的提示词仍然逐字一致（前缀缓存照样命中）；只有「工具调用次数超限、本轮刻意不挂
     * 记忆工具」那种退化轮次会不同，那一轮本来就要让模型看到不一样的指令。
     */
    private String buildHybridSystemPrompt(AgentContext ctx) {
        return buildHybridSystemPrompt(ctx, memoryToolProvider.isOpen(ctx));
    }

    /**
     * 在身份设定/总控模板之后追加记忆纪律段（{@link MemoryToolProvider#MEMORY_PROMPT_SECTION}）。
     * <p>
     * 段里只放「什么时候调、什么不能干」（memoryId 严禁编造、事实变更必须更新旧记忆、别提内部细节）；
     * 分类体系那种长文本随 memory_save 的工具描述下发，不在系统提示词里重复一遍。
     */
    private String buildHybridSystemPrompt(AgentContext ctx, boolean memoryToolsMounted) {
        String base = buildBaseSystemPrompt(ctx);
        if (!memoryToolsMounted) {
            return base;
        }
        // 身份设定可能为空（既没配 systemPrompt、又不是沙箱型 TaskAgent），那时别留两个前导换行
        return StringUtils.isBlank(base)
                ? MemoryToolProvider.MEMORY_PROMPT_SECTION
                : base + "\n\n" + MemoryToolProvider.MEMORY_PROMPT_SECTION;
    }

    /**
     * 智能体身份设定 +（仅沙箱型 TaskAgent 才追加的）总控提示词模板。不含记忆段。
     */
    private String buildBaseSystemPrompt(AgentContext ctx) {
        // <sandbox_system_prompt> 块是沙箱智能体专用提示词，总控模型（正常 LLM 请求）不应看到，先移除
        String customPersona = removeSandboxSystemPrompt(ctx.getAgentConfig().getSystemPrompt());
        // 仅 sandbox 启用且 type=TaskAgent 时才使用总控提示词模板（HYBRID_SYSTEM_PROMPT）；
        // 否则直接用智能体配置的身份设定（变量替换），不追加总控提示词，由智能体自身设定主导对话
        if (!isSandboxEnabled(ctx) || !"TaskAgent".equals(ctx.getAgentConfig().getType())) {
            return StringUtils.isNotBlank(customPersona)
                    ? PlaceholderParser.resoleAndReplacePlaceholder(ctx.getVariableParams(), customPersona)
                    : "";
        }
        String base = HYBRID_SYSTEM_PROMPT;
        if (StringUtils.isNotBlank(customPersona)) {
            // 占位符变量替换（对齐 buildUserPrompt ③：{{variableName}} → variableParams 实际值）
            customPersona = PlaceholderParser.resoleAndReplacePlaceholder(ctx.getVariableParams(), customPersona);
            return base + "\n\n## 你的身份设定\n" + customPersona;
        }
        return base;
    }

    /**
     * 组装本轮 user 侧输入。
     * <p>
     * 统一采用 XML 标签分块，使模型能明确区分"上下文信息"与"用户消息"；
     * 各块按存在性条件注入，顺序固定（先背景上下文，后本轮诉求）：
     * <pre>
     * &lt;user-memory&gt;            长期记忆（开启且命中时）
     * &lt;conversation-summary&gt;   对话摘要（有时）
     * &lt;user-reminder&gt;          智能体自定义指令（配置时）
     * &lt;attachments&gt;            附件元数据（有时）
     * &lt;im-reminder&gt;            IM 会话上下文与回复方式（本轮挂了 IM 工具时）
     * &lt;user-message&gt;           本轮用户消息（恒有，永远位于最后）
     * </pre>
     *
     * @param imMounted 本轮是否挂载了 IM 原生工具。★必须与实际挂载情况一致，见
     *                  {@link ImChatToolProvider#buildReminder(ImChatIdentity)} 与调用点注释。
     */
    private String buildUserPrompt(AgentContext ctx, boolean imMounted) {
        StringBuilder sb = new StringBuilder();

        // ① 长期记忆注入（对齐 AgentExecutor 的 <user-memory> 格式）
        if (StringUtils.isNotBlank(ctx.getLongMemory())) {
            sb.append("<user-memory>\n").append(ctx.getLongMemory()).append("\n</user-memory>\n\n");
        }

        // ② 对话摘要
        if (ctx.getConversation() != null && StringUtils.isNotBlank(ctx.getConversation().getSummary())) {
            sb.append("<conversation-summary>\n").append(ctx.getConversation().getSummary()).append("\n</conversation-summary>\n\n");
        }

        // ③ 智能体自定义指令（占位符解析后注入）
        if (StringUtils.isNotBlank(ctx.getAgentConfig().getUserPrompt())) {
            String userPrompt = PlaceholderParser.resoleAndReplacePlaceholder(
                    ctx.getVariableParams(), ctx.getAgentConfig().getUserPrompt());
            sb.append("<user-reminder>\n").append(userPrompt).append("\n</user-reminder>\n\n");
        }

        // ④ 附件元数据
        if (CollectionUtils.isNotEmpty(ctx.getAttachments())) {
            sb.append("<attachments>\n").append(JSON.toJSONString(ctx.getAttachments())).append("\n</attachments>\n\n");
        }
        // 仅有附件而无文字时，补充系统指令让模型分析附件
        if (StringUtils.isBlank(ctx.getMessage()) && CollectionUtils.isNotEmpty(ctx.getAttachments())) {
            ctx.setMessage("\n" + I18nUtil.systemMessage(ctx.getUser().getLangMap(), "Backend.Agent.Message.AnalyzeAttachments"));
        }

        // ⑤ IM 会话上下文（模式 / 会话 ID / 触发人 / 回复方式）
        // ★放在 <user-message> 之前：它是「怎么回」的背景，不是「回什么」的诉求。
        //   沙箱那条腿由 SandboxAgentClient 注 buildP2pReminder/buildGroupReminder + USE_IM_SKILL_PROMPT，
        //   chatbot 这条腿此前一个 IM 提醒都没有 —— 缺了它，监听模式下模型只会输出文本，而那份文本被整段丢弃。
        if (imMounted) {
            sb.append(imChatToolProvider.buildReminder(ImChatIdentity.of(ctx)));
        }

        // ⑥ 本轮用户消息（恒以标签包裹并置于末尾，与上方上下文块严格区分）
        sb.append("<user-message>\n").append(StringUtils.defaultString(ctx.getMessage())).append("\n</user-message>");

        // ⑦ 当前时间（ModelInvoker 不再走 buildUserMessage，此处补齐，对齐其格式）
        sb.append("\n<current-time>").append(DateUtil.getNowTime()).append("</current-time>\n");

        return sb.toString();
    }

    /**
     * 不挂 IM 工具的上下文（变量/参数提取、沙箱任务描述等）走这个重载。
     *
     * <p>★刻意不给这条路径注 IM 提醒：提取请求要的是「只输出 JSON 入参」，
     * 提醒里的「需要回复时调用 im_send_group」会把模型从结构化输出里带跑；
     * 沙箱那条腿另有自己的 IM 技能提醒（{@code USE_IM_SKILL_PROMPT}），两套同时出现只会互相打架。
     */
    private String buildUserPrompt(AgentContext ctx) {
        return buildUserPrompt(ctx, false);
    }

    // ======================== Model Context ========================

    private ModelContext buildModelContext(AgentContext ctx, String systemPrompt, String userPrompt,
                                           MasterLoopContext mctx) {
        if (ctx.getAgentConfig().getModelComponentConfig() == null
                || ctx.getAgentConfig().getModelComponentConfig().getTargetConfig() == null) {
            throw new BizException("Incomplete agent model configuration. Please check if the associated model has been deleted.");
        }
        ModelConfigDto modelConfigDto = (ModelConfigDto) ctx.getAgentConfig().getModelComponentConfig().getTargetConfig();
        ModelBindConfigDto modelBindConfigDto = (ModelBindConfigDto) ctx.getAgentConfig().getModelComponentConfig().getBindConfig();

        // 构造完整执行链消息（历史 execContext + 本轮 user + 本轮 toolChain），替代 DB roundMessage 历史。
        // roundMessage 仅前端展示；模型执行上下文唯一来源是持久化在 sessionState 的执行链。
        List<Message> contextMessages = buildExecutionMessages(mctx, userPrompt);
        ctx.setContextMessages(contextMessages);

        ModelContext modelContext = new ModelContext();
        modelConfigDto.setIsReasonModel(0);// 禁用推理
        modelContext.setRequestId(ctx.getRequestId());
        modelContext.setAgentContext(ctx);
        modelContext.setConversationId(ctx.getConversationId());
        modelContext.setModelConfig(modelConfigDto);

        ModelCallConfigDto callConfig = new ModelCallConfigDto();
        modelContext.setModelCallConfig(callConfig);
        callConfig.setSystemPrompt(systemPrompt);
        callConfig.setUserPrompt(userPrompt);
        callConfig.setChatRound(0); // 历史已通过 contextMessages 传入，chatRound 仅控制 ModelInvoker 内部追加轮数
        callConfig.setStreamCall(true);
        if (modelBindConfigDto != null) {
            callConfig.setMaxTokens(modelBindConfigDto.getMaxTokens());
            callConfig.setTemperature(modelBindConfigDto.getTemperature());
            callConfig.setTopP(modelBindConfigDto.getTopP());
        }
        modelContext.setTraceContext(ctx.getTraceContext().next(
                TraceContext.TraceTargetType.Model, modelConfigDto.getId().toString(),
                modelConfigDto.getModel(), modelConfigDto.getName(), null,
                ctx.isDefaultModelChanged() ? ctx.getUserId() : null));
        return modelContext;
    }

    /**
     * 由执行链构造完整的 spring-ai 消息序列：
     * <ol>
     *   <li>历史执行链（跨轮持久化的 user / assistant(tool_use) / tool / assistant 交错序列）</li>
     *   <li>本轮 user（buildUserPrompt 含 user-memory / user-reminder / attachments / user-message）</li>
     *   <li>本轮工具链（assistant(tool_use) → tool(result) 配对）</li>
     * </ol>
     * 消息顺序严格遵循 OpenAI function-call 协议（tool 消息必须在 user 之后、assistant 之前），
     * 由 ModelInvoker 原样透传给模型（executionMessagesComplete=true 时不再追加 user）。
     */
    private List<Message> buildExecutionMessages(MasterLoopContext mctx, String userPrompt) {
        List<Message> messages = buildHistoryMessages(mctx);
        // ② 本轮 user（动态构造，含当前上下文块；附件媒体按模型能力挂到 UserMessage，
        //    对齐 ModelInvoker.buildUserMessage —— executionMessagesComplete=true 时 ModelInvoker 不追加 user）
        messages.add(buildUserMessageWithMedia(mctx.ctx(), userPrompt));
        // ③ 本轮工具链（assistant(tool_use) → tool(result) 交错，工具结果紧随调用之后）；
        //    同样过一遍配对校验兜底本回合断链（防御未来新增的孤儿产生路径）
        List<ExecMsg> sanitizedChain = sanitizeExecMsgs(mctx.getToolChain(), false);
        if (sanitizedChain.size() != mctx.getToolChain().size()) {
            mctx.getToolChain().clear();
            mctx.getToolChain().addAll(sanitizedChain);
        }
        for (ExecMsg m : sanitizedChain) {
            Message springMsg = toSpringAiMessage(m);
            if (springMsg != null) {
                messages.add(springMsg);
            }
        }
        return messages;
    }

    /**
     * 由执行链历史（execContext）构造消息序列（不含本轮 user / toolChain）：
     * 供正常请求（buildExecutionMessages）与变量提取请求（buildExtractContext，保证上下文完整）复用。
     */
    private List<Message> buildHistoryMessages(MasterLoopContext mctx) {
        List<Message> messages = new ArrayList<>();
        // ① 历史执行链（对称配对校验：孤儿 tool_use / tool_result 无论位置一律丢弃，防止 Anthropic 协议校验失败；
        //    存量脏数据发送时自动修复，丢弃后写回执行链使持久化数据逐步干净，砖死会话下一轮即自愈）
        List<ExecMsg> sanitizedHistory = sanitizeExecMsgs(mctx.getExecContext(), true);
        if (sanitizedHistory.size() != mctx.getExecContext().size()) {
            mctx.getExecContext().clear();
            mctx.getExecContext().addAll(sanitizedHistory);
        }
        for (ExecMsg m : sanitizedHistory) {
            Message msg = toSpringAiMessage(m);
            if (msg != null) {
                messages.add(msg);
            }
        }
        // 历史以 user 类消息结尾（tool_result 或旧数据缺轮末 assistant）：Anthropic 要求角色严格交替，
        // 且 tool_result 后必须紧跟 assistant，补占位 assistant 收尾后再衔接后续 user，
        // 否则 "roles must alternate" / "tool_use ids without tool_result" 400（部分网关要求 content 非空，不能补空串）
        if (!messages.isEmpty() && messages.get(messages.size() - 1).getMessageType() != MessageType.ASSISTANT) {
            messages.add(new AssistantMessage(EMPTY_CONTENT_PLACEHOLDER));
        }
        return messages;
    }

    /**
     * 按 Anthropic/OpenAI function-call 协议清洗执行链消息，输出保证协议恒合法（发送前最后一道兜底）：
     * <ol>
     *   <li>孤儿 tool_use（后续无配对 result）：无论位置一律丢弃 —— 旧实现只处理"末尾恰为孤儿 tool_use"，
     *       落在历史中间的孤儿永远留在消息列表中，所在会话每次模型调用都 400 且无法自愈；</li>
     *   <li>孤儿 tool_result（前面没有待配对 tool_use 或配对不上）：丢弃；</li>
     *   <li>配对成功但任一 toolCallId 为空（旧版本脏数据）：降级按"紧邻 + 同 toolName"配对后，
     *       给这对消息回填同一个 UUID —— 否则 {@link #toSpringAiMessage} 对空 id 各自生成独立随机 UUID，
     *       tool_use/tool_result 的 id 仍然对不上，照样触发 "tool_use ids were found without tool_result" 400；</li>
     *   <li>requireUserStart=true 时（历史执行链）：丢弃首个 user 之前的残片 —— trimExecContext
     *       对超长单轮会逐条从头删，可能留下以 tool_use/assistant 开头的残片，转换后首条消息非 user，
     *       Anthropic 报 "first message must be user" 且每轮重复触发；</li>
     *   <li>相邻消息同属一类（tool_result 与 user 同属 user 类）：插入占位消息保证角色严格交替 ——
     *       轮末 assistant 占位是后补逻辑，存量数据可能缺占位导致 tool_result 直接接下一轮 user，
     *       Anthropic 报 "roles must alternate"。</li>
     * </ol>
     * 对已污染会话是"发送即自愈"：清洗发生在构造消息时，调用方检测到结构变化会写回执行链，持久化数据逐步变干净。
     * <p>
     * 注意：返回新列表（插入/丢弃改变结构）；但对配对消息的 id 回填是原地修改（入参列表持有同一对象引用），
     * 即使结构未变化未触发写回，commitExecContext 序列化时也会带上修正后的 id。
     *
     * @param requireUserStart true=丢弃首个 user 之前的残片（历史执行链）；
     *                         false=允许以 tool_use 开头（本轮 toolChain，其前面紧跟本轮 user 消息）
     */
    private List<ExecMsg> sanitizeExecMsgs(List<ExecMsg> msgs, boolean requireUserStart) {
        List<ExecMsg> sanitized = new ArrayList<>();
        if (CollectionUtils.isEmpty(msgs)) {
            return sanitized;
        }
        int start = 0;
        if (requireUserStart) {
            while (start < msgs.size() && !"user".equals(msgs.get(start).getRole())) {
                start++;
            }
            if (start >= msgs.size()) {
                log.warn("[HybridAgentLoop] exec context has no user message, dropped as trim fragment, size={}", msgs.size());
                return sanitized;
            }
            if (start > 0) {
                log.warn("[HybridAgentLoop] exec context leading fragment before first user dropped, size={}", start);
            }
        }
        ExecMsg pendingUse = null;
        for (int i = start; i < msgs.size(); i++) {
            ExecMsg m = msgs.get(i);
            if ("assistant".equals(m.getRole()) && StringUtils.isNotBlank(m.getToolName())) {
                // 新 tool_use：上一个 pending tool_use 未等到配对结果，视为孤儿丢弃
                if (pendingUse != null) {
                    log.warn("[HybridAgentLoop] orphan tool_use dropped from exec context: {}", pendingUse.getToolName());
                }
                pendingUse = m;
            } else if ("tool".equals(m.getRole())) {
                if (pendingUse != null && isToolPair(pendingUse, m)) {
                    backfillToolCallId(pendingUse, m);
                    sanitized.add(pendingUse);
                    sanitized.add(m);
                    pendingUse = null;
                } else {
                    // 孤儿 tool_result（前面没有待配对 tool_use 或配对不上）：丢弃；pendingUse 保留等待其真正的结果
                    log.warn("[HybridAgentLoop] orphan tool_result dropped from exec context: {}", m.getToolName());
                }
            } else {
                // user / 纯文本 assistant：轮次边界，未配对的 pending tool_use 一并丢弃
                if (pendingUse != null) {
                    log.warn("[HybridAgentLoop] orphan tool_use dropped from exec context: {}", pendingUse.getToolName());
                    pendingUse = null;
                }
                ensureRoleAlternation(sanitized, m);
                sanitized.add(m);
            }
        }
        // 末尾孤儿 tool_use（无对应 tool_result）：Anthropic 要求 tool_use 必须得到响应，丢弃
        if (pendingUse != null) {
            log.warn("[HybridAgentLoop] orphan tool_use dropped from exec context tail: {}", pendingUse.getToolName());
        }
        return sanitized;
    }

    /**
     * 配对双方任一 toolCallId 为空时回填同一 UUID：
     * {@link #toSpringAiMessage} 对空 id 会各自生成独立随机 UUID，导致转换后 tool_use/tool_result
     * 的 id 不一致，即使按名配对成功，仍触发 Anthropic "tool_use ids were found without tool_result blocks" 400。
     */
    private void backfillToolCallId(ExecMsg use, ExecMsg result) {
        if (StringUtils.isBlank(use.getToolCallId()) || StringUtils.isBlank(result.getToolCallId())) {
            String sharedId = UUID.randomUUID().toString().replace("-", "");
            use.setToolCallId(sharedId);
            result.setToolCallId(sharedId);
        }
    }

    /**
     * 角色交替保障：Anthropic 要求消息角色严格 user/assistant 交替（tool_result 归属 user 类）。
     * 若清洗结果末尾与待加入消息同属一类（存量数据缺轮末 assistant 占位等），插入占位消息补齐交替。
     */
    private void ensureRoleAlternation(List<ExecMsg> sanitized, ExecMsg next) {
        if (sanitized.isEmpty()) {
            return;
        }
        ExecMsg last = sanitized.get(sanitized.size() - 1);
        boolean lastIsAssistant = "assistant".equals(last.getRole());
        boolean nextIsAssistant = "assistant".equals(next.getRole());
        if (lastIsAssistant == nextIsAssistant) {
            ExecMsg placeholder = new ExecMsg();
            placeholder.setRole(lastIsAssistant ? "user" : "assistant");
            placeholder.setText(EMPTY_CONTENT_PLACEHOLDER);
            sanitized.add(placeholder);
            log.warn("[HybridAgentLoop] role alternation broken in exec context, inserted placeholder {}", placeholder.getRole());
        }
    }

    /**
     * 判断 tool_use 与 tool_result 是否配对：双方 toolCallId 均非空时必须严格相等；
     * 任一为空（旧版本脏数据）降级按同 toolName 判定（清洗为顺序单遍，结果必紧随调用）。
     */
    private boolean isToolPair(ExecMsg use, ExecMsg result) {
        if (StringUtils.isNotBlank(use.getToolCallId()) && StringUtils.isNotBlank(result.getToolCallId())) {
            return StringUtils.equals(use.getToolCallId(), result.getToolCallId());
        }
        return StringUtils.equals(use.getToolName(), result.getToolName());
    }

    /**
     * 构造本轮 user 消息（文本 + 附件媒体，对齐 ModelInvoker.buildUserMessage 的媒体构建）。
     * <p>
     * executionMessagesComplete=true 时 ModelInvoker:214 不再追加 buildUserMessage，附件媒体
     * （图片/音频/视频，按模型能力过滤）必须由本 loop 自行挂到 UserMessage，否则模型看不到附件。
     */
    private static UserMessage buildUserMessageWithMedia(AgentContext ctx, String userPrompt) {
        return UserMessage.builder()
                .media(buildMediaList(ctx))
                .text(userPrompt)
                .build();
    }

    /**
     * 按模型能力（图像/音频/视频）过滤附件并转 spring-ai Media（对齐 ModelInvoker.buildUserMessage:1211-1269）。
     */
    private static List<Media> buildMediaList(AgentContext ctx) {
        List<Media> mediaList = new ArrayList<>();
        ModelConfigDto modelConfig = (ModelConfigDto) ctx.getAgentConfig().getModelComponentConfig().getTargetConfig();
        if (modelConfig == null) {
            return mediaList;
        }
        boolean imageCapable = modelConfig.getType() == ModelTypeEnum.Multi
                || (modelConfig.getTypes() != null && modelConfig.getTypes().stream().anyMatch(t -> t == ModelCapabilityEnum.Image));
        boolean audioCapable = modelConfig.getTypes() != null && modelConfig.getTypes().stream().anyMatch(t -> t == ModelCapabilityEnum.Audio);
        boolean videoCapable = modelConfig.getTypes() != null && modelConfig.getTypes().stream().anyMatch(t -> t == ModelCapabilityEnum.Video);
        if (imageCapable && CollectionUtils.isNotEmpty(ctx.getAttachments())) {
            ctx.getAttachments().forEach(attachment -> {
                try {
                    String[] split = attachment.getMimeType().split("/");
                    if (split.length != 2 || !split[0].equals("image")) {
                        return;
                    }
                    Media media = Media.builder()
                            .mimeType(new MimeType(split[0], split[1]))
                            .data(UrlFile.urlToBytes(attachment.getFileUrl()))
                            .id(attachment.getFileKey())
                            .name(attachment.getFileName())
                            .build();
                    mediaList.add(media);
                } catch (Exception e) {
                    log.warn("Invalid URL: {}", attachment.getFileUrl(), e);
                }
            });
        }
        if (audioCapable && CollectionUtils.isNotEmpty(ctx.getAttachments())) {
            ctx.getAttachments().forEach(attachment -> {
                try {
                    String[] split = attachment.getMimeType().split("/");
                    if (split.length != 2 || !split[0].equals("audio")) {
                        return;
                    }
                    Media media = Media.builder()
                            .mimeType(new MimeType(split[0], split[1]))
                            .data(attachment.getFileUrl())
                            .id(attachment.getFileKey())
                            .name(attachment.getFileName())
                            .build();
                    mediaList.add(media);
                } catch (Exception e) {
                    log.warn("Invalid URL: {}", attachment.getFileUrl(), e);
                }
            });
        }
        if (videoCapable && CollectionUtils.isNotEmpty(ctx.getAttachments())) {
            ctx.getAttachments().forEach(attachment -> {
                try {
                    String[] split = attachment.getMimeType().split("/");
                    if (split.length != 2 || !split[0].equals("video") || !split[1].equals("mp4")) {
                        return;
                    }
                    Media media = Media.builder()
                            .data(attachment.getFileUrl())
                            .mimeType(new MimeType(split[0], split[1]))
                            .id(attachment.getFileKey())
                            .name(attachment.getFileName())
                            .build();
                    mediaList.add(media);
                } catch (Exception e) {
                    log.warn("Invalid URL: {}", attachment.getFileUrl(), e);
                }
            });
        }
        return mediaList;
    }

    /**
     * 将执行链消息（{@link ExecMsg}）转换为 spring-ai Message。
     * <ul>
     *   <li>user → {@link UserMessage}</li>
     *   <li>assistant 且带 toolName → {@link AssistantMessage} + {@code toolCalls}（工具调用声明）</li>
     *   <li>assistant 纯文本 → {@link AssistantMessage}</li>
     *   <li>tool → {@link ToolResponseMessage}（工具结果，按 toolCallId 与调用配对）</li>
     * </ul>
     */
    private Message toSpringAiMessage(ExecMsg m) {
        switch (m.getRole()) {
            case "user":
                // 空 content 兜底：部分网关要求所有消息 content 非空（含历史会话变量中的脏数据）
                return new UserMessage(StringUtils.defaultIfBlank(m.getText(), EMPTY_CONTENT_PLACEHOLDER));
            case "assistant":
                if (StringUtils.isNotBlank(m.getToolName())) {
                    // assistant+tool_calls 场景 content 为空是 function-call 协议合法形态，保持空串
                    return AssistantMessage.builder()
                            .content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall(
                                    StringUtils.defaultIfBlank(m.getToolCallId(), UUID.randomUUID().toString().replace("-", "")),
                                    "function", m.getToolName(),
                                    StringUtils.defaultIfBlank(m.getToolArgs(), "{}"))))
                            .build();
                }
                String text = m.getText();
                if (StringUtils.isNotBlank(m.getText())) {
                    text = text.replaceAll("\n<div><markdown-custom-process[^>]*>.*?</markdown-custom-process></div>\n\n", "");
                    text = text.replaceAll("<markdown-custom-process[^>]*>.*?</markdown-custom-process>", "");
                }
                // replaceAll 可能把整段文本清成空串，同样走占位兜底
                return new AssistantMessage(StringUtils.defaultIfBlank(text, EMPTY_CONTENT_PLACEHOLDER));
            case "tool":
                return ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse(
                                StringUtils.defaultIfBlank(m.getToolCallId(), UUID.randomUUID().toString().replace("-", "")),
                                StringUtils.defaultIfBlank(m.getToolName(), "tool"),
                                StringUtils.defaultIfBlank(m.getText(), EMPTY_CONTENT_PLACEHOLDER))))
                        .build();
            default:
                return null;
        }
    }

    // ======================== 执行链持久化 ========================

    /**
     * 从 sessionState 恢复上一轮持久化的完整执行链（execute 入口调用）。
     */
    private void loadExecContext(MasterLoopContext mctx) {
        AgentContext ctx = mctx.ctx();
        if (ctx.getSessionState() == null) {
            return;
        }
        Object saved = ctx.getSessionState().get(EXEC_CONTEXT_KEY);
        if (saved == null || StringUtils.isBlank(saved.toString())) {
            return;
        }
        try {
            List<ExecMsg> list = JSON.parseArray(saved.toString(), ExecMsg.class);
            if (CollectionUtils.isNotEmpty(list)) {
                mctx.getExecContext().addAll(list);
            }
        } catch (Exception e) {
            log.warn("[HybridAgentLoop] load exec context failed: {}", e.getMessage());
        }
    }

    /**
     * 轮次正常结束时，把本轮 [user, 工具链(assistant(tool_use)+tool), assistant] 提交到执行链并持久化。
     * 之后按 {@link #MAX_EXEC_CONTEXT_LENGTH} 裁剪最旧轮次，防止会话变量无限膨胀。
     */
    private void commitExecContext(MasterLoopContext mctx) {
        AgentContext ctx = mctx.ctx();
        // 错误轮次（模型重试耗尽）不提交，避免把错误响应/不完整工具链混入执行链
        if (mctx.getErrorOccurred().get()) {
            log.warn("[HybridAgentLoop] error round, skip exec context commit");
            return;
        }
        // 本轮 user（空消息占位，避免持久化空 content 脏数据）
        ExecMsg userMsg = new ExecMsg();
        userMsg.setRole("user");
        userMsg.setText(StringUtils.defaultIfBlank(ctx.getMessage(), EMPTY_CONTENT_PLACEHOLDER));
        mctx.getExecContext().add(userMsg);
        // 本轮工具链
        mctx.getExecContext().addAll(mctx.getToolChain());
        mctx.getToolChain().clear();
        // assistant 最终文本（自答/沙箱输出）
        if (mctx.getTextAccum().length() > 0) {
            ExecMsg assistantMsg = new ExecMsg();
            assistantMsg.setRole("assistant");
            assistantMsg.setText(mctx.getTextAccum().toString());
            assistantMsg.setThink(mctx.getThinkAccum().toString());
            mctx.getExecContext().add(assistantMsg);
        } else {
            // 执行链每轮必须以 assistant 结尾：Anthropic 协议要求 tool_result 之后必须紧跟 assistant 消息，
            // 跨轮时 tool_result 不能直接衔接新 user。异常/空输出场景 textAccum 为空，补占位 assistant 收尾
            // （不能补空串：部分网关要求所有消息 content 非空，空 assistant 会在下一轮随执行链发出时报错）
            ExecMsg assistantMsg = new ExecMsg();
            assistantMsg.setRole("assistant");
            assistantMsg.setText(EMPTY_CONTENT_PLACEHOLDER);
            mctx.getExecContext().add(assistantMsg);
        }
        trimExecContext(mctx);
        persistExecContext(mctx);
    }

    /**
     * 持久化执行链到 sessionState（跨轮可见）。
     */
    private void persistExecContext(MasterLoopContext mctx) {
        try {
            AgentContext ctx = mctx.ctx();
            if (ctx.getSessionState() != null) {
                ctx.getSessionState().put(EXEC_CONTEXT_KEY, JSON.toJSONString(mctx.getExecContext()));
                persistSessionState(ctx);
            }
        } catch (Exception e) {
            log.warn("[HybridAgentLoop] persist exec context failed: {}", e.getMessage());
        }
    }

    /**
     * 执行链超过 {@link #MAX_EXEC_CONTEXT_LENGTH} 时，按轮次（user 消息为轮次起点）裁剪最旧部分。
     */
    private void trimExecContext(MasterLoopContext mctx) {
        AgentComponentConfigDto modelComponentConfig = mctx.getAgentContext().getAgentConfig().getModelComponentConfig();
        int maxContextLength = MAX_EXEC_CONTEXT_LENGTH;
        if (modelComponentConfig != null
                && modelComponentConfig.getBindConfig() instanceof ModelBindConfigDto modelBindConfigDto
                && modelBindConfigDto.getMaxContextTokens() != null) {
            // maxContextTokens 是 token 上限，执行链按 JSON 字符串长度（字符）裁剪；
            // 中文 1 token ≈ 1 字符为最坏情况（token 数不会超过字符数），取 maxContextTokens 作为
            // 字符上限即可保证裁剪后不超模型 token 上限（此处取默认字符上限与 token 上限的较大值）。
            maxContextLength = Math.max(maxContextLength, modelBindConfigDto.getMaxContextTokens());
        }
        while (!mctx.getExecContext().isEmpty()) {
            String json = JSON.toJSONString(mctx.getExecContext());
            if (json.length() <= maxContextLength) {
                return;
            }
            int removeTo = 1;
            // 从第二条起找第一个新的轮次起点（user 消息），整轮移除
            for (int i = 1; i < mctx.getExecContext().size(); i++) {
                if ("user".equals(mctx.getExecContext().get(i).getRole())) {
                    removeTo = i;
                    break;
                }
            }
            if (removeTo <= 1) {
                // 没有可独立移除的轮次，直接移除最旧一条
                mctx.getExecContext().remove(0);
            } else {
                mctx.getExecContext().subList(0, removeTo).clear();
            }
        }
    }

    // ======================== Output / Message Builders ========================

    private AgentOutputDto buildCallMessageOutput(AgentContext ctx, CallMessage msg) {
        msg.setId(ctx.getRequestId());
        msg.setAgentId(ctx.getAgentConfig().getId());
        msg.setSenderId(ctx.getAgentConfig().getId().toString());
        msg.setSenderType(ChatMessageDto.SenderType.AGENT);
        AgentOutputDto dto = new AgentOutputDto();
        dto.setEventType(AgentOutputDto.EventTypeEnum.MESSAGE);
        dto.setRequestId(ctx.getRequestId());
        dto.setData(msg);
        dto.setCompleted(false);
        return dto;
    }

    private AgentOutputDto buildTextMessage(AgentContext ctx, String text) {
        CallMessage msg = new CallMessage();
        msg.setId(ctx.getRequestId());
        msg.setRole(ChatMessageDto.Role.ASSISTANT);
        msg.setType(MessageTypeEnum.CHAT);
        msg.setText(text);
        msg.setAgentId(ctx.getAgentConfig().getId());
        msg.setSenderId(ctx.getAgentConfig().getId().toString());
        msg.setSenderType(ChatMessageDto.SenderType.AGENT);
        msg.setTime(new Date());
        msg.setFinished(true);
        AgentOutputDto dto = new AgentOutputDto();
        dto.setEventType(AgentOutputDto.EventTypeEnum.MESSAGE);
        dto.setRequestId(ctx.getRequestId());
        dto.setData(msg);
        dto.setCompleted(false);
        return dto;
    }

    private void addUserRoundMessage(AgentContext ctx) {
        ChatMessageDto userMessage = ChatMessageDto.builder()
                .tenantId(ctx.getAgentConfig().getTenantId())
                .id(UUID.randomUUID().toString().replace("-", ""))
                .senderType(ChatMessageDto.SenderType.USER)
                .senderId(ctx.getUserId() == null ? null : ctx.getUserId().toString())
                .userId(ctx.getUserId())
                .agentId(ctx.getAgentConfig().getId())
                .role(ChatMessageDto.Role.USER)
                .type(MessageTypeEnum.CHAT)
                .text(ctx.getMessage())
                .time(new Date())
                .finished(true)
                .build();
        conversationApplicationService.addRoundMessage(ctx.getConversationId(), userMessage);
    }

    private void addAssistantRoundMessage(AgentContext ctx, String text, String think) {
        addAssistantRoundMessage(ctx, text, think, null);
    }

    /**
     * 写 assistant 轮消息（对齐 AgentExecutor.chatComplete：包含组件执行记录）。
     *
     * @param componentExecutedList 本轮组件执行记录（插件/知识库/工作流等），null 则不携带
     */
    private void addAssistantRoundMessage(AgentContext ctx, String text, String think,
                                          List<ComponentExecutingDto> componentExecutedList) {
        ChatMessageDto.ChatMessageDtoBuilder builder = ChatMessageDto.builder()
                .tenantId(ctx.getAgentConfig().getTenantId())
                .id(ctx.getRequestId())
                .senderType(ChatMessageDto.SenderType.AGENT)
                .senderId(ctx.getAgentConfig().getId().toString())
                .userId(ctx.getUserId())
                .agentId(ctx.getAgentConfig().getId())
                .role(ChatMessageDto.Role.ASSISTANT)
                .type(MessageTypeEnum.CHAT)
                .text(text)
                .think(think)
                .time(new Date())
                .finished(true);
        if (CollectionUtils.isNotEmpty(componentExecutedList)) {
            builder.componentExecutedList(componentExecutedList.stream().map(dto -> (Object) dto).toList());
        }
        conversationApplicationService.addRoundMessage(ctx.getConversationId(), builder.build());
    }

    // ======================== Inner Classes ========================

    /**
     * 混合编排上下文（包装 AgentContext + loop 专有状态）。
     * 在 {@link #execute} 入口创建一次，贯穿整个编排流程。
     */
    @Getter
    public static class MasterLoopContext {
        private final AgentContext agentContext;
        private final StringBuilder textAccum = new StringBuilder();
        private final StringBuilder thinkAccum = new StringBuilder();
        // 本轮是否发生模型错误（重试耗尽），true 时 commitExecContext 跳过提交，避免错误轮次污染执行链
        private final AtomicBoolean errorOccurred = new AtomicBoolean(false);
        // 结果回喂型控制工具（记忆三件套）本轮已调用次数：达到 MAX_TOOL_CALLS 后不再挂载记忆工具，
        // 强制模型基于已累积上下文直接回答，防「调工具 → 回喂 → 再调同一工具」死循环（对齐 MasterAgentLoop）
        private final AtomicInteger toolCallCount = new AtomicInteger(0);
        // 完整执行链（跨轮持久化）：user → assistant(tool_use) → tool(result) → assistant 交错序列。
        // 每轮结束由 commitExecContext 追加本轮 [user, 工具链, assistant] 并经 persistExecContext 存入
        // sessionState（conversation.variables），下一轮由 loadExecContext 恢复，形成跨轮上下文连贯。
        private final List<ExecMsg> execContext = new ArrayList<>();
        // 本轮工具链：assistant(tool_use) → tool(result) 配对，随 commitExecContext 并入 execContext。
        // 记录工具调用的完整语义，ModelInvoker 与工具回调之间不维护状态，
        // 模型看到的工具调用/结果顺序完全由这里的配对顺序决定。
        private final List<ExecMsg> toolChain = new ArrayList<>();
        // 组件执行过程回调（对齐 AgentExecutor：PROCESSING 事件流式推送给前端）
        private final List<ComponentExecutingDto> componentExecutedList = new ArrayList<>();

        public MasterLoopContext(AgentContext agentContext) {
            this.agentContext = agentContext;
        }

        /**
         * 快捷访问 AgentContext
         */
        public AgentContext ctx() {
            return agentContext;
        }
    }

    /**
     * 控制工具调用意图（从 returnDirect 的控制信号 JSON 反序列化）。
     * <p>
     * 仅 {@code action=sandbox}，参数为 {@code inputMessage}（交给沙箱的完整任务描述）。
     */
    @Data
    public static class IntentDecision {
        private String action;
        private String inputMessage;
    }

    /**
     * 执行链消息（loop 自己的上下文单元，与 DB 展示消息（roundMessage）解耦）。
     * <ul>
     *   <li>role=user：用户消息（text 为用户原始消息）</li>
     *   <li>role=assistant + toolName：工具调用声明（tool_use，toolArgs 为参数 JSON）</li>
     *   <li>role=tool：工具执行结果（text 为结果文本，按 toolCallId/toolName 与调用配对）</li>
     *   <li>role=assistant：模型最终回答（text 为回答文本，think 为思考过程）</li>
     * </ul>
     * 该序列构成 user → assistant(tool_use) → tool(result) → assistant 的完整执行链条，
     * 经 {@link #toSpringAiMessage} 转为 spring-ai Message 交给模型，并经
     * {@link #persistExecContext} 持久化到 sessionState，跨轮保持上下文连贯。
     */
    @Data
    public static class ExecMsg {
        private String role;
        private String text;
        private String think;
        private String toolName;
        private String toolArgs;
        private String toolCallId;
    }
}
