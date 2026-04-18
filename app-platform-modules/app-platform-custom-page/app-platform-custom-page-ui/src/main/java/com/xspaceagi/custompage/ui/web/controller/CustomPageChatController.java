package com.xspaceagi.custompage.ui.web.controller;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.ModelQueryDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.spec.enums.ModelApiProtocolEnum;
import com.xspaceagi.agent.core.spec.enums.ModelTypeEnum;
import com.xspaceagi.custompage.application.service.ICustomPageChatApplicationService;
import com.xspaceagi.custompage.application.service.ICustomPageConfigApplicationService;
import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.domain.model.CustomPageConversationModel;
import com.xspaceagi.custompage.ui.web.config.SseConfig;
import com.xspaceagi.custompage.ui.web.dto.ConversationPageQueryReq;
import com.xspaceagi.custompage.ui.web.dto.ConversationRes;
import com.xspaceagi.custompage.ui.web.dto.CustomPageModelRes;
import com.xspaceagi.custompage.ui.web.dto.SaveConversationReq;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.server.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.server.IUserMetricRpcService;
import com.xspaceagi.system.sdk.service.dto.BizType;
import com.xspaceagi.system.sdk.service.dto.PeriodType;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.annotation.RequireResource;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.dto.ReqResult;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.SpacePermissionException;
import com.xspaceagi.system.spec.page.PageQueryVo;
import com.xspaceagi.system.spec.page.SuperPage;
import com.xspaceagi.system.spec.tenant.thread.TenantRunnable;
import com.xspaceagi.system.spec.utils.I18nUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

import static com.xspaceagi.system.spec.enums.ResourceEnum.PAGE_APP_AI_CHAT;
import static com.xspaceagi.system.spec.enums.ResourceEnum.PAGE_APP_QUERY_DETAIL;

@Tag(name = "Web app", description = "Custom page web app APIs")
@RestController
@RequestMapping("/api/custom-page")
@Slf4j
@RequiredArgsConstructor
public class CustomPageChatController extends BaseController {

    @Resource
    private ModelApplicationService modelApplicationService;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private SseConfig.SseConnectionManager sseConnectionManager;
    @Resource
    private ICustomPageChatApplicationService customPageChatApplicationService;
    @Resource
    private ICustomPageConfigApplicationService customPageConfigApplicationService;
    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;
    @Resource
    private IUserMetricRpcService iUserMetricRpcService;
    @Resource
    @Qualifier("aiChatFluxExecutor")
    private Executor aiChatFluxExecutor;

    @RequireResource(PAGE_APP_AI_CHAT)
    @Operation(summary = "Send chat (streaming)", description = "Reactive AI chat with progress pushed via SSE")
    @PostMapping(value = "/ai-chat-flux", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter aiChatFlux(@RequestBody Map<String, Object> chatBody, HttpServletResponse response) {
        log.info("[Web] Received chat flux request");

        // 设置SSE相关的响应头
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Content-Type", "text/event-stream;charset=UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Headers",
                "Origin, X-Requested-With, Content-Type, Accept, Authorization, Cache-Control");
        response.setHeader("Access-Control-Allow-Methods", "HEAD,GET,POST,PUT,DELETE,OPTIONS");
        response.setHeader("Access-Control-Allow-Credentials", "true");

        try {
            if (chatBody == null) {
                SseEmitter emitter = new SseEmitter();
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"code\":\"0001\",\"message\":\"Request body cannot be null\"}"));
                emitter.complete();
                return emitter;
            }

            UserContext userContext = getUser();

