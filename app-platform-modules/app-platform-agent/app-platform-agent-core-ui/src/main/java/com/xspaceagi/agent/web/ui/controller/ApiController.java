package com.xspaceagi.agent.web.ui.controller;

import com.xspaceagi.agent.core.adapter.application.AgentApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.AgentConfigDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.agent.AgentExecutor;
import com.xspaceagi.agent.core.spec.enums.MessageTypeEnum;
import com.xspaceagi.agent.web.ui.controller.dto.AgentNotificationDto;
import com.xspaceagi.agent.web.ui.controller.dto.VerifyCodeCheckDto;
import com.xspaceagi.agent.web.ui.controller.dto.VerifyCodeSendDto;
import com.xspaceagi.agent.web.ui.controller.dto.api.ConvCreateDto;
import com.xspaceagi.system.application.dto.SendNotifyMessageDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.NotifyMessageApplicationService;
import com.xspaceagi.system.infra.dao.entity.NotifyMessage;
import com.xspaceagi.system.infra.verify.VerifyCodeSendAndCheckService;
import com.xspaceagi.system.infra.verify.sms.SmsConfig;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.CodeTypeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
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
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;

@Tag(name = "开放API")
@RestController
@RequestMapping("/api/v1")
@Slf4j
public class ApiController {

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private AgentApplicationService agentApplicationService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private NotifyMessageApplicationService notifyMessageApplicationService;

    @Resource
    private VerifyCodeSendAndCheckService verifyCodeSendAndCheckService;

    @Resource
    private RedisUtil redisUtil;

    @Resource
    private AgentExecutor agentExecutor;

    @Operation(summary = "查询智能体信息")
    @RequestMapping(path = "/agent", method = RequestMethod.GET)
    public ReqResult<AgentDetailDto> agent() {
        AgentDetailDto agentDetailDto = (AgentDetailDto) RequestContext.get().getAkTarget();
        return ReqResult.success(agentDetailDto);
    }

