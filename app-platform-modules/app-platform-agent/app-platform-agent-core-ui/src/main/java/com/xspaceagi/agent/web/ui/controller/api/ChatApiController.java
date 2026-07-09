package com.xspaceagi.agent.web.ui.controller.api;

import com.xspaceagi.agent.core.adapter.application.ConversationApplicationService;
import com.xspaceagi.agent.core.adapter.application.IComputerFileApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.web.ui.controller.dto.ComputerFileListRes;
import com.xspaceagi.agent.web.ui.controller.dto.api.ConvCreateDto;
import com.xspaceagi.system.application.dto.SpaceDto;
import com.xspaceagi.system.application.service.SpaceApplicationService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.utils.I18nUtil;
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

import static com.xspaceagi.agent.web.ui.controller.ConversationController.DEFAULT_TOPIC;

@Tag(name = "开放API-会话相关接口")
@RestController
@RequestMapping("/api/v1/chat")
@Slf4j
public class ChatApiController {

    @Resource
    private ConversationApplicationService conversationApplicationService;

    @Resource
    private IComputerFileApplicationService computerFileApplicationService;

    @Resource
    private PublishApplicationService publishApplicationService;

    @Resource
    private SpaceApplicationService spaceApplicationService;

    @Resource
    private RedisUtil redisUtil;

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
        if (DEFAULT_TOPIC.equals(conversationDto.getTopic())) {
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

    @Operation(summary = "智能体会话接口")
    @RequestMapping(path = "/{conversationId}", method = RequestMethod.POST, produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<AgentOutputDto> chat(@PathVariable Long conversationId, @RequestBody @Valid TryReqDto tryReqDto, HttpServletRequest request, HttpServletResponse response) {
        response.setCharacterEncoding("utf-8");
        tryReqDto.setConversationId(conversationId);
        Map<String, String> headersFromRequest = getHeadersFromRequest(request);
        tryReqDto.setFrom("api");
        tryReqDto.setFilterSensitive(null);
        return conversationApplicationService.chat(tryReqDto, headersFromRequest, false, false);
    }

    @Operation(summary = "智能体会话停止接口")
    @RequestMapping(path = "/{conversationId}/stop", method = RequestMethod.POST)
    public ReqResult<Void> chatStop(@PathVariable Long conversationId) {
        checkConversation(conversationId);
        redisUtil.set("chat.stop." + conversationId, String.valueOf(System.currentTimeMillis()), 60);
        return ReqResult.success();
    }


    @Operation(summary = "会话文件列表查询", description = "查询文件列表")
    @GetMapping("/{conversationId}/files")
    public ReqResult<ComputerFileListRes> getFileList(@PathVariable Long conversationId) {
        String proxyPath = String.format("/api/v1/chat/%s/file", conversationId);
        Map<String, Object> result = computerFileApplicationService.getFileList(RequestContext.get().getUserId(), conversationId, proxyPath, null, null);
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

        ComputerFileListRes res = new ComputerFileListRes();
        res.setFiles(result.get("files"));
        return ReqResult.success(res);
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
