package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.ChatApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentComponentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.AgentComponentConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskCancelReqDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskCancelResultDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskDispatchReqDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskDispatchResultDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskItemDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskListReqDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskMessageDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskProgressDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskProgressReqDto;
import com.xspaceagi.agent.web.ui.controller.dto.SandboxAgentTaskSearchReqDto;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * 沙盒侧「智能体任务」接口 —— 把 MasterAgentLoop 内部的任务工具
 * （dispatch / list_running_tasks / search_tasks / query_progress_or_result / cancel_task）
 * 暴露成 4sandbox 端点，供沙盒里的技能通过 Bearer SANDBOX_ACCESS_KEY 调用。
 *
 * <p>★与 {@link TaskCenterForSandboxController}（/api/v1/4sandbox/task，定时任务）不是一个域：
 * 这里管理的是「主智能体派发给子智能体的会话任务」（Conversation，type=Chat）。
 *
 * <p>★安全边界：底层的 getConversationByCid / stopChat / chat 都【没有】归属校验
 * （MasterAgentLoop 里可信是因为跑在循环上下文中），沙盒暴露面必须自己收口：
 * <ul>
 *   <li>身份 = AK 的归属 userId + 该钥匙锚定的 agentId（接受 Agent 型与 Sandbox 型，见
 *       {@link #resolveAkAgentId}；其余类型一律拒绝）；</li>
 *   <li>范围 = AK 智能体自身 + 它配置的子智能体（AgentComponentConfig.Type.Agent）；</li>
 *   <li>progress / cancel / dispatch（续聊）前先做归属校验（userId 相等 且 agentId 在范围内），
 *       失败统一回「任务不存在或无权访问」，不泄露任务是否存在；</li>
 *   <li>dispatch 的 targetAgentId 必须在范围内，不能借 AK 向任意智能体派发。</li>
 * </ul>
 *
 * <p>★dispatch（派发）= 创建（或续聊）会话并触发【真实模型执行】，会产生费用与副作用。
 * 与 MasterAgentLoop#dispatchToAgent 一致走 fire-and-forget：chat() 的同步段把会话置
 * EXECUTING 并启动异步执行，返回的 Flux 只是观察通道，本端点订阅后立即响应，不等任务完成；
 * 启动阶段的早期拒绝（无执行权限等）以 Flux.just 同步回来，能在响应里带出原因。
 * 结果之后用 /progress 查、要停用 /cancel。
 */
@Tag(name = "沙盒Agent任务管理相关接口")
@RestController
@RequestMapping("/api/v1/4sandbox/agent-task")
@Slf4j
public class AgentTaskForSandboxController {

    /**
     * 与 MasterAgentLoop 的 list/search 保持一致的默认条数
     */
    private static final int DEFAULT_LIMIT = 20;
    /**
     * 沙盒面的条数硬上限，防止一次拉爆响应
     */
    private static final int MAX_LIMIT = 50;
    /**
     * 归属校验失败的统一话术：不区分「不存在」与「无权」，避免存在性泄露
     */
    private static final String NOT_FOUND_OR_DENIED = "任务不存在或无权访问";
    /**
     * AK 不合法（非 Agent/Sandbox 类型 / targetId 解不出正数 agentId / 上下文缺失）的统一话术
     */
    private static final String INVALID_AK = "仅支持绑定智能体的访问密钥（AK）调用本接口";
    /**
     * 派发目标不在 AK 可见范围内
     */
    private static final String TARGET_OUT_OF_SCOPE = "目标智能体不在可派发范围内";
    /**
     * 目标智能体拿不到可执行配置（未发布 / 下线 / 模型不可用）
     */
    private static final String TARGET_NOT_EXECUTABLE = "目标智能体不可执行（未发布、已下线或模型不可用）";
    /**
     * 续聊目标任务还在执行中
     */
    private static final String ALREADY_RUNNING = "任务正在执行中，不能重复派发（可等待完成或先取消）";
    /**
     * chat() 启动阶段抛出未预期异常（会话已建，但执行没启动）
     */
    private static final String DISPATCH_START_FAILED = "任务派发启动失败，请稍后重试或用 progress 查看任务状态";
    /**
     * 派发链路在 TryReqDto 上的来源标记：区别于 chat / MasterAgent，日志与统计可按来源区分
     */
    private static final String FROM_SANDBOX = "Sandbox";

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private ChatApplicationService chatApplicationService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Operation(summary = "派发任务给目标智能体（对应 dispatch，触发真实模型执行）")
    @RequestMapping(path = "/dispatch", method = RequestMethod.POST)
    public ReqResult<SandboxAgentTaskDispatchResultDto> dispatch(@RequestBody SandboxAgentTaskDispatchReqDto req) {
        Assert.notNull(req, "请求体不能为空");
        Assert.notNull(req.getTargetAgentId(), "targetAgentId 不能为空");
        Assert.isTrue(StringUtils.isNotBlank(req.getInputMessage()), "inputMessage 不能为空");
        AkScope scope = resolveAkScope();
        if (scope == null) {
            return ReqResult.error(INVALID_AK);
        }
        // ★范围守卫：只能派发给 AK 智能体自身或其配置的子智能体，防止借 AK 向任意智能体派发
        PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(RequestContext.get().getUserId(), Published.TargetType.Agent, req.getTargetAgentId());
        if (!publishedPermissionDto.isExecute()) {
            return ReqResult.error(TARGET_OUT_OF_SCOPE);
        }
        // 预取执行配置：未发布/模型下线时直接报错，不创建孤儿会话；
        // 同时塞进 TryReqDto.agentConfig，chat() 就不必再查一遍（与 MasterAgentLoop#dispatchToAgent 一致）
        AgentConfigDto target;
        try {
            target = agentApplicationService.queryPublishedConfigForExecute(req.getTargetAgentId());
        } catch (Exception e) {
            // queryPublishedConfig 对「模型组件缺失」会直接抛 agentBoundModelOffline，按不可执行处理
            log.warn("4sandbox agent-task dispatch 取执行配置失败 targetAgentId={}", req.getTargetAgentId(), e);
            target = null;
        }
        if (target == null) {
            return ReqResult.error(TARGET_NOT_EXECUTABLE);
        }

        boolean created;
        Long conversationId;
        String topic;
        if (req.getConversationId() != null) {
            // 续聊已有任务：★先归属校验 + 目标一致 + 未在执行中，再触发执行。
            // chat() 自身只校验「会话属于当前用户」，不校验智能体范围，顺序反了就是越权派发。
            ConversationDto conversation = conversationApplicationService.getConversationByCid(req.getConversationId());
            if (!ownedBy(conversation, scope) || !req.getTargetAgentId().equals(conversation.getAgentId())) {
                return ReqResult.error(NOT_FOUND_OR_DENIED);
            }
            if (conversation.getTaskStatus() == Conversation.ConversationTaskStatus.EXECUTING) {
                // 同一会话并发跑两个执行流会互相踩消息与停止标记，直接拒绝
                return ReqResult.error(ALREADY_RUNNING);
            }
            created = false;
            conversationId = conversation.getId();
            topic = conversation.getTopic();
        } else {
            // 与 MasterAgentLoop 一致：主题缺省取任务输入原文
            topic = StringUtils.defaultIfBlank(req.getSubject(), req.getInputMessage());
            Long tenantId = target.getTenantId() != null ? target.getTenantId() : RequestContext.get().getTenantId();
            ConversationDto conversation = conversationApplicationService.createConversationForMasterAgentLoop(
                    tenantId, scope.userId, req.getTargetAgentId(), topic, new HashMap<>());
            created = true;
            conversationId = conversation.getId();
        }

        TryReqDto subReq = new TryReqDto();
        subReq.setMessage(req.getInputMessage());
        subReq.setConversationId(conversationId);
        subReq.setFrom(FROM_SANDBOX);
        subReq.setRequestId(RequestContext.get().getRequestId());
        subReq.setAgentConfig(target);
        if (YesOrNoEnum.Y.getKey().equals(target.getAllowOtherModel())) {
            // 与 MasterAgentLoop 一致：允许换模型的智能体沿用用户为该智能体选定的模型
            subReq.setModelId(chatApplicationService.getSelectedModelId(scope.userId, req.getTargetAgentId()));
        }

        // ★fire-and-forget：chat() 同步段已把会话置 EXECUTING 并在内部启动了异步执行，
        // 返回的 Flux 只是观察通道。启动阶段的早期拒绝（无执行权限/超配额等）以 Flux.just
        // 同步发射，subscribe 的 onNext 会在本方法返回前收到，可捕获拒绝原因带回给调用方。
        AtomicReference<String> startupError = new AtomicReference<>();
        try {
            chatApplicationService.chat(subReq, null, false).subscribe(
                    output -> {
                        if (output != null && StringUtils.isNotBlank(output.getError())) {
                            startupError.compareAndSet(null, output.getError());
                        }
                    },
                    err -> log.error("4sandbox agent-task dispatch 执行异常 conversationId={} targetAgentId={}",
                            conversationId, req.getTargetAgentId(), err),
                    () -> log.info("4sandbox agent-task dispatch 执行结束 conversationId={}", conversationId));
        } catch (Exception e) {
            // chat() 同步段抛出未预期异常：会话已创建但执行没启动，如实报启动失败
            log.error("4sandbox agent-task dispatch 启动失败 conversationId={} targetAgentId={}",
                    conversationId, req.getTargetAgentId(), e);
            return ReqResult.error(DISPATCH_START_FAILED);
        }

        // 回读真实状态：EXECUTING=已启动；仍 CREATE=启动被早期拒绝（原因在 error）；FAILED=启动后立即失败
        String taskStatus = Conversation.ConversationTaskStatus.EXECUTING.name();
        try {
            ConversationDto after = conversationApplicationService.getConversationByCid(conversationId);
            if (after != null) {
                taskStatus = statusName(after.getTaskStatus());
            }
        } catch (Exception e) {
            log.warn("4sandbox agent-task dispatch 回读状态失败 conversationId={}", conversationId, e);
        }
        return ReqResult.success(SandboxAgentTaskDispatchResultDto.builder()
                .conversationId(conversationId)
                .created(created)
                .topic(topic)
                .agentId(req.getTargetAgentId())
                .agentName(StringUtils.defaultIfBlank(target.getName(), scope.agentNames.get(req.getTargetAgentId())))
                .taskStatus(taskStatus)
                .error(startupError.get())
                .build());
    }

    @Operation(summary = "查询执行中的任务（对应 list_running_tasks）")
    @RequestMapping(path = "/list-running", method = RequestMethod.POST)
    public ReqResult<List<SandboxAgentTaskItemDto>> listRunning(@RequestBody(required = false) SandboxAgentTaskListReqDto req) {
        AkScope scope = resolveAkScope();
        if (scope == null) {
            return ReqResult.error(INVALID_AK);
        }
        int limit = normalizeLimit(req == null ? null : req.getLimit());
        List<ConversationDto> conversations = conversationApplicationService.queryLatestConversationList(
                scope.userId, null, null, limit);
        List<SandboxAgentTaskItemDto> items = safeList(conversations).stream()
                .filter(c -> c.getTaskStatus() == Conversation.ConversationTaskStatus.EXECUTING)
                .map(c -> toItem(c, scope))
                .collect(Collectors.toList());
        return ReqResult.success(items);
    }

    @Operation(summary = "搜索任务，含已结束的（对应 search_tasks）")
    @RequestMapping(path = "/search", method = RequestMethod.POST)
    public ReqResult<List<SandboxAgentTaskItemDto>> search(@RequestBody(required = false) SandboxAgentTaskSearchReqDto req) {
        AkScope scope = resolveAkScope();
        if (scope == null) {
            return ReqResult.error(INVALID_AK);
        }
        int limit = normalizeLimit(req == null ? null : req.getLimit());
        String keyword = req == null ? null : StringUtils.trimToNull(req.getKeyword());
        List<ConversationDto> conversations = conversationApplicationService.queryLatestConversationList(
                scope.userId, null, keyword, limit);
        List<SandboxAgentTaskItemDto> items = safeList(conversations).stream()
                .map(c -> toItem(c, scope))
                .collect(Collectors.toList());
        return ReqResult.success(items);
    }

    @Operation(summary = "查询任务进度或结果（对应 query_progress_or_result）")
    @RequestMapping(path = "/progress", method = RequestMethod.POST)
    public ReqResult<SandboxAgentTaskProgressDto> progress(@RequestBody SandboxAgentTaskProgressReqDto req) {
        Assert.notNull(req, "请求体不能为空");
        Assert.notNull(req.getConversationId(), "conversationId 不能为空");
        AkScope scope = resolveAkScope();
        if (scope == null) {
            return ReqResult.error(INVALID_AK);
        }
        ConversationDto conversation = conversationApplicationService.getConversationWithLastMessageByCid(req.getConversationId());
        if (!ownedBy(conversation, scope)) {
            return ReqResult.error(NOT_FOUND_OR_DENIED);
        }
        List<SandboxAgentTaskMessageDto> messages = safeList(conversation.getMessageList()).stream()
                .map(this::toMessage)
                .collect(Collectors.toList());
        SandboxAgentTaskProgressDto dto = SandboxAgentTaskProgressDto.builder()
                .conversationId(conversation.getId())
                .topic(conversation.getTopic())
                .agentId(conversation.getAgentId())
                .agentName(scope.agentNames.get(conversation.getAgentId()))
                .taskStatus(statusName(conversation.getTaskStatus()))
                .running(conversation.getTaskStatus() == Conversation.ConversationTaskStatus.EXECUTING)
                .messages(messages)
                .build();
        return ReqResult.success(dto);
    }

    @Operation(summary = "取消执行中的任务（对应 cancel_task）")
    @RequestMapping(path = "/cancel", method = RequestMethod.POST)
    public ReqResult<SandboxAgentTaskCancelResultDto> cancel(@RequestBody SandboxAgentTaskCancelReqDto req) {
        Assert.notNull(req, "请求体不能为空");
        Assert.notNull(req.getConversationId(), "conversationId 不能为空");
        AkScope scope = resolveAkScope();
        if (scope == null) {
            return ReqResult.error(INVALID_AK);
        }
        // ★先归属校验、再 stopChat：stopChat 自身没有归属检查，顺序反了就是越权取消
        ConversationDto conversation = conversationApplicationService.getConversationByCid(req.getConversationId());
        if (!ownedBy(conversation, scope)) {
            return ReqResult.error(NOT_FOUND_OR_DENIED);
        }
        boolean wasExecuting = conversation.getTaskStatus() == Conversation.ConversationTaskStatus.EXECUTING;
        if (StringUtils.isNotBlank(req.getReason())) {
            // 与 MasterAgentLoop 一致：reason 只记日志，不参与取消逻辑
            log.info("4sandbox agent-task cancel conversationId={} agentId={} reason={}",
                    req.getConversationId(), conversation.getAgentId(), req.getReason());
        }
        chatApplicationService.stopChat(String.valueOf(req.getConversationId()));
        // stopChat 对 EXECUTING 的任务会置 CANCEL；非 EXECUTING 时静默无操作，状态保持原样
        String postStatus = wasExecuting
                ? Conversation.ConversationTaskStatus.CANCEL.name()
                : statusName(conversation.getTaskStatus());
        return ReqResult.success(SandboxAgentTaskCancelResultDto.builder()
                .conversationId(req.getConversationId())
                .cancelled(wasExecuting)
                .taskStatus(postStatus)
                .build());
    }

    // ------------------------------------------------------------------ 身份与范围

    /**
     * 从 AK 解出「这把钥匙锚定哪个智能体」，解不出返回 null。
     *
     * <p>★两种形态都得认 —— 沙盒里默认拿到的就是后者：
     * <ul>
     *   <li>{@code Agent} 型（智能体页签里建的钥匙）：{@code targetId} 就是 agentId；</li>
     *   <li>{@code Sandbox} 型：{@code targetId} 是 {@code "{agentId}-{conversationId}"}。这是
     *       {@code ChatApplicationServiceImpl} 铸沙盒钥匙时拼的，并作为 {@code SANDBOX_ACCESS_KEY}
     *       注入沙盒环境变量 —— 所以技能手里那把必然是 Sandbox 型。取第一个 {@code '-'} 之前的部分。</li>
     * </ul>
     * 其余类型（Tenant / OpenApi / Mcp / TempChat …）不代表某个具体智能体，没有可锚定的可见范围，一律不认。
     */
    private static Long resolveAkAgentId(UserAccessKeyDto ak) {
        String targetId = ak.getTargetId();
        if (StringUtils.isBlank(targetId)) {
            return null;
        }
        if (ak.getTargetType() == UserAccessKeyDto.AKTargetType.Agent) {
            return parsePositiveLong(targetId);
        }
        if (ak.getTargetType() == UserAccessKeyDto.AKTargetType.Sandbox) {
            int dash = targetId.indexOf('-');
            return parsePositiveLong(dash < 0 ? targetId : targetId.substring(0, dash));
        }
        return null;
    }

    /**
     * 解析成正数 ID；非数字 / 非正数一律 null（宁可不认，也不锚到 0 或负数上）。
     */
    private static Long parsePositiveLong(String raw) {
        try {
            long value = Long.parseLong(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 解析本次调用的 AK 身份与可见范围。返回 null 表示钥匙不合法（统一回 INVALID_AK）。
     */
    private AkScope resolveAkScope() {
        RequestContext context = RequestContext.get();
        if (context == null || context.getUserId() == null) {
            return null;
        }
        Object akObj = context.getUserAccessKey();
        if (!(akObj instanceof UserAccessKeyDto)) {
            return null;
        }
        UserAccessKeyDto ak = (UserAccessKeyDto) akObj;
        Long akAgentId = resolveAkAgentId(ak);
        if (akAgentId == null) {
            return null;
        }
        List<Long> scopeAgentIds = new ArrayList<>();
        scopeAgentIds.add(akAgentId);
        Map<Long, String> agentNames = new HashMap<>();
        Object akTarget = context.getAkTarget();
        if (akTarget instanceof AgentDetailDto) {
            agentNames.put(akAgentId, ((AgentDetailDto) akTarget).getName());
        } else {
            // Sandbox 型钥匙在 ApiKeyInterceptor 里走的是「直接放行」分支，不会给 akTarget 赋值，
            // 自身名字就缺了。名字只用于展示，补查一次；查不到也只是少个名字，不能因此拒掉整次调用。
            try {
                AgentDetailDto ownAgent = agentApplicationService.queryAgentDetail(akAgentId, false);
                if (ownAgent != null) {
                    agentNames.put(akAgentId, ownAgent.getName());
                }
            } catch (Exception e) {
                log.warn("4sandbox agent-task 查智能体名称失败，按无名字处理 agentId={}", akAgentId, e);
            }
        }
        try {
            List<AgentComponentConfigDto> subAgents = agentApplicationService
                    .queryComponentConfigListByAgentIdAndType(akAgentId, AgentComponentConfig.Type.Agent);
            for (AgentComponentConfigDto sub : safeList(subAgents)) {
                if (sub.getTargetId() != null && !scopeAgentIds.contains(sub.getTargetId())) {
                    scopeAgentIds.add(sub.getTargetId());
                    agentNames.put(sub.getTargetId(), sub.getName());
                }
            }
        } catch (Exception e) {
            // 子智能体列表解析失败时收窄到「仅自身」——宁可少看，不能多看
            log.warn("4sandbox agent-task 解析子智能体列表失败，按仅自身处理 agentId={}", akAgentId, e);
        }
        return new AkScope(context.getUserId(), akAgentId, scopeAgentIds, agentNames);
    }

    /**
     * 归属校验：任务是「当前 AK 用户」的，且执行智能体在 AK 的可见范围内。
     * ★getConversationByCid 底层没有任何用户过滤，这里是唯一的收口点。
     */
    private boolean ownedBy(ConversationDto conversation, AkScope scope) {
        return conversation != null
                && scope.userId.equals(conversation.getUserId());
    }

    // ------------------------------------------------------------------ 转换

    private SandboxAgentTaskItemDto toItem(ConversationDto conversation, AkScope scope) {
        return SandboxAgentTaskItemDto.builder()
                .conversationId(conversation.getId())
                .topic(conversation.getTopic())
                .agentId(conversation.getAgentId())
                .agentName(scope.agentNames.get(conversation.getAgentId()))
                .taskStatus(statusName(conversation.getTaskStatus()))
                .created(conversation.getCreated())
                .modified(conversation.getModified())
                .build();
    }

    private SandboxAgentTaskMessageDto toMessage(ChatMessageDto message) {
        return SandboxAgentTaskMessageDto.builder()
                .role(message.getRole() == null ? null : message.getRole().name())
                .senderType(message.getSenderType() == null ? null : message.getSenderType().name())
                .text(message.getText())
                .time(message.getTime())
                .build();
    }

    /**
     * 与 MasterAgentLoop#loadHistoryTasks 一致：taskStatus 为空按 CREATE 报
     */
    private String statusName(Conversation.ConversationTaskStatus status) {
        return status == null ? Conversation.ConversationTaskStatus.CREATE.name() : status.name();
    }

    private int normalizeLimit(Integer limit) {
        if (limit == null || limit <= 0) {
            return DEFAULT_LIMIT;
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private static <T> List<T> safeList(List<T> list) {
        return list == null ? Collections.emptyList() : list;
    }

    /**
     * 一次调用内不可变的 AK 身份快照
     */
    private static final class AkScope {
        private final Long userId;
        private final Long akAgentId;
        private final List<Long> scopeAgentIds;
        private final Map<Long, String> agentNames;

        private AkScope(Long userId, Long akAgentId, List<Long> scopeAgentIds, Map<Long, String> agentNames) {
            this.userId = userId;
            this.akAgentId = akAgentId;
            this.scopeAgentIds = scopeAgentIds;
            this.agentNames = agentNames;
        }
    }
}