            // 检查会话次数
            UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(userContext.getUserId());
            try {
                BigDecimal ct = iUserMetricRpcService.queryMetricCurrent(userContext.getTenantId(), userContext.getUserId(), BizType.APP_DEV_CHAT.getCode(), PeriodType.DAY);
                if (userDataPermission.getPageDailyPromptLimit() != null && userDataPermission.getPageDailyPromptLimit() >= 0 && ct.intValue() >= userDataPermission.getPageDailyPromptLimit()) {
                    String msg = I18nUtil.systemMessage("Backend.CustomPage.DailyLimitReached", userDataPermission.getPageDailyPromptLimit().toString());
                    SseEmitter emitter = new SseEmitter();
                    Map<String, String> errorBody = new HashMap<>();
                    errorBody.put("code", "0001");
                    errorBody.put("message", msg);
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data(JSON.toJSONString(errorBody)));
                    emitter.complete();
                    return emitter;
                }
            } catch (Exception e) {
                log.error("query Metric Current error", e);
            }


            SseEmitter emitter = new SseEmitter(10L * 60 * 1000);

            // sendAgentChatFlux() 只是创建了一个 Flux 对象，不会立即执行
            Flux<Map<String, Object>> flux = customPageChatApplicationService.sendAgentChatFlux(chatBody, userContext);

            //只有调用 subscribe() 时，才会在调用线程中同步执行 Flux.create() 的回调
            // 在异步线程中订阅 Flux 并发送事件
            // 使用 TenantRunnable 在异步线程中传递 RequestContext
            aiChatFluxExecutor.execute(new TenantRunnable(() -> {
                flux.subscribe(
                        data -> {
                            try {
                                String eventType = (String) data.get("type");
                                String jsonData = JSON.toJSONString(data);
                                emitter.send(SseEmitter.event()
                                        .name(eventType)
                                        .data(jsonData));
                                log.debug("[Web] Flux sent event: {}", eventType);
                            } catch (Exception e) {
                                log.error("[Web] Failed to send Flux event", e);
                            }
                        },
                        error -> {
                            log.error("[Web] Flux stream error", error);
                            try {
                                Map<String, Object> errorData = new HashMap<>();
                                errorData.put("type", "error");
                                errorData.put("code", "0001");
                                errorData.put("message", error.getMessage());
                                emitter.send(SseEmitter.event()
                                        .name("error")
                                        .data(JSON.toJSONString(errorData)));
                                emitter.complete();
                            } catch (Exception e) {
                                emitter.completeWithError(e);
                            }
                        },
                        () -> {
                            log.info("[Web] Flux stream completed");
                            emitter.complete();
                        });
            }));

            return emitter;
        } catch (Exception e) {
            log.error("[Web] Chat flux error", e);
            SseEmitter emitter = new SseEmitter();
            try {
                emitter.send(SseEmitter.event()
                        .name("error")
                        .data("{\"code\":\"0001\",\"message\":\"Chat message error: " + e.getMessage() + "\"}"));
                emitter.complete();
            } catch (Exception ex) {
                log.error("[Web] Failed to send error message", ex);
                emitter.completeWithError(ex);
            }
            return emitter;
        }
    }

    @RequireResource(PAGE_APP_AI_CHAT)
    @Operation(summary = "Terminate chat SSE session", description = "Terminate an active chat SSE session by session ID")
    @PostMapping(value = "/ai-chat-terminate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> aiChatTerminate(@RequestBody Map<String, Object> requestBody) {
        log.info("[Web] Received terminate chat SSE request");
        try {
            if (requestBody == null) {
                return ReqResult.error("0001", "Request body cannot be null");
            }
            Object sessionIdObj = requestBody.get("session_id");
            if (sessionIdObj == null) {
                return ReqResult.error("0001", "session_id cannot be null");
            }

            String sessionId = String.valueOf(sessionIdObj);
            UserContext userContext = getUser();
            return customPageChatApplicationService.terminateChatSession(sessionId, userContext);
        } catch (Exception e) {
            log.error("[Web] Terminate chat SSE error", e);
            return ReqResult.error("0001", "Terminate chat SSE session exception: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_AI_CHAT)
    @Operation(summary = "Agent session notification (SSE)", description = "Open SSE for real-time notifications for a session")
    @GetMapping(value = "/ai-session-sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter startSessionSse(@RequestParam("session_id") String sessionId,
                                      HttpServletResponse response) {
        log.info("[Web] Session SSE connected, session Id={}", sessionId);

        // 设置SSE相关的响应头
        response.setHeader("Cache-Control", "no-cache");
        response.setHeader("Connection", "keep-alive");
        response.setHeader("Content-Type", "text/event-stream;charset=UTF-8");
        response.setHeader("Access-Control-Allow-Origin", "*");
        response.setHeader("Access-Control-Allow-Headers",
                "Origin, X-Requested-With, Content-Type, Accept, Authorization, Cache-Control");
        response.setHeader("Access-Control-Allow-Methods", "HEAD,GET,POST,PUT,DELETE,OPTIONS");
        response.setHeader("Access-Control-Allow-Credentials", "true");

        UserContext userContext = getUser();
        SseEmitter emitter = customPageChatApplicationService.startAgentSessionSse(sessionId, userContext);

        sseConnectionManager.addConnection(sessionId, emitter);
        Long tenantId = RequestContext.get().getTenantId();
        emitter.onCompletion(() -> {
            //会话数增加1
            iUserMetricRpcService.incrementMetricAllPeriods(tenantId, userContext.getUserId(), BizType.APP_DEV_CHAT.getCode(), BigDecimal.ONE);
        });
        return emitter;
    }

    @RequireResource(PAGE_APP_AI_CHAT)
    @Operation(summary = "Cancel Agent task", description = "Cancel the Agent task for the given session")
    @PostMapping(value = "/ai-session-cancel", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> agentSessionCancel(@RequestBody Map<String, Object> requestBody) {
        log.info("[Web] Received agent cancel request");
        try {
            if (requestBody == null) {
                return ReqResult.error("0001", "Request body cannot be null");
            }

            Object projectIdObj = requestBody.get("project_id");
            Object sessionIdObj = requestBody.get("session_id");

            if (projectIdObj == null) {
                return ReqResult.error("0001", "project_id is required parameter");
            }

            String projectId = String.valueOf(projectIdObj);
            String sessionId = sessionIdObj == null ? null : String.valueOf(sessionIdObj);

            UserContext userContext = getUser();
            return customPageChatApplicationService.agentSessionCancel(projectId, sessionId, userContext);
        } catch (Exception e) {
            log.error("[Web] Agent cancel error", e);
            return ReqResult.error("0001", "Cancel Agent task exception: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_AI_CHAT)
    @Operation(summary = "Get Agent status", description = "Query Agent service status for a project")
    @GetMapping(value = "/agent/status/{project_id}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> getAgentStatus(@PathVariable("project_id") String projectId) {
        log.info("[Web] Received agent status request, project Id={}", projectId);
        try {
            if (projectId == null || projectId.trim().isEmpty()) {
                return ReqResult.error("0001", "project_id cannot be null");
            }

            UserContext userContext = getUser();
            return customPageChatApplicationService.getAgentStatus(projectId, userContext);
        } catch (Exception e) {
            log.error("[Web] Agent status query error, project Id={}", projectId, e);
            return ReqResult.error("0001", "Query Agent status exception: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_AI_CHAT)
    @Operation(summary = "Stop Agent service", description = "Stop the Agent service for a project")
    @PostMapping(value = "/agent/stop", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Map<String, Object>> stopAgent(@RequestParam("project_id") String projectId) {
        log.info("[Web] Received agent stop request, project Id={}", projectId);
        try {
            if (projectId == null || projectId.trim().isEmpty()) {
                return ReqResult.error("0001", "project_id cannot be null");
            }

            UserContext userContext = getUser();
            return customPageChatApplicationService.stopAgent(projectId, userContext);
        } catch (Exception e) {
            log.error("[Web] Agent stop error, project Id={}", projectId, e);
            return ReqResult.error("0001", "Stop Agent service exception: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_QUERY_DETAIL)
    @Operation(summary = "List models", description = "List available chat and multimodal models")
    @GetMapping(value = "/list-models", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<CustomPageModelRes> listModels(@RequestParam("projectId") Long projectId) {
        log.info("[Web] Received model list request");
        try {
            if (projectId == null || projectId <= 0) {
                return ReqResult.error("0001", "project_id cannot be null");
            }
            CustomPageConfigModel configModel = customPageConfigApplicationService.getByProjectId(projectId);
            if (configModel == null) {
                return ReqResult.error("0001", "Project does not exist");
            }

            spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

            List<CustomPageModelRes.ModelDto> chatModelList = new ArrayList<>();
            List<CustomPageModelRes.ModelDto> multiModelList = new ArrayList<>();

            ModelQueryDto queryDto = new ModelQueryDto();
            queryDto.setSpaceId(configModel.getSpaceId());
            // queryDto.setModelType(ModelTypeEnum.Chat);
            // queryDto.setApiProtocol(ModelApiProtocolEnum.Anthropic);
            // queryDto.setScope(null);
            List<ModelConfigDto> modelList = modelApplicationService.queryModelConfigList(queryDto);
            if (modelList != null && modelList.size() > 0) {
                modelList.stream()
                        .filter(m -> (m.getEnabled() == null || YesOrNoEnum.Y.getKey().equals(m.getEnabled())))
                        .forEach(m -> {
                            if (m.getType() == ModelTypeEnum.Chat) {
                                if (m.getApiProtocol() == ModelApiProtocolEnum.Anthropic) {
                                    chatModelList.add(convertModelDto(m));
                                }
                            } else if (m.getType() == ModelTypeEnum.Multi) {
                                multiModelList.add(convertModelDto(m));
                            }
                        });
            }
            log.info("[Web] Found {} chat models, {} multi-modal models", chatModelList.size(), multiModelList.size());

            CustomPageModelRes res = new CustomPageModelRes();
            res.setChatModelList(chatModelList);
            res.setMultiModelList(multiModelList);
            return ReqResult.success(res);
        } catch (Exception e) {
            log.error("[Web] Model list query error", e);
            return ReqResult.error("0001", "Query model list exception: " + e.getMessage());
        }
    }

    private CustomPageModelRes.ModelDto convertModelDto(ModelConfigDto m) {
        CustomPageModelRes.ModelDto dto = new CustomPageModelRes.ModelDto();
        dto.setId(m.getId());
        dto.setName(m.getName());
        dto.setDescription(m.getDescription());
        dto.setModel(m.getModel());
        dto.setApiProtocol(m.getApiProtocol());
        dto.setTenantId(m.getTenantId());
        dto.setSpaceId(m.getSpaceId());
        dto.setIsReasonModel(m.getIsReasonModel());
        dto.setMaxTokens(m.getMaxTokens());
        return dto;
    }

    @RequireResource(PAGE_APP_AI_CHAT)
    @Operation(summary = "Save conversation", description = "Save a user conversation record")
    @PostMapping(value = "/save-conversation", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<Void> saveConversation(@RequestBody SaveConversationReq req) {
        log.info("[Web] Received save conversation request, project Id={}", req.getProjectId());
        try {
            CustomPageConversationModel model = new CustomPageConversationModel();
            model.setProjectId(req.getProjectId());
            model.setTopic(req.getTopic());
            model.setContent(req.getContent());

            UserContext userContext = getUser();
            return customPageChatApplicationService.saveConversation(model, userContext);
        } catch (SpacePermissionException e) {
            log.error("[Web] Failed to save conversation, project Id={}, {}", req.getProjectId(), e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] Failed to save conversation, project Id={}", req.getProjectId(), e);
            return ReqResult.error("0001", "Save conversation record failed: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_QUERY_DETAIL)
    @Operation(summary = "List conversations", description = "List conversation records for a project")
    @GetMapping(value = "/list-conversations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<List<ConversationRes>> listConversations(@RequestParam("projectId") Long projectId) {
        log.info("[Web] Received conversation list request, project Id={}", projectId);
        try {
            if (projectId == null || projectId <= 0) {
                return ReqResult.error("0001", "projectId cannot be null or invalid");
            }

            UserContext userContext = getUser();
            ReqResult<List<CustomPageConversationModel>> result = customPageChatApplicationService
                    .listConversations(projectId, userContext);
            if (!result.isSuccess()) {
                return ReqResult.error(result.getCode(), result.getMessage());
            }
            return ReqResult.success(result.getData().stream()
                    .map(m -> {
                        ConversationRes res = new ConversationRes();
                        res.setProjectId(m.getProjectId());
                        res.setConversationId(m.getId());
                        res.setTopic(m.getTopic());
                        res.setContent(m.getContent());
                        res.setCreated(m.getCreated());
                        res.setCreatorId(m.getCreatorId());
                        return res;
                    })
                    .collect(Collectors.toList()));
        } catch (SpacePermissionException e) {
            log.error("[Web] Failed to query conversation, project Id={}, {}", projectId, e.getMessage());
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] Failed to query conversation, project Id={}", projectId, e);
            return ReqResult.error("0001", "Query conversation record failed: " + e.getMessage());
        }
    }

    @RequireResource(PAGE_APP_QUERY_DETAIL)
    @Operation(summary = "Page query conversations", description = "Paginated conversation records for a project")
    @PostMapping(value = "/page-query-conversations", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReqResult<SuperPage<ConversationRes>> pageQueryConversations(
            @RequestBody PageQueryVo<ConversationPageQueryReq> pageQueryVo) {
        log.info("[Web] Received paged conversation query request, page Query Vo={}", pageQueryVo);
        try {
            if (pageQueryVo == null || pageQueryVo.getQueryFilter() == null) {
                return ReqResult.error("0001", "Request parameters cannot be null");
            }

            ConversationPageQueryReq queryReq = pageQueryVo.getQueryFilter();
            if (queryReq.getProjectId() == null || queryReq.getProjectId() <= 0) {
                return ReqResult.error("0001", "projectId cannot be null or invalid");
            }

            CustomPageConversationModel queryModel = new CustomPageConversationModel();
            queryModel.setProjectId(queryReq.getProjectId());

            UserContext userContext = getUser();
            ReqResult<SuperPage<CustomPageConversationModel>> result = customPageChatApplicationService
                    .pageQueryConversations(queryModel, pageQueryVo.getCurrent(), pageQueryVo.getPageSize(),
                            userContext);

            if (!result.isSuccess()) {
                return ReqResult.error(result.getCode(), result.getMessage());
            }

            if (result.getData() == null) {
                return ReqResult.error("0001", "Query result is null");
            }

            SuperPage<ConversationRes> responsePage = new SuperPage<>(result.getData().getCurrent(),
                    result.getData().getSize(), result.getData().getTotal());

            List<ConversationRes> conversationResList = new ArrayList<>();
            if (result.getData().getRecords() != null && !result.getData().getRecords().isEmpty()) {
                conversationResList = result.getData().getRecords().stream()
                        .map(m -> {
                            ConversationRes res = new ConversationRes();
                            res.setProjectId(m.getProjectId());
                            res.setConversationId(m.getId());
                            res.setTopic(m.getTopic());
                            res.setContent(m.getContent());
                            res.setCreated(m.getCreated());
                            res.setCreatorId(m.getCreatorId());
                            return res;
                        })
                        .collect(Collectors.toList());
            }

            responsePage.setRecords(conversationResList);

            log.info("[Web] Paged conversation query completed, total={}", responsePage.getTotal());
            return ReqResult.success(responsePage);
        } catch (SpacePermissionException e) {
            log.error("[Web] Paged conversation query failed", e);
            return ReqResult.error(e.getCode(), e.getMessage());
        } catch (Exception e) {
            log.error("[Web] Paged conversation query failed", e);
            return ReqResult.error("0001", "Paged query conversation record failed: " + e.getMessage());
        }
    }
}