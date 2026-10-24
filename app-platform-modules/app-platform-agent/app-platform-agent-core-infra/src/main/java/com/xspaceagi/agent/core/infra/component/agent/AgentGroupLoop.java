package com.xspaceagi.agent.core.infra.component.agent;

import com.alibaba.fastjson2.JSONObject;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.google.common.base.Joiner;
import com.xspaceagi.agent.core.adapter.application.ChatApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AgentOutputDto;
import com.xspaceagi.agent.core.adapter.dto.ChatMessageDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentComponentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.Arg;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.bind.ModelBindConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.infra.component.model.ModelContext;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.infra.component.model.dto.CallMessage;
import com.xspaceagi.agent.core.infra.component.model.dto.ComponentExecutingDto;
import com.xspaceagi.agent.core.infra.component.model.dto.ModelCallConfigDto;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.agent.core.spec.enums.GlobalVariableEnum;
import com.xspaceagi.agent.core.spec.enums.MessageTypeEnum;
import com.xspaceagi.agent.core.spec.enums.OutputTypeEnum;
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

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Slf4j
@Component
public class AgentGroupLoop {

    // ======================== Prompts ========================

    private static final String PLANNER_SYSTEM_PROMPT = """
            You are the planner of an agent group. Your ONLY job: analyze the user's request and create an execution plan — an ordered list of steps, each assigned to a specific agent.
            
            ## Available agents
            
            ${agents}
            
            ## Planning rules
            
            1. Break the user's request into discrete steps. Each step has ONE goal and ONE agent.
            2. Simple requests may need only one step. Complex requests may need multiple steps in sequence.
            3. For each step, specify:
               - agentId: which agent handles this step
               - goal: what this step should accomplish (be specific and concise)
            4. Consider dependencies: later steps may need results from earlier steps.
            5. Output the plan ONLY via the output fields. Do not explain, greet, or add commentary.
            
            ## When no plan is needed
            
            Leave steps empty and respond via clarifyingMessage in these cases:
            - The user's intent is unclear or no suitable agent exists → ask a clarifying question
            - Simple greetings or casual chat (e.g. "hello", "thanks") → respond warmly and briefly guide the user toward what the agents can help with
            - The request is outside the scope of all available agents → explain what you CAN help with and suggest alternatives
            - The user is asking a meta question about the system itself → answer briefly and offer to help with actual tasks
            
            When using clarifyingMessage, be friendly, reference the conversation context when relevant, and always include a guiding hint about what the user could ask next.
            
            ## If the user's request is a continuation of a previous task, check the existing plan. If the plan is still relevant, you can adjust it. If not, create a new plan.
            
            ## Extra Instruction or Role Definition
            
            ${instruction}
            """;

    private static final String ROUTER_SYSTEM_PROMPT = """
            You are a routing assistant. Your ONLY job: construct the input for the current plan step's target agent, using the goal, previous results, and available context.
            
            You do NOT choose which agent to call — that is already decided by the plan.
            You do NOT answer business questions yourself.
            
            ## Rules
            
            1. Craft a clear, self-contained inputMessage for the target agent. It must include all necessary context from previous steps so the agent can work independently.
            2. Use {{messageId}} (eg. {{abcd}} `abcd` is the messageId) to reference a specific previous message when helpful, do NOT include {{messageId}} in the clarifyingMessage..
            3. Extract relevant variables for the agent to skip re-asking.
            4. If the previous step failed and cannot proceed, set skip=true.
            5. If you need user input before this step can proceed, set clarifyingMessage.
            6. If the plan needs adjustment based on new information, provide newPlanSteps to replace remaining steps.
            """;

    // ======================== Constants ========================

    private static final int MAX_RETRY_COUNT = 5;
    private static final int MAX_DISPATCH_COUNT = 10;
    private static final int MAX_STEP_RETRY = 2;
    private static final int MAX_CONTENT_LENGTH = 800;
    private static final int MAX_MESSAGES_TOKEN_BUDGET = 8000;
    private static final Pattern INPUT_MESSAGE_ID_PATTERN = Pattern.compile("\\{\\{\\s*([^}]+?)\\s*}}");
    private static final String PLAN_KEY = "executionPlan";
    private static final String PLAN_STEP_KEY = "executionPlanStep";
    private static final String ASK_QUESTION_WAITING_ANSWER_KEY = "askQuestionWaitingAnswer";

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private ChatApplicationService chatApplicationService;

    @Resource
    private AgentExecutor agentExecutor;

    @Resource
    private ModelInvoker modelInvoker;

    // ======================== Public API ========================

