package com.xspaceagi.agent.core.infra.component.agent;

import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.common.base.Joiner;
import com.xspaceagi.agent.core.adapter.application.ChatApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AgentOutputDto;
import com.xspaceagi.agent.core.adapter.dto.ChatMessageDto;
import com.xspaceagi.agent.core.adapter.dto.config.*;
import com.xspaceagi.agent.core.adapter.dto.config.bind.ModelBindConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.workflow.*;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.WorkflowNodeConfig;
import com.xspaceagi.agent.core.infra.component.ArgExtractUtil;
import com.xspaceagi.agent.core.infra.component.model.ModelContext;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.infra.component.model.dto.CallMessage;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentExecuteResult;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentExecutingDto;
import com.xspaceagi.agent.core.infra.component.model.dto.ModelCallConfigDto;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowContext;
import com.xspaceagi.agent.core.infra.component.workflow.WorkflowExecutor;
import com.xspaceagi.agent.core.spec.enums.*;
import com.xspaceagi.agent.core.spec.utils.TikTokensUtil;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.annotation.Resource;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.xspaceagi.agent.core.infra.component.workflow.handler.ConditionNodeHandler.eval;

@Slf4j
@Component
public class AgentFlowLoop {

    // ======================== Prompts ========================

    /**
     * Dispatcher: analyzes user intent and dispatches execution to the appropriate node in the graph.
     * Runs once per turn to determine the execution entry point or handle intent changes.
     */
    private static final String NAVIGATOR_SYSTEM_PROMPT = """
            You are the flow dispatcher for a pre-configured workflow graph. Your core responsibility: analyze the user's intent from the current message and conversation history, then dispatch execution to the correct graph node.
            
            You are NOT a chatbot — you are the scheduling brain that routes user requests to the appropriate workflow step.
            
            ## Graph Structure
            
            ${graph}
            
            ## Dispatch Decision Process
            
            1. **Analyze intent**: Understand what the user actually wants to accomplish from their latest message and conversation history.
            2. **Match to node**: Find the graph node whose purpose best fits the user's intent. Each node has a name and description — use these to match.
            3. **Check current position**: If there is a current position in the graph (see "Current Position" below):
               - Is the user clearly continuing the same workflow? (e.g., answering a question the previous node asked, providing additional details) → set `continueCurrent=true`
               - Has the intent shifted to something a different node handles? → set `targetNodeId` to the new target
            4. **No current position**: Pick the entry node that matches the user's intent. Prefer starting from the graph's start node unless the intent clearly maps to a specific mid-graph node.
            
            ## When NOT to dispatch
            
            Leave `targetNodeId` empty and respond via `clarifyingMessage` in these cases:
            - **Unclear intent**: The user's goal cannot be determined → ask a targeted clarifying question that helps narrow down which node to dispatch to
            - **Greetings / casual chat**: The user is not making a workflow request → respond briefly and guide them toward what this graph can help with
            - **Out of scope**: The request cannot be handled by any node in the graph → explain what you CAN help with, referencing specific capabilities
            
            When using `clarifyingMessage`, be natural and conversational. Reference the conversation context when relevant. Always include a hint about what the user could ask to trigger a workflow.
            
            ## Decision Criteria
            
            - `continueCurrent=true` ONLY when: (a) there IS a current position, AND (b) the user is clearly continuing the same task (e.g., answering a question from the previous node, providing requested details). Do NOT set this when the execution path is already long and the user is starting something new.
            - `targetNodeId` MUST be a valid executable node from the graph (AGENT, WORKFLOW, ASK_QUESTION, or ROUTER type — never START or END).
            - When in doubt between continuing and jumping: prefer jumping to the node that best matches the CURRENT intent.
            
            ## Variable Extraction
            
            While analyzing the user's message, also look for information that matches configured graph variables (shown in "Graph Variables" below). If the user explicitly mentions a value that corresponds to a graph variable (e.g., order number, product name, user preference), include it in `variableUpdates`. Only extract values that are clearly present — do not guess or infer. This is a secondary responsibility; routing accuracy comes first.
            
            ## Extra Instruction
            
            ${instruction}
            """;

    /**
     * Agent Input Builder: constructs the input message and extracts context for an agent node.
     * The target agent is already determined by the graph — this LLM only builds the input.
     */
    private static final String AGENT_INPUT_BUILDER_PROMPT = """
            You are the input builder for agent nodes in a workflow graph. The navigator has ALREADY determined the execution path — your PRIMARY job is to construct a complete, self-contained input for the CURRENT node so it can execute.
            
            You do NOT choose which agent to call. You do NOT answer the user's question directly. You do NOT re-evaluate routing decisions.
            
            ## Decision Framework (in priority order)
            
            **DEFAULT: Execute the current node.** Build the best possible inputMessage and proceed. The current node is the planned next step — it has the highest priority.
            
            Only consider the alternatives below when execution is clearly not the right action:
            
            1. **Build inputMessage** (default action): Craft a clear, self-contained message for the target agent. It MUST include all necessary context from previous nodes so the agent can work independently without re-reading the full conversation.
            2. **Reference specific messages**: Use `{{messageId}}` syntax to reference a previous message by its ID. For example, if the message list shows `[id: a1b2c3] Agent: Your order #12345 has shipped`, you can write "Regarding the previous response: {{a1b2c3}}, the user wants to know the delivery time." Do NOT use `{{messageId}}` in clarifyingMessage.
            3. **Extract variables**: When you identify new information from the conversation that matches a configured graph variable, include it in `variableUpdates`. The system handles REPLACE/APPEND strategy — you only provide name and value.
            4. **Skip** (`skip=true`): Use when the current node's task has ALREADY been accomplished by a previous node, or when the current node's capability is clearly not needed given the conversation context. Example: previous node already resolved the user's issue, so this follow-up node is unnecessary. When skipped, the flow will automatically advance to the planned next node.
            5. **Clarify** (`clarifyingMessage`): Set when the user needs to provide more information before this node can execute.
            6. **Redirect** (`redirectNodeId`) — **LAST RESORT, use with extreme caution**: ONLY when ALL of the following are true:
               - The current node is COMPLETELY UNABLE to handle the task (not just a partial mismatch)
               - The target node has NOT already been executed (check "Completed" in Planned Flow Path)
               - No amount of input adjustment could make the current node work
               Do NOT redirect just because the user's original request text seems to match another node — the navigator already made that routing decision.
            7. **End** (`end=true`): Terminate the entire flow early (use sparingly).
            
            ## Input Parameters to Extract
            
            The current node expects the following input parameters. Extract their values from the conversation context (user messages, previous agent outputs, session state) and include them in the inputMessage or variables:
            
            ${inputArgs}
            
            ## Graph Variables
            
            ${variables}
            """;

    /**
     * Router Evaluator: evaluates router node conditions to pick the correct branch.
     * Used as LLM fallback when expression-based evaluation doesn't produce a match.
     */
    private static final String ROUTER_EVALUATOR_PROMPT = """
            You are a branch evaluator for router nodes in a workflow graph. Your job: given the available branches and the current conversation context, select which branch to follow.
            
            ## Router Node
            - Name: ${nodeName}
            - Description: ${nodeDescription}
            
            ## Available Branches
            ${branches}
            
            ## Decision Process
            
            1. Read the conversation context, user's latest message, and previous node outputs.
            2. Match the current situation against each branch's description or expression.
            3. Pick the single best-matching branch and output its `edgeId`.
            4. If no branch clearly matches, use the default branch if available.
            5. Provide brief reasoning explaining why this branch was selected.
            """;

    /**
     * Workflow Input Extractor: extracts input parameter values for a workflow node from conversation context.
     * Used when a workflow node has Input-type args that cannot be resolved from session state alone.
     */
    private static final String WORKFLOW_INPUT_EXTRACT_PROMPT = """
            You are a parameter extractor for workflow nodes in a flow graph. Your ONLY job: extract the required input parameter values for the current workflow node from the conversation context.
            
            ## Rules
            
            1. Extract parameter values from the conversation context, user messages, previous node outputs, and session state.
            2. Return ALL requested parameters in the "params" object. If a value cannot be determined, use null.
            3. Do NOT invent values — only extract what is available in the context.
            4. For string parameters, use the exact text from context.
            5. For numeric parameters, parse the number from context.
            6. For array parameters, collect all matching items from context.
            7. For boolean parameters, infer true/false from context.
            """;

    // ======================== Constants ========================