    @Operation(summary = "创建会话")
    @RequestMapping(path = "/conversation/create", method = RequestMethod.POST)
    public ReqResult<Long> create(@RequestBody ConvCreateDto conversationCreateDto) {
        AgentDetailDto agentDetailDto = (AgentDetailDto) RequestContext.get().getAkTarget();
        UserAccessKeyDto userAccessKeyDto = (UserAccessKeyDto) RequestContext.get().getUserAccessKey();
        Long agentId = agentDetailDto.getAgentId();
        boolean isDevMode = conversationCreateDto.getDevMode() != null && conversationCreateDto.getDevMode();
        if (conversationCreateDto.getDevMode() == null && userAccessKeyDto.getConfig() != null
                && userAccessKeyDto.getConfig().getIsDevMode() != null && userAccessKeyDto.getConfig().getIsDevMode().equals(YesOrNoEnum.Y.getKey())) {
            isDevMode = true;
        }
        if (!isDevMode) {
            PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Agent, agentId);
            if (publishedDto == null) {
                throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentOfflineOrNotFound);
            }
        }
        ConversationDto conversation = conversationApplicationService.createConversation(RequestContext.get().getUserId(), agentId, isDevMode, isDevMode, conversationCreateDto.getVariables());
        return ReqResult.success(conversation == null ? null : conversation.getId());
    }

    @Operation(summary = "查询会话信息（含会话内的历史消息）")
    @RequestMapping(path = "/conversation/{conversationId}", method = RequestMethod.GET)
    public ReqResult<ConversationDto> conversation(@PathVariable Long conversationId) {
        ConversationDto conversationDto = checkConversation(conversationId);
        List<ChatMessageDto> messageDtos = conversationApplicationService.queryConversationMessageList(RequestContext.get().getUserId(), conversationDto.getId(), null, 20);
        conversationDto.setMessageList(messageDtos);
        if (conversationDto.getAgent() != null && StringUtils.isNotBlank(conversationDto.getAgent().getOpeningChatMsg())) {
            ChatMessageDto chatMessageDto = new ChatMessageDto();
            chatMessageDto.setType(MessageTypeEnum.CHAT);
            chatMessageDto.setRole(ChatMessageDto.Role.ASSISTANT);
            chatMessageDto.setText(conversationDto.getAgent().getOpeningChatMsg());
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

    @Operation(summary = "删除会话")
    @RequestMapping(path = "/conversation/{conversationId}/delete", method = RequestMethod.POST)
    public ReqResult<ConversationDto> delete(@PathVariable Long conversationId) {
        checkConversation(conversationId);
        conversationApplicationService.deleteConversation(RequestContext.get().getUserId(), conversationId);
        return ReqResult.success();
    }


    @Operation(summary = "查询历史会话")
    @RequestMapping(path = "/conversations", method = RequestMethod.GET)
    public ReqResult<List<ConversationDto>> conversations() {
        AgentDetailDto agentDetailDto = (AgentDetailDto) RequestContext.get().getAkTarget();
        List<ConversationDto> conversationDtoList = conversationApplicationService.queryConversationList(RequestContext.get().getUserId(), agentDetailDto.getAgentId());
        return ReqResult.success(conversationDtoList);
    }

    @Operation(summary = "智能体会话接口")
    @RequestMapping(path = "/chat", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentOutputDto> chat(@RequestBody @Valid TryReqDto tryReqDto, HttpServletRequest request, HttpServletResponse response) {
        response.setCharacterEncoding("utf-8");
        checkConversation(tryReqDto.getConversationId());
        Map<String, String> headersFromRequest = getHeadersFromRequest(request);
        UserAccessKeyDto userAccessKeyDto = (UserAccessKeyDto) RequestContext.get().getUserAccessKey();
        boolean isDevMode = userAccessKeyDto.getConfig() != null && userAccessKeyDto.getConfig().getIsDevMode() != null && userAccessKeyDto.getConfig().getIsDevMode().equals(YesOrNoEnum.Y.getKey());
        tryReqDto.setFrom("api");
        tryReqDto.setFilterSensitive(null);
        return conversationApplicationService.chat(tryReqDto, headersFromRequest, false, isDevMode);
    }

    @Operation(summary = "智能体会话停止接口")
    @RequestMapping(path = "/chat/stop/{conversationId}", method = RequestMethod.POST)
    public ReqResult<Void> chatStop(@PathVariable String conversationId) {
        try {
            conversation(Long.parseLong(conversationId));
        } catch (NumberFormatException e) {
            // ignore
        }
        redisUtil.set("chat.stop." + conversationId, String.valueOf(System.currentTimeMillis()), 60);
        return ReqResult.success();
    }

    @Operation(summary = "智能体会话问题建议")
    @RequestMapping(path = "/chat/suggest/{conversationId}", method = RequestMethod.GET)
    public Mono<ReqResult<List<String>>> suggestQuestions(@PathVariable Long conversationId) {
        ConversationDto conversationDto = checkConversation(conversationId);
        AgentConfigDto agentConfigDto;
        if (conversationDto.getDevMode() == 1) {
            agentConfigDto = agentApplicationService.queryConfigForTestExecute(conversationDto.getAgentId());
        } else {
            agentConfigDto = agentApplicationService.queryPublishedConfigForExecute(conversationDto.getAgentId());
        }
        if (agentConfigDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentOfflineOrNotFound);
        }
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfigDto.getDefaultSuggestModelId() != null) {
            ModelConfigDto modelConfigDto = modelApplicationService.queryModelConfigById(tenantConfigDto.getDefaultSuggestModelId());
            if (modelConfigDto != null) {
                if (CollectionUtils.isEmpty(modelConfigDto.getApiInfoList())) {
                    log.warn("Model config API list empty; question suggestions disabled");
                    return Mono.just(ReqResult.success(Collections.emptyList()));
                }
            }
            agentConfigDto.getModelComponentConfig().setTargetConfig(modelConfigDto);
        }
        AgentContext agentContext = new AgentContext();
        agentContext.setUserId(RequestContext.get().getUserId());
        agentContext.setAgentConfig(agentConfigDto);
        agentContext.setConversationId(conversationDto.getId().toString());
        agentContext.setDebug(conversationDto.getDevMode() == 1);
        return agentExecutor.suggestQuestions(agentContext).map(ReqResult::success);
    }


    @Operation(summary = "查询智能体用户列表")
    @RequestMapping(path = "/agent/user/list/{cursorUserId}", method = RequestMethod.GET)
    public ReqResult<List<AgentUserDto>> agentUserList(@PathVariable Long cursorUserId) {
        AgentDetailDto agentDetailDto = (AgentDetailDto) RequestContext.get().getAkTarget();
        Long agentId = agentDetailDto.getAgentId();
        return ReqResult.success(conversationApplicationService.queryAgentUserList(agentId, cursorUserId));
    }

    //发送通知消息

    @Operation(summary = "智能体消息推送")
    @RequestMapping(path = "/notification/send", method = RequestMethod.POST)
    public ReqResult<Void> sendNotifyMessage(@RequestBody AgentNotificationDto agentNotificationDto) {
        AgentDetailDto agentDetailDto = (AgentDetailDto) RequestContext.get().getAkTarget();
        Long agentId = agentDetailDto.getAgentId();
        List<ConversationDto> conversationDtoList = conversationApplicationService.queryConversationList(agentNotificationDto.getUserId(), agentId);
        if (CollectionUtils.isEmpty(conversationDtoList)) {
            return ReqResult.error("无向用户发送消息权限");
        }
        notifyMessageApplicationService.sendNotifyMessage(SendNotifyMessageDto.builder()
                .scope(NotifyMessage.MessageScope.System)
                .content(agentNotificationDto.getContent() + "\n" + "`消息来自于" + agentDetailDto.getName() + "`")
                .userIds(Collections.singletonList(agentNotificationDto.getUserId()))
                .build());
        return ReqResult.success();
    }


    @Operation(summary = "智能体验证码消息发送")
    @RequestMapping(path = "/verify-code/send", method = RequestMethod.POST)
    public ReqResult<Void> sendPhoneCode(@RequestBody VerifyCodeSendDto verifyCodeSendDto) {
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        SmsConfig smsConfig = new SmsConfig();
        smsConfig.setSmsAccessKeyId(tenantConfigDto.getSmsAccessKeyId());
        smsConfig.setSmsAccessKeySecret(tenantConfigDto.getSmsAccessKeySecret());
        smsConfig.setSmsSignName(tenantConfigDto.getSmsSignName());
        smsConfig.setSmsTemplateCode(tenantConfigDto.getSmsTemplateCode());
        verifyCodeSendAndCheckService.sendPhoneCode(smsConfig, CodeTypeEnum.VERIFY_CODE, verifyCodeSendDto.getPhone());
        return ReqResult.success();
    }

    @Operation(summary = "智能体验证码消息验证")
    @RequestMapping(path = "/verify-code/check", method = RequestMethod.POST)
    public ReqResult<Void> checkPhoneCode(@RequestBody VerifyCodeCheckDto verifyCodeCheckDto) {
        verifyCodeSendAndCheckService.checkPhoneCode(CodeTypeEnum.VERIFY_CODE, verifyCodeCheckDto.getPhone(), verifyCodeCheckDto.getCode());
        return ReqResult.success();
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

    private ConversationDto checkConversation(Long conversationId) {
        ConversationDto conversationDto = conversationApplicationService.getConversation(null, conversationId);
        if (conversationDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentConversationNotFound);
        }
        if (conversationDto.getAgent() == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentRelatedOfflineOrNotFound);
        }
        //判断会话是否为agent的会话
        AgentDetailDto agentDetailDto = (AgentDetailDto) RequestContext.get().getAkTarget();
        Long agentId = agentDetailDto.getAgentId();
        if (!conversationDto.getAgentId().equals(agentId)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentConversationIdInvalid);
        }
        return conversationDto;
    }

}