    public Flux<AgentOutputDto> execute(AgentContext agentContext) {
        List<AgentComponentConfigDto> agentComponentConfigs = agentContext.getAgentConfig().getAgentComponentConfigList()
                .stream().filter(c -> c.getType() == AgentComponentConfig.Type.Agent).toList();
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

        GroupLoopContext ctx = buildGroupLoopContext(agentContext, agentComponentConfigs);
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
     * Main loop: plan → route each step → dispatch → auto-advance → repeat.
     * The key architectural change from the old design:
     * - Planning (which agents, in what order) is done ONCE by the Planner LLM
     * - Routing (constructing input for a specific agent) is done per-step by the Router LLM
     * - Step progression (planStep++) is managed by CODE, not LLM
     */
    private Mono<Void> runOrchestration(GroupLoopContext ctx, FluxSink<AgentOutputDto> sink) {
        return Mono.<Void>defer(() -> {
            // Circuit breakers
            if (ctx.getRetryCount().get() > MAX_RETRY_COUNT) {
                sink.error(new IllegalArgumentException("The agent failed to correctly identify the necessary data."));
                return Mono.empty();
            }
            if (ctx.getTotalDispatchCount() > MAX_DISPATCH_COUNT) {
                log.warn("[AgentGroupLoop] max dispatch count ({}) reached, forcing end", MAX_DISPATCH_COUNT);
                sink.next(buildOutputMessage(ctx.getGroupAgentContext(), "[AgentGroupLoop] max dispatch count (" + MAX_DISPATCH_COUNT + ") reached, forcing end"));
                sendComplete(ctx, sink, "Max dispatch count reached");
                return Mono.empty();
            }
            if (ctx.getGroupAgentContext().isInterrupted()) {
                sendComplete(ctx, sink, null);
                return Mono.empty();
            }

            // User answer
            if (ctx.isWaitingForUserAnswer()) {
                ctx.setWaitingForUserAnswer(false);
                ctx.getSessionState().put(ASK_QUESTION_WAITING_ANSWER_KEY, ctx.isWaitingForUserAnswer());
                log.warn("[AgentGroupLoop] user answer, skipping plan and route, dispatchStep");
                try {
                    PlanStep currentStep = ctx.getCurrentPlan().getSteps().get(ctx.getCurrentPlanStepIndex());
                    if (currentStep != null) {
                        return dispatchStep(ctx, sink, currentStep, new RouterDecision(), ctx.getGroupAgentContext().getMessage());
                    }
                } catch (Exception e) {
                    // ignore
                    log.warn("[AgentGroupLoop] error", e);
                }
            }

            // Persist session state
            conversationApplicationService.updateConversationVariables(
                    ctx.getGroupAgentContext().getConversation().getId(), ctx.getSessionState());

            // Phase 1: Plan if needed
            if (ctx.getCurrentPlan() == null || ctx.getCurrentPlanStepIndex() >= planSize(ctx)) {
                // Shortcut: user explicitly @mentioned an agent → skip both Planner and Router, dispatch directly
                AgentConfigDto mentionedAgent = resolveMentionedAgent(ctx);
                if (mentionedAgent != null) {
                    PlanStep directStep = new PlanStep();
                    directStep.setAgentId(mentionedAgent.getId().toString());
                    directStep.setGoal("Directly handle user's @agent:" + mentionedAgent.getName() + " request");

                    // Set up a single-step plan for state tracking
                    Plan directPlan = new Plan();
                    directPlan.setSteps(List.of(directStep));
                    directPlan.setReasoning("User explicitly mentioned @agent:" + mentionedAgent.getName());
                    ctx.setCurrentPlan(directPlan);
                    ctx.setCurrentPlanStepIndex(0);
                    ctx.setStepRetryCount(0);

                    // Strip @mention from message, use remaining text as direct input
                    String directInput = stripMention(ctx.getGroupAgentContext().getMessage());
                    RouterDecision directDecision = new RouterDecision(); // empty — no variables/stateUpdate needed

                    log.info("[AgentGroupLoop] @mention shortcut: directly dispatching to {}, skipping planner and router",
                            mentionedAgent.getName());
                    return dispatchStep(ctx, sink, directStep, directDecision, directInput);
                }

                return runPlanningPhase(ctx, sink)
                        .then(Mono.defer(() -> runOrchestration(ctx, sink)));
            }

            // Phase 2: Route + dispatch current step
            return runRoutingPhase(ctx, sink);
        }).onErrorResume(e -> {
            log.error("[AgentGroupLoop] orchestration error: {}", e.getMessage(), e);
            AgentOutputDto agentOutputDto = buildOutputMessage(ctx.getGroupAgentContext(), e.getMessage());
            sink.next(agentOutputDto);
            sink.error(e);
            return Mono.empty();
        });
    }

    private int planSize(GroupLoopContext ctx) {
        Plan plan = ctx.getCurrentPlan();
        return (plan != null && plan.getSteps() != null) ? plan.getSteps().size() : 0;
    }

    // ======================== Phase 1: Planning ========================

    private Mono<Void> runPlanningPhase(GroupLoopContext ctx, FluxSink<AgentOutputDto> sink) {
        String plannerContext = buildPlannerContext(ctx);
        String systemPrompt = PLANNER_SYSTEM_PROMPT;
        if (StringUtils.isNotBlank(ctx.getGroupAgentContext().getAgentConfig().getSystemPrompt())) {
            systemPrompt = systemPrompt.replace("${instruction}", ctx.getGroupAgentContext().getAgentConfig().getSystemPrompt());
        }
        systemPrompt = systemPrompt.replace("${agents}", buildAgentList(ctx));
        ModelContext modelContext = buildModelContext(ctx.getGroupAgentContext(), systemPrompt, plannerContext, buildPlannerArgs());

        log.info("[AgentGroupLoop] planning phase started");
        return modelInvoker.invoke(modelContext).onErrorResume(e -> {
            log.warn("[AgentGroupLoop] planner error: {}", e.getMessage(), e);
            modelContext.getModelCallResult().setResponseText(e.getMessage());
            return Mono.empty();
        }).then(Mono.defer(() -> handlePlannerResult(ctx, sink, modelContext)));
    }

    private Mono<Void> handlePlannerResult(GroupLoopContext ctx, FluxSink<AgentOutputDto> sink, ModelContext modelContext) {
        Object response = modelContext.getModelCallResult().getData();
        if (!(response instanceof JSONObject jsonResponse)) {
            addRetryMessage(ctx, "Planner response is not valid JSON:\n" + modelContext.getModelCallResult().getResponseText());
            return runOrchestration(ctx, sink);
        }

        Plan plan = jsonResponse.to(Plan.class);
        if (plan != null && CollectionUtils.isNotEmpty(plan.getSteps())) {
            //校验step中的agentId是否正确
            for (PlanStep step : plan.getSteps()) {
                AgentConfigDto agentConfig = findAgentConfig(ctx, step.getAgentId());
                if (agentConfig == null) {
                    addRetryMessage(ctx, "Planner response contains invalid agentId: " + step.getAgentId());
                    return runOrchestration(ctx, sink);
                }
            }
        }

        if (plan != null && StringUtils.isNotBlank(plan.getReasoning())) {
            ctx.getReasoningBuilder().append("\n").append(plan.getReasoning());
            buildAndSendReasoning(ctx, sink, plan.getReasoning());
        }

        if (plan == null || CollectionUtils.isEmpty(plan.getSteps())) {
            // No plan: clarifying message or empty
            String msg = (plan != null && StringUtils.isNotBlank(plan.getClarifyingMessage()))
                    ? plan.getClarifyingMessage()
                    : I18nUtil.systemMessage(ctx.getGroupAgentContext().getUser().getLangMap(), "Backend.AgentGroup.InfoNotEnough");
            ctx.getResponseBuilder().append(msg);
            addRoundMessage(ctx, ctx.groupAgentContext, msg);
            sink.next(buildOutputMessage(ctx.getGroupAgentContext(), msg));
            sendComplete(ctx, sink, null);
            log.info("[AgentGroupLoop] planning phase: no plan created, ending");
            return Mono.empty();
        }

        // Validate agent IDs in the plan
        for (PlanStep step : plan.getSteps()) {
            if (findAgentConfig(ctx, step.getAgentId()) == null) {
                addRetryMessage(ctx, "Planner referenced invalid agentId: " + step.getAgentId());
                return runOrchestration(ctx, sink);
            }
        }

        ctx.setCurrentPlan(plan);
        ctx.setCurrentPlanStepIndex(0);
        ctx.setStepRetryCount(0);
        persistPlan(ctx);

        log.info("[AgentGroupLoop] plan created: {} steps, reason={}", plan.getSteps().size(), plan.getReasoning());
        return Mono.empty();
    }

    private void buildAndSendReasoning(GroupLoopContext ctx, FluxSink<AgentOutputDto> sink, String reasoning) {
        CallMessage callMessage = new CallMessage();
        callMessage.setId(ctx.groupAgentContext.getRequestId());
        callMessage.setText(reasoning);
        callMessage.setType(MessageTypeEnum.THINK);
        callMessage.setSenderType(ChatMessageDto.SenderType.AGENT);
        callMessage.setRole(ChatMessageDto.Role.ASSISTANT);
        callMessage.setTime(new Date());
        callMessage.setSenderId(ctx.groupAgentContext.getAgentConfig().getId().toString());
        callMessage.setAgentId(ctx.groupAgentContext.getAgentConfig().getId());
        AgentOutputDto agentOutputDto = new AgentOutputDto();
        agentOutputDto.setEventType(AgentOutputDto.EventTypeEnum.MESSAGE);
        agentOutputDto.setRequestId(ctx.groupAgentContext.getRequestId());
        agentOutputDto.setData(callMessage);
        agentOutputDto.setCompleted(false);
        sink.next(agentOutputDto);
    }

    private void persistPlan(GroupLoopContext ctx) {
        ctx.getSessionState().put(PLAN_KEY, ctx.getCurrentPlan());
        ctx.getSessionState().put(PLAN_STEP_KEY, ctx.getCurrentPlanStepIndex());
    }

    // ======================== Phase 2: Routing ========================

    private Mono<Void> runRoutingPhase(GroupLoopContext ctx, FluxSink<AgentOutputDto> sink) {
        PlanStep currentStep = ctx.getCurrentPlan().getSteps().get(ctx.getCurrentPlanStepIndex());
        String routingContext = buildRoutingContext(ctx, currentStep);
        ModelContext modelContext = buildModelContext(
                ctx.getGroupAgentContext(), ROUTER_SYSTEM_PROMPT, routingContext, buildRouterArgs());

        log.info("[AgentGroupLoop] routing phase: step {}/{}, agent={}, goal={}",
                ctx.getCurrentPlanStepIndex() + 1, planSize(ctx),
                currentStep.getAgentId(), currentStep.getGoal());

        return modelInvoker.invoke(modelContext).onErrorResume(e -> {
            log.warn("[AgentGroupLoop] routing phase: error", e);
            modelContext.getModelCallResult().setResponseText(e.getMessage());
            return Mono.empty();
        }).then(Mono.defer(() -> handleRouterResult(ctx, sink, modelContext, currentStep)));
    }

    private Mono<Void> handleRouterResult(GroupLoopContext ctx, FluxSink<AgentOutputDto> sink,
                                          ModelContext modelContext, PlanStep currentStep) {
        Object response = modelContext.getModelCallResult().getData();
        if (!(response instanceof JSONObject jsonResponse)) {
            addRetryMessage(ctx, "Router response is not valid JSON:\n" + modelContext.getModelCallResult().getResponseText());
            return runOrchestration(ctx, sink);
        }

        RouterDecision decision = jsonResponse.to(RouterDecision.class);
        if (decision == null) {
            addRetryMessage(ctx, "Router returned empty decision");
            return runOrchestration(ctx, sink);
        }

        // Apply state update
        if (decision.getStateUpdate() != null) {
            ctx.updateSessionState(decision.getStateUpdate());
        }

        // Handle control flags
        if (Boolean.TRUE.equals(decision.getReplan()) && CollectionUtils.isNotEmpty(decision.getNewPlanSteps())) {
            log.info("[AgentGroupLoop] replan requested at step {}", ctx.getCurrentPlanStepIndex());
            Plan newPlan = new Plan();
            newPlan.setSteps(decision.getNewPlanSteps());
            newPlan.setReasoning("Replanned at step " + ctx.getCurrentPlanStepIndex());
            ctx.setCurrentPlan(newPlan);
            ctx.setCurrentPlanStepIndex(0);
            ctx.setStepRetryCount(0);
            persistPlan(ctx);
            return runOrchestration(ctx, sink);
        }

        if (Boolean.TRUE.equals(decision.getEnd()) || Boolean.TRUE.equals(decision.getSkip())) {
            log.info("[AgentGroupLoop] step {} skipped or ended", ctx.getCurrentPlanStepIndex());
            return advanceStep(ctx, sink);
        }

        if (StringUtils.isNotBlank(decision.getClarifyingMessage())) {
            addRoundMessage(ctx, ctx.getGroupAgentContext(), decision.getClarifyingMessage());
            sink.next(buildOutputMessage(ctx.getGroupAgentContext(), decision.getClarifyingMessage().replace("\\n", "\n")));
            sendComplete(ctx, sink, null);
            return Mono.empty();
        }

        // Resolve input message
        String inputMessage = resolveInputMessage(ctx, decision.getInputMessage());

        // Dispatch to the agent specified in the current plan step
        return dispatchStep(ctx, sink, currentStep, decision, inputMessage);
    }

    private Mono<Void> advanceStep(GroupLoopContext ctx, FluxSink<AgentOutputDto> sink) {
        if (ctx.isWaitingForUserAnswer()) {
            log.info("[AgentGroupLoop] waiting for user answer");
            ctx.getSessionState().put(ASK_QUESTION_WAITING_ANSWER_KEY, ctx.isWaitingForUserAnswer());
            sendComplete(ctx, sink, null);
            return Mono.empty();
        }

        if (ctx.getGroupAgentContext().isInterrupted() || chatApplicationService.isChatStop(ctx.getGroupAgentContext().getConversationId())) {
            sendComplete(ctx, sink, null);
            return Mono.empty();
        }

        ctx.setCurrentPlanStepIndex(ctx.getCurrentPlanStepIndex() + 1);
        ctx.setStepRetryCount(0);
        persistPlan(ctx);

        if (ctx.getCurrentPlanStepIndex() >= planSize(ctx)) {
            log.info("[AgentGroupLoop] all plan steps completed");
            // If no agent produced output, tell the user what happened
            if (ctx.getResponseBuilder().isEmpty()) {
                String failureMessage = buildPlanFailureMessage(ctx);
                sink.next(buildOutputMessage(ctx.getGroupAgentContext(), failureMessage));
            }
            sendComplete(ctx, sink, null);
            return Mono.empty();
        }
        return runOrchestration(ctx, sink);
    }

    // ======================== Agent Dispatch ========================

    private Mono<Void> dispatchStep(GroupLoopContext ctx, FluxSink<AgentOutputDto> sink,
                                    PlanStep step, RouterDecision decision, String inputMessage) {
        AgentConfigDto agentConfigDto = findAgentConfig(ctx, step.getAgentId());
        if (agentConfigDto == null) {
            log.warn("[AgentGroupLoop] agent not found for step {}: {}", ctx.getCurrentPlanStepIndex(), step.getAgentId());
            appendStepFailure(ctx, step, "Agent not found");
            return advanceStep(ctx, sink);
        }

        // Step-level retry
        if (ctx.getStepRetryCount() > MAX_STEP_RETRY) {
            log.warn("[AgentGroupLoop] step {} exceeded max retries, skipping", ctx.getCurrentPlanStepIndex());
            appendStepFailure(ctx, step, "Exceeded max retries due to repeated errors");
            return advanceStep(ctx, sink);
        }

        ctx.setTotalDispatchCount(ctx.getTotalDispatchCount() + 1);
        @SuppressWarnings("unchecked")
        Map<String, Object> agentState = (Map<String, Object>) ctx.getSessionState().computeIfAbsent("_agent" + agentConfigDto.getId(), k -> new HashMap<>());
        AgentContext agentContext = new AgentContext();
        BeanUtils.copyProperties(ctx.getGroupAgentContext(), agentContext);
        agentContext.setAgentConfig(agentConfigDto);
        agentContext.setMessage(buildStepAgentPrompt(ctx, step, decision, inputMessage));
        agentContext.setUseSate(true);
        agentContext.setSessionState(agentState);
        String dispatchMsg = "**@" + agentConfigDto.getName() + "** " + inputMessage;
        addRoundMessage(ctx, ctx.getGroupAgentContext(), dispatchMsg);
        //用于上下文
        StringBuilder messageBuilder = new StringBuilder();
        boolean[] isFirst = {true};
        return agentExecutor.execute(agentContext)
                .onErrorResume(error -> {
                    ctx.setStepRetryCount(ctx.getStepRetryCount() + 1);
                    addStepRetryMessage(ctx, "Agent [" + agentConfigDto.getName() + "] error: " + error.getMessage());
                    return Mono.empty();
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
                                AgentOutputDto agentOutputDto = buildOutputMessage(ctx.getGroupAgentContext(), buildGroupAgentName(ctx, agentConfigDto));
                                sink.next(agentOutputDto);
                            }
                            String processingText = AgentExecutor.buildProcessingText(componentExecutingDto);
                            messageBuilder.append(processingText);
                            ctx.getResponseBuilder().append(processingText);
                            if (componentExecutingDto.getSubEventType() == ComponentExecutingDto.SubEventTypeEnum.ASK_QUESTION) {
                                ctx.setWaitingForUserAnswer(true);
                            }
                            if (componentExecutingDto.getSubEventType() == ComponentExecutingDto.SubEventTypeEnum.REQUEST_PERMISSION) {
                                conversationApplicationService.updateConversationVariables(ctx.getGroupAgentContext().getConversation().getId(), ctx.getSessionState());
                            }
                            ctx.groupAgentContext.getAgentExecuteResult().getComponentExecuteResults().add(componentExecutingDto.getResult());
                        }
                        sink.next(outputDto);
                    }
                })
                .then(Mono.defer(() -> {
                    if (messageBuilder.isEmpty()) {
                        // Agent returned no output — count as step failure, retry
                        if (ctx.getStepRetryCount() <= MAX_STEP_RETRY) {
                            addStepRetryMessage(ctx, "Agent [" + agentConfigDto.getName() + "] returned empty output");
                            ctx.setStepRetryCount(ctx.getStepRetryCount() + 1);
                            return runOrchestration(ctx, sink);
                        }
                        log.warn("[AgentGroupLoop] step {} exceeded max retries (empty output), skipping",
                                ctx.getCurrentPlanStepIndex());
                        appendStepFailure(ctx, step, "Agent returned empty output after retries");
                    } else {
                        // Success — record in route history and round messages
                        addRoundMessage(ctx, agentContext, messageBuilder.toString());
                        RouteRecord record = new RouteRecord();
                        record.setAgentId(step.getAgentId());
                        record.setInputMessage(inputMessage);
                        ctx.getRouteHistory().add(record);
                    }
                    return advanceStep(ctx, sink);
                }));
    }

    private String buildGroupAgentName(GroupLoopContext ctx, AgentConfigDto agentConfigDto) {
        String agentInfo = "<agent-info name=\"" + agentConfigDto.getName() + "\" icon=\"" + agentConfigDto.getIcon() + "\"/>";
        return ctx.getResponseBuilder().isEmpty() ? agentInfo + "\n\n" : "\n\n" + agentInfo + "\n\n";
    }

    private void enrichCallMessage(GroupLoopContext ctx, CallMessage message, AgentConfigDto agentConfigDto) {
        message.setId(ctx.getGroupAgentContext().getRequestId());
        message.setFinished(false);
        message.setFinishReason(null);
        message.setSenderType(ChatMessageDto.SenderType.AGENT);
        message.setAgentId(agentConfigDto.getId());
        message.setSenderId(agentConfigDto.getId().toString());
    }

    /**
     * Append a failure notice to responseBuilder when a step is skipped.
     * This ensures the user sees what happened even if the agent produced no output.
     */
    private void appendStepFailure(GroupLoopContext ctx, PlanStep step, String reason) {
        String agentName = step.getAgentId();
        AgentConfigDto cfg = findAgentConfig(ctx, step.getAgentId());
        if (cfg != null) agentName = cfg.getName();

        String notice;
        String i18nMessage = I18nUtil.systemMessage(ctx.getGroupAgentContext().getUser().getLangMap(), "Backend.AgentGroup.StepExecutionFailed")
                .replace("{agentName}", agentName).replace("{reason}", reason);
        if (ctx.getResponseBuilder().isEmpty()) {
            notice = i18nMessage;
        } else {
            notice = "\n\n" + i18nMessage;
        }
        ctx.getResponseBuilder().append(notice);
    }

    /**
     * Build a user-friendly failure message when all plan steps failed and no output was produced.
     * References the plan context so the user understands what was attempted.
     */
    private String buildPlanFailureMessage(GroupLoopContext ctx) {
        Plan plan = ctx.getCurrentPlan();
        if (plan == null || CollectionUtils.isEmpty(plan.getSteps())) {
            return I18nUtil.systemMessage(ctx.getGroupAgentContext().getUser().getLangMap(), "Backend.AgentGroup.PlanFailureRetry");
        }

        StringBuilder sb = new StringBuilder();
        sb.append(I18nUtil.systemMessage(ctx.getGroupAgentContext().getUser().getLangMap(), "Backend.AgentGroup.PlanStepsFailed")).append("\n\n");
        for (int i = 0; i < plan.getSteps().size(); i++) {
            PlanStep step = plan.getSteps().get(i);
            String agentName = step.getAgentId();
            AgentConfigDto cfg = findAgentConfig(ctx, step.getAgentId());
            if (cfg != null) agentName = cfg.getName();
            sb.append("- ").append(agentName).append("：").append(step.getGoal()).append("\n");
        }
        sb.append("\n").append(I18nUtil.systemMessage(ctx.getGroupAgentContext().getUser().getLangMap(), "Backend.AgentGroup.RetryOrRephrase"));
        return sb.toString();
    }

    // ======================== Message Builders ========================

    private void sendComplete(GroupLoopContext ctx, FluxSink<AgentOutputDto> sink, String error) {
        AgentContext agentContext = ctx.getGroupAgentContext();
        ChatMessageDto assistantMessage = ChatMessageDto.builder()
                .tenantId(agentContext.getAgentConfig().getTenantId())
                .id(ctx.getGroupAgentContext().getRequestId())
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
                .componentExecutedList(ctx.groupAgentContext.getAgentExecuteResult().getComponentExecuteResults().stream()
                        .map(componentExecutingDto -> (Object) componentExecutingDto).toList())
                .build();
        conversationApplicationService.addRoundMessage(ctx.getGroupAgentContext().getConversationId(), assistantMessage);
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

    private void addRoundMessage(GroupLoopContext groupLoopContext, AgentContext agentContext, String message) {
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

    private void addRetryMessage(GroupLoopContext groupLoopContext, String errorMessage) {
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
     * Add a retry message for agent step-level failures.
     * Unlike addRetryMessage, this does NOT increment the global retryCount —
     * agent execution failures are tracked by stepRetryCount, not the orchestration-level counter.
     */
    private void addStepRetryMessage(GroupLoopContext groupLoopContext, String errorMessage) {
        ChatMessageDto retryMessage = ChatMessageDto.builder()
                .id(UUID.randomUUID().toString().replace("-", ""))
                .type(MessageTypeEnum.CHAT)
                .senderType(ChatMessageDto.SenderType.REMINDER)
                .text(errorMessage)
                .build();
        groupLoopContext.getCurrentMessages().add(retryMessage);
    }

    // ======================== Prompt Builders ========================

    private String buildAgentList(GroupLoopContext ctx) {
        StringBuilder sb = new StringBuilder();
        for (AgentConfigDto agent : ctx.getGroupAgentConfigs()) {
            sb.append("- ").append(agent.getName())
                    .append("[agentId: ").append(agent.getId()).append("] - ")
                    .append(agent.getDescription()).append("\n");
        }
        return sb.toString();
    }

    private String buildPlannerContext(GroupLoopContext ctx) {
        StringBuilder sb = new StringBuilder();

        // Summary — long-term conversation context
        if (StringUtils.isNotBlank(ctx.getSummary())) {
            sb.append("## Conversation Summary\n").append(ctx.getSummary()).append("\n\n");
        }

        // Recent interaction history — critical for understanding intent and continuation
        appendRecentMessages(ctx, sb);

        // Existing plan from previous turn
        if (ctx.getCurrentPlan() != null && !ctx.getCurrentPlan().getSteps().isEmpty()) {
            sb.append("## Previous Plan (step ").append(ctx.getCurrentPlanStepIndex())
                    .append("/").append(planSize(ctx)).append(" completed)\n");
            List<PlanStep> steps = ctx.getCurrentPlan().getSteps();
            for (int i = 0; i < steps.size(); i++) {
                PlanStep step = steps.get(i);
                String marker = (i < ctx.getCurrentPlanStepIndex()) ? "[completed]" :
                        (i == ctx.getCurrentPlanStepIndex()) ? "[current]" : "[pending]";
                sb.append("- Step ").append(i).append(" ").append(marker)
                        .append(": agent=").append(step.getAgentId())
                        .append(", goal=").append(step.getGoal()).append("\n");
            }
            sb.append("\n");
        }

        // Current user request
        sb.append("## Current User Request\n").append(ctx.getGroupAgentContext().getMessage()).append("\n");

        return sb.toString();
    }

    private String buildRoutingContext(GroupLoopContext ctx, PlanStep currentStep) {
        StringBuilder sb = new StringBuilder();

        // Current step info — the most important context
        sb.append("## Current Step\n");
        sb.append("- Step: ").append(ctx.getCurrentPlanStepIndex() + 1).append(" of ").append(planSize(ctx)).append("\n");
        sb.append("- Goal: ").append(currentStep.getGoal()).append("\n");
        sb.append("- Target agent: ").append(currentStep.getAgentId()).append("\n\n");

        // Overall plan overview
        sb.append("## Plan Overview\n");
        List<PlanStep> steps = ctx.getCurrentPlan().getSteps();
        for (int i = 0; i < steps.size(); i++) {
            PlanStep s = steps.get(i);
            String marker = (i < ctx.getCurrentPlanStepIndex()) ? "[done]" :
                    (i == ctx.getCurrentPlanStepIndex()) ? "[NOW]" : "[pending]";
            sb.append("- Step ").append(i).append(" ").append(marker)
                    .append(": agent=").append(s.getAgentId())
                    .append(", goal=").append(s.getGoal()).append("\n");
        }
        sb.append("\n");

        // Summary — long-term context
        if (StringUtils.isNotBlank(ctx.getSummary())) {
            sb.append("## Conversation Summary\n").append(ctx.getSummary()).append("\n\n");
        }

        // Recent interaction history — router needs messageId references and context
        appendRecentMessages(ctx, sb);

        // Session state
        appendSessionState(ctx, sb);

        // Current user request
        sb.append("## Current User Request\n").append(ctx.getGroupAgentContext().getMessage()).append("\n");

        return sb.toString();
    }

    /**
     * Append recent conversation messages for both Planner and Router context.
     * Includes messageId so the router can use {{messageId}} references in inputMessage.
     * Separates historical messages (past turns) from current-turn messages.
     */
    private void appendRecentMessages(GroupLoopContext ctx, StringBuilder sb) {
        List<ChatMessageDto> messages = ctx.getCurrentMessagesForContext();
        if (CollectionUtils.isEmpty(messages)) return;

        Long controllerAgentId = ctx.getGroupAgentContext().getAgentConfig().getId();
        Map<Long, AgentConfigDto> agentMap = ctx.getGroupAgentConfigs().stream()
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
            for (int i = 0; i < lastUserIdx; i++) {
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

    private void appendSessionState(GroupLoopContext ctx, StringBuilder sb) {
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

    private String buildStepAgentPrompt(GroupLoopContext ctx, PlanStep step,
                                        RouterDecision decision, String inputMessage) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("<system-reminder>\n");

        // Peer agents
        prompt.append("You are an agent in a group. The following peer agents are available — use @agent:${agentName} to request assistance:\n");
        ctx.getGroupAgentConfigs().stream()
                .filter(a -> !a.getId().toString().equals(step.getAgentId()))
                .forEach(agent -> prompt.append("- ").append(agent.getName())
                        .append("[agentId:").append(agent.getId()).append("] - ")
                        .append(agent.getDescription()).append("\n"));

        // Variables from router decision
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
        prompt.append(inputMessage);
        return prompt.toString();
    }

    private String resolveInputMessage(GroupLoopContext ctx, String inputMessage) {
        if (StringUtils.isNotBlank(inputMessage)) {
            if (inputMessage.contains("{{") && inputMessage.contains("}}")) {
                for (String messageId : extractMessageIds(inputMessage)) {
                    String referencedText = ctx.getCurrentMessages().stream()
                            .filter(m -> m.getId().equals(messageId))
                            .findFirst()
                            .map(ChatMessageDto::getText)
                            .orElse("");
                    if (inputMessage.startsWith("{{") || inputMessage.endsWith("}}")) {// 引用消息不管是开头还是结束，都不做任何修饰
                        inputMessage = inputMessage.replace("{{" + messageId + "}}", referencedText);
                    } else {
                        inputMessage = inputMessage.replace("{{" + messageId + "}}", "<referencedText>" + referencedText + "</referencedText>");
                    }
                }
            }
        } else {
            inputMessage = ctx.getGroupAgentContext().getMessage();
        }
        return inputMessage;
    }

    // ======================== Arg Definitions ========================

    private List<Arg> buildPlannerArgs() {
        return List.of(
                Arg.builder()
                        .name("reasoning")
                        .description("Brief analysis of the user's request and why this plan was chosen.")
                        .dataType(DataTypeEnum.String)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("steps")
                        .description("Ordered list of execution steps. Empty array when intent is unclear.")
                        .dataType(DataTypeEnum.Array_Object)
                        .subArgs(List.of(
                                Arg.builder().name("agentId").description("The agent ID to handle this step").dataType(DataTypeEnum.String).require(true).build(),
                                Arg.builder().name("goal").description("What this step should accomplish").dataType(DataTypeEnum.String).require(true).build()
                        ))
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("clarifyingMessage")
                        .description("Used when steps is empty. For unclear intent: ask a clarifying question. For greetings/casual chat: respond warmly with a guiding hint about what agents can help with. For out-of-scope requests: explain available capabilities. Always be friendly and reference conversation context.")
                        .dataType(DataTypeEnum.String)
                        .require(false)
                        .build()
        );
    }

    private List<Arg> buildRouterArgs() {
        return List.of(
                Arg.builder()
                        .name("inputMessage")
                        .description("The input message for the target agent. Must be self-contained with all context from previous steps. Use {{messageId}}(eg. {{abcd}} `abcd` is the messageId) to reference previous messages.")
                        .dataType(DataTypeEnum.String)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("variables")
                        .description("Known context variables extracted for the target agent. Set null when none available.")
                        .dataType(DataTypeEnum.Object)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("stateUpdate")
                        .description("Session state updates from this routing decision. Set null when nothing to update.")
                        .dataType(DataTypeEnum.Object)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("skip")
                        .description("Set to true to skip the current step (e.g. previous step failed and this step depends on it).")
                        .dataType(DataTypeEnum.Boolean)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("end")
                        .description("Set to true to end the entire plan early (all remaining steps should be skipped).")
                        .dataType(DataTypeEnum.Boolean)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("replan")
                        .description("Set to true if the plan needs to be adjusted based on new information. Must provide newPlanSteps.")
                        .dataType(DataTypeEnum.Boolean)
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("newPlanSteps")
                        .description("New plan steps to replace the remaining plan. Required when replan=true.")
                        .dataType(DataTypeEnum.Array_Object)
                        .subArgs(List.of(
                                Arg.builder().name("agentId").description("The agent ID to handle this step").dataType(DataTypeEnum.String).require(true).build(),
                                Arg.builder().name("goal").description("What this step should accomplish").dataType(DataTypeEnum.String).require(true).build()
                        ))
                        .require(false)
                        .build(),
                Arg.builder()
                        .name("clarifyingMessage")
                        .description("Question to ask the user when more info is needed before this step can proceed.")
                        .dataType(DataTypeEnum.String)
                        .require(false)
                        .build()
        );
    }

    // ======================== Context Building ========================

    private GroupLoopContext buildGroupLoopContext(AgentContext agentContext,
                                                   List<AgentComponentConfigDto> agentComponentConfigs) {
        GroupLoopContext ctx = new GroupLoopContext();
        ctx.setResponseBuilder(new StringBuilder());
        ctx.setReasoningBuilder(new StringBuilder());
        ctx.setGroupAgentContext(agentContext);
        ctx.setGroupAgentConfigs(resolveGroupAgentConfigs(agentContext, agentComponentConfigs));

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

        // Parse existing plan from session state (for cross-turn continuation)
        ctx.setCurrentPlan(parsePlanFromState(variableParams));
        Object stepObj = variableParams.get(PLAN_STEP_KEY);
        ctx.setCurrentPlanStepIndex(stepObj instanceof Number ? ((Number) stepObj).intValue() : 0);

        Object waitingForUserAnswer = variableParams.get(ASK_QUESTION_WAITING_ANSWER_KEY);
        ctx.setWaitingForUserAnswer(waitingForUserAnswer != null && (boolean) waitingForUserAnswer);

        ctx.setRouteHistory(parseRouteHistory(variableParams));
        ctx.setInitialRouteCount(ctx.getRouteHistory().size());
        ctx.setSummary(agentContext.getConversation().getSummary());
        ctx.setCurrentMessages(parseAndInitMessages(agentContext, variableParams));
        ctx.setRetryCount(new AtomicInteger(0));
        ctx.setStepRetryCount(0);
        return ctx;
    }

    private Plan parsePlanFromState(Map<String, Object> variableParams) {
        Object planObj = variableParams.get(PLAN_KEY);
        if (planObj == null) return null;
        if (planObj instanceof Plan plan) return plan;
        if (planObj instanceof Map<?, ?> map) {
            return JSONObject.from(map).to(Plan.class);
        }
        return null;
    }

    private List<AgentConfigDto> resolveGroupAgentConfigs(AgentContext agentContext, List<AgentComponentConfigDto> agentComponentConfigs) {
        List<AgentConfigDto> groupAgentConfigs = new ArrayList<>();
        for (AgentComponentConfigDto config : agentComponentConfigs) {
            if (config.getTargetConfig() instanceof AgentConfigDto agentConfigDto) {
                agentConfigDto.setEnableAskQuestion(agentContext.getAgentConfig().getEnableAskQuestion());
                agentConfigDto.setEnableVersionControl(agentContext.getAgentConfig().getEnableVersionControl());
                groupAgentConfigs.add(agentConfigDto);
                appendSkillsAndSubagentsPrompt(agentConfigDto);
            }
        }
        return groupAgentConfigs;
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

    private List<RouteRecord> parseRouteHistory(Map<String, Object> variableParams) {
        Object routeHistoryObj = variableParams.get("routeHistory");
        List<RouteRecord> routeHistory;
        if (routeHistoryObj instanceof List<?> list) {
            routeHistory = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof RouteRecord rr) {
                    routeHistory.add(rr);
                } else if (item instanceof Map<?, ?> map) {
                    routeHistory.add(JSONObject.from(map).to(RouteRecord.class));
                }
            }
        } else {
            routeHistory = new ArrayList<>();
        }
        variableParams.put("routeHistory", routeHistory);
        return routeHistory;
    }

    private List<ChatMessageDto> parseAndInitMessages(AgentContext agentContext, Map<String, Object> variableParams) {
        Object contextMessagesObj = variableParams.get("groupLoopContextMessages");
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
        variableParams.put("groupLoopContextMessages", roundMessages);

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

    private AgentConfigDto findAgentConfig(GroupLoopContext ctx, String agentId) {
        return ctx.getGroupAgentConfigs().stream()
                .filter(a -> a.getId().toString().equals(agentId))
                .findFirst().orElse(null);
    }

    private static final Pattern MENTION_PATTERN = Pattern.compile("@agent:(\\S+)");

    /**
     * Strip the @agent:xxx mention prefix from the user's message.
     * e.g. "@agent:订单查询 帮我查一下12345" → "帮我查一下12345"
     */
    private static String stripMention(String message) {
        if (StringUtils.isBlank(message)) return message;
        String stripped = MENTION_PATTERN.matcher(message).replaceFirst("").trim();
        return StringUtils.isBlank(stripped) ? message : stripped;
    }

    /**
     * Check if the user's message contains an @agent:xxx mention.
     * Matches agent names case-insensitively and supports partial matching.
     * Returns the matched agent config, or null if no valid mention found.
     */
    private AgentConfigDto resolveMentionedAgent(GroupLoopContext ctx) {
        String message = ctx.getGroupAgentContext().getMessage();
        if (StringUtils.isBlank(message)) return null;

        Matcher matcher = MENTION_PATTERN.matcher(message);
        if (!matcher.find()) return null;

        String mentionedName = matcher.group(1).trim();
        if (mentionedName.isEmpty()) return null;

        // Exact match first (case-insensitive)
        for (AgentConfigDto agent : ctx.getGroupAgentConfigs()) {
            if (agent.getName().equalsIgnoreCase(mentionedName)) {
                return agent;
            }
        }

        // Partial match: mentioned name is contained in agent name or vice versa
        String mentionedLower = mentionedName.toLowerCase();
        for (AgentConfigDto agent : ctx.getGroupAgentConfigs()) {
            String agentNameLower = agent.getName().toLowerCase();
            if (agentNameLower.contains(mentionedLower) || mentionedLower.contains(agentNameLower)) {
                return agent;
            }
        }

        return null;
    }

    private static boolean filteredKey(String key) {
        return "routeHistory".equals(key) || "CHAT_CONTEXT".equals(key) || "AGENT_ID".equals(key)
                || "REQUEST_ID".equals(key) || "AGENT_USER_MSG".equals(key) || "permissionRequests".equals(key)
                || PLAN_KEY.equals(key) || PLAN_STEP_KEY.equals(key) || ASK_QUESTION_WAITING_ANSWER_KEY.equals(key)
                || key.startsWith("_agent") || key.endsWith("ContextMessages");
    }

    private static String truncateContent(String text) {
        if (text == null) return "";
        if (text.length() <= MAX_CONTENT_LENGTH) return text;
        int headEnd = safeCutPoint(text, MAX_CONTENT_LENGTH / 2);
        int tailStart = safeCutPoint(text, text.length() - MAX_CONTENT_LENGTH / 2);
        return text.substring(0, headEnd) + "\n...[truncated]...\n"
                + text.substring(tailStart);
    }

    /**
     * Adjust cut position backwards to avoid splitting a surrogate pair.
     */
    private static int safeCutPoint(String text, int pos) {
        if (pos <= 0) return 0;
        if (pos >= text.length()) return text.length();
        if (Character.isLowSurrogate(text.charAt(pos))) {
            return pos - 1;
        }
        return pos;
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
    public static class GroupLoopContext {

        @JsonPropertyDescription("The agent context")
        private AgentContext groupAgentContext;

        @Schema(description = "The agent group configs")
        private List<AgentConfigDto> groupAgentConfigs;

        @Schema(description = "The current execution plan")
        private Plan currentPlan;

        @Schema(description = "Current step index in the plan")
        private int currentPlanStepIndex;

        @JsonPropertyDescription("Whether the agent is waiting for user answer")
        private boolean waitingForUserAnswer;

        @Schema(description = "The route history")
        private List<RouteRecord> routeHistory;

        @Schema(description = "The session state")
        private Map<String, Object> sessionState;

        @Schema(description = "The summary")
        private String summary;

        @Schema(description = "The history messages")
        private List<ChatMessageDto> currentMessages;

        private int initialRouteCount;
        private AtomicInteger retryCount;
        private int stepRetryCount;
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
                return trimmed;
            }
            return new ArrayList<>(currentMessages);
        }

        private int getMaxContextTokens() {
            int maxContextTokens = MAX_MESSAGES_TOKEN_BUDGET;
            if (getGroupAgentContext() != null
                    && getGroupAgentContext().getAgentConfig() != null
                    && getGroupAgentContext().getAgentConfig().getModelComponentConfig() != null
                    && getGroupAgentContext().getAgentConfig().getModelComponentConfig().getTargetConfig() instanceof ModelConfigDto modelConfigDto) {
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

    /**
     * Execution plan created by the Planner.
     */
    @Data
    public static class Plan {
        @JsonPropertyDescription("Brief reasoning for this plan")
        private String reasoning;

        @JsonPropertyDescription("Ordered list of execution steps")
        private List<PlanStep> steps;

        @JsonPropertyDescription("Question to ask user when intent is unclear. When set, steps should be empty.")
        private String clarifyingMessage;
    }

    /**
     * A single step in the execution plan.
     */
    @Data
    public static class PlanStep {
        @JsonPropertyDescription("The agent ID to handle this step")
        private String agentId;

        @JsonPropertyDescription("What this step should accomplish")
        private String goal;
    }

    /**
     * Router's decision for a single step.
     */
    @Data
    public static class RouterDecision {
        @JsonPropertyDescription("The input message for the target agent")
        private String inputMessage;

        @JsonPropertyDescription("Context variables for the target agent")
        private Map<String, Object> variables;

        @JsonPropertyDescription("Session state updates")
        private Map<String, Object> stateUpdate;

        @JsonPropertyDescription("Skip the current step")
        private Boolean skip;

        @JsonPropertyDescription("End the entire plan early")
        private Boolean end;

        @JsonPropertyDescription("Replan — replace remaining steps")
        private Boolean replan;

        @JsonPropertyDescription("New plan steps when replan=true")
        private List<PlanStep> newPlanSteps;

        @JsonPropertyDescription("Question to ask user")
        private String clarifyingMessage;
    }

    /**
     * Legacy route record, kept for backward compatibility and route history tracking.
     */
    @Data
    public static class RouteRecord {
        @JsonPropertyDescription("The agent ID")
        private String agentId;
        @JsonPropertyDescription("Extract variables from the context")
        private Map<String, Object> variables;
        private Map<String, Object> stateUpdate;
        private String inputMessage;
        private String clarifyingMessage;
        @JsonPropertyDescription("Whether the task is complete")
        private Boolean end;
    }
}