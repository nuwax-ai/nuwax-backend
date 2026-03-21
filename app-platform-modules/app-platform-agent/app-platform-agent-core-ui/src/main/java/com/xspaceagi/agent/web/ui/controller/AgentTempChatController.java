package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.AgentTempChatApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AgentOutputDto;
import com.xspaceagi.agent.core.adapter.dto.AgentTempChatDto;
import com.xspaceagi.agent.core.adapter.dto.ConversationDto;
import com.xspaceagi.agent.core.adapter.dto.TryReqDto;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import com.xspaceagi.agent.web.ui.controller.dto.TempChatMessage;
import com.xspaceagi.agent.web.ui.controller.dto.TempConversationCreateDto;
import com.xspaceagi.agent.web.ui.controller.dto.TempConversationQueryDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.HttpStatusEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.utils.RedisUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.MediaType;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.xspaceagi.system.spec.enums.ResourceEnum.AGENT_TEMP_CONVERSATION;

@Tag(name = "智能体临时会话管理")
@RestController
@RequestMapping("/api/temp/chat")
@Slf4j
public class AgentTempChatController {

    @Resource
    private AgentTempChatApplicationService agentTempChatApplicationService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private RedisUtil redisUtil;

    @RequireResource(AGENT_TEMP_CONVERSATION)
    @Operation(summary = "新增智能体临时会话链接接口")
    @RequestMapping(path = "/{agentId}/link/create", method = RequestMethod.POST)
    public ReqResult<AgentTempChatDto> linkCreate(@PathVariable Long agentId) {
        checkAgentPermission(agentId);
        AgentTempChatDto agentTempChatDto = agentTempChatApplicationService.createTempChat(agentId);
        return ReqResult.success(agentTempChatDto);
    }

    @RequireResource(AGENT_TEMP_CONVERSATION)
    @Operation(summary = "删除智能体临时会话链接接口")
    @RequestMapping(path = "/{agentId}/link/{id}/delete", method = RequestMethod.POST)
    public ReqResult<Void> linkDelete(@PathVariable Long agentId, @PathVariable Long id) {
        checkAgentPermission(agentId);
        agentTempChatApplicationService.deleteTempChat(agentId, id);
        return ReqResult.success();
    }

    @RequireResource(AGENT_TEMP_CONVERSATION)
    @Operation(summary = "修改智能体临时会话链接接口")
    @RequestMapping(path = "/link/update", method = RequestMethod.POST)
    public ReqResult<Void> updateLink(@RequestBody AgentTempChatDto agentTempChatDto) {
        checkAgentPermission(agentTempChatDto.getAgentId());
        agentTempChatApplicationService.updateTempChat(agentTempChatDto);
        return ReqResult.success();
    }

    @RequireResource(AGENT_TEMP_CONVERSATION)
    @Operation(summary = "查询智能体临时会话链接接口")
    @RequestMapping(path = "/{agentId}/link/list", method = RequestMethod.GET)
    public ReqResult<List<AgentTempChatDto>> queryLinkList(@PathVariable Long agentId) {
        checkAgentPermission(agentId);
        List<AgentTempChatDto> agentTempChatDtos = agentTempChatApplicationService.queryTempChatList(agentId);
        return ReqResult.success(agentTempChatDtos);
    }

    @Operation(summary = "创建临时会话")
    @RequestMapping(path = "/conversation/create", method = RequestMethod.POST)
    public ReqResult<ConversationDto> createConversation(@RequestBody @Valid TempConversationCreateDto conversationCreateDto, HttpServletRequest request) {
        AgentTempChatDto agentTempChatDto = queryAndCheckTempChatByChatKey(request, conversationCreateDto.getChatKey());
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        boolean allowAgentTempChat = tenantConfigDto.getAllowAgentTempChat() == null || tenantConfigDto.getAllowAgentTempChat().equals(YesOrNoEnum.Y.getKey());
        if (!allowAgentTempChat && userDto.getRole() != User.Role.Admin) {
            return ReqResult.error("当前不允许使用临时会话");
        }
        ConversationDto conversationDto = conversationApplicationService.createConversation(userDto.getId(), agentTempChatDto.getAgentId(), true, true);
        return ReqResult.success(conversationDto);
    }

