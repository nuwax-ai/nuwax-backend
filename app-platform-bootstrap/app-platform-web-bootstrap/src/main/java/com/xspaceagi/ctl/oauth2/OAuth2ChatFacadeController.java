package com.xspaceagi.ctl.oauth2;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.xspaceagi.agent.core.adapter.application.ChatApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AgentOutputDto;
import com.xspaceagi.agent.core.adapter.dto.ChatMessageDto;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.ConversationUpdateDto;
import com.xspaceagi.agent.core.adapter.dto.PublishedDto;
import com.xspaceagi.agent.core.adapter.dto.TryReqDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.infra.component.agent.SandboxAgentClient;
import com.xspaceagi.agent.web.ui.controller.dto.PermissionRequestResponseDto;
import com.xspaceagi.agent.web.ui.controller.dto.api.ConvCreateDto;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.I18nUtil;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/**
 * OAuth2 门面 —— 任务会话管理组（对应开放接口 {@code /api/v1/chat/**}）。
 * <p>
 * scope 划分：读 {@code chat:read}（会话列表 / 详情、消息列表、订阅），
 * 写 {@code chat:write}（建 / 改 / 删会话、发消息、停止、清消息、权限请求回复）。
 * <p>
 * ★<b>路径带动词、不用 HTTP 方法区分读写</b>：{@code OAuth2ScopeRegistry.allows} 只匹配路径、
 * 看不到方法，若沿用 {@code /conversation/{id}} 这种 REST 风格，「读详情」和「发消息」
 * 会落在同一个路径模式上，scope 就无法区分。因此会话 id 一律走请求参数 / 请求体，路径保持字面量。
 * <p>
 * ★<b>转调而非重写</b>：每个端点都对齐 {@code ChatApiController} 的既有口径，包括它的私有校验
 * （{@link #checkPermission}、{@link #checkConversation}、{@link #getHeadersFromRequest}）——
 * 这些校验在 controller 里而不是 service 里，直接调 service 会<b>静默绕过</b>，所以必须在此复刻。
 * <p>
 * ★相比原开放接口<b>补了两处归属校验</b>（原实现缺失，门面不能照抄漏洞）：
 * <ul>
 * <li>{@code subscribe}：原 {@code GET /api/v1/chat/{cid}/subscribe} 不校验会话归属，
 * 任何持 AK 者都能订阅他人会话的实时输出；</li>
 * <li>{@code clear}：{@code clearConversationMessages(cid)} 的签名<b>不带 userId</b>，
 * 归属校验完全依赖调用前先 {@code getConversation(userId, cid)}。</li>
 * </ul>
 * <p>
 * ⚠️ <b>本轮未纳入</b>：会话产物文件的两个端点（{@code /{cid}/files} 列表、{@code /{cid}/file/**} 二进制流）。
 * 原因不是遗漏——{@code ComputerFileApplicationServiceImpl.getStaticFile} 靠<b>硬编码 URI 前缀</b>
 * （{@code /api/v1/chat/{cid}/file/}、{@code /api/computer/static/{cid}/}）从 requestURI 反解相对路径，
 * 门面路径两个前缀都不含，会直接 400；且它有独立的一套鉴权（{@code staticFileAuth}，
 * 含匿名产物访问与浏览器登录跳转），与 OAuth2 scope 语义不兼容。要开放需改共享 service 加显式
 * relativePath 入参，属独立改动，不夹带在本轮。
 */
@Tag(name = "OAuth2 门面-任务会话", description = "需 OAuth2 access_token（oa- 前缀）")
@RestController
@RequestMapping("/api/oauth2/chat")
@Slf4j
public class OAuth2ChatFacadeController {

    /** TaskAgent 的停止 / 权限回复要额外通知沙箱侧，类型名与既有实现保持一致 */
    private static final String AGENT_TYPE_TASK = "TaskAgent";

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private ChatApplicationService chatApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private SandboxAgentClient sandboxAgentClient;

    // ================================================================== 读（chat:read）

