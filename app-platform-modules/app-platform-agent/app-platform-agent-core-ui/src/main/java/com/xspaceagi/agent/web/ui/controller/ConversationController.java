package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.agent.AgentExecutor;
import com.xspaceagi.agent.core.infra.component.agent.SandboxAgentClient;
import com.xspaceagi.agent.core.infra.component.model.ModelPageRequest;
import com.xspaceagi.agent.core.infra.rpc.SandboxServerConfigService;
import com.xspaceagi.agent.core.infra.rpc.UserShareRpcService;
import com.xspaceagi.agent.core.spec.enums.MessageTypeEnum;
import com.xspaceagi.agent.web.ui.controller.dto.ConversationMessageQueryDto;
import com.xspaceagi.agent.web.ui.controller.dto.ConversationShareDto;
import com.xspaceagi.agent.web.ui.controller.dto.ModelPageRequestResultDto;
import com.xspaceagi.agent.web.ui.dto.ConversationCreateDto;
import com.xspaceagi.sandbox.sdk.service.dto.SandboxConfigRpcDto;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.dto.UserShareDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.RedisUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.xspaceagi.system.spec.enums.ResourceEnum.AGENT_QUERY_DETAIL;

@Tag(name = "智能体会话相关接口")
@RestController
@RequestMapping("/api/agent/conversation")
@Slf4j
public class ConversationController {

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private UserShareRpcService userShareRpcService;

    @Resource
    private ModelPageRequest modelPageRequest;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private SandboxServerConfigService sandboxServerConfigService;

    @Resource
    private AgentExecutor agentExecutor;

    @Resource
    private SandboxAgentClient sandboxAgentClient;