    @Operation(summary = "查询临时会话详细")
    @RequestMapping(path = "/conversation/query", method = RequestMethod.POST)
    public ReqResult<ConversationDto> createConversation(@RequestBody @Valid TempConversationQueryDto tempConversationQueryDto, HttpServletRequest request) {
        AgentTempChatDto agentTempChatDto = queryAndCheckTempChatByChatKey(request, tempConversationQueryDto.getChatKey());
        ConversationDto conversation = conversationApplicationService.getConversationByUid(tempConversationQueryDto.getConversationUid());
        if (conversation.getType() != Conversation.ConversationType.TempChat || !conversation.getAgentId().equals(agentTempChatDto.getAgentId())) {
            throw new BizException("会话不存在");
        }
        int size = tempConversationQueryDto.getSize() == null || tempConversationQueryDto.getSize() < 0 ? 20 : tempConversationQueryDto.getSize();
        conversation.setMessageList(conversationApplicationService.queryConversationMessageList(RequestContext.get().getUserId(), conversation.getId(), tempConversationQueryDto.getIndex(), size));
        return ReqResult.success(conversation);
    }

    @Operation(summary = "停止临时会话")
    @RequestMapping(path = "/conversation/{requestId}", method = RequestMethod.POST)
    public ReqResult<Void> stopConversation(@PathVariable String requestId) {
        redisUtil.set("chat.stop." + requestId, String.valueOf(System.currentTimeMillis()), 60);
        return ReqResult.success();
    }

    @Operation(summary = "消息会话")
    @RequestMapping(path = "/completions", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentOutputDto> chat(@RequestBody @Valid TempChatMessage chatMessage, HttpServletRequest request, HttpServletResponse response) {
        response.setCharacterEncoding("utf-8");
        AgentTempChatDto agentTempChatDto = queryAndCheckTempChatByChatKey(request, chatMessage.getChatKey());
        ConversationDto conversation = conversationApplicationService.getConversationByUid(chatMessage.getConversationUid());
        if (conversation == null || conversation.getType() != Conversation.ConversationType.TempChat) {
            throw new BizException("会话不存在");
        }
        if (!agentTempChatDto.getAgentId().equals(conversation.getAgentId())) {
            throw new BizException("会话不存在");
        }
        Map<String, String> headersFromRequest = getHeadersFromRequest(request);
        TryReqDto tryReqDto = new TryReqDto();
        tryReqDto.setFrom("temp_chat");
        tryReqDto.setConversationId(conversation.getId());
        tryReqDto.setMessage(chatMessage.getMessage());
        tryReqDto.setVariableParams(chatMessage.getVariableParams());
        tryReqDto.setDebug(true);
        tryReqDto.setAttachments(chatMessage.getAttachments());
        tryReqDto.setSelectedComponents(chatMessage.getSelectedComponents());
        tryReqDto.setFilterSensitive(null);
        return conversationApplicationService.chat(tryReqDto, headersFromRequest, true);
    }

    private AgentTempChatDto queryAndCheckTempChatByChatKey(HttpServletRequest request, String chatKey) {
        Assert.isTrue(StringUtils.isNotBlank(chatKey), "chatKey must be non-null");
        AgentTempChatDto agentTempChatDto = agentTempChatApplicationService.queryTempChatByChatKey(chatKey);
        if (agentTempChatDto == null) {
            throw new BizException("临时会话不存在");
        }
        if (agentTempChatDto.getExpire() != null && agentTempChatDto.getExpire().getTime() < System.currentTimeMillis()) {
            throw new BizException("临时会话已过期");
        }
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        if (agentTempChatDto.getRequireLogin() == 1) {
            String referer = request.getHeader("Referer");
            if (userDto == null) {
                if (StringUtils.isNotBlank(referer)) {
                    throw new BizException("4011", "/login?redirect=" + URLEncoder.encode(referer, StandardCharsets.UTF_8));
                }else{
                    throw new BizException(HttpStatusEnum.UNAUTHORIZED, ErrorCodeEnum.UNAUTHORIZED);
                }
            }
        }
        if (userDto == null) {
            userDto = userApplicationService.queryById(agentTempChatDto.getUserId());
            RequestContext.get().setUser(userDto);
            RequestContext.get().setUserId(userDto.getId());
        }
        return agentTempChatDto;
    }

    private Map<String, String> getHeadersFromRequest(HttpServletRequest request) {
        Enumeration<String> headerNames = request.getHeaderNames();
        Map<String, String> headers = new HashMap<>();
        while (headerNames.hasMoreElements()) {
            String headerName = headerNames.nextElement();
            headers.put(headerName, request.getHeader(headerName));
        }
        return headers;
    }

    private void checkAgentPermission(Long agentId) {
        AgentConfigDto agentDto = agentApplicationService.queryById(agentId);
        if (agentDto == null) {
            throw new BizException("Agent不存在");
        }
        spacePermissionService.checkSpaceUserPermission(agentDto.getSpaceId());
    }
}