    @Operation(summary = "查询授权用户在某智能体下的历史会话")
    @GetMapping("/conversation/list")
    public ReqResult<List<ConversationDto>> conversationList(
            @RequestParam("agentId") Long agentId,
            @RequestParam(value = "lastId", required = false) Long lastId,
            @RequestParam(value = "limit", required = false, defaultValue = "10") Integer limit,
            @RequestParam(value = "topic", required = false) String topic) {
        return ReqResult.success(conversationApplicationService.queryConversationList(
                RequestContext.get().getUserId(), agentId, lastId, limit, topic));
    }

    @Operation(summary = "查询会话详情（默认主题会按智能体名回填为可读文案）")
    @GetMapping("/conversation/detail")
    public ReqResult<ConversationDto> conversationDetail(@RequestParam("conversationId") Long conversationId) {
        return ReqResult.success(detail(conversationId));
    }

    @Operation(summary = "查询会话消息列表")
    @GetMapping("/message/list")
    public ReqResult<List<ChatMessageDto>> messageList(
            @RequestParam("conversationId") Long conversationId,
            @RequestParam(value = "index", required = false) Long index,
            @RequestParam(value = "limit", required = false, defaultValue = "10") Integer limit) {
        checkConversation(conversationId);
        // 第 4 个入参是 int（非 Integer），limit 必须有值，否则拆箱 NPE
        return ReqResult.success(conversationApplicationService.queryConversationMessageList(
                RequestContext.get().getUserId(), conversationId, index, limit == null ? 10 : limit));
    }

    /**
     * 订阅会话的实时输出（SSE）。
     * <p>
     * ★补归属校验：{@code chatApplicationService.subscribe(cid)} 本身不认用户，
     * 原开放接口直接透传，等于谁都能听别人的会话。
     */
    @Operation(summary = "订阅会话实时输出（SSE）")
    @GetMapping(value = "/message/subscribe", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentOutputDto> messageSubscribe(@RequestParam("conversationId") Long conversationId,
                                                 HttpServletResponse response) {
        checkConversation(conversationId);
        response.setCharacterEncoding("utf-8");
        return chatApplicationService.subscribe(conversationId);
    }

    // ================================================================== 写（chat:write）

    /**
     * 创建会话。
     * <p>
     * 前置校验与 {@code ChatApiController.add} 一致：智能体必须已发布（否则 {@code agentOfflineOrNotFound}），
     * 且发布范围允许当前用户使用（{@link #checkPermission}）。少了这两步，第三方就能拿任意 agentId
     * 给授权用户建会话，包括未发布 / 限定空间的智能体。
     */
    @Operation(summary = "创建会话（返回会话 id）")
    @PostMapping("/conversation/create")
    public ReqResult<Long> conversationCreate(@RequestBody ConvCreateDto createDto) {
        Assert.notNull(createDto.getAgentId(), "Agent id cannot be null");
        PublishedDto published = publishApplicationService.queryPublished(Published.TargetType.Agent,
                createDto.getAgentId());
        if (published == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentOfflineOrNotFound);
        }
        checkPermission(published.getPublishedSpaceIds(), published.getScope());
        ConversationDto conversation = conversationApplicationService.createConversation(
                RequestContext.get().getUserId(), createDto.getAgentId(), false, false, createDto.getVariables());
        if (conversation == null) {
            return ReqResult.success(null);
        }
        if (StringUtils.isNotBlank(createDto.getTopic())) {
            ConversationUpdateDto updateDto = new ConversationUpdateDto();
            updateDto.setId(conversation.getId());
            updateDto.setTopic(createDto.getTopic());
            conversationApplicationService.updateConversationTopic(RequestContext.get().getUserId(), updateDto);
        }
        return ReqResult.success(conversation.getId());
    }

    @Operation(summary = "更新会话主题")
    @PostMapping("/conversation/update")
    public ReqResult<ConversationDto> conversationUpdate(@RequestBody ConversationUpdateDto updateDto) {
        Assert.notNull(updateDto.getId(), "Conversation id cannot be null");
        checkConversation(updateDto.getId());
        conversationApplicationService.updateConversationTopic(RequestContext.get().getUserId(), updateDto);
        return ReqResult.success(detail(updateDto.getId()));
    }