    @Operation(summary = "创建会话")
    @RequestMapping(path = "/create", method = RequestMethod.POST)
    public ReqResult<ConversationDto> create(@RequestBody ConversationCreateDto conversationCreateDto) {
        if (conversationCreateDto.isDevMode()) {
            AgentConfigDto agentConfigDto = agentApplicationService.queryById(conversationCreateDto.getAgentId());
            if (agentConfigDto == null) {
                return ReqResult.error("智能体不存在");
            }
            spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId());
        } else {
            PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Agent, conversationCreateDto.getAgentId());
            if (publishedDto == null) {
                return ReqResult.error("智能体不存在或已下架");
            }
            PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(Published.TargetType.Agent, conversationCreateDto.getAgentId());
            if (!publishedPermissionDto.isExecute()) {
                return ReqResult.error("无智能体会话权限");
            }
            //agentApplicationService.addOrUpdateRecentUsed(RequestContext.get().getUserId(), conversationCreateDto.getAgentId());
        }
        return ReqResult.success(conversationApplicationService.createConversation(RequestContext.get().getUserId(), conversationCreateDto.getAgentId(), conversationCreateDto.isDevMode(), conversationCreateDto.getVariables()));
    }

    @Operation(summary = "根据用户消息更新会话主题")
    @RequestMapping(path = "/update", method = RequestMethod.POST)
    public ReqResult<ConversationDto> update(@RequestBody ConversationUpdateDto conversationUpdateDto) {
        ConversationDto conversationDto = conversationApplicationService.getConversation(RequestContext.get().getUserId(), conversationUpdateDto.getId());
        try {
            if (conversationDto != null && conversationDto.getType() != Conversation.ConversationType.TASK) {
                conversationApplicationService.updateConversationTopic(RequestContext.get().getUserId(), conversationUpdateDto);
            }
        } catch (Exception e) {
            log.warn("会话主题更新失败", e);
            // ignore
        }
        conversationDto = conversationApplicationService.getConversation(RequestContext.get().getUserId(), conversationUpdateDto.getId());
        if (conversationDto != null && "未命名会话".equals(conversationDto.getTopic())) {
            conversationDto.setTopic("和" + conversationDto.getAgent().getName() + "开始会话");
        }
        return ReqResult.success(conversationDto == null ? new ConversationDto() : conversationDto);
    }

    @Operation(summary = "删除会话")
    @RequestMapping(path = "/delete/{conversationId}", method = RequestMethod.POST)
    public ReqResult<ConversationDto> delete(@PathVariable Long conversationId) {
        conversationApplicationService.deleteConversation(RequestContext.get().getUserId(), conversationId);
        return ReqResult.success();
    }

    @Operation(summary = "查询会话")
    @RequestMapping(path = "/{conversationId}", method = RequestMethod.POST)
    public ReqResult<ConversationDto> get(@PathVariable Long conversationId) {
        ConversationDto conversationDto = conversationApplicationService.getConversation(null, conversationId);
        if (conversationDto == null) {
            return ReqResult.error("会话不存在");
        }
        if (conversationDto.getAgent() == null) {
            return ReqResult.error("相关智能体不存在或已下架");
        }
        if (conversationDto.getDevMode() != null && conversationDto.getDevMode() == 1) {
            spacePermissionService.checkSpaceUserPermission(conversationDto.getAgent().getSpaceId());
        } else {
            if (!conversationDto.getUserId().equals(RequestContext.get().getUserId())) {
                return ReqResult.error("无智能体会话权限");
            }
            PublishedPermissionDto publishedPermissionDto = publishApplicationService.hasPermission(Published.TargetType.Agent, conversationDto.getAgentId());
            if (!publishedPermissionDto.isView()) {
                return ReqResult.error("无智能体会话权限");
            }
        }

        if (StringUtils.isNotBlank(conversationDto.getSandboxServerId())) {
            try {
                SandboxConfigRpcDto sandboxConfigRpcDto = sandboxServerConfigService.queryById(Long.parseLong(conversationDto.getSandboxServerId()));
                if (sandboxConfigRpcDto != null && sandboxConfigRpcDto.getScope() == SandboxScopeEnum.GLOBAL) {
                    conversationDto.setSandboxServerId("-1");//云端电脑，统一为-1
                }
            } catch (Exception e) {
                log.warn("查询沙盒配置信息失败", e);
            }
        }

        conversationDto.setMessageList(conversationApplicationService.queryConversationMessageList(RequestContext.get().getUserId(), conversationDto.getId(), null, 10));
        if (conversationDto.getAgent() != null && StringUtils.isNotBlank(conversationDto.getAgent().getOpeningChatMsg()) && conversationDto.getMessageList().isEmpty()) {
            ChatMessageDto chatMessageDto = new ChatMessageDto();
            chatMessageDto.setText(conversationDto.getAgent().getOpeningChatMsg());
            chatMessageDto.setType(MessageTypeEnum.CHAT);
            chatMessageDto.setRole(ChatMessageDto.Role.ASSISTANT);
            chatMessageDto.setTime(!conversationDto.getMessageList().isEmpty() ? conversationDto.getMessageList().get(0).getTime() : new Date());
            conversationDto.getMessageList().add(0, chatMessageDto);
        }
        if ("未命名会话".equals(conversationDto.getTopic())) {
            if (conversationDto.getAgent() != null) {
                conversationDto.setTopic("和" + conversationDto.getAgent().getName() + "开始会话");
            }
        }
        return ReqResult.success(conversationDto);
    }

    @Operation(summary = "查询会话消息列表")
    @RequestMapping(path = "/message/list", method = RequestMethod.POST)
    public ReqResult<List<ChatMessageDto>> messageList(@RequestBody ConversationMessageQueryDto conversationMessageQueryDto) {
        Assert.notNull(conversationMessageQueryDto.getConversationId(), "conversationId must be non-null");
        ConversationDto conversationDto = conversationApplicationService.getConversationByCid(conversationMessageQueryDto.getConversationId());
        if (conversationDto == null) {
            return ReqResult.error("会话不存在");
        }
        if (conversationDto.getDevMode() != null && conversationDto.getDevMode() == 1) {
            AgentConfigDto agentConfigDto = agentApplicationService.queryById(conversationDto.getAgentId());
            Assert.notNull(agentConfigDto, "智能体不存在");
            spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId());
        } else {
            if (!conversationDto.getUserId().equals(RequestContext.get().getUserId())) {
                return ReqResult.error("无智能体会话权限");
            }
        }
        Long index = conversationMessageQueryDto.getIndex() == null || conversationMessageQueryDto.getIndex() <= 0 ? Long.MAX_VALUE : conversationMessageQueryDto.getIndex();
        int size = conversationMessageQueryDto.getSize() == null || conversationMessageQueryDto.getSize() < 0 ? 20 : conversationMessageQueryDto.getSize();
        List<ChatMessageDto> chatMessages = conversationApplicationService.queryConversationMessageList(RequestContext.get().getUserId(), conversationDto.getId(), index, size);
        return ReqResult.success(chatMessages);
    }

    @Operation(summary = "查询用户历史会话")
    @RequestMapping(path = "/list", method = RequestMethod.POST)
    public ReqResult<List<ConversationDto>> list(@RequestBody ConversationQueryDto conversationQueryDto) {
        if (conversationQueryDto.getLimit() == null || conversationQueryDto.getLimit() <= 0) {
            conversationQueryDto.setLimit(100);
        }
        List<ConversationDto> conversationDtoList = conversationApplicationService.queryConversationList(RequestContext.get().getUserId(), conversationQueryDto.getAgentId(), conversationQueryDto.getLastId(), conversationQueryDto.getLimit(), conversationQueryDto.getTopic());
        return ReqResult.success(conversationDtoList);
    }

    @Operation(summary = "智能体会话测试接口（勿用）")
    @RequestMapping(path = "/chat", method = RequestMethod.GET, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentOutputDto> testExecute(TryReqDto tryReqDto, HttpServletRequest request, HttpServletResponse response) {
        return tryExecute(tryReqDto, request, response);
    }

    @Operation(summary = "智能体会话接口")
    @RequestMapping(path = "/chat", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentOutputDto> tryExecute(@RequestBody @Valid TryReqDto tryReqDto, HttpServletRequest request, HttpServletResponse response) {
        response.setCharacterEncoding("utf-8");
        Map<String, String> headersFromRequest = getHeadersFromRequest(request);
        tryReqDto.setFrom("chat");
        tryReqDto.setFilterSensitive(null);
        return conversationApplicationService.chat(tryReqDto, headersFromRequest, false);
    }

    @Operation(summary = "停止会话")
    @RequestMapping(path = "/chat/stop/{conversationId}", method = RequestMethod.POST)
    public ReqResult<Void> chatStop(@PathVariable String conversationId) {
        long cid;
        try {
            cid = Long.parseLong(conversationId);
        } catch (NumberFormatException e) {
            // 兼容之前的接口调用
            redisUtil.set("chat.stop." + conversationId, String.valueOf(System.currentTimeMillis()), 60);
            return ReqResult.success();
        }
        ConversationDto conversation = conversationApplicationService.getConversation(RequestContext.get().getUserId(), cid);
        if (conversation == null) {
            return ReqResult.error("会话不存在");
        }

        if ("TaskAgent".equals(conversation.getAgent().getType())) {
            if (!sandboxAgentClient.chatCancel(conversationId)) {
                redisUtil.set("chat.stop." + conversationId, String.valueOf(System.currentTimeMillis()), 60);
            }
        } else {
            redisUtil.set("chat.stop." + conversationId, String.valueOf(System.currentTimeMillis()), 60);
        }
        conversationApplicationService.updateConversationStatus(conversation.getId(), Conversation.ConversationTaskStatus.CANCEL);
        return ReqResult.success();
    }

    @Operation(summary = "获取用户智能体电脑存活的Agent会话")
    @RequestMapping(path = "/alive/{sandboxServerId}", method = RequestMethod.POST)
    public ReqResult<List<ConversationDto>> getSandboxAliveAgentConversation(@PathVariable Long sandboxServerId) {
        SandboxConfigRpcDto sandboxConfigRpcDto = sandboxServerConfigService.queryById(sandboxServerId);
        if (sandboxConfigRpcDto == null || sandboxConfigRpcDto.getScope() != SandboxScopeEnum.USER || !sandboxConfigRpcDto.getIsActive()
                || !sandboxConfigRpcDto.isOnline() || !RequestContext.get().getUserId().equals(sandboxConfigRpcDto.getUserId())) {
            return ReqResult.success(new ArrayList<>());
        }
        return ReqResult.success(conversationApplicationService.queryConversationListBySandboxServerId(sandboxServerId));
    }

    @Operation(summary = "停止智能体电脑存活的Agent")
    @RequestMapping(path = "/agent/stop/{conversationId}", method = RequestMethod.POST)
    public ReqResult<Void> agentStop(@PathVariable Long conversationId) {
        ConversationDto conversationByCid = conversationApplicationService.getConversationByCid(conversationId);
        if (conversationByCid == null || conversationByCid.getUserId().equals(RequestContext.get().getUserId())) {
            return ReqResult.error("无会话权限");
        }
        sandboxAgentClient.agentStop(conversationId.toString());
        return ReqResult.success();
    }

    @Operation(summary = "页面请求结果回写")
    @RequestMapping(path = "/chat/page/result", method = RequestMethod.POST)
    public ReqResult<Void> pageResult(@RequestBody ModelPageRequestResultDto modelPageRequestResultDto) {
        Assert.notNull(modelPageRequestResultDto.getRequestId(), "requestId不能为空");
        modelPageRequest.setPageRequestResult(modelPageRequestResultDto.getRequestId(), modelPageRequestResultDto.getHtml());
        return ReqResult.success();
    }

    @Operation(summary = "智能体会话问题建议")
    @RequestMapping(path = "/chat/suggest", method = RequestMethod.POST)
    public ReqResult<List<String>> suggestQuestions(@RequestBody TryReqDto tryReqDto) {
        ConversationDto conversationDto = conversationApplicationService.getConversation(null, tryReqDto.getConversationId());
        if (conversationDto == null) {
            throw new BizException("会话不存在");
        }
        AgentConfigDto agentConfigDto;
        if (conversationDto.getDevMode() == 1) {
            agentConfigDto = agentApplicationService.queryConfigForTestExecute(conversationDto.getAgentId());
            if (agentConfigDto == null) {
                throw new BizException("智能体不存在");
            }
            //权限验证
            try {
                spacePermissionService.checkSpaceUserPermission(agentConfigDto.getSpaceId());
            } catch (Exception e) {
                throw new BizException("无智能体会话权限");
            }
        } else {
            if (!conversationDto.getUserId().equals(RequestContext.get().getUserId())) {
                throw new BizException("无智能体会话权限");
            }
            agentConfigDto = agentApplicationService.queryPublishedConfigForExecute(conversationDto.getAgentId());
        }
        if (agentConfigDto == null) {
            throw new BizException("智能体不存在或已下架");
        }
        TenantConfigDto tenantConfigDto = tenantConfigApplicationService.getTenantConfig(RequestContext.get().getTenantId());
        if (tenantConfigDto.getDefaultSuggestModelId() != null) {
            ModelConfigDto modelConfigDto = modelApplicationService.queryModelConfigById(tenantConfigDto.getDefaultSuggestModelId());
            if (modelConfigDto != null) {
                if (CollectionUtils.isEmpty(modelConfigDto.getApiInfoList())) {
                    log.warn("模型配置API列表为空，无法进行问题建议");
                    return ReqResult.success(Collections.emptyList());
                }
                agentConfigDto.getModelComponentConfig().setTargetConfig(modelConfigDto);
            }
        }
        AgentContext agentContext = new AgentContext();
        agentContext.setAgentConfig(agentConfigDto);
        agentContext.setConversationId(conversationDto.getId().toString());
        agentContext.setDebug(conversationDto.getDevMode() == 1);
        agentContext.setUserId(RequestContext.get().getUserId());
        try {
            return ReqResult.success(agentExecutor.suggestQuestions(agentContext).timeout(Duration.ofSeconds(30)).block());
        } catch (Exception e) {
            log.warn("问题建议异常", e);
            return ReqResult.success(Collections.emptyList());
        }
    }

    @Operation(summary = "智能体会话、桌面分享")
    @RequestMapping(path = "/share", method = RequestMethod.POST)
    public ReqResult<UserShareDto> share(@RequestBody ConversationShareDto conversationShareDto) {
        Assert.notNull(conversationShareDto.getConversationId(), "conversationId不能为空");
        ConversationDto conversation = conversationApplicationService.getConversation(RequestContext.get().getUserId(), conversationShareDto.getConversationId());
        if (conversation == null) {
            return ReqResult.error("会话不存在");
        }
        UserShareDto userShareDto = new UserShareDto();
        userShareDto.setType(conversationShareDto.getType());
        userShareDto.setTargetId(conversationShareDto.getConversationId().toString());
        userShareDto.setUserId(RequestContext.get().getUserId());
        userShareDto.setContent(conversationShareDto.getContent());
        userShareDto.setExpire(conversationShareDto.getExpireSeconds() == null ? null : Date.from(Instant.now().plusSeconds(conversationShareDto.getExpireSeconds())));
        userShareDto = userShareRpcService.addOrUpdateUserShare(userShareDto);
        return ReqResult.success(userShareDto);
    }

    @Operation(summary = "智能体会话、桌面分享明细查询")
    @RequestMapping(path = "/share/detail/{shareKey}", method = RequestMethod.GET)
    public ReqResult<UserShareDto> shareDetail(@PathVariable String shareKey) {
        UserShareDto userShare = userShareRpcService.getUserShare(shareKey, true);
        if (userShare == null) {
            return ReqResult.error("分享不存在或已过期");
        }
        return ReqResult.success(userShare);
    }

    private Map<String, String> getHeadersFromRequest(HttpServletRequest request) {
        Map<String, String> headers = new HashMap<>();
        Enumeration<String> headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName, request.getHeader(headerName));
        }
        return headers;
    }

}
