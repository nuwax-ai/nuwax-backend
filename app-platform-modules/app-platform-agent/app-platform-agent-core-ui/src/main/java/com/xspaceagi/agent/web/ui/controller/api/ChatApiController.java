package com.xspaceagi.agent.web.ui.controller.api;

import com.xspaceagi.agent.core.adapter.application.ChatApplicationService;
import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.application.IComputerFileApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.repository.entity.Conversation;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.infra.component.agent.SandboxAgentClient;
import com.xspaceagi.agent.core.application.service.ConversationPermissionChecker;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFileListRes;
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
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;

import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Tag(name = "开放API-会话相关接口")
@RestController
@RequestMapping("/api/v1/chat")
@Slf4j
public class ChatApiController {

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private ChatApplicationService chatApplicationService;

    @Resource
    private IComputerFileApplicationService computerFileApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private ConversationPermissionChecker conversationPermissionChecker;

    @Resource
    private SandboxAgentClient sandboxAgentClient;

    @Operation(summary = "创建会话")
    @RequestMapping(path = "/conversation/add", method = RequestMethod.POST)
    public ReqResult<Long> add(@RequestBody ConvCreateDto conversationCreateDto) {
        Assert.notNull(conversationCreateDto.getAgentId(), "Agent id cannot be null");
        PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Agent, conversationCreateDto.getAgentId());
        if (publishedDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentOfflineOrNotFound);
        }
        checkPermission(publishedDto.getPublishedSpaceIds(), publishedDto.getScope());
        ConversationDto conversation = conversationApplicationService.createConversation(RequestContext.get().getUserId(), conversationCreateDto.getAgentId(), false, false, conversationCreateDto.getVariables());
        if (StringUtils.isNotBlank(conversationCreateDto.getTopic())) {
            ConversationUpdateDto conversationUpdateDto = new ConversationUpdateDto();
            conversationUpdateDto.setTopic(conversationCreateDto.getTopic());
            conversationUpdateDto.setId(conversation.getId());
            conversationApplicationService.updateConversationTopic(RequestContext.get().getUserId(), conversationUpdateDto);
        }
        return ReqResult.success(conversation == null ? null : conversation.getId());
    }

    @Operation(summary = "更新会话主题")
    @RequestMapping(path = "/conversation/{conversationId}/update", method = RequestMethod.POST)
    public ReqResult<ConversationDto> update(@PathVariable Long conversationId, @RequestBody ConversationUpdateDto conversationUpdateDto) {
        conversationUpdateDto.setId(conversationId);
        conversationApplicationService.updateConversationTopic(RequestContext.get().getUserId(), conversationUpdateDto);
        return conversation(conversationId);
    }

    @Operation(summary = "查询会话信息")
    @RequestMapping(path = "/conversation/{id}", method = RequestMethod.GET)
    public ReqResult<ConversationDto> conversation(@PathVariable Long id) {
        ConversationDto conversationDto = checkConversation(id);
        if (conversationDto.isDefaultTopic()) {
            if (conversationDto.getAgent() != null) {
                conversationDto.setTopic(I18nUtil.systemMessage("Backend.Conversation.DefaultTopic", conversationDto.getAgent().getName()));
            }
        }
        return ReqResult.success(conversationDto);
    }

    @Operation(summary = "删除会话")
    @RequestMapping(path = "/conversation/{id}/delete", method = RequestMethod.POST)
    public ReqResult<ConversationDto> delete(@PathVariable Long id) {
        checkConversation(id);
        conversationApplicationService.deleteConversation(RequestContext.get().getUserId(), id);
        return ReqResult.success();
    }


    @Operation(summary = "查询智能体历史会话")
    @RequestMapping(path = "/conversation/list", method = RequestMethod.GET)
    public ReqResult<List<ConversationDto>> conversations(@RequestParam(name = "agentId") Long agentId,
                                                          @RequestParam(name = "lastId", required = false) Long lastId,
                                                          @RequestParam(name = "limit", required = false, defaultValue = "10") Integer limit,
                                                          @RequestParam(name = "topic", required = false) String topic) {
        List<ConversationDto> conversationDtoList = conversationApplicationService.queryConversationList(RequestContext.get().getUserId(), agentId, lastId, limit, topic);
        return ReqResult.success(conversationDtoList);
    }

    @Operation(summary = "查询会话消息列表")
    @RequestMapping(path = "/{conversationId}/messages", method = RequestMethod.GET)
    public ReqResult<List<ChatMessageDto>> conversationMessages(@PathVariable Long conversationId,
                                                                @RequestParam(name = "index", required = false) Long index,
                                                                @RequestParam(name = "limit", required = false, defaultValue = "10") Integer limit) {
        checkConversation(conversationId);
        List<ChatMessageDto> messageDtos = conversationApplicationService.queryConversationMessageList(RequestContext.get().getUserId(), conversationId, index, limit);
        return ReqResult.success(messageDtos);
    }

    @Operation(summary = "删除会话历史消息列表")
    @RequestMapping(path = "/{conversationId}/messages/delete", method = RequestMethod.POST)
    public ReqResult<Void> clearConversationMessages(@PathVariable Long conversationId) {
        checkConversation(conversationId);
        conversationApplicationService.clearConversationMessages(conversationId);
        return ReqResult.success();
    }

    @Operation(summary = "智能体会话接口")
    @RequestMapping(path = "/{conversationId}", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentOutputDto> chat(@PathVariable Long conversationId, @RequestBody @Valid TryReqDto tryReqDto, HttpServletRequest request, HttpServletResponse response) {
        response.setCharacterEncoding("utf-8");
        tryReqDto.setConversationId(conversationId);
        Map<String, String> headersFromRequest = getHeadersFromRequest(request);
        tryReqDto.setFrom("api");
        tryReqDto.setFilterSensitive(null);
        tryReqDto.setAgentConfig(null);
        return chatApplicationService.chat(tryReqDto, headersFromRequest, false, false);
    }

    @Operation(summary = "智能体会话停止接口")
    @RequestMapping(path = "/{conversationId}/stop", method = RequestMethod.POST)
    public ReqResult<Void> chatStop(@PathVariable String conversationId) {
        long cid;
        try {
            cid = Long.parseLong(conversationId);
        } catch (NumberFormatException e) {
            // Compatible with previous API calls
            chatApplicationService.setChatStopStatus(conversationId);
            return ReqResult.success();
        }
        ConversationDto conversation = conversationApplicationService.getConversation(RequestContext.get().getUserId(), cid);
        if (conversation == null) {
            return ReqResult.error("Conversation not found");
        }

        if ("TaskAgent".equals(conversation.getAgent().getType())) {
            sandboxAgentClient.chatCancel(conversationId);
        }
        chatApplicationService.setChatStopStatus(conversationId);
        conversationApplicationService.updateConversationStatus(conversation.getId(), Conversation.ConversationTaskStatus.CANCEL);
        return ReqResult.success();
    }

    @Operation(summary = "订阅会话消息")
    @RequestMapping(path = "/{conversationId}/subscribe", method = RequestMethod.GET, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentOutputDto> sub(@PathVariable Long conversationId, HttpServletResponse response) {
        response.setCharacterEncoding("utf-8");
        return chatApplicationService.subscribe(conversationId);
    }

    @Operation(summary = "权限请求回复")
    @RequestMapping(path = "/{conversationId}/permission-request/response", method = RequestMethod.POST)
    public ReqResult<Void> notifyResolved(@PathVariable Long conversationId, @RequestBody PermissionRequestResponseDto permissionRequestResponseDto) {
        permissionRequestResponseDto.setConversationId(conversationId);
        ConversationDto conversation = conversationApplicationService.getConversation(RequestContext.get().getUserId(), permissionRequestResponseDto.getConversationId());
        if (conversation == null) {
            return ReqResult.error("Conversation not found");
        }

        if ("TaskAgent".equals(conversation.getAgent().getType())) {
            Assert.notNull(permissionRequestResponseDto.getOption(), "Option is null");
            sandboxAgentClient.notifyResolved(permissionRequestResponseDto.getConversationId().toString(), permissionRequestResponseDto.getToolId(), Map.of(
                    "optionId", permissionRequestResponseDto.getOption().getOptionId(),
                    "outcome", "selected"
            ));
        }
        return ReqResult.success();
    }


    @Operation(summary = "会话文件列表查询", description = "默认 recursive=true 全量扁平（原逻辑）；recursive=false 时按 relativePath 只列当前一层")
    @GetMapping("/{conversationId}/files")
    public ReqResult<ComputerFileListRes> getFileList(@PathVariable Long conversationId,
                                                       @RequestParam(value = "relativePath", required = false) String relativePath,
                                                       @Parameter(description = "是否递归全量。不传默认 true（全量递归）；false 为单层浏览")
                                                       @RequestParam(value = "recursive", required = false) Boolean recursive) {
        String proxyPath = String.format("/api/v1/chat/%s/file", conversationId);
        Boolean effectiveRecursive = recursive == null ? Boolean.TRUE : recursive;
        Map<String, Object> result = computerFileApplicationService.getFileList(
                RequestContext.get().getUserId(), conversationId, proxyPath, null, null, relativePath, effectiveRecursive, null, null, null);
        if (result == null) {
            return ReqResult.error("Failed to query file list");
        }

        Object successObj = result.get("success");
        Object codeObj = result.get("code");

        boolean success;
        if (successObj instanceof Boolean) {
            success = (Boolean) successObj;
        } else {
            String code = codeObj != null ? codeObj.toString() : ReqResult.SUCCESS;
            success = ReqResult.SUCCESS.equals(code);
        }

        if (!success) {
            String message = result.getOrDefault("message", "Failed to query file list").toString();
            String code = codeObj != null ? codeObj.toString() : "9999";
            return ReqResult.create(code, message, null);
        }

        return ReqResult.success(ComputerFileListRes.from(result));
    }

    @Operation(summary = "会话文件访问", description = "静态文件访问，返回二进制流（图片、文件等）")
    @CrossOrigin // 确保 OPTIONS 预检请求被正确处理
    @GetMapping(value = "/{conversationId}/file/**")
    public ResponseEntity<StreamingResponseBody> getUserStaticFile(@PathVariable("conversationId") Long cId, HttpServletRequest request) {
        log.info("[Web] Static file access request, cId={}", cId);
        if (cId == null) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        String customTargetDir = request.getParameter("customTargetDir");
        // customTargetDir 仅会话属主本人的个人沙箱可用（云端/协作者/匿名拒绝）
        conversationPermissionChecker.checkCustomTargetDir(cId, customTargetDir);
        return computerFileApplicationService.getStaticFile(cId, request, customTargetDir);
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
        ConversationDto conversationDto = conversationApplicationService.getConversation(RequestContext.get().getUserId(), conversationId);
        if (conversationDto == null || conversationDto.getAgent() == null) {
            throw new BizException("Invalid conversationId");
        }
        return conversationDto;
    }

    private void checkPermission(List<Long> publishedSpaceIds, Published.PublishScope scope) {
        if (scope == Published.PublishScope.Tenant) {
            return;
        }
        List<SpaceDto> spaceDtos = spaceApplicationService.queryListByUserId(RequestContext.get().getUserId());
        List<Long> userSpaceIds = spaceDtos.stream().map(SpaceDto::getId).toList();
        if (CollectionUtils.isNotEmpty(publishedSpaceIds)) {
            boolean anyMatch = publishedSpaceIds.stream().anyMatch(spaceId -> spaceId != null && userSpaceIds.contains(spaceId));
            if (!anyMatch) {
                throw new BizException("Permission denied");
            }
        }
    }
}