    private static final int MAX_RETRY_COUNT = 5;
    private static final int MAX_DISPATCH_COUNT = 100;
    private static final int MAX_NODE_RETRY = 2;
    private static final int MAX_CONTENT_LENGTH = 800;
    private static final int MAX_MESSAGES_TOKEN_BUDGET = 32000;
    private static final Pattern INPUT_MESSAGE_ID_PATTERN = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}");
    private static final Pattern VARIABLE_PATTERN = Pattern.compile("\\{\\{\\s*([\\w.]+)\\s*}}");
    private static final String GRAPH_CURRENT_NODE_KEY = "graphCurrentNodeId";
    private static final String GRAPH_EXECUTION_PATH_KEY = "graphExecutionPath";
    private static final String ASK_QUESTION_WAITING_ANSWER_KEY = "askQuestionWaitingAnswer";

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private ChatApplicationService chatApplicationService;

    @Resource
    private AgentExecutor agentExecutor;

    @Resource
    private ModelInvoker modelInvoker;

    @Resource
    private WorkflowExecutor workflowExecutor;

    // ======================== Public API ========================

    public Flux<AgentOutputDto> execute(AgentContext agentContext) {
        if (!AgentConfigDto.AgentSubType.Flow.name().equals(agentContext.getAgentConfig().getSubType())) {
            return Flux.error(new IllegalArgumentException("The agent is not an AgentFlow."));
        }
        List<AgentComponentConfigDto> agentComponentConfigs = agentContext.getAgentConfig().getAgentComponentConfigList()
                .stream().filter(c -> c.getType() == AgentComponentConfig.Type.Workflow).toList();
        if (agentComponentConfigs.isEmpty()) {
            return Flux.error(new IllegalArgumentException("The agent is not configured or has been deleted."));
        }

        ChatMessageDto userMessage = ChatMessageDto.builder()
                .tenantId(agentContext.getAgentConfig().getTenantId())
                .id(UUID.randomUUID().toString().replace("-", ""))
                .senderType(ChatMessageDto.SenderType.USER)
                .senderId(agentContext.getUserId().toString())
                .userId(agentContext.getUserId())
                .agentId(agentContext.getAgentConfig().getId())
                .role(ChatMessageDto.Role.USER)
                .type(MessageTypeEnum.CHAT)
                .text(agentContext.getMessage())
                .time(new Date())
                .finished(true)
                .build();
        conversationApplicationService.addRoundMessage(agentContext.getConversationId(), userMessage);

        FlowLoopContext ctx = buildFlowLoopContext(agentContext);
        AtomicReference<Disposable> disposableRef = new AtomicReference<>();
        return Flux.<AgentOutputDto>create(sink -> {
            Disposable d = runOrchestration(ctx, sink).subscribe();
            disposableRef.set(d);
            sink.onDispose(d);
        }).doOnCancel(() -> {
            Disposable d = disposableRef.get();
            if (d != null && !d.isDisposed()) {
                d.dispose();
                sendComplete(ctx, null, "Cancelled");
            }
        });
    }

    // ======================== Orchestration Loop ========================

    /**
     * Main loop: navigate → execute node → advance → repeat.
     * <p>
     * Graph-based execution flow:
     * 1. Dispatcher determines entry/resumption point in the graph (once per turn)
     * 2. Execute current node:
     * - AGENT: InputBuilder LLM constructs input → dispatch to agent → follow outgoing edge
     * - ROUTER: Expression eval / LLM eval picks branch → follow selected edge
     * - START: auto-advance to next node
     * - END: complete the flow
     * 3. Advance to next node via graph edges → repeat from step 2
     */
    private Mono<Void> runOrchestration(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink) {
        return Mono.<Void>defer(() -> {
            // Circuit breakers
            if (ctx.getRetryCount().get() > MAX_RETRY_COUNT) {
                sink.error(new IllegalArgumentException("The agent failed to correctly identify the necessary data."));
                clearGraphState(ctx);
                sendComplete(ctx, sink, "Max retry count (" + MAX_RETRY_COUNT + ") reached, forcing end");
                return Mono.empty();
            }
            if (ctx.getTotalDispatchCount() > MAX_DISPATCH_COUNT) {
                log.warn("[AgentFlowLoop] max dispatch count ({}) reached, forcing end", MAX_DISPATCH_COUNT);
                sink.next(buildOutputMessage(ctx.getFlowAgentContext(), "[AgentFlowLoop] max dispatch count (" + MAX_DISPATCH_COUNT + ") reached, forcing end"));
                clearGraphState(ctx);
                sendComplete(ctx, sink, "Max dispatch count (" + MAX_DISPATCH_COUNT + ") reached, forcing end");
                return Mono.empty();
            }
            if (ctx.getFlowAgentContext().isInterrupted()) {
                persistSessionState(ctx);
                sendComplete(ctx, sink, null);
                return Mono.empty();
            }

            // Graph validation
            Graph graph = ctx.getFlowGraph();
            if (graph == null || CollectionUtils.isEmpty(graph.getNodes())) {
                sink.error(new IllegalArgumentException("The flow graph is not configured or is empty."));
                return Mono.empty();
            }

            // User answer: continue with current node
            if (ctx.isWaitingForUserAnswer()) {
                log.info("[AgentFlowLoop] user answer received, continuing with current node: {}", ctx.getCurrentNodeId());
                GraphNode currentNode = findNodeById(graph, ctx.getCurrentNodeId());
                if (currentNode != null && currentNode.getType() == NodeType.AGENT) {
                    ctx.setWaitingForUserAnswer(false);
                    ctx.getSessionState().put(ASK_QUESTION_WAITING_ANSWER_KEY, ctx.isWaitingForUserAnswer());
                    return dispatchNode(ctx, sink, currentNode, new AgentInputBuilderDecision(), ctx.getFlowAgentContext().getMessage());
                }
            }

            // Persist session state
            conversationApplicationService.updateConversationVariables(
                    ctx.getFlowAgentContext().getConversation().getId(), ctx.getSessionState());

            // Phase 1: Navigate if no current position (new turn or first entry)
            if (StringUtils.isBlank(ctx.getCurrentNodeId())) {
                // Shortcut: user explicitly @mentioned an agent → skip dispatcher, jump to that node
                AgentConfigDto mentionedAgent = resolveMentionedAgent(ctx);
                if (mentionedAgent != null) {
                    GraphNode mentionNode = findOrCreateAgentNode(graph, mentionedAgent);
                    ctx.setCurrentNodeId(mentionNode.getId());
                    addExecutionTrace(ctx, mentionNode, "@mention shortcut to " + mentionedAgent.getName());
                    persistGraphState(ctx);

                    String directInput = stripMention(ctx.getFlowAgentContext().getMessage());
                    AgentInputBuilderDecision directDecision = new AgentInputBuilderDecision();

                    log.info("[AgentFlowLoop] @mention shortcut: directly dispatching to {}, skipping dispatcher",
                            mentionedAgent.getName());
                    return dispatchNode(ctx, sink, mentionNode, directDecision, directInput);
                }

                return runNavigationPhase(ctx, sink)
                        .then(Mono.defer(() -> runOrchestration(ctx, sink)));
            }

            // Phase 2: Execute current node
            GraphNode currentNode = findNodeById(graph, ctx.getCurrentNodeId());
            if (currentNode == null) {
                log.warn("[AgentFlowLoop] current node not found: {}, re-navigating", ctx.getCurrentNodeId());
                ctx.setCurrentNodeId(null);
                return runOrchestration(ctx, sink);
            }

            // Emit node start state
            buildAndSendStateMessage(ctx, sink, "node_start");

            return switch (currentNode.getType()) {
                case START -> {
                    // START node: auto-advance to the next node
                    log.info("[AgentFlowLoop] START node, auto-advancing");
                    yield advanceToNextNode(ctx, sink, currentNode, null, "START node auto-advance");
                }
                case END -> {
                    // END node: complete the flow
                    log.info("[AgentFlowLoop] END node reached, completing flow");
                    if (ctx.getResponseBuilder().isEmpty()) {
                        String fallbackMsg = I18nUtil.systemMessage(ctx.getFlowAgentContext().getUser().getLangMap(), "Backend.AgentFlow.FlowCompleted");
                        sink.next(buildOutputMessage(ctx.getFlowAgentContext(), fallbackMsg));
                    }
                    clearGraphState(ctx);
                    sendComplete(ctx, sink, null);
                    yield Mono.empty();
                }
                case AGENT -> runAgentNodeExecution(ctx, sink, currentNode);
                case WORKFLOW -> runWorkflowNodeExecution(ctx, sink, currentNode);
                case ASK_QUESTION -> runAskQuestionNodeExecution(ctx, sink, currentNode);
                case ROUTER -> runRouterNodeEvaluation(ctx, sink, currentNode);
            };
        }).onErrorResume(e -> {
            log.error("[AgentFlowLoop] orchestration error: {}", e.getMessage(), e);
            String errorMsg = I18nUtil.systemMessage(ctx.getFlowAgentContext().getUser().getLangMap(), "Backend.AgentFlow.ProcessingException").replace("{error}", e.getMessage());
            ctx.getResponseBuilder().append(errorMsg);
            addRoundMessage(ctx, ctx.getFlowAgentContext(), errorMsg);
            sink.next(buildOutputMessage(ctx.getFlowAgentContext(), errorMsg));
            clearGraphState(ctx);
            sendComplete(ctx, sink, null);
            return Mono.empty();
        });
    }

    // ======================== Phase 1: Dispatch (intent analysis → graph positioning) ========================

    /**
     * Dispatcher LLM analyzes user intent and determines where to execute in the graph.
     * Handles: initial entry, intent changes across turns, continuation of current flow.
     */
    private Mono<Void> runNavigationPhase(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink) {
        String graphDescription = buildGraphDescription(ctx.getFlowGraph());
        String instruction = ctx.getFlowAgentContext().getAgentConfig().getSystemPrompt();
        String systemPrompt = NAVIGATOR_SYSTEM_PROMPT.replace("${graph}", graphDescription).replace("${instruction}", instruction == null ? "" : instruction);
        String navigatorContext = buildNavigatorContext(ctx);
        ModelContext modelContext = buildModelContext(
                ctx.getFlowAgentContext(), systemPrompt, navigatorContext, buildNavigatorArgs());

        log.info("[AgentFlowLoop] dispatch phase started");
        return modelInvoker.invoke(modelContext).onErrorResume(e -> {
            log.warn("[AgentFlowLoop] dispatcher error: {}", e.getMessage(), e);
            modelContext.getModelCallResult().setResponseText(e.getMessage());
            return Mono.empty();
        }).then(Mono.defer(() -> handleNavigatorResult(ctx, sink, modelContext)));
    }

    private Mono<Void> handleNavigatorResult(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink, ModelContext modelContext) {
        Object response = modelContext.getModelCallResult().getData();
        if (!(response instanceof JSONObject jsonResponse)) {
            addRetryMessage(ctx, "Dispatcher response is not valid JSON:\n" + modelContext.getModelCallResult().getResponseText());
            return runOrchestration(ctx, sink);
        }

        NavigatorDecision decision = jsonResponse.to(NavigatorDecision.class);

        if (decision != null && StringUtils.isNotBlank(decision.getReasoning())) {
            ctx.getReasoningBuilder().append("\n").append(decision.getReasoning());
            buildAndSendReasoning(ctx, sink, decision.getReasoning());
        }

        // Apply variable updates extracted by the dispatcher (available for ROUTER expressions, etc.)
        if (decision != null && CollectionUtils.isNotEmpty(decision.getVariableUpdates())) {
            applyVariableUpdates(ctx, decision.getVariableUpdates());
        }

        // Clarifying message (no navigation needed)
        if (decision == null || (StringUtils.isBlank(decision.getTargetNodeId()) && !Boolean.TRUE.equals(decision.getContinueCurrent()))) {
            String msg = (decision != null && StringUtils.isNotBlank(decision.getClarifyingMessage()))
                    ? decision.getClarifyingMessage()
                    : I18nUtil.systemMessage(ctx.getFlowAgentContext().getUser().getLangMap(), "Backend.AgentFlow.InfoNotEnough");
            ctx.getResponseBuilder().append(msg);
            addRoundMessage(ctx, ctx.getFlowAgentContext(), msg);
            sink.next(buildOutputMessage(ctx.getFlowAgentContext(), msg));
            persistSessionState(ctx);
            sendComplete(ctx, sink, null);
            log.info("[AgentFlowLoop] dispatch phase: no target node, ending with clarifying message");
            return Mono.empty();
        }

        // Continue from current position
        if (Boolean.TRUE.equals(decision.getContinueCurrent())) {
            if (StringUtils.isNotBlank(ctx.getCurrentNodeId())) {
                log.info("[AgentFlowLoop] dispatcher: continuing from current node: {}", ctx.getCurrentNodeId());
                return Mono.empty(); // currentNodeId already set, orchestration will proceed
            }
            // currentNodeId was cleared (flow ended last turn) — recover from executionPath
            GraphNode resumedNode = recoverLastNode(ctx);
            if (resumedNode != null) {
                // continueCurrent means user is continuing the same task — re-execute the last node
                ctx.setCurrentNodeId(resumedNode.getId());
                addExecutionTrace(ctx, resumedNode, "continueCurrent: re-execute last node " + resumedNode.getName());
                persistGraphState(ctx);
                log.info("[AgentFlowLoop] dispatcher: continueCurrent resumed at node '{}'", resumedNode.getName());
                return Mono.empty(); // orchestration will re-execute this node
            }
            // No executionPath either — fall through to targetNodeId/clarifying handling
        }

        // Navigate to target node
        String targetNodeId = decision.getTargetNodeId();
        if (StringUtils.isBlank(targetNodeId)) {
            // Dispatcher didn't provide a target and continueCurrent didn't apply — fallback to clarifying
            String fallbackMsg = StringUtils.isNotBlank(decision.getClarifyingMessage())
                    ? decision.getClarifyingMessage()
                    : I18nUtil.systemMessage(ctx.getFlowAgentContext().getUser().getLangMap(), "Backend.AgentFlow.WhatHelpNeeded");
            ctx.getResponseBuilder().append(fallbackMsg);
            addRoundMessage(ctx, ctx.getFlowAgentContext(), fallbackMsg);
            sink.next(buildOutputMessage(ctx.getFlowAgentContext(), fallbackMsg));
            persistSessionState(ctx);
            sendComplete(ctx, sink, null);
            return Mono.empty();
        }

        GraphNode targetNode = findNodeById(ctx.getFlowGraph(), targetNodeId);
        if (targetNode == null) {
            addRetryMessage(ctx, "Dispatcher referenced invalid nodeId: " + targetNodeId);
            return runOrchestration(ctx, sink);
        }

        // Only allow dispatch to executable nodes (not START or END)
        if (targetNode.getType() == NodeType.START || targetNode.getType() == NodeType.END) {
            addRetryMessage(ctx, "Dispatcher targeted non-executable node: " + targetNodeId + " (type: " + targetNode.getType() + ")");
            return runOrchestration(ctx, sink);
        }

        ctx.setCurrentNodeId(targetNodeId);
        addExecutionTrace(ctx, targetNodeId, decision.getReasoning());
        ctx.setNodeRetryCount(0);
        persistGraphState(ctx);

        log.info("[AgentFlowLoop] dispatcher: positioned at node '{}' (type: {}), reason={}",
                targetNode.getName(), targetNode.getType(), decision.getReasoning());
        return Mono.empty();
    }

    // ======================== Phase 2a: Agent Node Execution ========================

    /**
     * For AGENT nodes: use InputBuilder LLM to construct input, then dispatch to the agent.
     */
    private Mono<Void> runAgentNodeExecution(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink, GraphNode node) {
        String inputBuildContext = buildAgentInputBuildContext(ctx, node);
        String variablesDescription = buildVariablesDescription(ctx);
        String inputArgsDescription = buildInputArgsDescription(node);
        String systemPrompt = AGENT_INPUT_BUILDER_PROMPT
                .replace("${variables}", variablesDescription)
                .replace("${inputArgs}", inputArgsDescription);
        ModelContext modelContext = buildModelContext(
                ctx.getFlowAgentContext(), systemPrompt, inputBuildContext, buildAgentInputBuilderArgs(node.getInputArgs()));

        log.info("[AgentFlowLoop] agent node execution: node={}, agentId={}",
                node.getName(), node.getAgentId());

        return modelInvoker.invoke(modelContext)
                .onErrorResume(e -> {
                    log.warn("[AgentFlowLoop] agent input builder error: {}", e.getMessage(), e);
                    modelContext.getModelCallResult().setResponseText(e.getMessage());
                    return Mono.empty();
                }).then(Mono.defer(() -> handleAgentInputBuilderResult(ctx, sink, modelContext, node)));
    }

    private Mono<Void> handleAgentInputBuilderResult(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink,
                                                     ModelContext modelContext, GraphNode node) {
        Object response = modelContext.getModelCallResult().getData();
        if (!(response instanceof JSONObject jsonResponse)) {
            addRetryMessage(ctx, "Agent input builder response is not valid JSON:\n" + modelContext.getModelCallResult().getResponseText());
            return runOrchestration(ctx, sink);
        }

        AgentInputBuilderDecision decision = jsonResponse.to(AgentInputBuilderDecision.class);
        if (decision == null) {
            addRetryMessage(ctx, "Agent input builder returned empty decision");
            return runOrchestration(ctx, sink);
        }

        // Apply state update (legacy map-based)
        if (decision.getStateUpdate() != null) {
            ctx.updateSessionState(decision.getStateUpdate());
        }

        // Apply structured variable updates (respects REPLACE/APPEND strategies)
        if (CollectionUtils.isNotEmpty(decision.getVariableUpdates())) {
            applyVariableUpdates(ctx, decision.getVariableUpdates());
        }

        // Dynamic adjustment: redirect to a different node
        if (StringUtils.isNotBlank(decision.getRedirectNodeId())) {
            GraphNode redirectNode = findNodeById(ctx.getFlowGraph(), decision.getRedirectNodeId());
            if (redirectNode != null && redirectNode.getType() != NodeType.START && redirectNode.getType() != NodeType.END) {
                // Guard: prevent redirect loop — allow at most 2 revisits of a visited node
                if (countNodeOccurrences(ctx.getExecutionPath(), decision.getRedirectNodeId()) > 2) {
                    log.warn("[AgentFlowLoop] redirect blocked: target node '{}' already visited {} times, auto-skipping current node",
                            decision.getRedirectNodeId(), countNodeOccurrences(ctx.getExecutionPath(), decision.getRedirectNodeId()));
                    // Redirect blocked — the LLM thinks current node shouldn't execute,
                    // and the intended redirect target is already visited.
                    // Safest action: skip current node, advance to the planned next node.
                    return advanceToNextNode(ctx, sink, node, null, "Redirect blocked (target already visited), auto-skip");
                }
                log.info("[AgentFlowLoop] dynamic adjustment: redirecting from '{}' to '{}'",
                        node.getName(), redirectNode.getName());
                ctx.setCurrentNodeId(redirectNode.getId());
                addExecutionTrace(ctx, redirectNode, "Input builder redirect from " + node.getName());
                ctx.setNodeRetryCount(0);
                persistGraphState(ctx);
                return runOrchestration(ctx, sink);
            } else {
                log.warn("[AgentFlowLoop] redirect to invalid node: {}", decision.getRedirectNodeId());
            }
        }

        // Handle control flags
        if (Boolean.TRUE.equals(decision.getEnd())) {
            log.info("[AgentFlowLoop] node '{}' ended by input builder", node.getName());
            addExecutionTrace(ctx, node, "Input builder ended the flow");
            if (StringUtils.isNotBlank(decision.getClarifyingMessage())) {
                // Input builder provided explicit end message — use it
                addRoundMessage(ctx, ctx.getFlowAgentContext(), decision.getClarifyingMessage());
                sink.next(buildOutputMessage(ctx.getFlowAgentContext(), decision.getClarifyingMessage().replace("\\n", "\n")));
                ctx.getResponseBuilder().append(decision.getClarifyingMessage());
            } else if (ctx.getResponseBuilder().isEmpty()) {
                // No previous output and no clarifying message — use generic completion
                sink.next(buildOutputMessage(ctx.getFlowAgentContext(), buildGraphCompletionMessage(ctx)));
            }
            persistSessionState(ctx);
            sendComplete(ctx, sink, null);
            return Mono.empty();
        }
        if (Boolean.TRUE.equals(decision.getSkip())) {
            log.info("[AgentFlowLoop] node '{}' skipped by input builder", node.getName());
            return advanceToNextNode(ctx, sink, node, null, "Node skipped by input builder");
        }

        // Clarifying message: ask user and wait for response
        if (StringUtils.isNotBlank(decision.getClarifyingMessage())) {
            addRoundMessage(ctx, ctx.getFlowAgentContext(), decision.getClarifyingMessage());
            sink.next(buildOutputMessage(ctx.getFlowAgentContext(), decision.getClarifyingMessage().replace("\\n", "\n")));
            persistSessionState(ctx);
            sendComplete(ctx, sink, null);
            return Mono.empty();
        }

        // Resolve input message and dispatch to agent
        String inputMessage = resolveInputMessage(ctx, decision.getInputMessage());
        return dispatchNode(ctx, sink, node, decision, inputMessage);
    }

    // ======================== Phase 2b: Workflow Node Execution ========================

    /**
     * For WORKFLOW nodes: resolve input parameters and execute the configured workflow.
     * <p>
     * Input args are resolved in three ways:
     * 1. Disabled args (enable=false) → use bindValue directly
     * 2. Reference args → resolve from session state via ArgExtractUtil
     * 3. Input args (enabled + BindValueType.Input) → extracted by LLM from conversation context
     * <p>
     * After execution, the workflow result is stored in session state under "_node{nodeId}"
     * so downstream nodes can reference it. If the workflow's end node returns TEXT type,
     * the endNodeContent is used as the result instead.
     */
    private Mono<Void> runWorkflowNodeExecution(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink, GraphNode node) {
        // Step 1: Resolve all input arguments
        Map<String, Object> input = new HashMap<>();
        List<Arg> inputArgsToExtract = new ArrayList<>();

        if (node.getInputArgs() != null) {
            for (Arg inputArg : node.getInputArgs()) {
                if (inputArg.getEnable() != null && !inputArg.getEnable()) {
                    // Disabled arg: use bindValue directly
                    if (StringUtils.isNotBlank(inputArg.getBindValue())) {
                        input.put(inputArg.getName(), inputArg.getBindValue());
                    }
                } else if (inputArg.getBindValueType() == Arg.BindValueType.Reference) {
                    // Reference arg: resolve from session state
                    Object value = extraBindValue(ctx, inputArg);
                    input.put(inputArg.getName(), value);
                } else if (inputArg.getBindValueType() == Arg.BindValueType.Input) {
                    // Input arg: needs LLM extraction from conversation context
                    inputArgsToExtract.add(inputArg);
                }
            }
        }

        // Step 2: If there are Input-type args, use LLM to extract them from context
        if (!inputArgsToExtract.isEmpty()) {
            return extractWorkflowInputArgs(ctx, sink, node, input, inputArgsToExtract);
        }

        // Step 3: All args resolved, execute the workflow
        return executeWorkflow(ctx, sink, node, input);
    }

    /**
     * Use LLM to extract Input-type argument values from conversation context,
     * then merge with already-resolved args and execute the workflow.
     */
    private Mono<Void> extractWorkflowInputArgs(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink,
                                                GraphNode node, Map<String, Object> input,
                                                List<Arg> inputArgsToExtract) {
        String extractContext = buildWorkflowInputExtractContext(ctx, node);
        List<Arg> extractOutputArgs = buildWorkflowInputExtractArgs(inputArgsToExtract);
        ModelContext modelContext = buildModelContext(
                ctx.getFlowAgentContext(), WORKFLOW_INPUT_EXTRACT_PROMPT, extractContext, extractOutputArgs);

        log.info("[AgentFlowLoop] workflow node '{}': extracting {} input args via LLM",
                node.getName(), inputArgsToExtract.size());

        return modelInvoker.invoke(modelContext)
                .then(Mono.defer(() -> {
                    Object response = modelContext.getModelCallResult().getData();
                    if (response instanceof JSONObject jsonResponse) {
                        JSONObject params = jsonResponse.getJSONObject("params");
                        if (params != null) {
                            input.putAll(params);
                        }
                    } else {
                        log.warn("[AgentFlowLoop] workflow input extract returned invalid JSON, proceeding with partial input");
                    }

                    log.info("[AgentFlowLoop] workflow node '{}': input args after LLM extraction: {}",
                            node.getName(), input.keySet());
                    return executeWorkflow(ctx, sink, node, input);
                }))
                .onErrorResume(e -> {
                    log.warn("[AgentFlowLoop] workflow input extract error for node '{}': {}",
                            node.getName(), e.getMessage(), e);
                    addRetryMessage(ctx, "Workflow input extraction failed for node '" + node.getName() + "': " + e.getMessage());
                    return runOrchestration(ctx, sink);
                });
    }

    /**
     * Execute the workflow using WorkflowExecutor, store result in session state, and advance.
     * Supports retry and timeout from ExceptionHandleConfigDto.
     */
    private Mono<Void> executeWorkflow(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink,
                                       GraphNode node, Map<String, Object> input) {
        return executeWorkflow(ctx, sink, node, input, 0);
    }

    private Mono<Void> executeWorkflow(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink,
                                       GraphNode node, Map<String, Object> input, int retryCount) {
        Object targetConfig = node.getTargetConfig();
        if (!(targetConfig instanceof WorkflowConfigDto workflowConfigDto)) {
            log.error("[AgentFlowLoop] WORKFLOW node '{}' has no valid WorkflowConfigDto, skipping", node.getName());
            return advanceToNextNode(ctx, sink, node, null, "Workflow config not found, skipping");
        }

        log.info("[AgentFlowLoop] executing workflow node '{}', workflowId={}, inputKeys={}, retry={}",
                node.getName(), workflowConfigDto.getId(), input.keySet(), retryCount);

        WorkflowContext workflowContext = new WorkflowContext();
        workflowContext.setOriginalWorkflowId(workflowConfigDto.getId());
        workflowContext.setAgentContext(ctx.getFlowAgentContext());
        workflowContext.setRequestId(ctx.getFlowAgentContext().getRequestId());
        workflowContext.setWorkflowConfig(workflowConfigDto);
        workflowContext.setParams(input);
        workflowContext.setNodeExecutingConsumer(nodeExecutingDto -> {
        });

        AgentContext agentContext = ctx.getFlowAgentContext();
        if (agentContext.getTraceContext() != null) {
            workflowContext.setTraceContext(agentContext.getTraceContext().next(
                    TraceContext.TraceTargetType.Workflow,
                    workflowConfigDto.getId().toString(),
                    workflowConfigDto.getName(),
                    workflowConfigDto.getDescription(),
                    workflowConfigDto.getIcon()));
        }

        // Apply timeout if configured
        ExceptionHandleConfigDto ehc = node.getExceptionHandleConfig();
        Mono<Object> workflowExecution = workflowExecutor.execute(workflowContext);
        if (ehc != null && ehc.getTimeout() != null && ehc.getTimeout() > 0) {
            workflowExecution = workflowExecution.timeout(Duration.ofSeconds(ehc.getTimeout()));
        }

        return workflowExecution
                .flatMap(result -> {
                    // Store workflow result in session state for downstream nodes
                    if (result != null) {
                        ctx.getSessionState().put("_node" + node.getId(), result);
                    }

                    // If end node returns TEXT type, use endNodeContent as the primary result
                    if (workflowConfigDto.getEndNode() != null
                            && workflowContext.getEndNodeContent() != null) {
                        Object endNodeConfig = workflowConfigDto.getEndNode().getNodeConfig();
                        if (endNodeConfig instanceof EndNodeConfigDto endConfig
                                && endConfig.getReturnType() == EndNodeConfigDto.ReturnType.TEXT) {
                            ctx.getSessionState().put("_node" + node.getId() + "_content",
                                    workflowContext.getEndNodeContent());
                        }
                    }

                    log.info("[AgentFlowLoop] workflow node '{}' completed successfully", node.getName());
                    return advanceToNextNode(ctx, sink, node, null, "Workflow completed successfully");
                })
                .onErrorResume(e -> {
                    // Check exception config retry first
                    if (ehc != null && ehc.getRetryCount() != null && retryCount < ehc.getRetryCount()) {
                        log.info("[AgentFlowLoop] workflow node '{}': retrying ({}/{}) per exception config",
                                node.getName(), retryCount + 1, ehc.getRetryCount());
                        return executeWorkflow(ctx, sink, node, input, retryCount + 1);
                    }
                    // Retries exhausted or not configured — apply exception handling strategy
                    return handleNodeException(ctx, sink, node, e);
                });
    }

    private Mono<Void> runAskQuestionNodeExecution(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink, GraphNode currentNode) {
        QaNodeConfigDto qaNodeConfig = (currentNode.getTargetConfig() instanceof QaNodeConfigDto qac) ? qac : null;

        // ---- Phase 1: Ask the question ----
        if (qaNodeConfig != null && !ctx.isWaitingForUserAnswer()) {
            // Step 1: Resolve input args (same pattern as workflow nodes)
            Map<String, Object> resolvedArgs = new HashMap<>();

            if (currentNode.getInputArgs() != null) {
                for (Arg inputArg : currentNode.getInputArgs()) {
                    if (inputArg.getEnable() != null && !inputArg.getEnable()) {
                        // Disabled arg: use bindValue directly
                        if (StringUtils.isNotBlank(inputArg.getBindValue())) {
                            resolvedArgs.put(inputArg.getName(), inputArg.getBindValue());
                        }
                    } else if (inputArg.getBindValueType() == Arg.BindValueType.Reference) {
                        // Reference arg: resolve from session state
                        Object value = ArgExtractUtil.extraBindValue(ctx.getSessionState(), "_node" + inputArg.getBindValue());
                        if (value == null) {
                            value = ctx.getSessionState().get(inputArg.getBindValue());
                        }
                        resolvedArgs.put(inputArg.getName(), value);
                    } else if (inputArg.getBindValueType() == Arg.BindValueType.Input && inputArg.getBindValue() != null) {
                        resolvedArgs.put(inputArg.getName(), inputArg.getBindValue());
                    }
                }
            }

            // Step 2: All args resolved, ask the question
            return askQuestion(ctx, sink, currentNode, qaNodeConfig, resolvedArgs);
        }

        // ---- Phase 2: Process user reply ----
        ctx.setWaitingForUserAnswer(false);
        ctx.getSessionState().put(ASK_QUESTION_WAITING_ANSWER_KEY, false);

        String message = ctx.getFlowAgentContext().getMessage();
        ctx.getSessionState().put("_node" + currentNode.getId(), Map.of("reply", message, "USER_RESPONSE", message));

        // SELECT routing: match user's choice to option's nextNodeIds
        if (qaNodeConfig != null && qaNodeConfig.getAnswerType() == QaNodeConfigDto.AnswerTypeEnum.SELECT
                && CollectionUtils.isNotEmpty(qaNodeConfig.getOptions())) {
            // qaNodeConfig.getOptions() 按照content从长到短排序
            for (QaNodeConfigDto.OptionConfigDto option : qaNodeConfig.getOptions()) {
                if (message.contains(option.getContent()) && CollectionUtils.isNotEmpty(option.getNextNodeIds())) {
                    String targetNodeId = option.getNextNodeIds().get(0).toString();
                    GraphNode targetNode = findNodeById(ctx.getFlowGraph(), targetNodeId);
                    if (targetNode != null) {
                        log.info("[AgentFlowLoop] AskQuestion '{}': SELECT matched option '{}', routing to node '{}'",
                                currentNode.getName(), option.getContent(), targetNode.getName());
                        ctx.setCurrentNodeId(targetNodeId);
                        addExecutionTrace(ctx, targetNode, "SELECT option matched: " + option.getContent());
                        ctx.setNodeRetryCount(0);
                        persistGraphState(ctx);
                        return runOrchestration(ctx, sink);
                    }
                }
            }
            log.info("[AgentFlowLoop] AskQuestion '{}': no SELECT option matched for '{}', using default edge",
                    currentNode.getName(), message);
        }

        // TEXT / FORM / unmatched SELECT → default next node
        return advanceToNextNode(ctx, sink, currentNode, null, "AskQuestion completed: " + currentNode.getName());
    }

    /**
     * Build question UI and emit ASK_QUESTION event to the user.
     * Resolves {{variableName}} placeholders in the question text from resolvedArgs and session state.
     */
    private Mono<Void> askQuestion(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink,
                                   GraphNode currentNode, QaNodeConfigDto qaNodeConfig,
                                   Map<String, Object> resolvedArgs) {
        String question = qaNodeConfig.getQuestion();

        // Resolve {{variableName}} placeholders: resolvedArgs first, then session state (supports dotted paths)
        if (StringUtils.isNotBlank(question)) {
            question = VARIABLE_PATTERN.matcher(question).replaceAll(match -> {
                String varName = match.group(1).trim();
                Object value = resolvedArgs.get(varName);
                if (value == null) {
                    value = ArgExtractUtil.extraBindValue(ctx.getSessionState(), varName);
                }
                return value != null ? Matcher.quoteReplacement(value.toString()) : match.group();
            });
        }

        Map<String, Object> askQuestionInput = new HashMap<>();
        askQuestionInput.put("schemaVersion", "nuwax.mcp_ask.v1");
        askQuestionInput.put("requestId", ctx.getFlowAgentContext().getRequestId());
        askQuestionInput.put("revision", 1);
        askQuestionInput.put("title", I18nUtil.systemMessage(ctx.getFlowAgentContext().getUser().getLangMap(), "Backend.AgentFlow.SupplementaryReply"));
        askQuestionInput.put("description", question);
        Map<String, Object> ui = new HashMap<>();
        askQuestionInput.put("ui", ui);
        ui.put("presentation", "inline");
        ui.put("title", question);
        List<Map<String, Object>> fields = new ArrayList<>();
        ui.put("fields", fields);

        if (qaNodeConfig.getAnswerType() == QaNodeConfigDto.AnswerTypeEnum.FORM) {
            List<Arg> formArgs = qaNodeConfig.getFormArgs();
            if (CollectionUtils.isEmpty(formArgs)) {
                log.warn("[AgentFlowLoop] AskQuestion node '{}' has no form args, using default reply field", currentNode.getName());
                formArgs = new ArrayList<>();
                formArgs.add(Arg.builder().name("reply").description(I18nUtil.systemMessage(ctx.getFlowAgentContext().getUser().getLangMap(), "Backend.AgentFlow.ReplyContent")).inputType(Arg.InputTypeEnum.Paragraph).build());
            }
            for (Arg arg : formArgs) {
                Map<String, Object> field = new HashMap<>();
                String type = switch (arg.getDataType()) {
                    case Number -> "number";
                    case Integer -> "integer";
                    default -> "string";
                };
                field.put("type", type);
                field.put("title", StringUtils.isNotBlank(arg.getDisplayName()) ? arg.getDisplayName() : arg.getDescription());
                field.put("required", arg.isRequire());
                field.put("name", arg.getName());
                String widget = (arg.getInputType() != null) ? switch (arg.getInputType()) {
                    case Text -> "text";
                    case Select -> "select";
                    case MultipleSelect -> "checkboxes";
                    case Number -> "number";
                    case File -> "file";
                    case Radio -> "radio";
                    default -> "textarea";
                } : "textarea";
                field.put("widget", widget);
                if ((widget.equals("select") || widget.equals("checkboxes") || widget.equals("radio"))
                        && arg.getSelectConfig() != null) {
                    field.put("options", arg.getSelectConfig().getOptions());
                }
                fields.add(field);
            }
        }
        if (qaNodeConfig.getAnswerType() == QaNodeConfigDto.AnswerTypeEnum.TEXT) {
            Map<String, Object> fieldReply = new HashMap<>();
            fieldReply.put("widget", "textarea");
            fieldReply.put("type", "string");
            fieldReply.put("title", I18nUtil.systemMessage(ctx.getFlowAgentContext().getUser().getLangMap(), "Backend.AgentFlow.ReplyContent"));
            fieldReply.put("required", true);
            fieldReply.put("name", "reply");
            fields.add(fieldReply);
        }
        if (qaNodeConfig.getAnswerType() == QaNodeConfigDto.AnswerTypeEnum.SELECT) {
            Map<String, Object> fieldReply = new HashMap<>();
            if (qaNodeConfig.getOptions() != null) {
                List<Map<String, String>> options = qaNodeConfig.getOptions().stream()
                        .map(opt -> Map.of("value", opt.getContent(), "label", opt.getContent()))
                        .collect(Collectors.toList());
                //移除options最后一个
                options.remove(options.size() - 1);
                fieldReply.put("options", options);
            }
            fieldReply.put("widget", "radio");
            fieldReply.put("type", "string");
            fieldReply.put("title", I18nUtil.systemMessage(ctx.getFlowAgentContext().getUser().getLangMap(), "Backend.AgentFlow.SelectReply"));
            fieldReply.put("required", true);
            fieldReply.put("name", "reply");
            fields.add(fieldReply);
        }

        // Output question as chat message
        addRoundMessage(ctx, ctx.getFlowAgentContext(), question);
        ctx.getResponseBuilder().append(question);
        sink.next(buildOutputMessage(ctx.getFlowAgentContext(), question));

        // Emit ASK_QUESTION event with form UI
        ComponentExecutingDto componentExecutingDto = new ComponentExecutingDto();
        componentExecutingDto.setName("AskQuestion");
        componentExecutingDto.setStatus(ExecuteStatusEnum.EXECUTING);
        componentExecutingDto.setType(ComponentTypeEnum.Event);
        componentExecutingDto.setSubEventType(ComponentExecutingDto.SubEventTypeEnum.ASK_QUESTION);
        ComponentExecuteResult componentExecuteResult = new ComponentExecuteResult();
        componentExecuteResult.setName(I18nUtil.systemMessage(ctx.getFlowAgentContext().getUser().getLangMap(), "Backend.AgentFlow.SupplementaryReply"));
        componentExecuteResult.setType(ComponentTypeEnum.Event);
        componentExecuteResult.setData(askQuestionInput);
        componentExecuteResult.setSuccess(true);
        componentExecuteResult.setExecuteId(UUID.randomUUID().toString().replace("-", ""));
        componentExecutingDto.setResult(componentExecuteResult);
        AgentOutputDto agentOutputDto = new AgentOutputDto();
        agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.PROCESSING);
        agentOutputDto.setData(componentExecutingDto);
        agentOutputDto.setRequestId(ctx.getFlowAgentContext().getRequestId());

        ctx.setWaitingForUserAnswer(true);
        ctx.getSessionState().put(ASK_QUESTION_WAITING_ANSWER_KEY, true);
        sink.next(agentOutputDto);
        persistSessionState(ctx);
        sendComplete(ctx, sink, null);
        return Mono.empty();
    }

    // ======================== Phase 2c: Router Node Evaluation ========================

    /**
     * For ROUTER nodes: evaluate conditions to pick the correct branch.
     * Strategy: expression evaluation first → LLM fallback.
     */
    private Mono<Void> runRouterNodeEvaluation(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink, GraphNode node) {
        RouterNodeConfig routerConfig = node.getRouterConfig();
        if (routerConfig == null || CollectionUtils.isEmpty(routerConfig.getConditions())) {
            log.warn("[AgentFlowLoop] router node '{}' has no conditions, using default edge", node.getName());
            String defaultEdgeId = (routerConfig != null) ? routerConfig.getDefaultEdgeId() : null;
            return advanceToNextNode(ctx, sink, node, defaultEdgeId, "Router has no conditions, using default branch");
        }

        // Step 1: Try expression-based evaluation first
        String matchedEdgeId = evaluateRouterExpressions(ctx, node);
        if (matchedEdgeId != null) {
            log.info("[AgentFlowLoop] router '{}': expression matched edge '{}'", node.getName(), matchedEdgeId);
            return advanceToNextNode(ctx, sink, node, matchedEdgeId, "Router expression matched: " + matchedEdgeId);
        }

        // Step 2: LLM fallback for conditions that have text descriptions
        boolean hasTextConditions = routerConfig.getConditions().stream()
                .anyMatch(c -> StringUtils.isNotBlank(c.getTextDescription()));
        if (!hasTextConditions) {
            // No text descriptions, can't use LLM → use default edge
            log.warn("[AgentFlowLoop] router '{}': no expression match and no text descriptions, using default", node.getName());
            return advanceToNextNode(ctx, sink, node, routerConfig.getDefaultEdgeId(), "No matching branch, using default");
        }

        log.info("[AgentFlowLoop] router '{}': no expression match, falling back to LLM evaluation", node.getName());
        return runRouterLLMEvaluation(ctx, sink, node);
    }

    /**
     * Evaluate router conditions using expressions against session state.
     * Returns the matched edgeId, or null if no condition matched.
     */
    private String evaluateRouterExpressions(FlowLoopContext ctx, GraphNode node) {
        RouterNodeConfig routerConfig = node.getRouterConfig();
        if (routerConfig == null || CollectionUtils.isEmpty(routerConfig.getConditions())) {
            return null;
        }
        for (RouterCondition condition : routerConfig.getConditions()) {
            if (CollectionUtils.isEmpty(condition.getConditionArgs())) {
                continue;
            }
            Boolean evalResult = null;
            for (ConditionNodeConfigDto.ConditionArgDto conditionArgDto : condition.getConditionArgs()) {
                Object firstValue = extraBindValue(ctx, conditionArgDto.getFirstArg());
                Object secondValue = extraBindValue(ctx, conditionArgDto.getSecondArg());
                if (firstValue instanceof List<?> && !((List<?>) firstValue).isEmpty()) {
                    firstValue = ((List<?>) firstValue).get(((List<?>) firstValue).size() - 1);
                }
                boolean res = eval(firstValue, secondValue, conditionArgDto.getCompareType());
                if (condition.getConditionType() == ConditionNodeConfigDto.ConditionTypeEnum.OR) {
                    evalResult = evalResult == null ? res : evalResult || res;
                } else {
                    evalResult = evalResult == null ? res : evalResult && res;
                }
            }
            if (evalResult == null || !evalResult) {
                continue;
            }
            return condition.getEdgeId();
        }
        return null;
    }

    private Object extraBindValue(FlowLoopContext ctx, Arg arg) {
        if (arg.getBindValueType() == Arg.BindValueType.Input) {
            return arg.getBindValue();
        }
        Object value = ArgExtractUtil.extraBindValue(ctx.getSessionState(), "_node" + arg.getBindValue());
        if (value == null) {
            String key = arg.getBindValue();
            if (arg.getBindValue().contains(ctx.getFlowGraph().getStartNodeId())) {
                key = arg.getBindValue().replace(ctx.getFlowGraph().getStartNodeId() + ".", ""); // 工作流上做绑定时追加了开始节点前缀
            }
            value = ctx.getSessionState().get(key);
        }
        return value;
    }

    /**
     * LLM-based router evaluation: asks LLM to pick the best branch based on context.
     */
    private Mono<Void> runRouterLLMEvaluation(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink, GraphNode node) {
        String branchList = buildBranchList(node);
        String systemPrompt = ROUTER_EVALUATOR_PROMPT
                .replace("${nodeName}", node.getName())
                .replace("${nodeDescription}", node.getDescription() != null ? node.getDescription() : "")
                .replace("${branches}", branchList);

        String evaluationContext = buildRouterEvaluationContext(ctx, node);
        ModelContext modelContext = buildModelContext(
                ctx.getFlowAgentContext(), systemPrompt, evaluationContext, buildRouterEvaluatorArgs());

        return modelInvoker.invoke(modelContext)
                .onErrorResume(e -> {
                    log.warn("[AgentFlowLoop] router LLM evaluation error: {}", e.getMessage(), e);
                    modelContext.getModelCallResult().setResponseText(e.getMessage());
                    return Mono.empty();
                })
                .then(Mono.defer(() -> handleRouterEvaluationResult(ctx, sink, modelContext, node)));
    }

    private Mono<Void> handleRouterEvaluationResult(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink,
                                                    ModelContext modelContext, GraphNode node) {
        Object response = modelContext.getModelCallResult().getData();
        if (!(response instanceof JSONObject jsonResponse)) {
            addRetryMessage(ctx, "Router evaluator response is not valid JSON:\n" + modelContext.getModelCallResult().getResponseText());
            return runOrchestration(ctx, sink);
        }

        RouterEvaluationDecision decision = jsonResponse.to(RouterEvaluationDecision.class);
        if (decision == null) {
            addRetryMessage(ctx, "Router evaluator returned empty decision");
            return runOrchestration(ctx, sink);
        }

        if (StringUtils.isNotBlank(decision.getReasoning())) {
            ctx.getReasoningBuilder().append("\n").append(decision.getReasoning());
            buildAndSendReasoning(ctx, sink, decision.getReasoning());
        }

        String selectedEdgeId = decision.getSelectedEdgeId();
        if (StringUtils.isBlank(selectedEdgeId)) {
            log.warn("[AgentFlowLoop] router evaluator returned no edge, using default");
            RouterNodeConfig routerConfig = node.getRouterConfig();
            selectedEdgeId = (routerConfig != null) ? routerConfig.getDefaultEdgeId() : null;
        }

        log.info("[AgentFlowLoop] router '{}': LLM selected edge '{}'", node.getName(), selectedEdgeId);
        String reasoning = (decision != null && StringUtils.isNotBlank(decision.getReasoning()))
                ? "Router LLM: " + decision.getReasoning()
                : "Router LLM selected: " + selectedEdgeId;
        return advanceToNextNode(ctx, sink, node, selectedEdgeId, reasoning);
    }

    // ======================== Graph Navigation ========================

    /**
     * Advance to the next node by following graph edges.
     *
     * @param node           the current node
     * @param selectedEdgeId the edge to follow (null = follow the first/default outgoing edge)
     * @param reasoning      why this transition is happening (recorded in execution trace)
     */
    private Mono<Void> advanceToNextNode(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink,
                                         GraphNode node, String selectedEdgeId, String reasoning) {
        // Emit node end state
        buildAndSendStateMessage(ctx, sink, "node_end");
        if (ctx.getFlowAgentContext().isInterrupted() || chatApplicationService.isChatStop(ctx.getFlowAgentContext().getConversationId())) {
            persistSessionState(ctx);
            sendComplete(ctx, sink, null);
            return Mono.empty();
        }
        Graph graph = ctx.getFlowGraph();
        String nextNodeId = resolveNextNodeId(graph, node, selectedEdgeId);

        if (StringUtils.isBlank(nextNodeId)) {
            log.info("[AgentFlowLoop] no outgoing edge from node '{}', flow ends", node.getName());
            if (ctx.getResponseBuilder().isEmpty()) {
                String fallbackMsg = buildGraphCompletionMessage(ctx);
                sink.next(buildOutputMessage(ctx.getFlowAgentContext(), fallbackMsg));
            }
            persistSessionState(ctx);
            sendComplete(ctx, sink, null);
            return Mono.empty();
        }

        // Check for cycles (prevent infinite loops)
        if (countNodeOccurrences(ctx.getExecutionPath(), nextNodeId) >= 10) {
            log.warn("[AgentFlowLoop] cycle detected: node '{}' visited {} times, forcing end",
                    nextNodeId, countNodeOccurrences(ctx.getExecutionPath(), nextNodeId));
            if (ctx.getResponseBuilder().isEmpty()) {
                sink.next(buildOutputMessage(ctx.getFlowAgentContext(), I18nUtil.systemMessage(ctx.getFlowAgentContext().getUser().getLangMap(), "Backend.AgentFlow.CycleDetected")));
            }
            clearGraphState(ctx);
            sendComplete(ctx, sink, "Cycle detected");
            return Mono.empty();
        }

        ctx.setCurrentNodeId(nextNodeId);
        addExecutionTrace(ctx, nextNodeId, reasoning);
        ctx.setNodeRetryCount(0);
        persistGraphState(ctx);

        log.info("[AgentFlowLoop] advanced to node '{}' (path size: {})", nextNodeId, ctx.getExecutionPath().size());
        return runOrchestration(ctx, sink);
    }

    /**
     * Recover the last executed node from executionPath when currentNodeId was cleared.
     * Used when the Dispatcher sets continueCurrent=true but the flow ended in the previous turn.
     */
    private GraphNode recoverLastNode(FlowLoopContext ctx) {
        List<ExecutionTrace> path = ctx.getExecutionPath();
        if (CollectionUtils.isEmpty(path)) return null;
        // Walk backwards to find the last node that still exists in the graph
        for (int i = path.size() - 1; i >= 0; i--) {
            String nodeId = path.get(i).getNodeId();
            if (StringUtils.isNotBlank(nodeId)) {
                GraphNode node = findNodeById(ctx.getFlowGraph(), nodeId);
                if (node != null && node.getType() != NodeType.START && node.getType() != NodeType.END) {
                    return node;
                }
            }
        }
        return null;
    }

    /**
     * Resolve the next node ID by following graph edges from the current node.
     */
    private String resolveNextNodeId(Graph graph, GraphNode currentNode, String selectedEdgeId) {
        if (graph == null || CollectionUtils.isEmpty(graph.getEdges())) return null;

        List<GraphEdge> outgoingEdges = graph.getEdges().stream()
                .filter(e -> e.getSourceNodeId().equals(currentNode.getId()))
                .toList();

        if (outgoingEdges.isEmpty()) return null;

        if (StringUtils.isNotBlank(selectedEdgeId)) {
            // Find the specific edge selected by the router
            return outgoingEdges.stream()
                    .filter(e -> e.getId().equals(selectedEdgeId))
                    .map(GraphEdge::getTargetNodeId)
                    .findFirst()
                    .orElse(null);
        }

        // Default: follow the first outgoing edge
        return outgoingEdges.get(0).getTargetNodeId();
    }

    /**
     * Resolve the names of planned next nodes by following graph edges from the given node.
     * Used to show the InputBuilder what's coming after the current node in the planned flow path.
     */
    private List<String> resolveNextNodeNames(Graph graph, GraphNode currentNode) {
        if (graph == null || CollectionUtils.isEmpty(graph.getEdges())) return Collections.emptyList();
        List<GraphEdge> outgoing = graph.getEdges().stream()
                .filter(e -> e.getSourceNodeId().equals(currentNode.getId()))
                .toList();
        List<String> names = new ArrayList<>();
        for (GraphEdge edge : outgoing) {
            GraphNode nextNode = findNodeById(graph, edge.getTargetNodeId());
            if (nextNode != null && nextNode.getType() != NodeType.END) {
                names.add(nextNode.getName());
            }
        }
        return names;
    }

    private void persistGraphState(FlowLoopContext ctx) {
        ctx.getSessionState().put(GRAPH_CURRENT_NODE_KEY, ctx.getCurrentNodeId());
        ctx.getSessionState().put(GRAPH_EXECUTION_PATH_KEY, ctx.getExecutionPath());
    }

    /**
     * Clear graph execution state so the next turn starts fresh through the dispatcher.
     * Also persists the final session state (including variable updates from the last node)
     * and the cleared graph keys, so the next turn doesn't see stale graph position.
     * Call this ONLY when the flow truly terminates (END node, max dispatch, user interrupt, cycle).
     */
    private void clearGraphState(FlowLoopContext ctx) {
        ctx.setCurrentNodeId(null);
        ctx.setExecutionPath(new ArrayList<>());
        ctx.getSessionState().remove(GRAPH_CURRENT_NODE_KEY);
        // Persist final session state + cleared currentNodeId (executionPath is kept for cross-turn context)
        conversationApplicationService.updateConversationVariables(
                ctx.getFlowAgentContext().getConversation().getId(), ctx.getSessionState());
    }

    /**
     * Persist session state to conversation without clearing graph position.
     * The next turn's dispatcher can see currentNodeId and executionPath
     * to make informed decisions (e.g., continue from current position, or re-route).
     * Call this when the flow pauses or stops without true termination
     * (clarifying message, no outgoing edge, exception).
     */
    private void persistSessionState(FlowLoopContext ctx) {
        conversationApplicationService.updateConversationVariables(
                ctx.getFlowAgentContext().getConversation().getId(), ctx.getSessionState());
    }

    // ======================== Variable Management ========================

    /**
     * Apply structured variable updates to session state, respecting each variable's configured strategy.
     * <p>
     * For each update:
     * 1. Look up the variable definition in the graph's variable list
     * 2. If found, apply according to strategy (REPLACE or APPEND)
     * 3. If not found, default to REPLACE (backward compatible with stateUpdate)
     * <p>
     * APPEND behavior:
     * - String values: concatenation (existing + " " + new)
     * - List values: add all elements from new list
     * - Number values: arithmetic addition
     * - Other types: fallback to REPLACE
     */
    private void applyVariableUpdates(FlowLoopContext ctx, List<VariableUpdate> updates) {
        if (CollectionUtils.isEmpty(updates)) return;

        Map<String, Object> sessionState = ctx.getSessionState();
        Map<String, GraphVariable> variableDefinitions = getVariableDefinitionMap(ctx);

        for (VariableUpdate update : updates) {
            if (StringUtils.isBlank(update.getName()) || update.getValue() == null) continue;

            String varName = update.getName();

            // Guard: user-defined variables must NOT overwrite system-critical session state
            if (isSystemReservedKey(varName)) {
                log.warn("[AgentFlowLoop] variable update rejected: '{}' is a system-reserved key and cannot be modified by user-defined variables",
                        varName);
                continue;
            }

            Object newValue = update.getValue();
            UpdateStrategy strategy = resolveStrategy(variableDefinitions, varName);

            Object existingValue = sessionState.get(varName);

            // Initialize default value if variable doesn't exist yet
            if (existingValue == null) {
                GraphVariable graphVar = variableDefinitions.get(varName);
                if (graphVar != null && graphVar.getDefaultValue() != null) {
                    existingValue = graphVar.getDefaultValue();
                    sessionState.put(varName, existingValue);
                }
            }

            if (strategy == UpdateStrategy.APPEND && existingValue != null) {
                Object appended = appendValues(existingValue, newValue);
                sessionState.put(varName, appended);
                log.info("[AgentFlowLoop] variable '{}' APPEND: {} + {} → {}",
                        varName, truncateForLog(existingValue), truncateForLog(newValue), truncateForLog(appended));
            } else {
                // REPLACE (default)
                sessionState.put(varName, newValue);
                log.info("[AgentFlowLoop] variable '{}' REPLACE: {} → {}",
                        varName, truncateForLog(existingValue), truncateForLog(newValue));
            }
        }
    }

    /**
     * Resolve the update strategy for a variable from graph definitions.
     * Returns REPLACE as default if the variable is not defined in the graph.
     */
    private UpdateStrategy resolveStrategy(Map<String, GraphVariable> variableDefinitions, String varName) {
        GraphVariable graphVar = variableDefinitions.get(varName);
        if (graphVar != null && graphVar.getStrategy() != null) {
            return graphVar.getStrategy();
        }
        return UpdateStrategy.REPLACE; // default strategy
    }

    /**
     * Build a map of variable name → GraphVariable from the graph definition.
     */
    private Map<String, GraphVariable> getVariableDefinitionMap(FlowLoopContext ctx) {
        Graph graph = ctx.getFlowGraph();
        if (graph == null || CollectionUtils.isEmpty(graph.getVariables())) {
            return Collections.emptyMap();
        }
        return graph.getVariables().stream()
                .collect(Collectors.toMap(GraphVariable::getName, v -> v, (a, b) -> a));
    }

    /**
     * Append newValue to existingValue based on their types.
     */
    @SuppressWarnings("unchecked")
    private Object appendValues(Object existingValue, Object newValue) {
        // Convert non-list existing values to a list for uniform APPEND semantics
        List<Object> merged;
        if (existingValue instanceof List<?> existingList) {
            merged = new ArrayList<>((List<Object>) existingList);
        } else if (existingValue instanceof String || existingValue instanceof Number) {
            merged = new ArrayList<>();
            merged.add(existingValue);
        } else if (existingValue instanceof StringBuilder sb) {
            // StringBuilder: keep existing behavior for backward compatibility
            sb.append(" ").append(newValue);
            return sb.toString();
        } else {
            // Fallback: wrap new value in a list
            return newValue instanceof String || newValue instanceof Number
                    ? new ArrayList<>(Collections.singletonList(newValue)) : newValue;
        }

        // Append new value(s), skipping consecutive duplicates
        if (newValue instanceof List<?> newList) {
            for (Object item : newList) {
                addIfNotDuplicate(merged, item);
            }
        } else {
            addIfNotDuplicate(merged, newValue);
        }
        return merged;
    }

    private static void addIfNotDuplicate(List<Object> list, Object item) {
        if (list.isEmpty() || !Objects.equals(list.get(list.size() - 1), item)) {
            list.add(item);
        }
    }

    private String truncateForLog(Object value) {
        if (value == null) return "null";
        String str = String.valueOf(value);
        return str.length() > 80 ? str.substring(0, 80) + "..." : str;
    }

    // ======================== Agent Dispatch ========================

    /**
     * Dispatch execution to an agent node. Adapted from the old dispatchStep.
     * After the agent completes, follows the outgoing edge to the next node.
     */
    private Mono<Void> dispatchNode(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink,
                                    GraphNode node, AgentInputBuilderDecision decision, String inputMessage) {
        AgentConfigDto agentConfigDto = findAgentConfig(ctx, node.getAgentId());
        if (agentConfigDto == null) {
            log.warn("[AgentFlowLoop] agent not found for node '{}': {}", node.getName(), node.getAgentId());
            appendNodeFailure(ctx, node, "Agent not found");
            return advanceToNextNode(ctx, sink, node, null, "Agent not found: " + node.getAgentId());
        }

        // Node-level retry
        if (ctx.getNodeRetryCount() > MAX_NODE_RETRY) {
            log.warn("[AgentFlowLoop] node '{}' exceeded max retries, skipping", node.getName());
            appendNodeFailure(ctx, node, "Exceeded max retries due to repeated errors");
            return advanceToNextNode(ctx, sink, node, null, "Agent max retries exceeded, skipping");
        }

        ctx.setTotalDispatchCount(ctx.getTotalDispatchCount() + 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> agentState = (Map<String, Object>) ctx.getSessionState().computeIfAbsent("_agent" + agentConfigDto.getId(), k -> new HashMap<>());
        AgentContext agentContext = new AgentContext();
        BeanUtils.copyProperties(ctx.getFlowAgentContext(), agentContext);
        agentContext.setAgentConfig(agentConfigDto);
        agentContext.setMessage(buildNodeAgentPrompt(ctx, node, decision, inputMessage));
        agentContext.setUseSate(true);
        agentContext.setSessionState(agentState);
        String dispatchMsg = "**@" + agentConfigDto.getName() + "** " + inputMessage;
        addRoundMessage(ctx, ctx.getFlowAgentContext(), dispatchMsg);
        // For collecting agent output
        StringBuilder messageBuilder = new StringBuilder();
        boolean[] isFirst = {true};

        // Apply timeout if configured
        ExceptionHandleConfigDto ehc = node.getExceptionHandleConfig();
        Flux<AgentOutputDto> agentExecution = agentExecutor.execute(agentContext);
        if (ehc != null && ehc.getTimeout() != null && ehc.getTimeout() > 0) {
            agentExecution = agentExecution.timeout(Duration.ofSeconds(ehc.getTimeout()));
        }

        return agentExecution
                .onErrorResume(error -> {
                    // Check exception config retry first
                    if (shouldRetryByExceptionConfig(ctx, node)) {
                        log.info("[AgentFlowLoop] node '{}': retrying ({}/{}) per exception config",
                                node.getName(), ctx.getNodeRetryCount() + 1, ehc == null ? MAX_NODE_RETRY : ehc.getRetryCount());
                        ctx.setNodeRetryCount(ctx.getNodeRetryCount() + 1);
                        addNodeRetryMessage(ctx, "Agent [" + agentConfigDto.getName() + "] error: " + error.getMessage() + ", retrying...");
                        return runOrchestration(ctx, sink).then(Mono.empty());
                    }
                    // Retries exhausted — apply exception handling strategy
                    return handleNodeException(ctx, sink, node, error).then(Mono.empty());
                })
                .doOnNext(outputDto -> {
                    if (outputDto.getEventType() == AgentOutputDto.EventTypeEnum.MESSAGE
                            && outputDto.getData() instanceof CallMessage message) {
                        enrichCallMessage(ctx, message, agentConfigDto);
                        if (message.getType() == MessageTypeEnum.THINK) {
                            ctx.getReasoningBuilder().append(message.getText());
                        } else {
                            String text = message.getText();
                            messageBuilder.append(text);
                            if (isFirst[0]) {
                                isFirst[0] = false;
                                String header = buildGroupAgentName(ctx, agentConfigDto);
                                text = header + text;
                                message.setText(text);
                            }
                            ctx.getResponseBuilder().append(text);
                        }
                        sink.next(outputDto);
                    }
                    if (outputDto.getEventType() == AgentOutputDto.EventTypeEnum.PROCESSING) {
                        if (outputDto.getData() instanceof ComponentExecutingDto componentExecutingDto) {
                            if (isFirst[0]) {
                                isFirst[0] = false;
                                AgentOutputDto agentOutputDto = buildOutputMessage(ctx.getFlowAgentContext(), buildGroupAgentName(ctx, agentConfigDto));
                                sink.next(agentOutputDto);
                            }
                            String processingText = AgentExecutor.buildProcessingText(componentExecutingDto);
                            messageBuilder.append(processingText);
                            ctx.getResponseBuilder().append(processingText);
                            if (componentExecutingDto.getSubEventType() == ComponentExecutingDto.SubEventTypeEnum.ASK_QUESTION) {
                                ctx.setWaitingForUserAnswer(true);
                            }
                            if (componentExecutingDto.getSubEventType() == ComponentExecutingDto.SubEventTypeEnum.REQUEST_PERMISSION) {
                                conversationApplicationService.updateConversationVariables(ctx.getFlowAgentContext().getConversation().getId(), ctx.getSessionState());
                            }
                            ctx.flowAgentContext.getAgentExecuteResult().getComponentExecuteResults().add(componentExecutingDto.getResult());
                        }
                        sink.next(outputDto);
                    }
                })
                .then(Mono.defer(() -> {
                    if (messageBuilder.isEmpty()) {
                        // Agent returned no output — treat as failure, apply exception handling
                        if (shouldRetryByExceptionConfig(ctx, node)) {
                            ctx.setNodeRetryCount(ctx.getNodeRetryCount() + 1);
                            addNodeRetryMessage(ctx, "Agent [" + agentConfigDto.getName() + "] returned empty output, retrying...");
                            return runOrchestration(ctx, sink);
                        }
                        if (ctx.getNodeRetryCount() <= MAX_NODE_RETRY) {
                            addNodeRetryMessage(ctx, "Agent [" + agentConfigDto.getName() + "] returned empty output");
                            ctx.setNodeRetryCount(ctx.getNodeRetryCount() + 1);
                            return runOrchestration(ctx, sink);
                        }
                        log.warn("[AgentFlowLoop] node '{}' exceeded max retries (empty output)", node.getName());
                        return handleNodeException(ctx, sink, node,
                                new RuntimeException("Agent returned empty output after retries"));
                    } else {
                        // Success — record round message
                        addRoundMessage(ctx, agentContext, messageBuilder.toString());
                    }

                    // Agent is waiting for user answer — stop flow at current node
                    if (ctx.isWaitingForUserAnswer()) {
                        log.info("[AgentFlowLoop] node '{}' is waiting for user answer, pausing flow", node.getName());
                        ctx.getSessionState().put(ASK_QUESTION_WAITING_ANSWER_KEY, true);
                        persistSessionState(ctx);
                        sendComplete(ctx, sink, null);
                        return Mono.empty();
                    }

                    return advanceToNextNode(ctx, sink, node, null, "Agent completed: " + node.getName());
                }));
    }

    private String buildGroupAgentName(FlowLoopContext ctx, AgentConfigDto agentConfigDto) {
        String agentInfo = "<agent-info name=\"" + agentConfigDto.getName() + "\" icon=\"" + agentConfigDto.getIcon() + "\"/>";
        return ctx.getResponseBuilder().isEmpty() ? agentInfo + "\n\n" : "\n\n" + agentInfo + "\n\n";
    }

    private void enrichCallMessage(FlowLoopContext ctx, CallMessage message, AgentConfigDto agentConfigDto) {
        message.setId(ctx.getFlowAgentContext().getRequestId());
        message.setFinished(false);
        message.setFinishReason(null);
        message.setSenderType(ChatMessageDto.SenderType.AGENT);
        message.setAgentId(agentConfigDto.getId());
        message.setSenderId(agentConfigDto.getId().toString());
    }

    /**
     * Append a failure notice to responseBuilder when a node is skipped.
     */
    private void appendNodeFailure(FlowLoopContext ctx, GraphNode node, String reason) {
        String agentName = node.getName();
        AgentConfigDto cfg = findAgentConfig(ctx, node.getAgentId());
        if (cfg != null) agentName = cfg.getName();

        String notice;
        reason = reason == null ? "" : reason;
        String i18nMessage = I18nUtil.systemMessage(ctx.getFlowAgentContext().getUser().getLangMap(), "Backend.AgentFlow.StepExecutionFailed")
                .replace("{agentName}", agentName).replace("{reason}", reason);
        if (ctx.getResponseBuilder().isEmpty()) {
            notice = i18nMessage;
        } else {
            notice = "\n\n" + i18nMessage;
        }
        ctx.getResponseBuilder().append(notice);
    }

    // ======================== Exception Handling ========================

    /**
     * Check whether a node should retry based on its exception handling config and current retry count.
     */
    private boolean shouldRetryByExceptionConfig(FlowLoopContext ctx, GraphNode node) {
        ExceptionHandleConfigDto ehc = node.getExceptionHandleConfig();
        if (ehc == null || ehc.getRetryCount() == null || ehc.getRetryCount() <= 0) {
            return false;
        }
        return ctx.getNodeRetryCount() < ehc.getRetryCount();
    }

    /**
     * Handle a node execution exception according to the node's ExceptionHandleConfigDto.
     * <p>
     * Three strategies:
     * <ul>
     *   <li>INTERRUPT (default): output error message to user and end the flow</li>
     *   <li>SPECIFIC_CONTENT: output configured content to user and advance to next node</li>
     *   <li>EXECUTE_EXCEPTION_FLOW: follow exception handling edges to designated nodes</li>
     * </ul>
     */
    private Mono<Void> handleNodeException(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink,
                                           GraphNode node, Throwable e) {
        ExceptionHandleConfigDto ehc = node.getExceptionHandleConfig();

        log.error("[AgentFlowLoop] node '{}' execution failed: {}", node.getName(), e.getMessage(), e);
        ctx.getSessionState().put("_node" + node.getId() + "_error", e.getMessage());

        // No config or INTERRUPT: output error and end flow
        if (ehc == null || ehc.getExceptionHandleType() == null
                || ehc.getExceptionHandleType() == ExceptionHandleConfigDto.ExceptionHandleTypeEnum.INTERRUPT) {
            appendNodeFailure(ctx, node, e.getMessage());
            persistSessionState(ctx);
            sendComplete(ctx, sink, null);
            return Mono.empty();
        }

        // SPECIFIC_CONTENT: output configured content and advance
        if (ehc.getExceptionHandleType() == ExceptionHandleConfigDto.ExceptionHandleTypeEnum.SPECIFIC_CONTENT) {
            Object content = ehc.getSpecificContent();
            String contentStr = (content != null) ? content.toString()
                    : I18nUtil.systemMessage(ctx.getFlowAgentContext().getUser().getLangMap(), "Backend.AgentFlow.NodeExecutionSkipped")
                    .replace("{nodeName}", node.getName());
            sink.next(buildOutputMessage(ctx.getFlowAgentContext(), contentStr));
            ctx.getResponseBuilder().append(contentStr);
            return advanceToNextNode(ctx, sink, node, null, "Exception handled: SPECIFIC_CONTENT");
        }

        // EXECUTE_EXCEPTION_FLOW: follow exception handling edges
        if (ehc.getExceptionHandleType() == ExceptionHandleConfigDto.ExceptionHandleTypeEnum.EXECUTE_EXCEPTION_FLOW) {
            List<Long> exceptionHandleNodeIds = ehc.getExceptionHandleNodeIds();
            if (CollectionUtils.isNotEmpty(exceptionHandleNodeIds)) {
                // Find an outgoing edge whose target matches an exception handler node ID
                String exceptionEdgeId = findExceptionEdgeId(ctx.getFlowGraph(), node, exceptionHandleNodeIds);
                if (exceptionEdgeId != null) {
                    log.info("[AgentFlowLoop] node '{}': following exception handling edge '{}'",
                            node.getName(), exceptionEdgeId);
                    return advanceToNextNode(ctx, sink, node, exceptionEdgeId, "Exception: EXECUTE_EXCEPTION_FLOW → " + exceptionEdgeId);
                }
            }
            // No exception edge found — fallback to INTERRUPT
            log.warn("[AgentFlowLoop] node '{}': EXECUTE_EXCEPTION_FLOW configured but no matching exception edge found, interrupting",
                    node.getName());
            appendNodeFailure(ctx, node, e.getMessage());
            persistSessionState(ctx);
            sendComplete(ctx, sink, "EXECUTE_EXCEPTION_FLOW configured but no matching exception edge found, interrupting");
            return Mono.empty();
        }

        // Fallback: INTERRUPT
        appendNodeFailure(ctx, node, e.getMessage());
        persistSessionState(ctx);
        sendComplete(ctx, sink, e.getMessage());
        return Mono.empty();
    }

    /**
     * Find an outgoing edge from the given node whose target matches one of the exception handler node IDs.
     */
    private String findExceptionEdgeId(Graph graph, GraphNode node, List<Long> exceptionHandleNodeIds) {
        if (graph == null || CollectionUtils.isEmpty(graph.getEdges())) return null;

        List<GraphEdge> outgoingEdges = graph.getEdges().stream()
                .filter(e -> e.getSourceNodeId().equals(node.getId()))
                .toList();

        for (GraphEdge edge : outgoingEdges) {
            String targetNodeId = edge.getTargetNodeId();
            // Match by string representation (handles both numeric and string IDs)
            for (Long handleId : exceptionHandleNodeIds) {
                if (handleId.toString().equals(targetNodeId)) {
                    return edge.getId();
                }
            }
        }
        return null;
    }

    /**
     * Build a user-friendly completion message when the graph flow ends without agent output.
     */
    private String buildGraphCompletionMessage(FlowLoopContext ctx) {
        if (ctx.getExecutionPath().isEmpty()) {
            return I18nUtil.systemMessage(ctx.getFlowAgentContext().getUser().getLangMap(), "Backend.AgentFlow.PlanFailureRetry");
        }
        return I18nUtil.systemMessage(ctx.getFlowAgentContext().getUser().getLangMap(), "Backend.AgentFlow.FlowExecutionCompleted");
    }

    // ======================== Message Builders ========================

    private void sendComplete(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink, String error) {
        if (sink != null) {
            buildAndSendStateMessage(ctx, sink, error != null ? "flow_error" : "flow_complete");
        }
        AgentContext agentContext = ctx.getFlowAgentContext();
        ChatMessageDto assistantMessage = ChatMessageDto.builder()
                .tenantId(agentContext.getAgentConfig().getTenantId())
                .id(agentContext.getRequestId())
                .senderType(ChatMessageDto.SenderType.AGENT)
                .senderId(agentContext.getAgentConfig().getId().toString())
                .userId(agentContext.getUserId())
                .agentId(agentContext.getAgentConfig().getId())
                .role(ChatMessageDto.Role.ASSISTANT)
                .type(MessageTypeEnum.CHAT)
                .text(ctx.getResponseBuilder().toString())
                .think(ctx.getReasoningBuilder().toString())
                .time(new Date())
                .finished(true)
                .componentExecutedList(ctx.flowAgentContext.getAgentExecuteResult().getComponentExecuteResults().stream()
                        .map(componentExecutingDto -> (Object) componentExecutingDto).toList())
                .build();
        conversationApplicationService.addRoundMessage(ctx.getFlowAgentContext().getConversationId(), assistantMessage);
        agentContext.getAgentExecuteResult().setOutputText(assistantMessage.getText());
        if (agentContext.getAgentExecuteResult().getSuccess() == null) {
            agentContext.getAgentExecuteResult().setSuccess(error == null);
            agentContext.getAgentExecuteResult().setError(error);
        }
        if (sink != null) {
            AgentOutputDto agentOutputDto = AgentExecutor.buildFinalResultOutput(agentContext);
            sink.next(agentOutputDto);
            sink.complete();
        }
    }

    private AgentOutputDto buildOutputMessage(AgentContext groupAgentContext, String clarifyingMessage) {
        CallMessage callMessage = new CallMessage();
        callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
        callMessage.setText(clarifyingMessage);
        callMessage.setType(MessageTypeEnum.CHAT);
        callMessage.setAgentId(groupAgentContext.getAgentConfig().getId());
        callMessage.setId(groupAgentContext.getRequestId());
        callMessage.setTime(new Date());
        callMessage.setFinished(true);

        AgentOutputDto agentOutputDto = new AgentOutputDto();
        agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.MESSAGE);
        agentOutputDto.setRequestId(groupAgentContext.getRequestId());
        agentOutputDto.setData(callMessage);
        agentOutputDto.setCompleted(false);
        return agentOutputDto;
    }

    private void buildAndSendStateMessage(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink, String nodeStatus) {
        if (!ctx.getFlowAgentContext().isDebug()) {
            return;
        }
        Map<String, Object> state = new HashMap<>(ctx.getSessionState());
        state.remove("SANDBOX_ACCESS_KEY");
        Map<String, Object> stateData = new HashMap<>();
        stateData.put("currentNodeId", ctx.getCurrentNodeId());
        stateData.put("nodeStatus", nodeStatus);
        stateData.put("executionPath", ctx.getExecutionPath());
        stateData.put("sessionState", state);
        AgentOutputDto agentOutputDto = new AgentOutputDto();
        agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.STATE);
        agentOutputDto.setRequestId(ctx.getFlowAgentContext().getRequestId());
        agentOutputDto.setData(stateData);
        agentOutputDto.setCompleted(false);
        sink.next(agentOutputDto);
    }

    private void addRoundMessage(FlowLoopContext groupLoopContext, AgentContext agentContext, String message) {
        message = message.replaceAll("\n<div><markdown-custom-process[^>]*>.*?</markdown-custom-process></div>\n\n", "");
        message = message.replaceAll("<markdown-custom-process[^>]*>.*?</markdown-custom-process>", "");
        ChatMessageDto chatMessage = ChatMessageDto.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .type(MessageTypeEnum.CHAT)
                .role(ChatMessageDto.Role.ASSISTANT)
                .senderType(ChatMessageDto.SenderType.AGENT)
                .senderId(agentContext.getAgentConfig().getId().toString())
                .agentId(agentContext.getAgentConfig().getId())
                .text(message)
                .build();
        groupLoopContext.getCurrentMessages().add(chatMessage);
    }

    private void addRetryMessage(FlowLoopContext groupLoopContext, String errorMessage) {
        ChatMessageDto retryMessage = ChatMessageDto.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .type(MessageTypeEnum.CHAT)
                .senderType(ChatMessageDto.SenderType.REMINDER)
                .text(errorMessage)
                .build();
        groupLoopContext.getCurrentMessages().add(retryMessage);
        groupLoopContext.getRetryCount().incrementAndGet();
    }

    /**
     * Add a retry message for agent node-level failures.
     * Does NOT increment the global retryCount — agent failures are tracked by nodeRetryCount.
     */
    private void addNodeRetryMessage(FlowLoopContext groupLoopContext, String errorMessage) {
        ChatMessageDto retryMessage = ChatMessageDto.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .type(MessageTypeEnum.CHAT)
                .senderType(ChatMessageDto.SenderType.REMINDER)
                .text(errorMessage)
                .build();
        groupLoopContext.getCurrentMessages().add(retryMessage);
    }

    private void buildAndSendReasoning(FlowLoopContext ctx, FluxSink<AgentOutputDto> sink, String reasoning) {
        CallMessage callMessage = new CallMessage();
        callMessage.setId(ctx.getFlowAgentContext().getRequestId());
        callMessage.setText(reasoning);
        callMessage.setType(MessageTypeEnum.THINK);
        callMessage.setSenderType(ChatMessageDto.SenderType.AGENT);
        callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
        callMessage.setTime(new Date());
        callMessage.setSenderId(ctx.getFlowAgentContext().getAgentConfig().getId().toString());
        callMessage.setAgentId(ctx.getFlowAgentContext().getAgentConfig().getId());
        AgentOutputDto agentOutputDto = new AgentOutputDto();
        agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.MESSAGE);
        agentOutputDto.setRequestId(ctx.getFlowAgentContext().getRequestId());
        agentOutputDto.setData(callMessage);
        agentOutputDto.setCompleted(false);
        sink.next(agentOutputDto);
    }

    // ======================== Prompt Builders ========================

    /**
     * Build a text representation of the graph for the Dispatcher LLM.
     */
    private String buildGraphDescription(Graph graph) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Nodes\n");
        for (GraphNode node : graph.getNodes()) {
            sb.append("- [").append(node.getType()).append("] ")
                    .append(node.getName())
                    .append(" (id: ").append(node.getId()).append(")");
            if (StringUtils.isNotBlank(node.getAgentId())) {
                sb.append(", agentId: ").append(node.getAgentId());
            }
            if (StringUtils.isNotBlank(node.getDescription())) {
                sb.append(" - ").append(node.getDescription());
            }
            if (node.getType() == NodeType.ROUTER && node.getRouterConfig() != null) {
                RouterNodeConfig rc = node.getRouterConfig();
                sb.append("\n  Branches:");
                if (CollectionUtils.isNotEmpty(rc.getConditions())) {
                    for (RouterCondition cond : rc.getConditions()) {
                        sb.append("\n    - edge ").append(cond.getEdgeId()).append(": ");
                        if (StringUtils.isNotBlank(cond.getTextDescription())) {
                            sb.append(cond.getTextDescription());
                        }
                    }
                }
                if (StringUtils.isNotBlank(rc.getDefaultEdgeId())) {
                    sb.append("\n    - default: edge ").append(rc.getDefaultEdgeId());
                }
            }
            sb.append("\n");
        }

        sb.append("\n### Edges\n");
        if (CollectionUtils.isNotEmpty(graph.getEdges())) {
            for (GraphEdge edge : graph.getEdges()) {
                sb.append("- ").append(edge.getSourceNodeId())
                        .append(" → ").append(edge.getTargetNodeId());
                if (StringUtils.isNotBlank(edge.getLabel())) {
                    sb.append(" (").append(edge.getLabel()).append(")");
                }
                sb.append(" [id: ").append(edge.getId()).append("]\n");
            }
        }

        sb.append("\nStart node: ").append(graph.getStartNodeId()).append("\n");
        return sb.toString();
    }

    /**
     * Build branch list for the Router Evaluator LLM.
     */
    private String buildBranchList(GraphNode node) {
        StringBuilder sb = new StringBuilder();
        RouterNodeConfig routerConfig = node.getRouterConfig();
        if (routerConfig == null || CollectionUtils.isEmpty(routerConfig.getConditions())) {
            return "No branches configured.";
        }

        for (RouterCondition condition : routerConfig.getConditions()) {
            sb.append("- Branch [edgeId: ").append(condition.getEdgeId()).append("]\n");
            if (StringUtils.isNotBlank(condition.getTextDescription())) {
                sb.append("  Description: ").append(condition.getTextDescription()).append("\n");
            }
            sb.append("\n");
        }

        if (StringUtils.isNotBlank(routerConfig.getDefaultEdgeId())) {
            sb.append("- Default branch [edgeId: ").append(routerConfig.getDefaultEdgeId()).append("]\n");
        }
        return sb.toString();
    }

    /**
     * Build a text description of graph variables for the LLM (AgentInputBuilder).
     * Shows variable names, strategies, current values, and descriptions.
     */
    private String buildVariablesDescription(FlowLoopContext ctx) {
        Graph graph = ctx.getFlowGraph();
        if (graph == null || CollectionUtils.isEmpty(graph.getVariables())) {
            return "No graph variables configured.";
        }

        StringBuilder sb = new StringBuilder();
        for (GraphVariable variable : graph.getVariables()) {
            sb.append("- ").append(variable.getName());
            sb.append(" [strategy: ").append(variable.getStrategy() != null ? variable.getStrategy() : "REPLACE").append("]");
            Object currentValue = ctx.getSessionState().get(variable.getName());
            if (currentValue != null) {
                sb.append(", current: ").append(truncateForLog(currentValue));
            } else if (variable.getDefaultValue() != null) {
                sb.append(", default: ").append(truncateForLog(variable.getDefaultValue()));
            } else {
                sb.append(", current: (empty)");
            }
            if (StringUtils.isNotBlank(variable.getDescription())) {
                sb.append(" — ").append(variable.getDescription());
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    /**
     * Build a text description of a node's expected input parameters.
     * Tells the InputBuilder LLM what values to extract from the conversation context.
     */
    private String buildInputArgsDescription(GraphNode node) {
        if (CollectionUtils.isEmpty(node.getInputArgs())) {
            return "No specific input parameters configured for this node.";
        }

        StringBuilder sb = new StringBuilder();
        for (Arg arg : node.getInputArgs()) {
            if (arg.getEnable() != null && !arg.getEnable()) continue; // skip disabled args
            if (arg.getBindValueType() == Arg.BindValueType.Reference) continue;
            sb.append("- ").append(arg.getName());
            if (StringUtils.isNotBlank(arg.getDescription())) {
                sb.append(": ").append(arg.getDescription());
            }
            if (arg.isRequire()) {
                sb.append(" [required]");
            }
            if (StringUtils.isNotBlank(arg.getBindValue())) {
                sb.append(" [default: ").append(arg.getBindValue()).append("]");
            }
            sb.append("\n");
        }
        return !sb.isEmpty() ? sb.toString() : "No specific input parameters configured for this node.";
    }

    private String buildNavigatorContext(FlowLoopContext ctx) {
        StringBuilder sb = new StringBuilder();

        // Summary — long-term conversation context
        if (StringUtils.isNotBlank(ctx.getSummary())) {
            sb.append("## Conversation Summary\n").append(ctx.getSummary()).append("\n\n");
        }

        // Current position in graph
        if (StringUtils.isNotBlank(ctx.getCurrentNodeId())) {
            GraphNode currentNode = findNodeById(ctx.getFlowGraph(), ctx.getCurrentNodeId());
            sb.append("## Current Position\n");
            sb.append("- Node: ").append(ctx.getCurrentNodeId());
            if (currentNode != null) {
                sb.append(" (").append(currentNode.getName()).append(", type: ").append(currentNode.getType()).append(")");
            }
            sb.append("\n");
        }

        // Execution path — which nodes have been visited
        if (!ctx.getExecutionPath().isEmpty()) {
            sb.append("## Execution Path\n");
            for (ExecutionTrace trace : ctx.getExecutionPath()) {
                sb.append("- ").append(trace.getNodeName() != null ? trace.getNodeName() : trace.getNodeId());
                if (StringUtils.isNotBlank(trace.getReasoning())) {
                    sb.append(" (").append(trace.getReasoning()).append(")");
                }
                if (StringUtils.isNotBlank(trace.getResult())) {
                    sb.append(" [").append(trace.getResult()).append("]");
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        // Graph variables — accumulated state for navigation context
        String varsDescription = buildVariablesDescription(ctx);
        if (!"No graph variables configured.".equals(varsDescription)) {
            sb.append("## Graph Variables\n").append(varsDescription).append("\n");
        }

        // Recent interaction history
        appendRecentMessages(ctx, sb);

        // Session state
        appendSessionState(ctx, sb);

        // Current user request
        sb.append("## Current User Request\n").append(ctx.getFlowAgentContext().getMessage()).append("\n");

        return sb.toString();
    }

    private String buildAgentInputBuildContext(FlowLoopContext ctx, GraphNode node) {
        StringBuilder sb = new StringBuilder();

        // 1. Current node info
        sb.append("## Current Node\n");
        sb.append("- Name: ").append(node.getName()).append("\n");
        sb.append("- Description: ").append(node.getDescription() != null ? node.getDescription() : "N/A").append("\n");
        sb.append("- Target agent: ").append(node.getAgentId()).append("\n\n");

        // 2. Planned Flow Path — completed + current + planned next
        sb.append("## Planned Flow Path\n");
        if (!ctx.getExecutionPath().isEmpty()) {
            sb.append("Completed:\n");
            for (ExecutionTrace trace : ctx.getExecutionPath()) {
                String name = trace.getNodeName() != null ? trace.getNodeName() : trace.getNodeId();
                sb.append("  - ").append(name);
                if (StringUtils.isNotBlank(trace.getReasoning())) {
                    sb.append(" (").append(trace.getReasoning()).append(")");
                }
                sb.append("\n");
            }
        }
        sb.append("► Current: ").append(node.getName()).append(" — this node is next in the planned path, execute it by default\n");
        List<String> nextNodeNames = resolveNextNodeNames(ctx.getFlowGraph(), node);
        if (!nextNodeNames.isEmpty()) {
            sb.append("Planned Next: ").append(String.join(" → ", nextNodeNames)).append("\n");
        } else {
            sb.append("Planned Next: (this is the last node before END)\n");
        }
        sb.append("\n");

        // 3. Summary
        if (StringUtils.isNotBlank(ctx.getSummary())) {
            sb.append("## Conversation Summary\n").append(ctx.getSummary()).append("\n\n");
        }

        // 4. Recent interaction history
        appendRecentMessages(ctx, sb);

        // 5. User's Original Task — placed after dynamic content so cached prefix (sections 1-4) remains stable across turns
        sb.append("## User's Original Task\n");
        sb.append("The following is the user's original request that initiated this flow. ");
        sb.append("The navigator has already analyzed it and routed to the current node. ");
        sb.append("Use it as BACKGROUND CONTEXT for building the agent input, NOT as a new routing request.\n");
        sb.append(ctx.getFlowAgentContext().getMessage()).append("\n\n");

        // 6. Session state
        appendSessionState(ctx, sb);

        // 7. Available nodes — moved to end, lower attention weight, only for redirect
        sb.append("## Available Nodes (for redirectNodeId — use only when current node is completely unable to handle the task)\n");
        for (GraphNode n : ctx.getFlowGraph().getNodes()) {
            if (n.getType() != NodeType.START && n.getType() != NodeType.END) {
                sb.append("- ").append(n.getName())
                        .append(" [id: ").append(n.getId()).append("] - ")
                        .append(n.getDescription() != null ? n.getDescription() : "").append("\n");
            }
        }
        sb.append("\n");

        return sb.toString();
    }

    private String buildRouterEvaluationContext(FlowLoopContext ctx, GraphNode node) {
        StringBuilder sb = new StringBuilder();

        // Summary
        if (StringUtils.isNotBlank(ctx.getSummary())) {
            sb.append("## Conversation Summary\n").append(ctx.getSummary()).append("\n\n");
        }

        // Execution path
        sb.append("## Execution Path\n");
        for (ExecutionTrace trace : ctx.getExecutionPath()) {
            sb.append("- ").append(trace.getNodeName() != null ? trace.getNodeName() : trace.getNodeId());
            if (StringUtils.isNotBlank(trace.getReasoning())) {
                sb.append(" (").append(trace.getReasoning()).append(")");
            }
            sb.append("\n");
        }
        sb.append("\n");

        // Recent interaction history
        appendRecentMessages(ctx, sb);

        // Session state — critical for router evaluation
        appendSessionState(ctx, sb);

        // Current user request
        sb.append("## Current User Request\n").append(ctx.getFlowAgentContext().getMessage()).append("\n");

        return sb.toString();
    }

    private String buildWorkflowInputExtractContext(FlowLoopContext ctx, GraphNode node) {
        StringBuilder sb = new StringBuilder();

        // Current workflow node info
        sb.append("## Current Workflow Node\n");
        sb.append("- Name: ").append(node.getName()).append("\n");
        sb.append("- Description: ").append(node.getDescription() != null ? node.getDescription() : "N/A").append("\n\n");

        // Execution path — previous node results
        sb.append("## Execution Path\n");
        for (ExecutionTrace trace : ctx.getExecutionPath()) {
            sb.append("- ").append(trace.getNodeName() != null ? trace.getNodeName() : trace.getNodeId());
            if (StringUtils.isNotBlank(trace.getReasoning())) {
                sb.append(" (").append(trace.getReasoning()).append(")");
            }
            sb.append("\n");
        }
        sb.append("\n");

        // Summary
        if (StringUtils.isNotBlank(ctx.getSummary())) {
            sb.append("## Conversation Summary\n").append(ctx.getSummary()).append("\n\n");
        }

        // Recent interaction history
        appendRecentMessages(ctx, sb);

        // Session state — includes previous node results
        appendSessionState(ctx, sb);

        // Current user request
        sb.append("## Current User Request\n").append(ctx.getFlowAgentContext().getMessage()).append("\n");

        return sb.toString();
    }

    private List<Arg> buildWorkflowInputExtractArgs(List<Arg> inputArgsToExtract) {
        return List.of(
                Arg.builder()
                        .name("params")
                        .description("Extracted parameter values for the workflow node. Each key must match a configured input parameter name.")
                        .dataType(DataTypeEnum.Object)
                        .subArgs(inputArgsToExtract)
                        .require(true)
                        .build()
        );
    }

    /**
     * Build the system prompt for a dispatched agent node.
     */
    private String buildNodeAgentPrompt(FlowLoopContext ctx, GraphNode node,
                                        AgentInputBuilderDecision decision, String inputMessage) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("<system-reminder>\n");

        // Peer agents
        prompt.append("You are an agent in a flow graph. The following peer agents are available — use @agent:${agentName} to request assistance:\n");
        ctx.getFlowAgentConfigs().stream()
                .filter(a -> !a.getId().toString().equals(node.getAgentId()))
                .forEach(agent -> prompt.append("- ").append(agent.getName())
                        .append("[agentId:").append(agent.getId()).append("] - ")
                        .append(agent.getDescription()).append("\n"));

        // Variables from input builder decision
        if (decision.getVariables() != null && !decision.getVariables().isEmpty()) {
            prompt.append("\n<context-variables>\n");
            decision.getVariables().forEach((k, v) -> prompt.append(k).append(": ").append(v).append("\n"));
            prompt.append("</context-variables>\n");
        }

        // Session state
        Map<String, Object> sessionState = ctx.getSessionState();
        if (sessionState != null && !sessionState.isEmpty()) {
            prompt.append("\n<session-state>\n");
            sessionState.forEach((k, v) -> {
                if (!filteredKey(k)) {
                    prompt.append(k).append(": ").append(v).append("\n");
                }
            });
            prompt.append("</session-state>\n");
        }

        prompt.append("</system-reminder>\n\n");

        AgentNodeConfigDto agentNodeConfigDto = (AgentNodeConfigDto) node.getTargetConfig();
        String extraPrompt = agentNodeConfigDto.getExtraPrompt();// 补充提示词
        if (StringUtils.isNotBlank(extraPrompt)) {
            prompt.append("<extra-instruction>");
            prompt.append(extraPrompt);
            prompt.append("</extra-instruction>\n\n");
        }

        prompt.append(inputMessage);
        return prompt.toString();
    }

    private String resolveInputMessage(FlowLoopContext ctx, String inputMessage) {
        if (StringUtils.isNotBlank(inputMessage)) {
            if (inputMessage.contains("{{") && inputMessage.contains("}}")) {
                for (String messageId : extractMessageIds(inputMessage)) {
                    String referencedText = ctx.getCurrentMessages().stream()
                            .filter(m -> m.getId().equals(messageId))
                            .findFirst()
                            .map(ChatMessageDto::getText)
                            .orElse("");
                    if (inputMessage.startsWith("{{") || inputMessage.endsWith("}}")) {
                        inputMessage = inputMessage.replace("{{" + messageId + "}}", referencedText);
                    } else {
                        inputMessage = inputMessage.replace("{{" + messageId + "}}", "<referencedText>" + referencedText + "</referencedText>");
                    }
                }
            }
        } else {
            inputMessage = ctx.getFlowAgentContext().getMessage();
        }
        return inputMessage;
    }

    /**
     * Append recent conversation messages for LLM context.
     */
    private void appendRecentMessages(FlowLoopContext ctx, StringBuilder sb) {
        List<ChatMessageDto> messages = ctx.getCurrentMessagesForContext();
        if (CollectionUtils.isEmpty(messages)) return;

        Long controllerAgentId = ctx.getFlowAgentContext().getAgentConfig().getId();
        Map<Long, AgentConfigDto> agentMap = ctx.getFlowAgentConfigs().stream()
                .collect(Collectors.toMap(AgentConfigDto::getId, a -> a, (a, b) -> a));

        // Find the last user message index (current request boundary)
        int lastUserIdx = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i).getSenderType() == ChatMessageDto.SenderType.USER) {
                lastUserIdx = i;
                break;
            }
        }

        // Historical messages (before current user request) — past turns
        if (lastUserIdx > 0) {
            sb.append("## Recent History (previous turns — for context, do NOT re-act on these)\n");
            int start = Math.max(0, lastUserIdx - 10);
            for (int i = start; i < lastUserIdx; i++) {
                appendMessageLine(messages.get(i), controllerAgentId, agentMap, sb);
            }
            sb.append("\n");
        }

        // Current turn messages (from the current user request onwards)
        if (lastUserIdx >= 0) {
            sb.append("## Current Turn\n");
            for (int i = lastUserIdx; i < messages.size(); i++) {
                appendMessageLine(messages.get(i), controllerAgentId, agentMap, sb);
            }
            sb.append("\n");
        }
    }

    private void appendMessageLine(ChatMessageDto msg, Long controllerAgentId,
                                   Map<Long, AgentConfigDto> agentMap, StringBuilder sb) {
        if (msg.getSenderType() == ChatMessageDto.SenderType.USER) {
            sb.append("- [USER, messageId:").append(msg.getId()).append("] ")
                    .append(truncateContent(msg.getText())).append("\n");
        } else if (msg.getSenderType() == ChatMessageDto.SenderType.AGENT
                && !controllerAgentId.equals(msg.getAgentId())) {
            AgentConfigDto cfg = agentMap.get(msg.getAgentId());
            String agentName = cfg != null ? cfg.getName() : "Agent";
            sb.append("- [").append(agentName).append(", messageId:").append(msg.getId()).append("] ")
                    .append(truncateContent(msg.getText())).append("\n");
        } else if (msg.getSenderType() == ChatMessageDto.SenderType.REMINDER) {
            sb.append("- [SYSTEM] ").append(truncateContent(msg.getText())).append("\n");
        }
    }

    private void appendSessionState(FlowLoopContext ctx, StringBuilder sb) {
        if (ctx.getSessionState() == null || ctx.getSessionState().isEmpty()) return;
        boolean hasState = ctx.getSessionState().entrySet().stream().anyMatch(e -> !filteredKey(e.getKey()));
        if (!hasState) return;
        sb.append("## Session State\n");
        for (Map.Entry<String, Object> entry : ctx.getSessionState().entrySet()) {
            if (!filteredKey(entry.getKey())) {
                sb.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append("\n");
            }
        }
        sb.append("\n");
    }

    // ======================== Arg Definitions ========================

    private List<Arg> buildNavigatorArgs() {
        return List.of(
                Arg.builder()
                        .name("targetNodeId")
                        .description("The ID of the graph node to dispatch execution to. Must be a valid executable node ID (AGENT, WORKFLOW, ASK_QUESTION, or ROUTER). Leave empty when using continueCurrent or clarifyingMessage.")
                        .dataType(DataTypeEnum.String)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("reasoning")
                        .description("Brief analysis of the user's intent and why this node was chosen for dispatch.")
                        .dataType(DataTypeEnum.String)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("continueCurrent")
                        .description("Set to true when the user is clearly continuing the same workflow from the current position (e.g. answering a question, providing requested details).")
                        .dataType(DataTypeEnum.Boolean)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("clarifyingMessage")
                        .description("Used when targetNodeId is empty and continueCurrent is false. For unclear intent: ask a targeted clarifying question. For greetings: respond briefly and guide toward what the graph handles.")
                        .dataType(DataTypeEnum.String)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("variableUpdates")
                        .description("Extract information explicitly mentioned by the user that matches a configured graph variable. Each entry has name (variable name) and value. Only include values clearly present in the user message — do not guess.")
                        .dataType(DataTypeEnum.Array_Object)
                        .subArgs(List.of(
                                Arg.builder().name("name").description("Variable name (must match a configured graph variable)").dataType(DataTypeEnum.String).require(true).build(),
                                Arg.builder().name("value").description("The value explicitly mentioned by the user").dataType(DataTypeEnum.String).require(true).build()
                        ))
                        .require(false)
                        .build()
        );
    }

    private List<Arg> buildAgentInputBuilderArgs(List<Arg> inputArgs) {
        if (inputArgs != null) {
            inputArgs = inputArgs.stream().filter(arg -> (arg.getEnable() == null || arg.getEnable()) && arg.getBindValueType() == Arg.BindValueType.Input).toList();
        }
        return List.of(
                Arg.builder()
                        .name("inputMessage")
                        .description("The input message for the target agent. Must be self-contained with all context from previous nodes. Use {{messageId}} to reference previous messages.")
                        .dataType(DataTypeEnum.String)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("variables")
                        .description("Known context variables extracted for the target agent. Set null when none available.")
                        .dataType(DataTypeEnum.Object)
                        .subArgs(inputArgs)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("stateUpdate")
                        .description("Session state updates from this decision. Set null when nothing to update.")
                        .dataType(DataTypeEnum.Object)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("skip")
                        .description("Set to true to skip the current node (e.g. previous node failed and this node depends on it).")
                        .dataType(DataTypeEnum.Boolean)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("end")
                        .description("Set to true to end the entire flow early.")
                        .dataType(DataTypeEnum.Boolean)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("redirectNodeId")
                        .description("Set this when the current agent node is NOT the right one for the user's actual intent. Provide the correct node ID to redirect to.")
                        .dataType(DataTypeEnum.String)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("variableUpdates")
                        .description("Structured variable updates for graph variables. Each entry has name (variable name) and value (new value). The system applies REPLACE or APPEND based on each variable's configured strategy. Use this to extract and persist information discovered during agent execution.")
                        .dataType(DataTypeEnum.Array_Object)
                        .subArgs(List.of(
                                Arg.builder().name("name").description("Variable name (must match a configured graph variable)").dataType(DataTypeEnum.String).require(true).build(),
                                Arg.builder().name("value").description("New value for the variable").dataType(DataTypeEnum.String).require(true).build()
                        ))
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("clarifyingMessage")
                        .description("Question to ask the user when more info is needed before this node can proceed.")
                        .dataType(DataTypeEnum.String)
                        .require(false)
                        .build()
        );
    }

    private List<Arg> buildRouterEvaluatorArgs() {
        return List.of(
                Arg.builder()
                        .name("selectedEdgeId")
                        .description("The ID of the branch/edge to follow. Must be one of the available branch IDs listed above.")
                        .dataType(DataTypeEnum.String)
                        .require(true)
                        .build(),
                Arg.builder()
                        .name("reasoning")
                        .description("Brief explanation of why this branch was selected.")
                        .dataType(DataTypeEnum.String)
                        .require(false)
                        .build()
        );
    }

    // ======================== Context Building ========================

    private FlowLoopContext buildFlowLoopContext(AgentContext agentContext) {
        FlowLoopContext ctx = new FlowLoopContext();
        ctx.setResponseBuilder(new StringBuilder());
        ctx.setReasoningBuilder(new StringBuilder());
        ctx.setFlowAgentContext(agentContext);
        ctx.setFlowAgentConfigs(resolveGroupAgentConfigs(agentContext));

        // Merge conversation variables into session state
        Map<String, Object> variableParams = agentContext.getVariableParams();
        Map<String, Object> variables = agentContext.getConversation().getVariables();
        if (variables != null) {
            variables.forEach((key, value) -> {
                if (!GlobalVariableEnum.isSystemVariable(key)) {
                    variableParams.put(key, value);
                }
            });
        }
        ctx.setSessionState(variableParams);

        // Build the flow graph from config
        ctx.setFlowGraph(buildGraphFromConfig(agentContext));

        // Restore graph state from session (for cross-turn continuation)
        Object currentNodeObj = variableParams.get(GRAPH_CURRENT_NODE_KEY);
        ctx.setCurrentNodeId(currentNodeObj instanceof String s ? s : null);

        ctx.setExecutionPath(parseExecutionPath(variableParams));
        //获取ctx.getExecutionPath()最后10条数据
        ctx.setExecutionPath(new ArrayList<>(ctx.getExecutionPath().subList(Math.max(ctx.getExecutionPath().size() - 10, 0), ctx.getExecutionPath().size())));

        Object waitingForUserAnswer = variableParams.get(ASK_QUESTION_WAITING_ANSWER_KEY);
        ctx.setWaitingForUserAnswer(waitingForUserAnswer != null && (boolean) waitingForUserAnswer);

        ctx.setSummary(agentContext.getConversation().getSummary());
        ctx.setCurrentMessages(parseAndInitMessages(agentContext, variableParams));
        ctx.setRetryCount(new AtomicInteger(0));
        ctx.setNodeRetryCount(0);
        return ctx;
    }

    /**
     * Build the flow graph from agent configuration.
     * <p>
     * The graph should be parsed from the agent's component configs or a dedicated config source.
     * Expected structure:
     * - Graph with nodes (START, AGENT, ROUTER, END) and edges
     * - AGENT nodes reference agent IDs from the agentComponentConfigs
     * - ROUTER nodes have RouterNodeConfig with conditions and default edge
     * - Edges connect source nodes to target nodes
     */
    private Graph buildGraphFromConfig(AgentContext agentContext) {
        List<WorkflowNodeDto> workflowNodes = getAgentWorkflowNodes(agentContext);
        WorkflowNodeDto workflowStartDto = workflowNodes.stream().filter(node -> node.getType() == WorkflowNodeConfig.NodeType.Start).findFirst().orElse(null);
        assert workflowStartDto != null;

        Graph graph = new Graph();
        graph.setId(agentContext.getAgentConfig().getId().toString());
        graph.setName(agentContext.getAgentConfig().getName());
        graph.setStartNodeId(workflowStartDto.getId().toString());

        AgentComponentConfigDto agentComponentConfigDto = agentContext.getAgentConfig().getAgentComponentConfigList().stream().filter(componentConfigDto -> componentConfigDto.getType() == AgentComponentConfig.Type.Variable).findFirst().orElse(null);
        if (agentComponentConfigDto != null && agentComponentConfigDto.getBindConfig() instanceof VariableConfigDto variableConfig) {
            List<GraphVariable> graphVariables = variableConfig.getVariables().stream().map(arg -> {
                if (isSystemReservedKey(arg.getName())) {
                    return null;
                }
                if (arg.getInputType() != Arg.InputTypeEnum.AutoRecognition) {
                    return null;
                }
                GraphVariable variable = new GraphVariable();
                variable.setName(arg.getName());
                variable.setStrategy(arg.getUpdateStrategy() == null ? UpdateStrategy.REPLACE : UpdateStrategy.valueOf(arg.getUpdateStrategy().name()));
                variable.setDescription(arg.getDescription());
                variable.setDefaultValue(arg.getBindValue());
                return variable;
            }).filter(Objects::nonNull).collect(Collectors.toList());
            graph.setVariables(graphVariables);
        } else {
            graph.setVariables(new ArrayList<>());
        }

        List<GraphNode> graphNodes = new ArrayList<>();
        List<GraphEdge> graphEdges = new ArrayList<>();
        graph.setNodes(graphNodes);
        graph.setEdges(graphEdges);
        for (WorkflowNodeDto node : workflowNodes) {
            if (node.getType() != WorkflowNodeConfig.NodeType.End && CollectionUtils.isEmpty(node.getNextNodeIds())) {
                continue;
            }
            if (node.getType() == WorkflowNodeConfig.NodeType.Start) {
                GraphNode graphNode = new GraphNode();
                graphNode.setId(node.getId().toString());
                graphNode.setType(NodeType.START);
                graphNode.setName(node.getName());
                graphNode.setDescription(node.getDescription());
                graphNodes.add(graphNode);
                graphEdges.add(buildEdge(buildEdgeId(node.getId().toString(), node.getNextNodeIds()), node.getId().toString(), node.getNextNodeIds().get(0).toString(), node.getName() + "_edge"));
            }
            if (node.getType() == WorkflowNodeConfig.NodeType.End) {
                GraphNode graphNode = new GraphNode();
                graphNode.setId(node.getId().toString());
                graphNode.setType(NodeType.END);
                graphNode.setName(node.getName());
                graphNode.setDescription(node.getDescription());
                graphNodes.add(graphNode);
            }
            if (node.getType() == WorkflowNodeConfig.NodeType.Agent) {
                AgentNodeConfigDto agentNodeConfigDto = (AgentNodeConfigDto) node.getNodeConfig();
                AgentConfigDto agentConfigDto = agentNodeConfigDto.getAgentConfigDto();
                GraphNode graphNode = new GraphNode();
                graphNode.setId(node.getId().toString());
                graphNode.setType(NodeType.AGENT);
                graphNode.setName(agentConfigDto.getName());
                graphNode.setDescription(agentConfigDto.getDescription());
                graphNode.setAgentId(agentConfigDto.getId().toString());
                graphNode.setTargetConfig(agentNodeConfigDto);
                graphNode.setExceptionHandleConfig(agentNodeConfigDto.getExceptionHandleConfig());
                graphNodes.add(graphNode);
                String edgeId = buildEdgeId(node.getId().toString(), node.getNextNodeIds());
                graphEdges.add(buildEdge(edgeId, node.getId().toString(), node.getNextNodeIds().get(0).toString(), node.getName() + "_edge"));
                if (agentNodeConfigDto.getExceptionHandleConfig() != null && agentNodeConfigDto.getExceptionHandleConfig().getExceptionHandleNodeIds() != null && !agentNodeConfigDto.getExceptionHandleConfig().getExceptionHandleNodeIds().isEmpty()) {
                    String edgeId0 = buildEdgeId(node.getId().toString(), agentNodeConfigDto.getExceptionHandleConfig().getExceptionHandleNodeIds());
                    if (!edgeId.equals(edgeId0)) {
                        graphEdges.add(buildEdge(edgeId0, node.getId().toString(), agentNodeConfigDto.getExceptionHandleConfig().getExceptionHandleNodeIds().get(0).toString(), node.getName() + "_edge"));
                    }
                }
            }
            if (node.getType() == WorkflowNodeConfig.NodeType.IntentRecognition) {
                IntentRecognitionNodeConfigDto nodeConfigDto = (IntentRecognitionNodeConfigDto) node.getNodeConfig();
                GraphNode graphNode = new GraphNode();
                graphNode.setId(node.getId().toString());
                graphNode.setType(NodeType.ROUTER);
                graphNode.setName(node.getName());
                graphNode.setDescription(node.getDescription());
                RouterNodeConfig routerNodeConfig = new RouterNodeConfig();
                graphNode.setRouterConfig(routerNodeConfig);
                routerNodeConfig.setDescription("Determine user intent");
                List<RouterCondition> routerConditions = nodeConfigDto.getIntentConfigs().stream().map(intentConfig -> {
                    graphEdges.add(buildEdge(buildEdgeId(node.getId().toString(), intentConfig.getNextNodeIds()), node.getId().toString(), intentConfig.getNextNodeIds().get(0).toString(), intentConfig.getName() + "_edge"));
                    if (intentConfig.getIntentType() == IntentRecognitionNodeConfigDto.IntentTypeEnum.OTHER) {
                        routerNodeConfig.setDefaultEdgeId(buildEdgeId(node.getId().toString(), intentConfig.getNextNodeIds()));
                        return null;
                    } else {
                        RouterCondition condition = new RouterCondition();
                        condition.setEdgeId(buildEdgeId(node.getId().toString(), intentConfig.getNextNodeIds()));
                        condition.setConditionType(intentConfig.getConditionType());
                        condition.setConditionArgs(intentConfig.getConditionArgs());
                        condition.setTextDescription(intentConfig.getIntent());
                        return condition;
                    }
                }).filter(Objects::nonNull).toList();
                routerNodeConfig.setConditions(routerConditions);
                graphNodes.add(graphNode);
            }
            if (node.getType() == WorkflowNodeConfig.NodeType.Workflow) {
                WorkflowAsNodeConfigDto workflowAsNodeConfig = (WorkflowAsNodeConfigDto) node.getNodeConfig();
                WorkflowConfigDto workflowConfig = workflowAsNodeConfig.getWorkflowConfig();
                GraphNode graphNode = new GraphNode();
                graphNode.setId(node.getId().toString());
                graphNode.setType(NodeType.WORKFLOW);
                graphNode.setName(workflowConfig.getName());
                graphNode.setDescription(workflowConfig.getDescription());
                graphNode.setInputArgs(node.getNodeConfig().getInputArgs());
                graphNode.setTargetConfig(workflowConfig);
                graphNode.setExceptionHandleConfig(workflowAsNodeConfig.getExceptionHandleConfig());
                graphNodes.add(graphNode);
                String edgeId = buildEdgeId(node.getId().toString(), node.getNextNodeIds());
                graphEdges.add(buildEdge(edgeId, node.getId().toString(), node.getNextNodeIds().get(0).toString(), node.getName() + "_edge"));
                if (workflowAsNodeConfig.getExceptionHandleConfig() != null && workflowAsNodeConfig.getExceptionHandleConfig().getExceptionHandleNodeIds() != null && !workflowAsNodeConfig.getExceptionHandleConfig().getExceptionHandleNodeIds().isEmpty()) {
                    String edgeId0 = buildEdgeId(node.getId().toString(), workflowAsNodeConfig.getExceptionHandleConfig().getExceptionHandleNodeIds());
                    if (!edgeId.equals(edgeId0)) {
                        graphEdges.add(buildEdge(edgeId0, node.getId().toString(), workflowAsNodeConfig.getExceptionHandleConfig().getExceptionHandleNodeIds().get(0).toString(), node.getName() + "_edge"));
                    }
                }
            }
            if (node.getType() == WorkflowNodeConfig.NodeType.QA) {
                QaNodeConfigDto nodeConfigDto = (QaNodeConfigDto) node.getNodeConfig();
                GraphNode graphNode = new GraphNode();
                graphNode.setId(node.getId().toString());
                graphNode.setType(NodeType.ASK_QUESTION);
                graphNode.setName(node.getName());
                graphNode.setDescription(node.getDescription());
                graphNode.setInputArgs(node.getNodeConfig().getInputArgs());
                graphNode.setTargetConfig(nodeConfigDto);
                graphNodes.add(graphNode);
                if (nodeConfigDto.getAnswerType() == QaNodeConfigDto.AnswerTypeEnum.SELECT && CollectionUtils.isNotEmpty(nodeConfigDto.getOptions())) {
                    nodeConfigDto.getOptions().forEach(optionConfigDto -> {
                        if (CollectionUtils.isEmpty(optionConfigDto.getNextNodeIds())) {
                            String edgeId = buildEdgeId(node.getId().toString(), optionConfigDto.getNextNodeIds());
                            graphEdges.add(buildEdge(edgeId, node.getId().toString(), optionConfigDto.getNextNodeIds().get(0).toString(), optionConfigDto.getContent() + "_edge"));
                        }
                    });
                } else {
                    graphEdges.add(buildEdge(buildEdgeId(node.getId().toString(), node.getNextNodeIds()), node.getId().toString(), node.getNextNodeIds().get(0).toString(), node.getName() + "_edge"));
                }
            }
        }

        return graph;
    }

    private String buildEdgeId(String nodeId, List<Long> nextNodeIds) {
        return nodeId + "_" + nextNodeIds.get(0);
    }

    @SuppressWarnings("unused")
    private GraphEdge buildEdge(String id, String sourceNodeId, String targetNodeId, String label) {
        GraphEdge edge = new GraphEdge();
        edge.setId(id);
        edge.setSourceNodeId(sourceNodeId);
        edge.setTargetNodeId(targetNodeId);
        edge.setLabel(label);
        return edge;
    }

    private List<AgentConfigDto> resolveGroupAgentConfigs(AgentContext agentContext) {
        List<WorkflowNodeDto> nodes = getAgentWorkflowNodes(agentContext);
        List<AgentConfigDto> groupAgentConfigs = new ArrayList<>();
        for (WorkflowNodeDto workflowNode : nodes) {
            if (workflowNode.getType() != WorkflowNodeConfig.NodeType.Agent) {
                continue;
            }
            if (workflowNode.getNodeConfig() instanceof AgentNodeConfigDto agentNodeConfig && agentNodeConfig.getAgentConfigDto() != null) {
                agentNodeConfig.getAgentConfigDto().setEnableAskQuestion(agentContext.getAgentConfig().getEnableAskQuestion());
                agentNodeConfig.getAgentConfigDto().setEnableVersionControl(agentContext.getAgentConfig().getEnableVersionControl());
                groupAgentConfigs.add(agentNodeConfig.getAgentConfigDto());
                appendSkillsAndSubagentsPrompt(agentNodeConfig.getAgentConfigDto());
            }
        }
        return groupAgentConfigs;
    }

    private List<WorkflowNodeDto> getAgentWorkflowNodes(AgentContext agentContext) {
        AgentComponentConfigDto componentConfigDto = agentContext.getAgentConfig().getAgentComponentConfigList().stream().
                filter(config -> config.getType() == AgentComponentConfig.Type.Workflow).findFirst().orElse(null);
        assert componentConfigDto != null;
        WorkflowConfigDto workflowConfigDto = (WorkflowConfigDto) componentConfigDto.getTargetConfig();
        return workflowConfigDto.getNodes();
    }

    private void appendSkillsAndSubagentsPrompt(AgentConfigDto agentConfig) {
        if (AgentConfigDto.AgentType.TaskAgent.name().equals(agentConfig.getType())) {
            List<String> skillAndSubagentNames = new ArrayList<>();
            List<String> skillNames = agentConfig.getAgentComponentConfigList().stream().filter(config -> config.getType() == AgentComponentConfig.Type.Skill).map(AgentComponentConfigDto::getName).toList();
            List<String> subagentNames = agentConfig.getAgentComponentConfigList().stream().filter(config -> config.getType() == AgentComponentConfig.Type.SubAgent).map(AgentComponentConfigDto::getName).toList();
            skillAndSubagentNames.addAll(subagentNames);
            skillAndSubagentNames.addAll(skillNames);
            if (!skillAndSubagentNames.isEmpty()) {
                String join = Joiner.on(",").join(skillAndSubagentNames);
                String reminder = "\n<reminder>You are authorized to use only the skills or subagents: " + join + ", do not use others unless the user mentions them</reminder>";
                agentConfig.setSystemPrompt(agentConfig.getSystemPrompt() + reminder);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private List<ExecutionTrace> parseExecutionPath(Map<String, Object> variableParams) {
        Object pathObj = variableParams.get(GRAPH_EXECUTION_PATH_KEY);
        List<ExecutionTrace> executionPath;
        if (pathObj instanceof List<?> list) {
            executionPath = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof ExecutionTrace trace) {
                    executionPath.add(trace);
                } else if (item instanceof Map<?, ?> map) {
                    ExecutionTrace trace = JSONObject.from(map).to(ExecutionTrace.class);
                    if (trace != null && trace.getNodeId() != null) {
                        executionPath.add(trace);
                    }
                } else if (item instanceof String s) {
                    // Backward compatibility: old format was List<String> of nodeIds
                    executionPath.add(new ExecutionTrace(s, s, null, null));
                }
            }
        } else {
            executionPath = new ArrayList<>();
        }
        variableParams.put(GRAPH_EXECUTION_PATH_KEY, executionPath);
        return executionPath;
    }

    private List<ChatMessageDto> parseAndInitMessages(AgentContext agentContext, Map<String, Object> variableParams) {
        Object contextMessagesObj = variableParams.get("flowLoopContextMessages");
        List<ChatMessageDto> roundMessages;
        if (contextMessagesObj instanceof List<?> list) {
            roundMessages = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof ChatMessageDto dto) {
                    roundMessages.add(dto);
                } else if (item instanceof Map<?, ?> map) {
                    roundMessages.add(JSONObject.from(map).to(ChatMessageDto.class));
                }
            }
        } else {
            roundMessages = new ArrayList<>();
        }
        variableParams.put("flowLoopContextMessages", roundMessages);

        String userPrompt = AgentExecutor.buildUserMessage(agentContext);
        ChatMessageDto userMessage = ChatMessageDto.builder()
                .id(agentContext.getRequestId())
                .role(ChatMessageDto.Role.USER)
                .senderType(ChatMessageDto.SenderType.USER)
                .type(MessageTypeEnum.CHAT)
                .text(userPrompt)
                .time(new Date())
                .build();
        roundMessages.add(userMessage);
        return roundMessages;
    }

    // ======================== Model Context ========================

    private ModelContext buildModelContext(AgentContext agentContext, String systemPrompt,
                                           String userPrompt, List<Arg> outputArgs) {
        agentContext.setContextMessages(new ArrayList<>());
        if (agentContext.getAgentConfig().getModelComponentConfig().getTargetConfig() == null) {
            throw new BizException("Incomplete agent model configuration. Please check if the associated model has been deleted.");
        }
        ModelConfigDto modelConfigDto = (ModelConfigDto) agentContext.getAgentConfig().getModelComponentConfig().getTargetConfig();
        ModelBindConfigDto modelBindConfigDto = (ModelBindConfigDto) agentContext.getAgentConfig().getModelComponentConfig().getBindConfig();

        ModelContext modelContext = new ModelContext();
        modelContext.setRequestId(agentContext.getRequestId());
        modelContext.setAgentContext(agentContext);
        modelContext.setConversationId(agentContext.getConversationId());
        modelContext.setModelConfig(modelConfigDto);

        ModelCallConfigDto modelCallConfigDto = new ModelCallConfigDto();
        modelContext.setModelCallConfig(modelCallConfigDto);
        modelCallConfigDto.setSystemPrompt(systemPrompt);
        modelCallConfigDto.setUserPrompt(userPrompt);
        modelCallConfigDto.setChatRound(0);
        modelCallConfigDto.setStreamCall(true);
        modelCallConfigDto.setOutputArgs(outputArgs);
        modelCallConfigDto.setOutputType(OutputTypeEnum.JSON);
        modelCallConfigDto.setMaxTokens(modelBindConfigDto.getMaxTokens());
        modelCallConfigDto.setTemperature(modelBindConfigDto.getTemperature());
        modelCallConfigDto.setTopP(modelBindConfigDto.getTopP());

        modelContext.setTraceContext(agentContext.getTraceContext().next(
                TraceContext.TraceTargetType.Model, modelContext.getModelConfig().getId().toString(),
                modelContext.getModelConfig().getModel(), modelContext.getModelConfig().getName(), null,
                agentContext.isDefaultModelChanged() ? agentContext.getUserId() : null));
        return modelContext;
    }

    // ======================== Utility ========================

    private AgentConfigDto findAgentConfig(FlowLoopContext ctx, String agentId) {
        if (StringUtils.isBlank(agentId)) return null;
        return ctx.getFlowAgentConfigs().stream()
                .filter(a -> a.getId().toString().equals(agentId))
                .findFirst().orElse(null);
    }

    private GraphNode findNodeById(Graph graph, String nodeId) {
        if (graph == null || StringUtils.isBlank(nodeId) || CollectionUtils.isEmpty(graph.getNodes())) return null;
        return graph.getNodes().stream()
                .filter(n -> n.getId().equals(nodeId))
                .findFirst().orElse(null);
    }

    /**
     * Find or create a synthetic graph node for an @mentioned agent.
     * Used for the @mention shortcut to bypass the dispatcher.
     */
    private GraphNode findOrCreateAgentNode(Graph graph, AgentConfigDto agent) {
        // First, try to find an existing AGENT node with this agentId
        if (graph != null && CollectionUtils.isNotEmpty(graph.getNodes())) {
            GraphNode existing = graph.getNodes().stream()
                    .filter(n -> n.getType() == NodeType.AGENT && agent.getId().toString().equals(n.getAgentId()))
                    .findFirst().orElse(null);
            if (existing != null) return existing;
        }

        // Create a synthetic node for direct dispatch
        GraphNode syntheticNode = new GraphNode();
        syntheticNode.setId("mention_" + agent.getId());
        syntheticNode.setType(NodeType.AGENT);
        syntheticNode.setName(agent.getName());
        syntheticNode.setDescription("Directly handle user's @agent:" + agent.getName() + " request");
        syntheticNode.setAgentId(agent.getId().toString());
        return syntheticNode;
    }

    private static final Pattern MENTION_PATTERN = Pattern.compile("@agent:(\\S+)");

    private static String stripMention(String message) {
        if (StringUtils.isBlank(message)) return message;
        String stripped = MENTION_PATTERN.matcher(message).replaceFirst("").trim();
        return StringUtils.isBlank(stripped) ? message : stripped;
    }

    private AgentConfigDto resolveMentionedAgent(FlowLoopContext ctx) {
        String message = ctx.getFlowAgentContext().getMessage();
        if (StringUtils.isBlank(message)) return null;

        Matcher matcher = MENTION_PATTERN.matcher(message);
        if (!matcher.find()) return null;

        String mentionedName = matcher.group(1).trim();
        if (mentionedName.isEmpty()) return null;

        for (AgentConfigDto agent : ctx.getFlowAgentConfigs()) {
            if (agent.getName().equalsIgnoreCase(mentionedName)) {
                return agent;
            }
        }

        String mentionedLower = mentionedName.toLowerCase();
        for (AgentConfigDto agent : ctx.getFlowAgentConfigs()) {
            String agentNameLower = agent.getName().toLowerCase();
            if (agentNameLower.contains(mentionedLower) || mentionedLower.contains(agentNameLower)) {
                return agent;
            }
        }

        return null;
    }

    private static boolean filteredKey(String key) {
        return "CHAT_CONTEXT".equals(key) || "AGENT_ID".equals(key)
                || "REQUEST_ID".equals(key) || "AGENT_USER_MSG".equals(key) || "permissionRequests".equals(key)
                || "SANDBOX_ACCESS_KEY".equals(key)
                || GRAPH_CURRENT_NODE_KEY.equals(key) || GRAPH_EXECUTION_PATH_KEY.equals(key) || ASK_QUESTION_WAITING_ANSWER_KEY.equals(key)
                || key.startsWith("_agent") || key.endsWith("ContextMessages");
    }

    /**
     * Check if a variable name is a system-reserved key that must NOT be overwritten by user-defined variables.
     * Protects: graph execution state, agent internal state, conversation infrastructure, and platform globals.
     */
    private static boolean isSystemReservedKey(String key) {
        if (StringUtils.isBlank(key)) return true;
        // System variables defined by the platform (AGENT_ID, REQUEST_ID, CONVERSATION_ID, SYS_USER_ID, etc.)
        if (GlobalVariableEnum.isSystemVariable(key)) return true;
        // Graph execution state, agent internal state, and other infrastructure keys
        return filteredKey(key);
    }

    private static String truncateContent(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_CONTENT_LENGTH) return text;
        int headEnd = safeCutPoint(text, MAX_CONTENT_LENGTH / 2);
        int tailStart = safeCutPoint(text, text.length() - MAX_CONTENT_LENGTH / 2);
        return text.substring(0, headEnd) + "\n...[truncated]...\n"
                + text.substring(tailStart);
    }

    private static int safeCutPoint(String text, int pos) {
        if (pos <= 0) return 0;
        if (pos >= text.length()) return text.length();
        if (Character.isLowSurrogate(text.charAt(pos))) {
            return pos - 1;
        }
        return pos;
    }

    private static int countNodeOccurrences(List<ExecutionTrace> path, String nodeId) {
        return (int) path.stream().filter(t -> nodeId.equals(t.getNodeId())).count();
    }

    /**
     * Add an execution trace entry for a node that is about to be executed.
     */
    private void addExecutionTrace(FlowLoopContext ctx, GraphNode node, String reasoning) {
        ExecutionTrace trace = new ExecutionTrace(
                node.getId(), node.getName(),
                node.getType() != null ? node.getType().name() : null,
                reasoning);
        ctx.getExecutionPath().add(trace);
    }

    /**
     * Add an execution trace entry by nodeId (when GraphNode is not directly available).
     */
    private void addExecutionTrace(FlowLoopContext ctx, String nodeId, String reasoning) {
        GraphNode node = findNodeById(ctx.getFlowGraph(), nodeId);
        String name = (node != null) ? node.getName() : nodeId;
        String type = (node != null && node.getType() != null) ? node.getType().name() : null;
        ctx.getExecutionPath().add(new ExecutionTrace(nodeId, name, type, reasoning));
    }

    public static List<String> extractMessageIds(String text) {
        if (text == null || text.isEmpty()) return Collections.emptyList();
        Matcher matcher = INPUT_MESSAGE_ID_PATTERN.matcher(text);
        List<String> ids = new ArrayList<>();
        while (matcher.find()) {
            ids.add(matcher.group(1));
        }
        return ids;
    }

    // ======================== Inner Classes ========================

    @Data
    public static class FlowLoopContext {

        @JsonPropertyDescription("The agent context")
        private AgentContext flowAgentContext;

        @Schema(description = "The agent flow configs")
        private List<AgentConfigDto> flowAgentConfigs;

        @Schema(description = "The flow graph defined by user configuration")
        private Graph flowGraph;

        @Schema(description = "Current node ID in the graph (execution position)")
        private String currentNodeId;

        @Schema(description = "Ordered execution trace with reasoning (replaces old nodeId-only path)")
        private List<ExecutionTrace> executionPath;

        @JsonPropertyDescription("Whether the agent is waiting for user answer")
        private boolean waitingForUserAnswer;

        @Schema(description = "The session state")
        private Map<String, Object> sessionState;

        @Schema(description = "The summary")
        private String summary;

        @Schema(description = "The history messages")
        private List<ChatMessageDto> currentMessages;

        private AtomicInteger retryCount;
        private int nodeRetryCount;
        private int totalDispatchCount;
        private StringBuilder responseBuilder;
        private StringBuilder reasoningBuilder;

        public List<ChatMessageDto> getCurrentMessagesForContext() {
            if (currentMessages == null || currentMessages.isEmpty()) return currentMessages;
            int maxContextTokens = getMaxContextTokens();
            int totalTokens = 0;
            int trimAnchor = 0;
            boolean exceeded = false;
            for (int i = currentMessages.size() - 1; i >= 0; i--) {
                totalTokens += TikTokensUtil.tikTokensCount(currentMessages.get(i).getText());
                if (trimAnchor == 0 && totalTokens > maxContextTokens / 2) {
                    trimAnchor = i;
                }
                if (totalTokens > maxContextTokens) {
                    exceeded = true;
                    break;
                }
            }
            if (exceeded) {
                List<ChatMessageDto> trimmed = new ArrayList<>(currentMessages.subList(trimAnchor, currentMessages.size()));
                currentMessages.clear();
                currentMessages.addAll(trimmed);
            }
            return currentMessages;
        }

        private int getMaxContextTokens() {
            int maxContextTokens = MAX_MESSAGES_TOKEN_BUDGET;
            if (getFlowAgentContext() != null
                    && getFlowAgentContext().getAgentConfig() != null
                    && getFlowAgentContext().getAgentConfig().getModelComponentConfig() != null
                    && getFlowAgentContext().getAgentConfig().getModelComponentConfig().getTargetConfig() instanceof ModelConfigDto modelConfigDto) {
                maxContextTokens = (int) (modelConfigDto.getMaxContextTokens() * 0.8);
            }
            return maxContextTokens;
        }

        public void updateSessionState(Map<String, Object> variables) {
            if (variables == null) return;
            variables.forEach((key, value) -> {
                if (!GlobalVariableEnum.isSystemVariable(key)) {
                    sessionState.put(key, value);
                }
            });
        }
    }

    // ======================== Graph Model ========================

    /**
     * Node types in the flow graph.
     */
    public enum NodeType {
        START,   // Entry point, auto-advances to next node
        AGENT,   // Dispatches to an agent
        WORKFLOW, // Dispatches to a workflow
        ASK_QUESTION, // Asks a question to the user
        ROUTER,  // Conditional branching based on context/intent
        END      // Terminal node, completes the flow
    }

    /**
     * Strategy for updating a graph variable.
     */
    public enum UpdateStrategy {
        REPLACE,  // Overwrite the existing value entirely
        APPEND    // Append to the existing value (string concatenation, list append, or numeric addition)
    }

    /**
     * A user-defined variable in the flow graph.
     * Graph designers define these variables with a strategy (REPLACE/APPEND)
     * and they get updated during execution at configured points.
     */
    @Data
    public static class GraphVariable {
        @JsonPropertyDescription("Variable name (used as key in session state)")
        private String name;

        @JsonPropertyDescription("Update strategy: REPLACE (overwrite) or APPEND (concatenate/add)")
        private UpdateStrategy strategy;

        @JsonPropertyDescription("Optional default value when the variable is first used")
        private Object defaultValue;

        @JsonPropertyDescription("Description of what this variable represents")
        private String description;
    }

    /**
     * A structured variable update produced by LLM or node configuration.
     * The actual update strategy (REPLACE/APPEND) is resolved from the graph's variable definitions.
     */
    @Data
    public static class VariableUpdate {
        @JsonPropertyDescription("Variable name to update")
        private String name;

        @JsonPropertyDescription("New value (will be applied according to the variable's configured strategy)")
        private Object value;
    }

    /**
     * The flow graph: a directed graph of nodes connected by edges.
     * Defined by user configuration, executed by the engine.
     */
    @Data
    public static class Graph {
        @JsonPropertyDescription("Graph ID")
        private String id;

        @JsonPropertyDescription("Graph name")
        private String name;

        @JsonPropertyDescription("ID of the START node")
        private String startNodeId;

        @JsonPropertyDescription("All nodes in the graph")
        private List<GraphNode> nodes;

        @JsonPropertyDescription("All edges connecting nodes")
        private List<GraphEdge> edges;

        @JsonPropertyDescription("User-defined variables with update strategies (REPLACE/APPEND)")
        private List<GraphVariable> variables;
    }

    /**
     * A node in the flow graph.
     */
    @Data
    public static class GraphNode {
        @JsonPropertyDescription("Unique node ID")
        private String id;

        @JsonPropertyDescription("Node type: START, AGENT, ROUTER, END")
        private NodeType type;

        @JsonPropertyDescription("Display name of the node")
        private String name;

        @JsonPropertyDescription("Description of what this node does")
        private String description;

        @JsonPropertyDescription("Target agent ID (for AGENT nodes)")
        private String agentId;

        @JsonPropertyDescription("Router configuration (for ROUTER nodes)")
        private RouterNodeConfig routerConfig;

        @JsonPropertyDescription("Expected input parameters for this node (InputBuilder LLM extracts these from context before execution)")
        private List<Arg> inputArgs;

        private Object targetConfig;

        @Schema(description = "异常处理配置")
        private ExceptionHandleConfigDto exceptionHandleConfig;
    }

    /**
     * An edge connecting two nodes in the graph.
     */
    @Data
    public static class GraphEdge {
        @JsonPropertyDescription("Unique edge ID")
        private String id;

        @JsonPropertyDescription("Source node ID")
        private String sourceNodeId;

        @JsonPropertyDescription("Target node ID")
        private String targetNodeId;

        @JsonPropertyDescription("Human-readable label for this edge")
        private String label;
    }

    /**
     * Configuration for a ROUTER node: defines branching conditions.
     */
    @Data
    public static class RouterNodeConfig {
        @JsonPropertyDescription("Description of what this router decides")
        private String description;

        @JsonPropertyDescription("Default edge ID (fallback when no condition matches)")
        private String defaultEdgeId;

        @JsonPropertyDescription("Ordered list of conditions for branch selection")
        private List<RouterCondition> conditions;
    }

    /**
     * A single routing condition within a ROUTER node.
     * Supports both expression-based (programmatic) and text-based (LLM) evaluation.
     */
    @Data
    public static class RouterCondition {
        @JsonPropertyDescription("The edge ID this condition leads to")
        private String edgeId;

        private ConditionNodeConfigDto.ConditionTypeEnum conditionType;

        @JsonPropertyDescription("Condition parameter, used for LLM fallback evaluation")
        private List<ConditionNodeConfigDto.ConditionArgDto> conditionArgs;

        @JsonPropertyDescription("Text description for LLM fallback evaluation (e.g. \"User wants to query orders\")")
        private String textDescription;
    }

    // ======================== Decision Models ========================

    /**
     * Dispatcher's decision: where to dispatch execution in the graph.
     */
    @Data
    public static class NavigatorDecision {
        @JsonPropertyDescription("Target node ID to dispatch execution to")
        private String targetNodeId;

        @JsonPropertyDescription("Reasoning for this dispatch decision")
        private String reasoning;

        @JsonPropertyDescription("Continue from current position (user is continuing the same workflow)")
        private Boolean continueCurrent;

        @JsonPropertyDescription("Clarifying message when no dispatch is needed")
        private String clarifyingMessage;

        @JsonPropertyDescription("Variable updates extracted from the user message (applied according to each variable's configured strategy)")
        private List<VariableUpdate> variableUpdates;
    }

    /**
     * Agent Input Builder's decision: how to construct input for an agent node.
     */
    @Data
    public static class AgentInputBuilderDecision {
        @JsonPropertyDescription("The input message for the target agent")
        private String inputMessage;

        @JsonPropertyDescription("Context variables for the target agent")
        private Map<String, Object> variables;

        @JsonPropertyDescription("Session state updates")
        private Map<String, Object> stateUpdate;

        @JsonPropertyDescription("Structured variable updates (applied according to each variable's configured strategy)")
        private List<VariableUpdate> variableUpdates;

        @JsonPropertyDescription("Skip the current node")
        private Boolean skip;

        @JsonPropertyDescription("End the entire flow early")
        private Boolean end;

        @JsonPropertyDescription("Redirect to a different node (dynamic adjustment)")
        private String redirectNodeId;

        @JsonPropertyDescription("Question to ask the user")
        private String clarifyingMessage;
    }

    /**
     * Router Evaluator's decision: which branch to take at a ROUTER node.
     */
    @Data
    public static class RouterEvaluationDecision {
        @JsonPropertyDescription("The selected edge ID to follow")
        private String selectedEdgeId;

        @JsonPropertyDescription("Reasoning for this branch selection")
        private String reasoning;
    }

    /**
     * A trace entry recording a node's execution decision and outcome within the graph.
     * Replaces the plain String nodeId in executionPath with structured reasoning.
     */
    @Data
    public static class ExecutionTrace {
        private String nodeId;
        private String nodeName;
        private String nodeType;
        private String reasoning;
        private String result;

        public ExecutionTrace() {
        }

        public ExecutionTrace(String nodeId, String nodeName, String nodeType, String reasoning) {
            this.nodeId = nodeId;
            this.nodeName = nodeName;
            this.nodeType = nodeType;
            this.reasoning = reasoning;
        }
    }

}
