package com.xspaceagi.agent.core.infra.component.agent;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.xspaceagi.agent.core.adapter.application.ChatApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentComponentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.bind.*;
import com.xspaceagi.agent.core.adapter.dto.config.plugin.PluginDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.WorkflowConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import com.xspaceagi.agent.core.infra.component.agent.dto.AgentExecuteResult;
import com.xspaceagi.agent.core.infra.component.memory.MemoryToolProvider;
import com.xspaceagi.agent.core.infra.component.model.ModelContext;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.infra.component.model.dto.*;
import com.xspaceagi.agent.core.spec.enums.ComponentTypeEnum;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.agent.core.spec.enums.ExecuteStatusEnum;
import com.xspaceagi.agent.core.spec.enums.MessageTypeEnum;
import com.xspaceagi.agent.core.spec.utils.PlaceholderParser;
import com.xspaceagi.mcp.sdk.dto.McpDto;
import com.xspaceagi.mcp.sdk.dto.McpToolDto;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.DateUtil;
import com.xspaceagi.system.spec.utils.I18nUtil;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 总控智能体编排组件（L1：原生 Function Call，自答零前置墙）。
 * <p>
 * 核心设计：利用模型<b>原生 function calling</b> 协议实现意图路由，无需独立意图识别轮。
 * <ul>
 *   <li>自答：模型直接流式输出文本 → 首字 = 主模型首字，零前置墙</li>
 *   <li>路由：模型发起 tool_call（协议层，schema 校验）→ {@code returnDirect} 终止模型循环 →
 *       工具执行返回路由 JSON → MasterAgentLoop 解析后分发</li>
 * </ul>
 * <p>
 * 控制工具（{@link ComponentTypeEnum#Control}，returnDirect=true）：
 * <ul>
 *   <li>{@code dispatch}：统一任务派发（单个/并行/新建/继续，通过 conversationId 区分会话）</li>
 *   <li>{@code search_tasks}：搜索更久远的历史任务（方案A：执行搜索后二次调用模型）</li>
 *   <li>{@code query_progress_or_result}：查询任务进度</li>
 *   <li>{@code list_running_tasks}：查询当前正在执行的任务汇总</li>
 *   <li>{@code cancel_task}：取消当前任务</li>
 *   <li>{@code memory_query}：查询用户长期记忆（仅开启长期记忆时挂载）</li>
 *   <li>{@code memory_save}：新增或更新用户长期记忆（仅开启长期记忆时挂载）</li>
 *   <li>{@code memory_delete}：删除用户长期记忆（仅开启长期记忆时挂载）</li>
 * </ul>
 * <p>
 * 响应式架构（对齐 AgentGroupLoop 模式）：
 * <ul>
 *   <li>顶层 {@code Flux.create(sink -> pipeline.subscribe())}，单一订阅点</li>
 *   <li>内部全部使用 {@code Mono.defer} 操作符链组合，无嵌套 subscribe</li>
 *   <li>取消/错误信号沿操作符链自然传播，无需手动 dispose 跟踪</li>
 *   <li>模型调用失败自动重试（仅首 token 前可重试，避免重复输出）</li>
 * </ul>
 */
@Slf4j
@Component
public class MasterAgentLoop {

    // ======================== System Prompt ========================

    private static final String MASTER_SYSTEM_PROMPT = """
            你是一个智能助手总控。你直接面对用户，负责回答用户的问题或协调子智能体完成任务。
            
            ## 你的能力
            - 直接回答用户的问题（常识、解释、分析、创作、闲聊等）——这是最常见的情况，直接输出回答即可
            - 当你绑定了工具技能（插件、知识库、工作流等）时，可以根据用户问题调用这些工具获取信息后回答
            - 当用户的任务需要子智能体执行时，调用 dispatch 工具派发任务
            
            ## 可用子智能体
            ${agents}
            
            ## 派发任务（dispatch 工具）
            dispatch 工具支持单个或并行多个子智能体任务派发，每个派发项包含：
            - targetAgentId（必填）：子智能体 ID
            - inputMessage（必填）：交给子智能体的完整自包含任务描述
            - subject（可选）：新建会话的标题，简洁概括任务内容（如"撰写季度报告"）
            - conversationId（可选）：继续已有会话时填入该会话 ID（从路由记录中获取）
            
            ### 何时派发
            - 用户点名某智能体（"让张三帮我…"、"@张三…"）→ dispatch 单个
            - 用户要完成复杂任务（未点名），你判断最合适的智能体 → dispatch 单个
            - 用户同时要求多个任务（"让张三写报告，同时让李四做PPT"）→ dispatch 多个
            - 用户说"继续"/"接着做"/"回到刚才的任务" → 查看路由记录，找到对应 conversationId → dispatch 带 conversationId
            - 用户要同时继续多个任务（"把UI设计和年度报告都继续完善"）→ dispatch 多个，各带对应的 conversationId（即使同属一个智能体，不同任务有不同 conversationId）
            - 用户对上次结果不满意（"不对""重新来"）→ dispatch 带 conversationId 继续同一会话，inputMessage 中包含修正要求
            - 子智能体上次向用户提了问题，用户现在回答 → dispatch 带 conversationId 继续同一会话
            
            ### 话题切换
            - 用户切换了新话题（如正在做任务突然说"给我讲个故事"）→ 直接回答新话题，不受之前路由影响
            - 用户说"继续"但路由记录为空 → 询问用户想继续什么
            
            ## 历史任务
            ${tasks}
            
            ## 搜索历史任务
            当用户提到的任务不在近期路由记录中（如"继续上个月做的那个数据分析"），调用 search_tasks 工具搜索。
            搜索结果会返回给你，你再根据结果决定是 dispatch 继续任务还是直接回答用户。
            
            ## 进度询问与任务总览
            - 用户询问某个具体任务的进度（如"做得怎么样了？""完成了吗？"查询结果"）→ 调用 query_progress_or_result 工具
            - 用户询问当前所有任务概况（如"现在有什么在跑？""我的任务列表"）→ 调用 list_running_tasks 工具
            
            ## 取消任务
            用户明确表示要取消/停止当前任务（如"取消""停""不做了"）→ 调用 cancel_task 工具
            
            ${memorySection}
            
            ## 重要约束
            - 绝大多数情况下直接回答（不调用任何工具）。
            - 只有明确需要派发任务时才调用 dispatch，需要查找更早任务时才调用 search_tasks。
            - 调用工具时不要同时输出文本（直接调用即可，系统会自动通知用户）。
            - 没有可用子智能体时，不要调用 dispatch。
            - 路由记录仅供参考上下文理解，不要主动重复执行已记录的路由。
            
            ## 严禁虚假派发（极其重要）
            - 你的文本回复不能启动任何任务。只有调用 dispatch 工具才能真正派发任务给子智能体。
            - 如果你回复"已派发给张三"但没有调用 dispatch 工具，任务实际上不会执行，用户会被误导。
            - 需要派发时：不要先说"好的我来派发"，直接调用 dispatch 工具，系统会自动通知用户。
            - 不需要或无法派发时：直接回答用户，不要提及"派发""分配""交给"等暗示已执行的措辞。
            """;

    /**
     * 控制工具名集合，用于识别 returnDirect 返回的路由 JSON
     */
    private static final Set<String> CONTROL_ACTIONS = Set.of(
            "dispatch", "cancel_task", "query_progress_or_result", "list_running_tasks", "search_tasks",
            MemoryToolProvider.ACTION_QUERY, MemoryToolProvider.ACTION_SAVE, MemoryToolProvider.ACTION_DELETE);

    /**
     * 模型调用重试次数（仅瞬时错误、首 token 前）
     */
    private static final int MODEL_RETRY_MAX = 2;
    /**
     * 重试初始退避
     */
    private static final Duration MODEL_RETRY_BACKOFF = Duration.ofSeconds(1);

    /**
     * 一次用户交互内"结果回喂型"控制工具(search_tasks / cancel_task / query_progress_or_result / list_running_tasks /
     * memory_query / memory_save / memory_delete)的最大调用次数。
     * 达到上限后不再挂载控制工具,强制模型基于已累积的完整上下文直接回答,防止"搜索→再决策→再搜索"的无限循环。
     */
    private static final int MAX_TOOL_CALLS = 6;

    // ======================== 执行链持久化 ========================

    /**
     * sessionState 中保存完整执行链（{@code List<ExecMsg>} 的 JSON 字符串）的 key。
     * 执行链 = user → assistant(tool_use) → tool(result) → assistant 交错序列，
     * 跨轮持久化到 conversation.variables，模型执行上下文唯一来源（roundMessage 仅前端展示）。
     */
    private static final String EXEC_CONTEXT_KEY = "masterExecContext";
    /**
     * 执行链 JSON 字符上限，超出后裁剪最旧轮次，防止会话变量无限膨胀。
     */
    private static final int MAX_EXEC_CONTEXT_LENGTH = 12000;

    /**
     * 空 content 兜底占位符：部分模型网关要求所有消息 content 非空（否则报
     * "messages.N: all messages must have non-empty content"），执行链中任何可能为空/空白
     * 的 user / 纯文本 assistant / tool 消息在发送给模型前统一替换为该占位符。
     * 同时覆盖历史会话变量中已持久化的空文本脏数据（转换层兜底）。
     */
    private static final String EMPTY_CONTENT_PLACEHOLDER = "(empty)";

    // ======================== Dependencies ========================

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private ChatApplicationService chatApplicationService;

    @Resource
    private ModelInvoker modelInvoker;

    /**
     * 长期记忆工具的<b>清单 + 执行</b>（memory_query / memory_save / memory_delete）。
     * 与 {@code HybridAgentLoop} 共用同一个 bean：开关判据（{@link MemoryToolProvider#isOpen}）、
     * 工具描述、分类体系、纪律提示词全平台只有一份。
     */
    @Resource
    private MemoryToolProvider memoryToolProvider;

    // ======================== Public API ========================

    public Flux<AgentOutputDto> execute(AgentContext agentContext) {
        // 对齐 AgentGroupLoop：sessionState = variableParams（共享同一 Map，持久化到 conversation.variables）
        initSessionState(agentContext);
        // 轮初对账：以 DB 权威任务状态校正路由历史中过期的 running（回调丢失/轮末回写覆盖的自愈机制）
        refreshRouteStatuses(agentContext);
        // 确保 AgentExecuteResult 已初始化（组件执行回调需要写入结果）
        if (agentContext.getAgentExecuteResult() == null) {
            agentContext.setAgentExecuteResult(new AgentExecuteResult());
        }
        if (agentContext.getAgentExecuteResult().getStartTime() == null) {
            agentContext.getAgentExecuteResult().setStartTime(System.currentTimeMillis());
        }
        // 长期记忆加载（对齐 AgentExecutor：个人信息预加载，其他信息按需查询）
        loadLongMemory(agentContext);
        MasterLoopContext mctx = new MasterLoopContext(agentContext, resolveSubAgents(agentContext), loadHistoryTasks(agentContext));
        // 从 sessionState 恢复上一轮持久化的完整执行链（跨轮连问时模型能看到历史工具调用与结果）
        loadExecContext(mctx);
        // 本 loop 自行构造完整消息链（执行链 + 本轮 user），ModelInvoker 不再追加 buildUserMessage
        agentContext.setExecutionMessagesComplete(true);
        // 入口即写入 user message（roundMessage 仅前端展示，不作为模型执行上下文）
        addUserRoundMessage(agentContext);
        // 对齐 AgentGroupLoop：顶层 Flux.create + 单一 subscribe，内部 Mono.defer 组合
        return Flux.create(sink -> {
            Disposable d = streamWithNativeFC(mctx, sink)
                    // 轮次正常结束：把本轮 [user, 工具链, assistant] 提交到执行链并持久化（错误轮次不提交）
                    .doOnSuccess(v -> commitExecContext(mctx))
                    .subscribe();
            sink.onDispose(d);
        });
    }

    // ======================== Core: Native Function Call Streaming ========================

    /**
     * 单次流式调用 master 模型（挂载控制工具），通过原生 function call 实现路由。
     * <p>
     * 响应式组合模式（对齐 AgentGroupLoop）：
     * <pre>
     * modelInvoker.invoke(mc)
     *     .retryWhen(...)           // 瞬时错误重试（仅首 token 前）
     *     .doOnNext(emit to sink)   // 流式输出 token
     *     .then(Mono.defer(...))    // 流结束后：路由分发 or 自答完成
     * </pre>
     *
     * @return Mono&lt;Void&gt; 表示整个流程完成（成功或已处理错误）
     */
    private Mono<Void> streamWithNativeFC(MasterLoopContext mctx, FluxSink<AgentOutputDto> sink) {
        return streamWithNativeFC(mctx, sink, null, true);
    }

    /**
     * 流式调用 master 模型（完整版，支持多轮工具结果回喂场景）。
     *
     * @param extraPromptSection 追加到 userPrompt 末尾的额外内容（如搜索结果），null 则不追加
     * @param includeSearchTool  是否挂载 search_tasks 工具（工具调用次数达到 {@link #MAX_TOOL_CALLS} 上限后传 false，防循环）
     */
    private Mono<Void> streamWithNativeFC(MasterLoopContext mctx, FluxSink<AgentOutputDto> sink,
                                          String extraPromptSection, boolean includeSearchTool) {
        AgentContext ctx = mctx.ctx();
        String systemPrompt = buildMasterSystemPrompt(mctx);
        String userPrompt = buildUserPrompt(ctx);
        if (StringUtils.isNotBlank(extraPromptSection)) {
            userPrompt += extraPromptSection;
        }
        // 完整执行链由 buildModelContext 统一构造（历史 execContext + 本轮 user + 本轮 toolChain），
        // 走 spring-ai 原生 Message 序列，ModelInvoker 不再追加 user message。
        ModelContext modelContext = buildModelContext(ctx, systemPrompt, userPrompt, mctx);

        // 工具列表 = 控制工具（dispatch/cancel/…） + 智能体绑定的组件工具（插件/知识库/工作流/MCP）
        List<ComponentConfig> allTools = new ArrayList<>(buildControlTools(mctx, includeSearchTool));
        allTools.addAll(buildComponentTools(ctx));
        modelContext.getModelCallConfig().setComponentConfigs(allTools);
        AtomicReference<String> controlSignalRef = new AtomicReference<>();
        modelContext.setComponentExecutingConsumer((componentExecutingDto) -> {
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
                        .doBeforeRetry(signal -> log.warn("[MasterAgentLoop] model call retry #{}: {}",
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
                        log.info("[MasterAgentLoop] control signal: {}", modelContext.getModelCallResult().getData());
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
                        // 控制信号 → 路由分发
                        return dispatchControlSignal(mctx, controlJson, sink);
                    } else {
                        // 自答完成
                        return finalizeSelfAnswer(ctx, sink, mctx.textAccum, mctx.thinkAccum, mctx.componentExecutedList);
                    }
                }))
                .onErrorResume(err -> {
                    log.error("[MasterAgentLoop] model error: {}", err.getMessage(), err);
                    mctx.getErrorOccurred().set(true);
                    return handleModelError(mctx, sink, err);
                });
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
                log.warn("[MasterAgentLoop] model returned empty response");
                setErrorResult(ctx, "模型未返回有效内容");
                sink.next(buildTextMessage(ctx, "抱歉，暂时无法回答，请稍后重试。"));
                sink.next(AgentExecutor.buildFinalResultOutput(ctx));
                sink.complete();
                return;
            }
            String finalText = textAccum.toString();
            // 安全网：检测虚假派发声明（模型说"已派发"但未调用 dispatch 工具）
            if (looksLikeFalseDispatch(finalText)) {
                log.warn("[MasterAgentLoop] possible false dispatch claim in self-answer, "
                                + "model said dispatch-like text without tool_call. convId={}, text={}",
                        ctx.getConversationId(), finalText.length() > 200 ? finalText.substring(0, 200) : finalText);
            }
            addAssistantRoundMessage(ctx, finalText, thinkAccum.toString(), componentExecutedList);
            setSuccessResult(ctx, finalText);
            sink.next(AgentExecutor.buildFinalResultOutput(ctx));
            sink.complete();
            // 长期记忆总结入队（对齐 AgentExecutor：对话完成后异步摘要）
            pushMemorySummary(ctx);
        });
    }

    /**
     * 模型调用错误处理（重试已耗尽后到达此处）。
     */
    private Mono<Void> handleModelError(MasterLoopContext mctx, FluxSink<AgentOutputDto> sink,
                                        Throwable err) {
        return Mono.<Void>fromRunnable(() -> {
            AgentContext ctx = mctx.ctx();
            setErrorResult(ctx, err.getMessage());
            // 错误提示优先展示真实错误信息（err.getMessage），为空时才用兜底文案
            String errText = StringUtils.defaultIfBlank(err.getMessage(), "回答生成失败，请稍后重试。");
            // 已有半截输出也保存（对齐 AgentExecutor.chatError），避免用户刷新后丢失
            if (mctx.getTextAccum().length() > 0) {
                addAssistantRoundMessage(ctx, mctx.getTextAccum().toString() + "\n\n" + errText,
                        mctx.getThinkAccum().toString(), mctx.getComponentExecutedList());
            }
            sink.next(buildTextMessage(ctx, errText));
            sink.next(AgentExecutor.buildFinalResultOutput(ctx));
            sink.complete();
        });
    }

    // ======================== Control Signal Detection & Dispatch ========================

    /**
     * 检测 CallMessage 文本是否为控制工具的 returnDirect 返回。
     * 判定条件：可解析为 JSON 且 "action" 字段值在 {@link #CONTROL_ACTIONS} 中。
     */
    private boolean isControlSignal(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String trimmed = text.trim();
        if (!trimmed.startsWith("{")) {
            return false;
        }
        try {
            JSONObject json = JSON.parseObject(trimmed);
            String action = json.getString("action");
            return action != null && CONTROL_ACTIONS.contains(action);
        } catch (Exception e) {
            return false;
        }
    }

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
     * 解析控制信号并分发到对应处理器。
     * 返回 Mono&lt;Void&gt;，handler 输出通过 doOnNext(sink::next) 桥接。
     */
    private Mono<Void> dispatchControlSignal(MasterLoopContext mctx, String rawJson,
                                             FluxSink<AgentOutputDto> sink) {
        AgentContext ctx = mctx.ctx();
        return Mono.defer(() -> {
            JSONObject control;
            try {
                control = JSON.parseObject(rawJson.trim());
            } catch (Exception e) {
                log.warn("[MasterAgentLoop] control signal parse failed, emit as text. raw={}", rawJson);
                return emitAsSelfAnswer(ctx, rawJson, sink);
            }

            String action = control.getString("action");
            log.info("[MasterAgentLoop] control action={}, params={}", action, rawJson);

            IntentDecision decision = control.to(IntentDecision.class);

            // 结果回喂型控制工具：先把"本次工具调用(含参数)"追加到调用记录累积器,执行后把结果追加到上下文再调用模型,
            // 由模型最终回答用户。调用记录(action+参数)与结果一起回喂,模型才能知道"已调用过该工具、参数是什么",
            // 避免下一轮盲目重复调用同一工具造成循环。
            // (search_tasks / cancel_task / query_progress_or_result / list_running_tasks /
            //  memory_query / memory_save / memory_delete)
            if ("search_tasks".equals(action)) {
                recordToolCall(mctx, action, rawJson);
                return handleSearchTasks(mctx, decision, sink);
            }
            if ("cancel_task".equals(action)) {
                recordToolCall(mctx, action, rawJson);
                return handleCancelTask(mctx, decision, sink);
            }
            if ("query_progress_or_result".equals(action)) {
                recordToolCall(mctx, action, rawJson);
                return handleCheckProgress(mctx, decision, sink);
            }
            if ("list_running_tasks".equals(action)) {
                recordToolCall(mctx, action, rawJson);
                return handleListRunningTasks(mctx, sink);
            }
            if (MemoryToolProvider.ACTION_QUERY.equals(action)) {
                recordToolCall(mctx, action, rawJson);
                return handleMemoryQuery(mctx, control, sink);
            }
            if (MemoryToolProvider.ACTION_SAVE.equals(action)) {
                recordToolCall(mctx, action, rawJson);
                return handleMemorySave(mctx, control, sink);
            }
            if (MemoryToolProvider.ACTION_DELETE.equals(action)) {
                recordToolCall(mctx, action, rawJson);
                return handleMemoryDelete(mctx, control, sink);
            }

            // dispatch 同样记录 tool_use（执行结果由 handleDispatch 内部 appendToolResult，随执行链持久化）
            if ("dispatch".equals(action)) {
                recordToolCall(mctx, action, rawJson);
            }

            // 路由记录：dispatch 由 handler 内部逐条记录（含 conversationId）；其他 action 无需路由记录
            Flux<AgentOutputDto> handlerFlux = switch (action) {
                case "dispatch" -> handleDispatch(mctx, decision);
                default -> {
                    log.warn("[MasterAgentLoop] unknown action [{}], emit as text", action);
                    yield emitAsSelfAnswerFlux(ctx, rawJson);
                }
            };

            // 桥接 handler 输出到 sink（对齐 AgentGroupLoop 的 doOnNext 模式，无嵌套 subscribe）
            return handlerFlux
                    .doOnNext(sink::next)
                    .doOnComplete(sink::complete)
                    .then()
                    .onErrorResume(err -> {
                        log.error("[MasterAgentLoop] handler error: {}", err.getMessage(), err);
                        return Mono.<Void>fromRunnable(() -> {
                            // 兜底路径（handler 内部未拦截的异常）：此时 tool_use 可能已入 toolChain 但
                            // 没有配对 tool(result)，保守置 errorOccurred 跳过执行链提交，避免孤儿 tool_use
                            // 污染执行链；错误信息仍写入 roundMessage，用户刷新后可见
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
     * 控制信号解析失败时的降级：作为普通文本输出（Mono 版本，用于 dispatchControlSignal 内部）
     */
    private Mono<Void> emitAsSelfAnswer(AgentContext ctx, String text,
                                        FluxSink<AgentOutputDto> sink) {
        return Mono.<Void>fromRunnable(() -> {
            addAssistantRoundMessage(ctx, text, "");
            sink.next(buildTextMessage(ctx, text));
            setSuccessResult(ctx, text);
            sink.next(AgentExecutor.buildFinalResultOutput(ctx));
            sink.complete();
        });
    }

    /**
     * 控制信号解析失败时的降级（Flux 版本，用于 switch 表达式）
     */
    private Flux<AgentOutputDto> emitAsSelfAnswerFlux(AgentContext ctx, String text) {
        return Flux.create(sink -> {
            addAssistantRoundMessage(ctx, text, "");
            sink.next(buildTextMessage(ctx, text));
            setSuccessResult(ctx, text);
            sink.next(AgentExecutor.buildFinalResultOutput(ctx));
            sink.complete();
        });
    }

    // ======================== Branch: SEARCH_TASKS（方案A：控制工具 + 二次模型调用） ========================

    /**
     * 搜索历史任务后二次调用模型。
     * <p>
     * 流程：执行搜索 → 搜索结果追加到累积上下文 → 再次调用模型（仍挂载工具,由工具调用次数上限防循环）→
     * 模型根据搜索结果决定 dispatch 继续任务或直接回答。
     */
    private Mono<Void> handleSearchTasks(MasterLoopContext mctx, IntentDecision decision,
                                         FluxSink<AgentOutputDto> sink) {
        String searchResults = executeTaskSearch(mctx, decision);
        log.info("[MasterAgentLoop] search_tasks keyword={}, results:\n{}", decision.getKeyword(), searchResults);

        // 结果追加到轨迹，与 recordToolCall 的 <tool-call> 形成 "input → result" 配对
        appendToolResult(mctx, "search_tasks", searchResults);
        String instruction = "请根据以上搜索结果决定下一步操作：调用 dispatch 继续任务，或直接回答用户。"
                + "\n注意：如果你已经用相同或相近关键词搜索过，请不要重复调用 search_tasks，"
                + "而应根据已返回的搜索结果直接决策（dispatch 或回答）。只有当你确实需要更精确/不同的搜索结果时，才换一个更具体的关键词再次搜索。";
        return continueWithContext(mctx, sink, instruction);
    }

    /**
     * 结果回喂型控制工具的统一续跑入口（search_tasks / cancel_task / query_progress_or_result / list_running_tasks /
     * memory_query / memory_save / memory_delete）。
     * <p>
     * 工具执行结果已由调用方通过 {@link #appendToolResult} 追加到 {@code toolChain}（与
     * {@link #recordToolCall} 的 {@code assistant(tool_use)} 形成 "tool_use → tool_result" 配对），本方法仅负责
     * 把当轮指令（instruction）作为临时 prompt 传给模型，由模型基于完整轨迹最终回答用户。
     * <p>
     * 防循环机制：每次调用递增 {@link MasterLoopContext#toolCallCount}，达到 {@link #MAX_TOOL_CALLS} 上限后
     * 不再挂载控制工具，强制模型基于已累积的完整上下文直接回答。
     */
    private Mono<Void> continueWithContext(MasterLoopContext mctx, FluxSink<AgentOutputDto> sink,
                                           String instruction) {
        boolean overLimit = mctx.getToolCallCount().incrementAndGet() > MAX_TOOL_CALLS;
        if (overLimit) {
            log.warn("[MasterAgentLoop] 工具调用已达上限 {}，停止挂载控制工具，基于已累积上下文直接回答", MAX_TOOL_CALLS);
        }
        // 未超限：继续挂载全部控制工具（可再搜索/再查询）；超限：不再挂载，强制直接回答
        return streamWithNativeFC(mctx, sink, instruction, !overLimit);
    }

    /**
     * 执行任务搜索。
     * 临时实现：从完整路由历史（最多 20 条）中按关键词过滤。
     */
    private String executeTaskSearch(MasterLoopContext ctx, IntentDecision decision) {
        String keyword = StringUtils.defaultIfBlank(decision.getKeyword(), "");
        if (CollectionUtils.isEmpty(ctx.getSubAgents())) {
            return "当前无历史任务。";
        }
        List<Long> agentIds = ctx.getSubAgents().stream().map(AgentConfigDto::getId).toList();
        List<ConversationDto> conversations = conversationApplicationService.queryLatestConversationList(ctx.getAgentContext().getUserId(), agentIds, keyword, 20);
        StringBuilder sb = new StringBuilder();
        for (ConversationDto r : conversations) {
            sb.append("- ");
            if (StringUtils.isNotBlank(r.getTopic())) {
                sb.append("「").append(r.getTopic()).append("」");
            }
            sb.append(" [conversationId=").append(r.getId()).append("]");
            sb.append(" [agentId=").append(r.getAgentId()).append("]");
            sb.append("\n");
        }
        return sb.toString();
    }

    // ======================== Branch: MEMORY（记忆管理：查询/写入更新/删除） ========================

    /**
     * 记忆查询后二次调用模型。
     * <p>
     * 工具清单与执行都在 {@link MemoryToolProvider}（chatbot 那条腿 {@code HybridAgentLoop} 共用同一份，
     * 免得工具描述/分类体系/纪律提示词抄两处后各自漂移）。本方法只做 loop 侧剩下的两步：
     * tool_use 已由 {@link #dispatchControlSignal} 记录 → 结果经 {@link #appendToolResult} 与之配对 →
     * {@link #continueWithContext} 把结果回喂给模型，由模型基于查询结果最终回答用户
     * （返回的 Markdown 表格含 memory_id 列，供后续 memory_save 更新 / memory_delete 删除引用）。
     * <p>
     * ★这三个工具是 {@code Control} 型（{@code ModelInvoker} 强制 {@code returnDirect=true}），
     * 模型调用它们<b>不产生任何用户可见输出</b>；少了最后那步回喂，用户一个字都收不到、sink 也不会 complete。
     */
    private Mono<Void> handleMemoryQuery(MasterLoopContext mctx, JSONObject control, FluxSink<AgentOutputDto> sink) {
        MemoryToolProvider.Op op = memoryToolProvider.query(mctx.ctx(), control);
        appendToolResult(mctx, op.action(), op.result());
        return continueWithContext(mctx, sink, op.instruction());
    }

    /**
     * 记忆新增或更新后二次调用模型（执行、有效性校验与分类体系见 {@link MemoryToolProvider#save}）。
     */
    private Mono<Void> handleMemorySave(MasterLoopContext mctx, JSONObject control, FluxSink<AgentOutputDto> sink) {
        MemoryToolProvider.Op op = memoryToolProvider.save(mctx.ctx(), control);
        appendToolResult(mctx, op.action(), op.result());
        return continueWithContext(mctx, sink, op.instruction());
    }

    /**
     * 记忆删除后二次调用模型。memoryIds 由 {@link MemoryToolProvider#delete} 从控制信号里取；
     * 空列表在那里就被回错喂给模型（不能报成"删了 0 条、成功"，那会让模型对用户说"已删除"）。
     */
    private Mono<Void> handleMemoryDelete(MasterLoopContext mctx, JSONObject control, FluxSink<AgentOutputDto> sink) {
        MemoryToolProvider.Op op = memoryToolProvider.delete(mctx.ctx(), control);
        appendToolResult(mctx, op.action(), op.result());
        return continueWithContext(mctx, sink, op.instruction());
    }

    // ======================== Dispatch: 统一派发 ========================

    /**
     * 统一任务派发入口（替代原 hand_off / delegate / parallel_dispatch / resume_task）。
     * <p>
     * 支持单个或并行多个子智能体，每个派发项通过 conversationId 决定新建/继续会话。
     * 同一智能体可同时有多个不同 conversationId 的并行会话。
     */
    private Flux<AgentOutputDto> handleDispatch(MasterLoopContext mctx, IntentDecision decision) {
        AgentContext ctx = mctx.ctx();
        List<AgentConfigDto> subAgents = mctx.getSubAgents();
        List<DispatchItem> dispatches = decision.getDispatches();
        if (CollectionUtils.isEmpty(dispatches)) {
            // 提前返回也必须补 tool(result)，与 dispatchControlSignal 中已记录的 dispatch tool_use 配对，
            // 否则 toolChain 以孤儿 tool_use 收尾并被 commitExecContext 持久化，下一轮 Anthropic 协议校验 400
            appendToolResult(mctx, "dispatch", "派发未执行：dispatches 列表为空。请指定至少一个子智能体任务（targetAgentId + inputMessage）。");
            return emitSimpleResponse(ctx, "请指定至少一个子智能体任务。");
        }

        // 校验并过滤有效的派发项
        List<DispatchItem> validItems = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (DispatchItem item : dispatches) {
            if (findSubAgent(subAgents, item.getTargetAgentId()) == null) {
                skipped.add(item.getTargetAgentId());
            } else {
                validItems.add(item);
            }
        }
        if (validItems.isEmpty()) {
            appendToolResult(mctx, "dispatch", "派发未执行：未找到子智能体（targetAgentId=" + String.join(", ", skipped)
                    + "）。targetAgentId 必须从可选子智能体列表的 agentId 中选择，请确认后重试。");
            return emitSimpleResponse(ctx, "未找到可派发的子智能体，请确认智能体名称后重试。");
        }

        // 单条派发（无跳过项）：简洁通知
        if (validItems.size() == 1 && skipped.isEmpty()) {
            DispatchItem item = validItems.get(0);
            AgentConfigDto target = findSubAgent(subAgents, item.getTargetAgentId());
            if (target == null) {
                appendToolResult(mctx, "dispatch", "派发未执行：未找到子智能体（targetAgentId=" + item.getTargetAgentId() + "）。");
                return emitSimpleResponse(ctx, "未找到可派发的子智能体，请确认智能体名称后重试。");
            }
            DispatchResult dr = dispatchToAgent(ctx, target, item.getInputMessage(), item.getSubject(), item.getConversationId());
            if (dr.getConversationId() != null) {
                recordRoute(ctx, "dispatch", item.getTargetAgentId(), target.getName(), item.getInputMessage(), dr.getSubject(), dr.getConversationId());
            }
            // 派发结果作为 tool(result) 追加，与 recordToolCall 的 dispatch tool_use 配对
            appendToolResult(mctx, "dispatch", dr.getNotice());
            return emitSimpleResponse(ctx, dr.getNotice());
        }

        // 多条派发（或含跳过项）：列表通知
        StringBuilder noticeBuilder = new StringBuilder("已同时派发 ");
        noticeBuilder.append(validItems.size()).append(" 个任务，正在并行执行中：\n");
        for (DispatchItem item : validItems) {
            AgentConfigDto target = findSubAgent(subAgents, item.getTargetAgentId());
            if (target == null) {
                noticeBuilder.append("- 未找到智能体：").append(item.getTargetAgentId()).append("\n");
                continue;
            }
            DispatchResult dr = dispatchToAgent(ctx, target, item.getInputMessage(), item.getSubject(), item.getConversationId());
            recordRoute(ctx, "dispatch", item.getTargetAgentId(), target.getName(), item.getInputMessage(), dr.getSubject(), dr.getConversationId());
            noticeBuilder.append("- ").append(target.getName())
                    .append("：").append(StringUtils.defaultIfBlank(item.getInputMessage(), "任务")).append("\n");
        }
        if (!skipped.isEmpty()) {
            noticeBuilder.append("\n（未找到智能体：").append(String.join(", ", skipped)).append("，已跳过）");
        }
        // 派发结果作为 tool(result) 追加，与 recordToolCall 的 dispatch tool_use 配对
        appendToolResult(mctx, "dispatch", noticeBuilder.toString());
        return emitSimpleResponse(ctx, noticeBuilder.toString());
    }

    /**
     * 派发任务给单个子智能体（fire-and-forget 核心）。
     * <p>
     * 有 conversationId → 继续该会话；无 → 新建会话（subject 做标题）。
     *
     * @param inputMessage   交给子智能体的任务描述（子智能体看到的 message）
     * @param subject        新建会话的主题/标题（仅新建时使用，空则取用户原始消息）
     * @param conversationId 继续已有会话时填入（空则新建）
     * @return 派发结果（通知文本、会话 ID、新建时的 subject）
     */
    private DispatchResult dispatchToAgent(AgentContext ctx, AgentConfigDto target,
                                           String inputMessage, String subject, String conversationId) {
        boolean contextSetting = false;
        if (RequestContext.get() == null || RequestContext.get().getUserId() == null) {
            contextSetting = true;
            RequestContext<Object> requestContext = new RequestContext<>();
            requestContext.setUserId(ctx.getUserId());
            requestContext.setUser(ctx.getUser());
            requestContext.setTenantId(target.getTenantId());
            requestContext.setTenantConfig(ctx.getTenantConfig());
            requestContext.setLang(ctx.getUser().getLang());
            requestContext.setLangMap(ctx.getUser().getLangMap());
            RequestContext.set(requestContext);
        }

        DispatchResult result = new DispatchResult();
        try {
            String subConversationId;
            if (StringUtils.isNotBlank(conversationId)) {
                // 继续已有会话
                subConversationId = conversationId;
                result.setContinuation(true);
                log.info("[MasterAgentLoop] dispatch CONTINUE to [{}] convId={}", target.getName(), subConversationId);
            } else {
                // 创建新会话：subject 优先用模型生成的标题，兜底取用户原始消息
                String convSubject = StringUtils.defaultIfBlank(subject, ctx.getMessage());
                result.setSubject(convSubject);
                subConversationId = createSubConversation(ctx, target, convSubject);
                result.setContinuation(false);
                log.info("[MasterAgentLoop] dispatch NEW to [{}] convId={}, subject={}", target.getName(), subConversationId, convSubject);
            }
            result.setConversationId(subConversationId);

            TryReqDto subReq = new TryReqDto();
            subReq.setMessage(inputMessage);
            subReq.setConversationId(Long.parseLong(subConversationId));
            subReq.setAttachments(ctx.getAttachments());
            subReq.setSkillIds(ctx.getSkillIds());
            subReq.setFrom("MasterAgent");
            subReq.setRequestId(ctx.getRequestId());
            subReq.setAgentMode(ctx.getAgentMode());
            subReq.setVariableParams(ctx.getVariableParams());
            subReq.setSandboxId(ctx.getSandboxId());
            subReq.setAgentConfig(target);
            if (YesOrNoEnum.Y.getKey().equals(target.getAllowOtherModel())) {
                subReq.setModelId(chatApplicationService.getSelectedModelId(ctx.getUserId(), target.getId()));
            }
            final String routeConvId = subConversationId;
            chatApplicationService.chat(subReq, ctx.getHeaders(), false).subscribe(
                    output -> { /* 子智能体输出由其他途径通知用户 */ },
                    err -> {
                        log.error("[MasterAgentLoop] sub-agent [{}] execution failed: {}",
                                target.getName(), err.getMessage(), err);
                        updateRouteStatus(ctx, routeConvId, "failed");
                    },
                    () -> updateRouteStatus(ctx, routeConvId, "completed")
            );
            ConversationDto conversation = conversationApplicationService.getConversationByCid(subReq.getConversationId());
            String conversationLink = "<conversation id=\"" + subConversationId + "\" agentId=\"" + target.getId() + "\"></conversation>";
            result.setNotice(result.isContinuation()
                    ? "「" + conversation.getTopic() + "」任务由「" + target.getName() + "」开始执行。" + conversationLink
                    : "已将任务「" + conversation.getTopic() + "」派发给「" + target.getName() + "」，正在执行中。" + conversationLink);
            return result;
        } catch (Exception e) {
            result.setNotice("派发任务给「" + target.getName() + "」时发生错误：" + e.getMessage());
            log.error("[MasterAgentLoop] dispatch error", e);
            return result;
        } finally {
            if (contextSetting) {
                RequestContext.remove();
            }
        }
    }

    // ======================== Dispatch Helpers ========================

    /**
     * 创建子智能体新会话。
     */
    private String createSubConversation(AgentContext ctx, AgentConfigDto target, String subject) {
        subject = StringUtils.isBlank(subject) ? ctx.getMessage() : subject;
        ConversationDto conversationForMasterAgentLoop = conversationApplicationService.createConversationForMasterAgentLoop(target.getTenantId(), ctx.getUserId(), target.getId(), subject, new HashMap<>());
        log.info("[MasterAgentLoop] createSubConversation: agent={}, subject={}", target.getName(), subject);
        return conversationForMasterAgentLoop.getId().toString();
    }

    /**
     * 构造子智能体独立上下文。
     * <p>
     * 关键：{@code useSate=true} + 共享 master 的 variableParams → 子智能体的消息
     * 存储在 {@code variableParams["_agent{subId}"]} 中，跨轮持久化。
     *
     * @param inputMessage      交给子智能体的任务描述（空则用 master 原始消息）
     * @param subConversationId 子智能体的目标会话 ID（新建或已有）
     */
    private AgentContext buildIndependentSubContext(AgentContext master, AgentConfigDto subConfig,
                                                    String inputMessage, String subConversationId) {
        AgentContext sub = new AgentContext();
        BeanUtils.copyProperties(master, sub);
        sub.setAgentConfig(subConfig);
        sub.setMessage(StringUtils.isNotBlank(inputMessage) ? inputMessage : master.getMessage());
        sub.setConversationId(subConversationId);
        sub.setContextMessages(new ArrayList<>());
        sub.setAutoToolCallMessages(null);
        sub.setUseSate(true);
        sub.setSessionState(master.getSessionState());
        sub.setVariableParams(master.getVariableParams());
        sub.setAgentExecuteResult(null);
        sub.setFinished(new AtomicBoolean(false));
        return sub;
    }

    // ======================== Branch: CANCEL_TASK ========================

    private Mono<Void> handleCancelTask(MasterLoopContext mctx, IntentDecision decision,
                                        FluxSink<AgentOutputDto> sink) {
        AgentContext ctx = mctx.ctx();
        // 1. 设置打断标志（正在执行的子智能体会检测到此标志并停止）
        chatApplicationService.stopChat(decision.getTargetConversationId());
        String reason = StringUtils.defaultIfBlank(decision.getReason(), decision.getInputMessage());
        log.info("[MasterAgentLoop] cancel task: reason={}", reason);

        // 3. 更新最近路由记录状态为 cancelled（防止"继续"恢复到已取消的任务）
        List<MasterRouteRecord> history = loadRouteHistory(ctx);
        if (!history.isEmpty()) {
            MasterRouteRecord last = history.get(history.size() - 1);
            last.setStatus("cancelled");
            persistSessionState(ctx);
        }

        // 结果追加到轨迹，与 recordToolCall 的 <tool-call> 形成 "input → result" 配对
        appendToolResult(mctx, "cancel_task", "已执行取消操作。原因：" + reason);
        String instruction = "请根据以上结果，以自然友好的方式向用户确认任务已取消。";
        return continueWithContext(mctx, sink, instruction);
    }

    // ======================== Branch: CHECK_PROGRESS ========================

    private Mono<Void> handleCheckProgress(MasterLoopContext mctx, IntentDecision decision,
                                           FluxSink<AgentOutputDto> sink) {
        AgentContext ctx = mctx.ctx();
        // 确定要查询的会话 ID
        String convId = decision.getTargetConversationId();
        String agentName = "子智能体";
        if (StringUtils.isBlank(convId)) {
            // 未指定 → 取路由记录中最近的有 conversationId 的记录
            List<MasterRouteRecord> history = loadRouteHistory(ctx);
            for (int i = history.size() - 1; i >= 0; i--) {
                MasterRouteRecord r = history.get(i);
                if (StringUtils.isNotBlank(r.getConversationId())) {
                    convId = r.getConversationId();
                    agentName = StringUtils.defaultIfBlank(r.getTargetName(), "子智能体");
                    break;
                }
            }
        }

        String statusMsg;
        if (StringUtils.isBlank(convId)) {
            statusMsg = "当前没有正在进行的任务。";
        } else {
            statusMsg = "你要查询的任务不存在";
            try {
                ConversationDto conversationByCid = conversationApplicationService.getConversationWithLastMessageByCid(Long.parseLong(convId));
                if (conversationByCid != null) {
                    statusMsg = conversationByCid.getTaskStatus() == Conversation.ConversationTaskStatus.EXECUTING ? "任务「" + conversationByCid.getTopic() + "」正在执行中" : "任务「" + conversationByCid.getTopic() + "」已结束";
                    if (conversationByCid.getTaskStatus() == Conversation.ConversationTaskStatus.COMPLETE && conversationByCid.getMessageList().size() == 2) {
                        statusMsg = "\n结果如下\n" + conversationByCid.getMessageList().get(1).getText();
                    }
                }
            } catch (Exception e) {
                log.error("[MasterAgentLoop] check_progress error", e);
                statusMsg = "查询任务状态时发生错误：" + e.getMessage();
            }
        }
        log.info("[MasterAgentLoop] check_progress: convId={}", convId);
        // 结果追加到轨迹，与 recordToolCall 的 <tool-call> 形成 "input → result" 配对。
        // 注意：action 名必须与 dispatchControlSignal 中 recordToolCall 记录的一致（query_progress_or_result），
        // 否则 appendToolResult 倒序配对找不到 tool_use，toolCallId 兜底成随机 UUID，Anthropic 校验会报
        // "tool_use_id found in tool_result blocks"。
        appendToolResult(mctx, "query_progress_or_result", statusMsg);
        String instruction = "请根据以上任务状态，以自然友好的方式回应用户。";
        return continueWithContext(mctx, sink, instruction);
    }

    // ======================== Branch: LIST_RUNNING_TASKS ========================

    private Mono<Void> handleListRunningTasks(MasterLoopContext mctx, FluxSink<AgentOutputDto> sink) {
        AgentContext ctx = mctx.ctx();
        log.info("[MasterAgentLoop] list_running_tasks: userId={}", ctx.getUserId());
        StringBuilder sb = new StringBuilder();
        if (CollectionUtils.isEmpty(mctx.getSubAgents())) {
            sb.append("当前没有正在执行任务。");
        } else {
            List<Long> agentIds = mctx.getSubAgents().stream().map(AgentConfigDto::getId).toList();
            List<ConversationDto> conversations = conversationApplicationService.queryLatestConversationList(ctx.getUserId(), agentIds, "", 20);
            List<ConversationDto> list = conversations.stream().filter(c -> c.getTaskStatus() == Conversation.ConversationTaskStatus.EXECUTING).toList();
            if (list.isEmpty()) {
                sb.append("当前没有正在执行的任务。");
            } else {
                sb.append("当前有").append(list.size()).append("个任务正在执行，分别为：\n");
                int count = 0;
                for (int i = list.size() - 1; i >= 0 && count < 10; i--, count++) {
                    ConversationDto r = list.get(i);
                    AgentConfigDto subAgent = findSubAgent(mctx.getSubAgents(), r.getAgentId().toString());
                    sb.append(count + 1).append(". ").append(r.getTopic());
                    if (subAgent != null) {
                        sb.append("，来自「").append(subAgent.getName()).append("」\n");
                    } else {
                        sb.append("\n");
                    }
                }
            }
        }
        // 结果追加到轨迹，与 recordToolCall 的 <tool-call> 形成 "input → result" 配对
        appendToolResult(mctx, "list_running_tasks", sb.toString());
        String instruction = "请根据以上正在执行的任务列表，以自然友好的方式回应用户。";
        return continueWithContext(mctx, sink, instruction);
    }

    // ======================== Helpers ========================

    private Flux<AgentOutputDto> emitSimpleResponse(AgentContext ctx, String msg) {
        return Flux.create(sink -> {
            addAssistantRoundMessage(ctx, msg, "");
            sink.next(buildTextMessage(ctx, msg));
            setSuccessResult(ctx, msg);
            sink.next(AgentExecutor.buildFinalResultOutput(ctx));
            sink.complete();
        });
    }

    /**
     * 检测自答文本是否包含虚假派发声明（模型说"已派发"但实际未调用 dispatch 工具）。
     * 仅用于日志监控，不阻断流程。
     */
    private static final String[] FALSE_DISPATCH_PATTERNS = {
            "已派发", "已经派发", "已分配", "已交给", "已转交", "任务已交", "已经交给", "已经分配"
    };

    private boolean looksLikeFalseDispatch(String text) {
        if (text == null || text.length() < 4) {
            return false;
        }
        for (String pattern : FALSE_DISPATCH_PATTERNS) {
            if (text.contains(pattern)) {
                return true;
            }
        }
        return false;
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

    // ======================== Control Tools Builder ========================

    /**
     * 构建控制工具列表（{@link ComponentTypeEnum#Control}，returnDirect=true）。
     * 模型通过原生 function call 调用这些工具来触发路由。
     */
    private List<ComponentConfig> buildControlTools(MasterLoopContext mctx, boolean includeSearchTool) {
        List<AgentConfigDto> subAgents = mctx.getSubAgents();
        List<ComponentConfig> tools = new ArrayList<>();

        // dispatch：统一任务派发（单个/并行/新建/继续）
        if (CollectionUtils.isNotEmpty(subAgents)) {
            ComponentConfig dispatch = new ComponentConfig();
            dispatch.setType(ComponentTypeEnum.Control);
            dispatch.setFunctionName("dispatch");
            dispatch.setName("派发任务");
            dispatch.setDescription("派发任务给子智能体执行，支持单个或并行多个。继续已有会话时填 conversationId(从路由记录获取),新建会话时填 subject 作为会话标题。" + buildAgentListForTool(subAgents));
            dispatch.setTargetId(0L);
            dispatch.setInputArgs(List.of(
                    Arg.builder().name("dispatches").description("派发列表,单个任务传1项,并行多任务传多项").dataType(DataTypeEnum.Array_Object).require(true)
                            .subArgs(List.of(
                                    Arg.builder().name("targetAgentId").description("子智能体ID").dataType(DataTypeEnum.String).require(true).build(),
                                    Arg.builder().name("inputMessage").description("交给子智能体的完整自包含任务描述(继续已有会话时写'继续之前的任务'并附上修正要求)").dataType(DataTypeEnum.String).require(true).build(),
                                    Arg.builder().name("subject").description("新建会话的标题,简洁概括任务内容(如'撰写季度报告'),继续已有会话时不填").dataType(DataTypeEnum.String).require(false).build(),
                                    Arg.builder().name("conversationId").description("继续已有会话时填入该会话ID(从路由记录中获取),不填则新建会话").dataType(DataTypeEnum.String).require(false).build()
                            ))
                            .build()
            ));
            tools.add(dispatch);
        }

        // query_progress_or_result：查询任务执行进度
        ComponentConfig progress = new ComponentConfig();
        progress.setType(ComponentTypeEnum.Control);
        progress.setFunctionName("query_progress_or_result");
        progress.setName("查询进度与结果");
        progress.setDescription("当用户询问某个任务的执行进度/状态/执行结果时调用(如'做得怎么样了''完成了吗''什么情况''查询结果')。");
        progress.setTargetId(0L);
        progress.setInputArgs(List.of(
                Arg.builder().name("targetConversationId").description("要查询进度或结果的会话ID(从路由记录中获取,若不确定可留空查最近的)").dataType(DataTypeEnum.String).require(false).build()
        ));
        tools.add(progress);

        // list_running_tasks：查询当前所有正在执行的任务汇总
        ComponentConfig listTasks = new ComponentConfig();
        listTasks.setType(ComponentTypeEnum.Control);
        listTasks.setFunctionName("list_running_tasks");
        listTasks.setName("任务总览");
        listTasks.setDescription("当用户询问当前有哪些任务在执行/进行中时调用(如'现在有什么任务在跑''我的任务列表''都在忙什么')。无需参数，直接返回所有进行中任务的汇总。");
        listTasks.setTargetId(0L);
        listTasks.setInputArgs(List.of());
        tools.add(listTasks);

        // cancel_task：取消当前正在进行的任务
        ComponentConfig cancel = new ComponentConfig();
        cancel.setType(ComponentTypeEnum.Control);
        cancel.setFunctionName("cancel_task");
        cancel.setName("取消任务");
        cancel.setDescription("当用户明确表示要取消/停止/终止当前正在进行的任务时调用(如'取消''停''不做了''算了不要了')。");
        cancel.setTargetId(0L);
        cancel.setInputArgs(List.of(
                Arg.builder().name("reason").description("取消原因(可选,用于记录)").dataType(DataTypeEnum.String).require(false).build(),
                Arg.builder().name("targetConversationId").description("需要取消的任务conversationId").dataType(DataTypeEnum.String).require(true).build()
        ));
        tools.add(cancel);

        // search_tasks：搜索更久远的历史任务（工具调用次数达到上限后不再挂载，防循环）
        if (includeSearchTool) {
            ComponentConfig searchTasks = new ComponentConfig();
            searchTasks.setType(ComponentTypeEnum.Control);
            searchTasks.setFunctionName("search_tasks");
            searchTasks.setName("搜索任务");
            searchTasks.setDescription("当用户提到的任务不在近期路由记录中时调用(如'继续上个月做的那个数据分析')。搜索更久远的历史任务,搜索结果会返回给你,再决定 dispatch 继续还是直接回答。");
            searchTasks.setTargetId(0L);
            searchTasks.setInputArgs(List.of(
                    Arg.builder().name("keyword").description("搜索关键词(任务名称、描述中的关键字)，不传则查询最近的任务列表（含正在执行中的任务）").dataType(DataTypeEnum.String).require(false).build()
            ));
            tools.add(searchTasks);
        }

        // 记忆管理工具（查询/写入更新/删除）：仅当智能体开启长期记忆时挂载。
        // ★判据收敛到 MemoryToolProvider#isOpen，与 ${memorySection} 提示词段注入同源（见 buildMasterSystemPrompt），
        //   也与 loadLongMemory 的门控一致 —— 三处只要有一处判据不同，就会出现"提示词里提到没挂的工具"
        //   或"挂了工具但模型不知道规矩（编造 memoryId）"。
        if (memoryToolProvider.isOpen(mctx.ctx())) {
            tools.addAll(memoryToolProvider.buildTools());
        }

        return tools;
    }

    private String buildAgentListForTool(List<AgentConfigDto> subAgents) {
        StringBuilder sb = new StringBuilder(" 可选子智能体：");
        for (AgentConfigDto agent : subAgents) {
            sb.append(agent.getName()).append("(agentId=").append(agent.getId()).append(", ")
                    .append(StringUtils.defaultString(agent.getDescription())).append("); ");
        }
        return sb.toString();
    }

    // ======================== Component Tools (插件/知识库/工作流/MCP) ========================

    /**
     * 将 master 智能体绑定的组件（插件/知识库/工作流/MCP）转换为模型可调用的工具。
     * <p>
     * 对齐 {@code AgentExecutor.convertToComponentConfigList}，但排除：
     * <ul>
     *   <li>{@code Type.Agent}：子智能体由 dispatch 控制工具处理，不暴露为直接工具</li>
     *   <li>{@code Type.Table}、{@code Type.Page} 等：总控暂不支持</li>
     * </ul>
     * ModelInvoker 在模型调用这些工具时会自动执行（调插件 API / 知识库检索 / 工作流运行），
     * 并通过 {@code componentExecutingConsumer} 回调通知执行进度。
     */
    private List<ComponentConfig> buildComponentTools(AgentContext ctx) {
        List<AgentComponentConfigDto> componentDtos = ctx.getAgentConfig().getAgentComponentConfigList();
        if (CollectionUtils.isEmpty(componentDtos)) {
            return List.of();
        }
        List<ComponentConfig> tools = new ArrayList<>();
        for (AgentComponentConfigDto dto : componentDtos) {
            // 子智能体由 dispatch 控制工具处理，不暴露为直接工具
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
                        // Table, Page 等：总控暂不支持，跳过
                        continue;
                }
                if (!CollectionUtils.isEmpty(inputArgBindConfigs)) {
                    config.setInputArgs(inputArgBindConfigs.stream().map(arg -> (Arg) arg).toList());
                }
                tools.add(config);
            } catch (Exception e) {
                log.warn("[MasterAgentLoop] skip component [{}] type={}: {}", dto.getName(), dto.getType(), e.getMessage());
            }
        }
        if (!tools.isEmpty()) {
            log.info("[MasterAgentLoop] loaded {} component tools: {}", tools.size(),
                    tools.stream().map(ComponentConfig::getName).toList());
        }
        return tools;
    }

    /**
     * 获取 MCP 工具的精确描述（从已部署的工具列表中按 toolName 查找）。
     * 对齐 {@code AgentExecutor.getMcpToolDescription}。
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

    private String buildTaskListForPrompt(List<HistoryTask> historyTasks) {
        StringBuilder sb = new StringBuilder();
        for (HistoryTask task : historyTasks) {
            sb.append("- ").append(task.getName());
            sb.append("[conversationId=").append(task.getTaskId()).append("]");
            if (StringUtils.isNotBlank(task.getAgentId())) {
                sb.append("[agentId=").append(task.getAgentId()).append("]");
            }
            if (StringUtils.isNotBlank(task.getStatus())) {
                sb.append("[").append(task.getStatus()).append("]");
            }
            if (StringUtils.isNotBlank(task.getTime())) {
                sb.append(" ").append(task.getTime());
            }
            if (StringUtils.isNotBlank(task.getDescription())) {
                String desc = task.getDescription();
                sb.append("：").append(desc.length() > 60 ? desc.substring(0, 60) + "…" : desc);
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private static String formatTime(Date date) {
        if (date == null) {
            return "";
        }
        return LocalDateTime.ofInstant(date.toInstant(), ZoneId.systemDefault()).format(TIME_FMT);
    }

    // ======================== Context / List Builders ========================

    /**
     * 初始化 sessionState（对齐 AgentGroupLoop 模式）。
     * <p>
     * 从 conversation.variables 加载上一轮持久化的状态（含路由历史），
     * 合并到 variableParams 后令 sessionState = variableParams（共享引用），
     * 后续 recordRoute 写入 sessionState 即写入 variableParams，
     * 通过 {@link #persistSessionState} 保存到 conversation.variables。
     */
    private void initSessionState(AgentContext ctx) {
        Map<String, Object> variableParams = ctx.getVariableParams();
        // 从会话变量中恢复上一轮的状态（路由历史等）
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
     * 对齐 AgentGroupLoop 的 {@code conversationApplicationService.updateConversationVariables} 调用。
     */
    private void persistSessionState(AgentContext ctx) {
        try {
            if (ctx.getSessionState() != null && StringUtils.isNotBlank(ctx.getConversationId())) {
                conversationApplicationService.updateConversationVariables(
                        Long.parseLong(ctx.getConversationId()), ctx.getSessionState());
            }
        } catch (Exception e) {
            log.warn("[MasterAgentLoop] persist session state failed: {}", e.getMessage());
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
            log.warn("[MasterAgentLoop] failed to query long-term memory", e);
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
            log.warn("[MasterAgentLoop] failed to push memory summary: {}", e.getMessage());
        }
    }

    private List<AgentConfigDto> resolveSubAgents(AgentContext ctx) {
        if (ctx.getAgentConfig() == null || CollectionUtils.isEmpty(ctx.getAgentConfig().getAgentComponentConfigList())) {
            return Collections.emptyList();
        }
        return ctx.getAgentConfig().getAgentComponentConfigList().stream()
                .filter(c -> c.getType() == AgentComponentConfig.Type.Agent)
                .filter(c -> c.getTargetConfig() instanceof AgentConfigDto)
                .map(c -> (AgentConfigDto) c.getTargetConfig())
                .toList();
    }

    /**
     * 加载历史任务列表：
     * 1. 从路由记录中提取 hand_off 创建的子会话（有 conversationId 的）
     */
    private List<HistoryTask> loadHistoryTasks(AgentContext ctx) {
        List<HistoryTask> tasks = new ArrayList<>();
        List<AgentConfigDto> agentConfigs = resolveSubAgents(ctx);
        if (agentConfigs == null || agentConfigs.isEmpty()) {
            return tasks;
        }
        List<Long> agentIds = agentConfigs.stream().map(AgentConfigDto::getId).toList();
        List<ConversationDto> conversations = conversationApplicationService.queryLatestConversationList(ctx.getUserId(), agentIds, null, 10);
        for (ConversationDto r : conversations) {
            HistoryTask task = new HistoryTask();
            task.setTaskId(r.getId().toString());
            task.setName(StringUtils.defaultIfBlank(r.getTopic(), "子智能体任务"));
            task.setDescription("");
            task.setAgentId(r.getAgentId() != null ? r.getAgentId().toString() : "");
            task.setTime(formatTime(r.getModified() != null ? r.getModified() : r.getCreated()));
            task.setStatus(r.getTaskStatus() != null ? r.getTaskStatus().name() : "CREATE");
            tasks.add(task);
        }
        return tasks;
    }

    private AgentConfigDto findSubAgent(List<AgentConfigDto> subAgents, String agentId) {
        if (StringUtils.isBlank(agentId)) {
            return null;
        }
        String id = agentId.trim();
        return subAgents.stream()
                .filter(a -> a.getId() != null && a.getId().toString().equals(id))
                .findFirst().orElse(null);
    }

    private HistoryTask findTask(List<HistoryTask> historyTasks, String taskId) {
        if (StringUtils.isBlank(taskId) || CollectionUtils.isEmpty(historyTasks)) {
            return null;
        }
        return historyTasks.stream()
                .filter(t -> taskId.trim().equals(t.getTaskId()))
                .findFirst().orElse(null);
    }

    // ======================== Prompt Builders ========================

    private String buildMasterSystemPrompt(MasterLoopContext mctx) {
        AgentContext ctx = mctx.ctx();
        String agents = CollectionUtils.isEmpty(mctx.getSubAgents()) ? "（无可用的子智能体）" : buildAgentListForTool(mctx.getSubAgents());
        String tasks = CollectionUtils.isEmpty(mctx.getHistoryTasks()) ? "（无可用历史任务）" : buildTaskListForPrompt(mctx.getHistoryTasks());
        // 记忆管理段仅在开启长期记忆时注入（判据与记忆工具挂载同源，都在 MemoryToolProvider#isOpen，
        // 避免提示词里提及本轮根本没挂的工具）
        boolean memoryOpen = memoryToolProvider.isOpen(ctx);
        String base = MASTER_SYSTEM_PROMPT
                .replace("${agents}", agents)
                .replace("${tasks}", tasks)
                .replace("${memorySection}", memoryOpen ? MemoryToolProvider.MEMORY_PROMPT_SECTION : "");
        String customPersona = ctx.getAgentConfig().getSystemPrompt();
        if (StringUtils.isNotBlank(customPersona)) {
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
     * &lt;route-history&gt;          近期派发记录（有时）
     * &lt;user-reminder&gt;          智能体自定义指令（配置时）
     * &lt;attachments&gt;            附件元数据（有时）
     * &lt;user-message&gt;           本轮用户消息（恒有，永远位于最后）
     * </pre>
     */
    private String buildUserPrompt(AgentContext ctx) {
        StringBuilder sb = new StringBuilder();

        // ① 长期记忆注入（对齐 AgentExecutor 的 <user-memory> 格式）
        if (StringUtils.isNotBlank(ctx.getLongMemory())) {
            sb.append("<user-memory>\n").append(ctx.getLongMemory()).append("\n</user-memory>\n\n");
        }

        // ② 对话摘要
        if (ctx.getConversation() != null && StringUtils.isNotBlank(ctx.getConversation().getSummary())) {
            sb.append("<conversation-summary>\n").append(ctx.getConversation().getSummary()).append("\n</conversation-summary>\n\n");
        }

        // ③ 近期路由记录（连问时模型知道之前做过什么路由）
        sb.append(buildRouteHistorySection(loadRouteHistory(ctx)));

        // ④ 智能体自定义指令（占位符解析后注入）
        if (StringUtils.isNotBlank(ctx.getAgentConfig().getUserPrompt())) {
            String userPrompt = PlaceholderParser.resoleAndReplacePlaceholder(
                    ctx.getVariableParams(), ctx.getAgentConfig().getUserPrompt());
            sb.append("<user-reminder>\n").append(userPrompt).append("\n</user-reminder>\n\n");
        }

        // ⑤ 附件元数据
        if (CollectionUtils.isNotEmpty(ctx.getAttachments())) {
            sb.append("<attachments>\n").append(JSON.toJSONString(ctx.getAttachments())).append("\n</attachments>\n\n");
        }
        // 仅有附件而无文字时，补充系统指令让模型分析附件
        if (StringUtils.isBlank(ctx.getMessage()) && CollectionUtils.isNotEmpty(ctx.getAttachments())) {
            ctx.setMessage("\n" + I18nUtil.systemMessage(ctx.getUser().getLangMap(), "Backend.Agent.Message.AnalyzeAttachments"));
        }

        // ⑥ 本轮用户消息（恒以标签包裹并置于末尾，与上方上下文块严格区分）
        sb.append("<user-message>\n").append(StringUtils.defaultString(ctx.getMessage())).append("\n</user-message>");

        // ⑦ 当前时间（ModelInvoker 不再走 buildUserMessage，此处补齐，对齐其格式）
        sb.append("\n<current-time>").append(DateUtil.getNowTime()).append("</current-time>\n");

        return sb.toString();
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
     *   <li>本轮 user（buildUserPrompt 含 user-memory / route-history / user-reminder / attachments / user-message）</li>
     *   <li>本轮工具链（assistant(tool_use) → tool(result) 配对）</li>
     * </ol>
     * 消息顺序严格遵循 OpenAI function-call 协议（tool 消息必须在 user 之后、assistant 之前），
     * 由 ModelInvoker 原样透传给模型（executionMessagesComplete=true 时不再追加 user）。
     */
    private List<Message> buildExecutionMessages(MasterLoopContext mctx, String userPrompt) {
        List<Message> messages = new ArrayList<>();
        // ① 历史执行链（对称配对校验：孤儿 tool_use / tool_result 无论位置一律丢弃，防止 Anthropic 协议校验失败；
        //    存量脏数据发送时自动修复，丢弃后写回执行链使持久化数据逐步干净，砖死会话下一轮即自愈）
        List<ExecMsg> sanitizedHistory = sanitizeExecMsgs(mctx.getExecContext(), true);
        // requireUserStart=true：丢弃首个 user 之前的残片（trimExecContext 逐条删除可能留下 tool_use/assistant 开头的残片）
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
        // 且 tool_result 后必须紧跟 assistant，补占位 assistant 收尾后再衔接本轮 user，
        // 否则 "roles must alternate" / "tool_use ids without tool_result" 400（部分网关要求 content 非空，不能补空串）
        if (!messages.isEmpty() && messages.get(messages.size() - 1).getMessageType() != MessageType.ASSISTANT) {
            messages.add(new AssistantMessage(EMPTY_CONTENT_PLACEHOLDER));
        }
        // ② 本轮 user（动态构造，含当前上下文块）
        messages.add(new UserMessage(userPrompt));
        // ③ 本轮工具链（assistant(tool_use) → tool(result) 交错，工具结果紧随调用之后）；
        //    同样过一遍配对校验兜底本回合断链（防御未来新增的孤儿产生路径）
        List<ExecMsg> sanitizedChain = sanitizeExecMsgs(mctx.getToolChain(), false);
        if (sanitizedChain.size() != mctx.getToolChain().size()) {
            mctx.getToolChain().clear();
            mctx.getToolChain().addAll(sanitizedChain);
        }
        for (ExecMsg m : sanitizedChain) {
            Message msg = toSpringAiMessage(m);
            if (msg != null) {
                messages.add(msg);
            }
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
     *   <li>requireUserStart=true 时（历史执行链）：丢弃首个 user 之前的残片 —— {@link #trimExecContext}
     *       对超长单轮会逐条从头删，可能留下以 tool_use/assistant 开头的残片，转换后首条消息非 user，
     *       Anthropic 报 "first message must be user" 且每轮重复触发；</li>
     *   <li>相邻消息同属一类（tool_result 与 user 同属 user 类）：插入占位消息保证角色严格交替 ——
     *       轮末 assistant 占位是后补逻辑，存量数据可能缺占位导致 tool_result 直接接下一轮 user，
     *       Anthropic 报 "roles must alternate"。</li>
     * </ol>
     * 对已污染会话是"发送即自愈"：清洗发生在构造消息时，调用方检测到结构变化会写回执行链，持久化数据逐步变干净。
     * <p>
     * 注意：返回新列表（插入/丢弃改变结构）；但对配对消息的 id 回填是原地修改（入参列表持有同一对象引用），
     * 即使结构未变化未触发写回，{@link #commitExecContext} 序列化时也会带上修正后的 id。
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
                log.warn("[MasterAgentLoop] exec context has no user message, dropped as trim fragment, size={}", msgs.size());
                return sanitized;
            }
            if (start > 0) {
                log.warn("[MasterAgentLoop] exec context leading fragment before first user dropped, size={}", start);
            }
        }
        ExecMsg pendingUse = null;
        for (int i = start; i < msgs.size(); i++) {
            ExecMsg m = msgs.get(i);
            if ("assistant".equals(m.getRole()) && StringUtils.isNotBlank(m.getToolName())) {
                // 新 tool_use：上一个 pending tool_use 未等到配对结果，视为孤儿丢弃
                if (pendingUse != null) {
                    log.warn("[MasterAgentLoop] orphan tool_use dropped from exec context: {}", pendingUse.getToolName());
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
                    log.warn("[MasterAgentLoop] orphan tool_result dropped from exec context: {}", m.getToolName());
                }
            } else {
                // user / 纯文本 assistant：轮次边界，未配对的 pending tool_use 一并丢弃
                if (pendingUse != null) {
                    log.warn("[MasterAgentLoop] orphan tool_use dropped from exec context: {}", pendingUse.getToolName());
                    pendingUse = null;
                }
                ensureRoleAlternation(sanitized, m);
                sanitized.add(m);
            }
        }
        // 末尾孤儿 tool_use（无对应 tool_result）：Anthropic 要求 tool_use 必须得到响应，丢弃
        if (pendingUse != null) {
            log.warn("[MasterAgentLoop] orphan tool_use dropped from exec context tail: {}", pendingUse.getToolName());
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
            log.warn("[MasterAgentLoop] role alternation broken in exec context, inserted placeholder {}", placeholder.getRole());
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
     * 从 sessionState 恢复上一轮持久化的完整执行链（run 入口调用）。
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
            log.warn("[MasterAgentLoop] load exec context failed: {}", e.getMessage());
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
            log.warn("[MasterAgentLoop] error round, skip exec context commit");
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
        // assistant 最终文本（自答/工具后回答；dispatch 等直接返回场景 notice 已作为 tool 结果）
        if (mctx.getTextAccum().length() > 0) {
            ExecMsg assistantMsg = new ExecMsg();
            assistantMsg.setRole("assistant");
            assistantMsg.setText(mctx.getTextAccum().toString());
            assistantMsg.setThink(mctx.getThinkAccum().toString());
            mctx.getExecContext().add(assistantMsg);
        } else {
            // 执行链每轮必须以 assistant 结尾：Anthropic 协议要求 tool_result 之后必须紧跟 assistant 消息，
            // 跨轮时 tool_result 不能直接衔接新 user。dispatch 等直接返回场景 textAccum 为空，补占位 assistant 收尾
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
     * 持久化执行链到 sessionState（跨轮可见，与路由历史同机制）。
     */
    private void persistExecContext(MasterLoopContext mctx) {
        try {
            AgentContext ctx = mctx.ctx();
            if (ctx.getSessionState() != null) {
                ctx.getSessionState().put(EXEC_CONTEXT_KEY, JSON.toJSONString(mctx.getExecContext()));
                persistSessionState(ctx);
            }
        } catch (Exception e) {
            log.warn("[MasterAgentLoop] persist exec context failed: {}", e.getMessage());
        }
    }

    /**
     * 执行链超过 {@link #MAX_EXEC_CONTEXT_LENGTH} 时，按轮次（user 消息为轮次起点）裁剪最旧部分。
     */
    private void trimExecContext(MasterLoopContext mctx) {
        while (!mctx.getExecContext().isEmpty()) {
            String json = JSON.toJSONString(mctx.getExecContext());
            if (json.length() <= MAX_EXEC_CONTEXT_LENGTH) {
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
                .id(UUID.randomUUID().toString().replace("-", ""))
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

    // ======================== Route History (跨轮持久化) ========================

    private static final String ROUTE_HISTORY_KEY = "masterRouteHistory";
    private static final int MAX_ROUTE_HISTORY = 20;
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
     * 从 sessionState 加载路由历史（跨轮持久化，连问时模型知道之前路由过什么）。
     */
    private List<MasterRouteRecord> loadRouteHistory(AgentContext ctx) {
        Map<String, Object> state = ctx.getSessionState();
        if (state == null) {
            return new ArrayList<>();
        }
        List<MasterRouteRecord> history = deserializeRouteHistory(state.get(ROUTE_HISTORY_KEY));
        state.put(ROUTE_HISTORY_KEY, history);
        return history;
    }

    /**
     * 将原始对象（可能是 List&lt;MasterRouteRecord&gt; 或 DB 反序列化出的 List&lt;Map&gt;）转为路由记录列表。
     * 供 {@link #loadRouteHistory}（内存）和 {@link #updateRouteStatus}（DB 最新）共用。
     */
    private List<MasterRouteRecord> deserializeRouteHistory(Object obj) {
        if (!(obj instanceof List<?> list)) {
            return new ArrayList<>();
        }
        List<MasterRouteRecord> history = new ArrayList<>();
        for (Object item : list) {
            try {
                if (item instanceof MasterRouteRecord r) {
                    history.add(r);
                } else if (item instanceof Map<?, ?> map) {
                    history.add(JSONObject.from(map).to(MasterRouteRecord.class));
                }
            } catch (Exception e) {
                log.warn("[MasterAgentLoop] skip malformed route record: {}", e.getMessage());
            }
        }
        return history;
    }

    /**
     * 记录一次路由决策到 sessionState（下次用户连问时模型可见）。
     *
     * @param subject 任务主题（新建时由 DispatchResult 提供；继续时自动从同 conversationId 的历史记录继承）
     */
    private void recordRoute(AgentContext ctx, String action, String targetId, String targetName,
                             String inputMessage, String subject, String conversationId) {
        List<MasterRouteRecord> history = loadRouteHistory(ctx);
        // 继续会话时 subject 可能为空，从同 conversationId 的历史记录中继承
        if (StringUtils.isBlank(subject) && StringUtils.isNotBlank(conversationId)) {
            for (int i = history.size() - 1; i >= 0; i--) {
                MasterRouteRecord r = history.get(i);
                if (conversationId.equals(r.getConversationId()) && StringUtils.isNotBlank(r.getSubject())) {
                    subject = r.getSubject();
                    break;
                }
            }
        }
        MasterRouteRecord record = new MasterRouteRecord();
        record.setAction(action);
        record.setTargetId(targetId);
        record.setTargetName(targetName);
        record.setInputMessage(inputMessage);
        record.setSubject(subject);
        record.setConversationId(conversationId);
        record.setTime(LocalDateTime.now().format(TIME_FMT));
        record.setStatus("running");
        history.add(record);
        // 保留最近 N 条，避免无限膨胀
        while (history.size() > MAX_ROUTE_HISTORY) {
            history.remove(0);
        }
        if (ctx.getSessionState() != null) {
            ctx.getSessionState().put(ROUTE_HISTORY_KEY, history);
        }
        // 立即持久化到 conversation.variables（跨轮可见）
        persistSessionState(ctx);
    }

    /**
     * 更新路由记录中指定会话的任务状态（子智能体完成/失败时由后台回调触发）。
     * <p>
     * <b>并发安全</b>：回调持有的 {@code ctx.sessionState} 是派发那一轮的内存快照，
     * 后续用户对话可能已经 persist 了新版本到 DB。因此此方法<b>不</b>读写 ctx.sessionState，
     * 而是从 DB 读 master 会话的最新 variables → 只改路由状态 → 写回，避免覆盖后续对话的数据。
     *
     * @param conversationId 子智能体会话 ID（用于匹配路由记录）
     * @param status         目标状态（completed / failed）
     */
    private void updateRouteStatus(AgentContext ctx, String conversationId, String status) {
        try {
            // 路由历史存在 master 会话的 variables 中（不是子智能体会话）
            Long masterConvId = Long.parseLong(ctx.getConversationId());
            ConversationDto masterConv = conversationApplicationService.getConversationByCid(masterConvId);
            if (masterConv == null || masterConv.getVariables() == null) {
                log.warn("[MasterAgentLoop] updateRouteStatus: master conversation {} not found or no variables", masterConvId);
                return;
            }
            // 以 DB 最新 variables 为基线（不碰 ctx.sessionState 的过期快照）
            Map<String, Object> latestVars = new HashMap<>(masterConv.getVariables());
            List<MasterRouteRecord> history = deserializeRouteHistory(latestVars.get(ROUTE_HISTORY_KEY));
            boolean updated = false;
            for (int i = history.size() - 1; i >= 0; i--) {
                MasterRouteRecord r = history.get(i);
                if (conversationId.equals(r.getConversationId()) && "running".equals(r.getStatus())) {
                    r.setStatus(status);
                    updated = true;
                    break;
                }
            }
            if (updated) {
                latestVars.put(ROUTE_HISTORY_KEY, history);
                conversationApplicationService.updateConversationVariables(masterConvId, latestVars);
                log.info("[MasterAgentLoop] route status updated: subConvId={}, status={}", conversationId, status);
            }
        } catch (Exception e) {
            log.warn("[MasterAgentLoop] update route status failed: convId={}, status={}, err={}",
                    conversationId, status, e.getMessage());
        }
    }

    /**
     * 轮初对账：以 DB 中子会话的权威 {@code taskStatus} 校正路由历史记录的状态。
     * <p>
     * 路由状态平时靠派发子智能体时的异步回调（{@link #updateRouteStatus}）更新，但该回调可能丢失
     * （进程重启、订阅中断），且轮末 {@code persistSessionState} 整体回写内存快照时可能把回调已写入
     * DB 的最新状态覆盖回旧值。本方法作为自愈机制，在每轮初始化（sessionState 刚与 DB 合并后）执行一次：
     * <ul>
     *   <li>收集所有记录的 conversationId（去重），{@code getConversationListByCIds} 一次批量查询；</li>
     *   <li>不论记录当前是什么状态都按权威状态刷新：
     *       COMPLETE→completed、FAILED→failed、CANCEL→cancelled、CREATE/EXECUTING→running；</li>
     *   <li>子会话不存在（已删除）或状态为 null 时保持原值；</li>
     *   <li>有变化才整体持久化一次（此时 sessionState 为刚合并的最新视图，回写安全）。</li>
     * </ul>
     * 对账后 {@code <route-history>} 与系统提示词 {@code ${tasks}} 块的任务状态保持一致。
     * 失败不阻塞主流程。
     */
    private void refreshRouteStatuses(AgentContext ctx) {
        try {
            List<MasterRouteRecord> history = loadRouteHistory(ctx);
            if (CollectionUtils.isEmpty(history)) {
                return;
            }
            // 收集可解析的子会话 ID（去重），一次批量查询权威状态
            Set<Long> subConvIds = new HashSet<>();
            for (MasterRouteRecord r : history) {
                if (StringUtils.isNotBlank(r.getConversationId())) {
                    try {
                        subConvIds.add(Long.parseLong(r.getConversationId()));
                    } catch (NumberFormatException ignored) {
                        // 非法 conversationId 不参与对账
                    }
                }
            }
            if (subConvIds.isEmpty()) {
                return;
            }
            List<ConversationDto> subConvs = conversationApplicationService
                    .getConversationListByCIds(new ArrayList<>(subConvIds));
            if (CollectionUtils.isEmpty(subConvs)) {
                return;
            }
            Map<Long, Conversation.ConversationTaskStatus> statusMap = new HashMap<>();
            for (ConversationDto c : subConvs) {
                if (c.getId() != null && c.getTaskStatus() != null) {
                    statusMap.put(c.getId(), c.getTaskStatus());
                }
            }
            boolean changed = false;
            for (MasterRouteRecord r : history) {
                if (StringUtils.isBlank(r.getConversationId())) {
                    continue;
                }
                Long subConvId;
                try {
                    subConvId = Long.parseLong(r.getConversationId());
                } catch (NumberFormatException e) {
                    continue;
                }
                Conversation.ConversationTaskStatus taskStatus = statusMap.get(subConvId);
                if (taskStatus == null) {
                    // 会话已删除或状态未知：保持原值
                    continue;
                }
                String mapped = switch (taskStatus) {
                    case COMPLETE -> "completed";
                    case FAILED -> "failed";
                    case CANCEL -> "cancelled";
                    // CREATE / EXECUTING 视为执行中
                    default -> "running";
                };
                if (!mapped.equals(r.getStatus())) {
                    r.setStatus(mapped);
                    changed = true;
                }
            }
            if (changed) {
                persistSessionState(ctx);
                log.info("[MasterAgentLoop] route statuses reconciled from conversation taskStatus");
            }
        } catch (Exception e) {
            log.warn("[MasterAgentLoop] refresh route statuses failed: {}", e.getMessage());
        }
    }

    /**
     * 将路由历史格式化为独立标签块（注入 userPrompt，让模型知道之前的路由上下文）。
     * 块首行是使用约束，其后为数据记录，二者分离以便模型解析。
     */
    private String buildRouteHistorySection(List<MasterRouteRecord> history) {
        if (CollectionUtils.isEmpty(history)) {
            return "";
        }
        StringBuilder sb = new StringBuilder("<route-history>\n");
        sb.append("以下仅为上下文参考：继续任务时从对应记录取 conversationId，不要主动重复执行已记录的路由。\n");
        // 只展示最近 5 条
        int start = Math.max(0, history.size() - 5);
        for (int i = start; i < history.size(); i++) {
            MasterRouteRecord r = history.get(i);
            sb.append("- ").append(r.getAction());
            if (StringUtils.isNotBlank(r.getTargetName())) {
                sb.append(" → ").append(r.getTargetName());
            }
            // subject 优先展示（简洁任务名），无 subject 时降级展示 inputMessage
            if (StringUtils.isNotBlank(r.getSubject())) {
                sb.append("「").append(r.getSubject()).append("」");
            } else if (StringUtils.isNotBlank(r.getInputMessage())) {
                String msg = r.getInputMessage();
                sb.append("（").append(msg.length() > 60 ? msg.substring(0, 60) + "…" : msg).append("）");
            }
            if (StringUtils.isNotBlank(r.getConversationId())) {
                sb.append(" [conversationId=").append(r.getConversationId()).append("]");
            }
            if (StringUtils.isNotBlank(r.getStatus())) {
                sb.append(" [").append(r.getStatus()).append("]");
            }
            if (StringUtils.isNotBlank(r.getTime())) {
                sb.append(" ").append(r.getTime());
            }
            sb.append("\n");
        }
        sb.append("</route-history>\n\n");
        return sb.toString();
    }

    // ======================== Inner Classes ========================

    /**
     * 总控编排上下文（包装 AgentContext + 总控专有状态）。
     * 在 {@link #execute} 入口创建一次，贯穿整个编排流程，
     * 避免 subAgents / historyTasks 在方法间反复传递。
     */
    @lombok.Getter
    public static class MasterLoopContext {
        private final AgentContext agentContext;
        private final List<AgentConfigDto> subAgents;
        private final List<HistoryTask> historyTasks;
        private final StringBuilder textAccum = new StringBuilder();
        private final StringBuilder thinkAccum = new StringBuilder();
        // 工具调用次数(一次用户交互内防循环上限,贯穿多轮模型调用)
        private final AtomicInteger toolCallCount = new AtomicInteger(0);
        // 本轮是否发生模型错误（重试耗尽），true 时 commitExecContext 跳过提交，避免错误轮次污染执行链
        private final AtomicBoolean errorOccurred = new AtomicBoolean(false);
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


        public MasterLoopContext(AgentContext agentContext, List<AgentConfigDto> subAgents,
                                 List<HistoryTask> historyTasks) {
            this.agentContext = agentContext;
            this.subAgents = subAgents != null ? subAgents : Collections.emptyList();
            this.historyTasks = historyTasks != null ? historyTasks : Collections.emptyList();
        }

        /**
         * 快捷访问 AgentContext
         */
        public AgentContext ctx() {
            return agentContext;
        }
    }

    @Data
    public static class IntentDecision {
        private String action;
        private String targetConversationId;
        private String inputMessage;
        private String reason;
        /**
         * dispatch 时：派发列表（统一入口，单个/并行/新建/继续）
         */
        private List<DispatchItem> dispatches;
        /**
         * search_tasks 时：搜索关键词
         */
        private String keyword;
    }

    /**
     * 派发项（dispatch 工具的每个子任务）
     */
    @Data
    public static class DispatchItem {
        private String targetAgentId;
        private String inputMessage;
        /**
         * 新建会话的主题/标题（仅新建时使用）
         */
        private String subject;
        /**
         * 继续已有会话时填入该会话 ID（从路由记录中获取）
         */
        private String conversationId;
    }

    @Data
    public static class HistoryTask {
        private String taskId;
        private String name;
        private String description;
        /**
         * 执行该任务的子智能体 ID（对应 AgentConfigDto.id）
         */
        private String agentId;
        /**
         * 记录时间（格式 yyyy-MM-dd HH:mm）
         */
        private String time;
        /**
         * 任务状态：running / completed / cancelled / failed
         */
        private String status;
    }

    /**
     * 执行链消息（总控自己的上下文单元，与 DB 展示消息（roundMessage）解耦）。
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

    /**
     * 派发结果（dispatchToAgent 返回值）
     */
    @Data
    private static class DispatchResult {
        /**
         * 通知用户的消息文本
         */
        private String notice;
        /**
         * 新建会话的主题（仅新建时有值，取用户原始消息）
         */
        private String subject;
        /**
         * 子智能体会话 ID（新建或已有）
         */
        private String conversationId;
        /**
         * 是否在已有会话上继续
         */
        private boolean continuation;
    }

    /**
     * 路由记录（持久化到 sessionState，跨轮可见）
     */
    @Data
    public static class MasterRouteRecord {
        private String action;
        private String targetId;
        private String targetName;
        private String inputMessage;
        /**
         * 任务主题/标题（如"UI设计""撰写季度报告"），用于用户按名称定位任务
         */
        private String subject;
        /**
         * dispatch 创建/继续的子会话 ID（供后续"继续任务"精准定位）
         */
        private String conversationId;
        /**
         * 记录时间（格式 yyyy-MM-dd HH:mm）
         */
        private String time;
        /**
         * 任务状态：running / completed / cancelled / failed
         */
        private String status;
    }
}