    @Operation(summary = "删除会话")
    @PostMapping("/conversation/delete")
    public ReqResult<Void> conversationDelete(@RequestParam("conversationId") Long conversationId) {
        checkConversation(conversationId);
        conversationApplicationService.deleteConversation(RequestContext.get().getUserId(), conversationId);
        return ReqResult.success();
    }

    /**
     * 发起对话（SSE 流式返回）。
     * <p>
     * ★三处入参强制覆盖，与既有开放接口一致，<b>不能让调用方自带</b>：
     * {@code from="api"}（流量归因）、{@code filterSensitive=null}（敏感词过滤策略由平台定，
     * 传值可能关掉过滤）、{@code agentConfig=null}（否则调用方可就地改写智能体配置，
     * 等于绕过发布与权限体系）。
     * <p>
     * header 只在返回 Flux <b>之前</b>同步拷贝一份，之后不再触碰 request，
     * 因此不依赖 Servlet 请求在异步线程里存活。会话归属、访问控制、计价与日配额校验都在
     * {@code ChatApplicationServiceImpl} 内部，不会被门面绕过。
     */
    @Operation(summary = "发起对话（SSE 流式返回）")
    @PostMapping(value = "/message/send", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentOutputDto> messageSend(@RequestBody @Valid TryReqDto tryReqDto, HttpServletRequest request,
                                            HttpServletResponse response) {
        Assert.notNull(tryReqDto.getConversationId(), "Conversation id cannot be null");
        checkConversation(tryReqDto.getConversationId());
        response.setCharacterEncoding("utf-8");
        Map<String, String> headers = getHeadersFromRequest(request);
        tryReqDto.setFrom("api");
        tryReqDto.setFilterSensitive(null);
        tryReqDto.setAgentConfig(null);
        return chatApplicationService.chat(tryReqDto, headers, false, false);
    }

    /**
     * 停止对话。
     * <p>
     * 保留原实现「conversationId 解析失败也要落停止标记」的兼容分支（历史上存在非数字的会话标识），
     * 但<b>只在能解析成数字时</b>才做归属校验与状态回写——解析不出归属就只打停止标记，
     * 与 {@code ChatApiController.chatStop} 行为一致。
     */
    @Operation(summary = "停止对话")
    @PostMapping("/message/stop")
    public ReqResult<Void> messageStop(@RequestParam("conversationId") String conversationId) {
        long cid;
        try {
            cid = Long.parseLong(conversationId);
        } catch (NumberFormatException e) {
            chatApplicationService.setChatStopStatus(conversationId);
            return ReqResult.success();
        }
        ConversationDto conversation = conversationApplicationService.getConversation(
                RequestContext.get().getUserId(), cid);
        if (conversation == null) {
            return ReqResult.error("Conversation not found");
        }
        if (conversation.getAgent() != null && AGENT_TYPE_TASK.equals(conversation.getAgent().getType())) {
            sandboxAgentClient.chatCancel(conversationId);
        }
        chatApplicationService.setChatStopStatus(conversationId);
        conversationApplicationService.updateConversationStatus(cid, Conversation.ConversationTaskStatus.CANCEL);
        return ReqResult.success();
    }

    /**
     * 清空会话历史消息。
     * <p>
     * ★{@code clearConversationMessages(cid)} 的签名<b>不带 userId</b>，service 内部不做归属校验；
     * 前置的 {@link #checkConversation} 是这里唯一的越权防线，不能省。
     */
    @Operation(summary = "清空会话历史消息")
    @PostMapping("/message/clear")
    public ReqResult<Void> messageClear(@RequestParam("conversationId") Long conversationId) {
        checkConversation(conversationId);
        conversationApplicationService.clearConversationMessages(conversationId);
        return ReqResult.success();
    }

    /**
     * 回复智能体发起的权限请求（仅 TaskAgent 生效）。
     * <p>
     * 非 TaskAgent 的会话直接返回成功而不通知沙箱——与既有实现一致，避免为不支持的会话类型报错。
     */
    @Operation(summary = "回复权限请求")
    @PostMapping("/message/permission-response")
    public ReqResult<Void> permissionResponse(@RequestBody PermissionRequestResponseDto responseDto) {
        Assert.notNull(responseDto.getConversationId(), "Conversation id cannot be null");
        ConversationDto conversation = conversationApplicationService.getConversation(
                RequestContext.get().getUserId(), responseDto.getConversationId());
        if (conversation == null) {
            return ReqResult.error("Conversation not found");
        }
        if (conversation.getAgent() != null && AGENT_TYPE_TASK.equals(conversation.getAgent().getType())) {
            Assert.notNull(responseDto.getOption(), "Option is null");
            sandboxAgentClient.notifyResolved(responseDto.getConversationId().toString(), responseDto.getToolId(),
                    Map.of("optionId", responseDto.getOption().getOptionId(), "outcome", "selected"));
        }
        return ReqResult.success();
    }

    // ================================================================== 私有校验（复刻自 ChatApiController）

    /**
     * 会话详情：默认主题按智能体名回填为可读文案（与 {@code ChatApiController.conversation} 一致）
     */
    private ConversationDto detail(Long conversationId) {
        ConversationDto conversation = checkConversation(conversationId);
        if (conversation.isDefaultTopic() && conversation.getAgent() != null) {
            conversation.setTopic(I18nUtil.systemMessage("Backend.Conversation.DefaultTopic",
                    conversation.getAgent().getName()));
        }
        return conversation;
    }

    /**
     * 归属校验：会话不存在、不属于当前授权用户、或已无关联智能体，一律按「非法 conversationId」拒绝。
     * <p>
     * 三种情况<b>刻意不区分</b>，否则会退化成「某个会话 id 是否存在 / 属于谁」的探测器。
     */
    private ConversationDto checkConversation(Long conversationId) {
        if (conversationId == null) {
            throw new BizException("Invalid conversationId");
        }
        ConversationDto conversation = conversationApplicationService.getConversation(
                RequestContext.get().getUserId(), conversationId);
        if (conversation == null || conversation.getAgent() == null) {
            throw new BizException("Invalid conversationId");
        }
        return conversation;
    }

    /**
     * 发布范围校验：租户级发布直接放行；空间级发布要求当前用户至少属于其中一个发布空间。
     * <p>
     * 与 {@code ChatApiController.checkPermission} 同逻辑（含「publishedSpaceIds 为空即放行」这一既有口径）。
     * 注意这里必须用 {@code queryListByUserId}（鉴权口径），不能用 {@code queryVisibleListByUserId}
     * ——后者会漏掉私聊 / 群组空间，用于鉴权会误判。
     */
    private void checkPermission(List<Long> publishedSpaceIds, Published.PublishScope scope) {
        if (scope == Published.PublishScope.Tenant) {
            return;
        }
        List<SpaceDto> spaces = spaceApplicationService.queryListByUserId(RequestContext.get().getUserId());
        List<Long> userSpaceIds = spaces == null ? List.of() : spaces.stream().map(SpaceDto::getId).toList();
        if (CollectionUtils.isNotEmpty(publishedSpaceIds)) {
            boolean anyMatch = publishedSpaceIds.stream()
                    .anyMatch(spaceId -> spaceId != null && userSpaceIds.contains(spaceId));
            if (!anyMatch) {
                throw new BizException("Permission denied");
            }
        }
    }

    /**
     * 同步拷贝请求头：下游 {@code chat()} 需要从中读取链路 / 客户端信息。
     * 必须在返回 Flux 之前完成，之后 Servlet 请求可能已被容器回收。
     */
    private static Map<String, String> getHeadersFromRequest(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames != null && headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName, request.getHeader(headerName));
        }
        return headers;
    }
}
