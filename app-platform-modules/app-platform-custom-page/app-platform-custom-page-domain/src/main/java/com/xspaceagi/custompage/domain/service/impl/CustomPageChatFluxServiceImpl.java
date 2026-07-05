package com.xspaceagi.custompage.domain.service.impl;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.application.PublishApplicationService;
import com.xspaceagi.agent.core.adapter.constant.SkillFileFormatConstants;
import com.xspaceagi.agent.core.adapter.dto.*;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.adapter.util.SkillNameUtil;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.model.ModelContext;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.infra.component.model.dto.ModelCallConfigDto;
import com.xspaceagi.agent.core.infra.rpc.MarketClientRpcService;
import com.xspaceagi.agent.core.infra.rpc.ModelApiProxyRpcService;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.enums.TargetTypeEnum;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.agent.core.spec.enums.ModelApiProtocolEnum;
import com.xspaceagi.agent.core.spec.enums.ModelTypeEnum;
import com.xspaceagi.agent.core.spec.enums.OutputTypeEnum;
import com.xspaceagi.agent.core.spec.utils.FileTypeUtils;
import com.xspaceagi.agent.core.spec.utils.UrlFile;
import com.xspaceagi.custompage.domain.constant.CustomPagePromptConstants;
import com.xspaceagi.custompage.domain.gateway.PageAppAIClient;
import com.xspaceagi.custompage.domain.gateway.PageAppFileClient;
import com.xspaceagi.custompage.domain.model.CustomPageBuildModel;
import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.domain.model.CustomPageConversationModel;
import com.xspaceagi.custompage.domain.repository.ICustomPageBuildRepository;
import com.xspaceagi.custompage.domain.repository.ICustomPageConfigRepository;
import com.xspaceagi.custompage.domain.service.AgentProgressSessionCoordinator;
import com.xspaceagi.custompage.domain.service.CustomPageChatSessionManager;
import com.xspaceagi.custompage.domain.service.ICustomPageChatFluxService;
import com.xspaceagi.custompage.domain.service.ICustomPageConversationDomainService;
import com.xspaceagi.custompage.domain.util.ClasspathSkillLoader;
import com.xspaceagi.custompage.sdk.dto.DataSourceDto;
import com.xspaceagi.custompage.sdk.dto.VersionInfoDto;
import com.xspaceagi.custompage.sdk.enums.CustomPageActionEnum;
import com.xspaceagi.eco.market.sdk.reponse.ClientSecretResponse;
import com.xspaceagi.eco.market.sdk.request.ClientSecretRequest;
import com.xspaceagi.file.sdk.IFileAccessService;
import com.xspaceagi.modelproxy.sdk.service.dto.BackendModelDto;
import com.xspaceagi.modelproxy.sdk.service.dto.FrontendModelDto;
import com.xspaceagi.pricing.sdk.dto.PriceEstimate;
import com.xspaceagi.pricing.sdk.rpc.IPricingRpcService;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.file.FileSystemMultipartFile;
import com.xspaceagi.system.spec.file.InMemoryMultipartFile;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.DateUtil;
import com.xspaceagi.system.spec.utils.I18nUtil;
import com.xspaceagi.system.spec.utils.TimeWheel;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.xspaceagi.custompage.domain.constant.CustomPagePromptConstants.*;

@Slf4j
@Service
public class CustomPageChatFluxServiceImpl implements ICustomPageChatFluxService {

    @Resource
    private IFileAccessService iFileAccessService;
    @Resource
    private PageAppAIClient pageAppAIClient;
    @Resource
    private IAgentRpcService agentRpcService;
    @Resource
    private PageAppFileClient pageAppFileClient;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private ModelApplicationService modelApplicationService;
    @Resource
    private PublishApplicationService publishApplicationService;
    @Resource
    private ICustomPageBuildRepository customPageBuildRepository;
    @Resource
    private ICustomPageConfigRepository customPageConfigRepository;
    @Resource
    private ICustomPageConversationDomainService customPageConversationDomainService;
    @Resource
    private CustomPageChatSessionManager sessionManager;
    @Resource
    private AgentProgressSessionCoordinator agentProgressSessionCoordinator;
    @Resource
    private MarketClientRpcService marketClientRpcService;
    @Resource
    @Qualifier("aiChatExecutor")
    private Executor aiChatExecutor;

    @Resource
    private ModelInvoker modelInvoker;

    @Resource
    private ModelApiProxyRpcService modelApiProxyRpcService;
    @Resource
    private TimeWheel timeWheel;

    @Resource
    private UserAccessKeyApiService userAccessKeyApiService;

    private final static ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Resource
    private IPricingRpcService iPricingRpcService;

    @Override
    public Flux<Map<String, Object>> sendAgentChatFlux(Map<String, Object> chatBody, UserContext userContext) {
        // 验证参数
        if (chatBody == null) {
            return Flux.error(new IllegalArgumentException("Request body cannot be empty"));
        }
        if (!chatBody.containsKey("project_id")) {
            return Flux.error(new IllegalArgumentException("project_id is required"));
        }
        if (!chatBody.containsKey("prompt") || StringUtils.isBlank(String.valueOf(chatBody.get("prompt")))) {
            return Flux.error(new IllegalArgumentException("prompt is required"));
        }

        Long projectId;
        try {
            projectId = Long.valueOf(String.valueOf(chatBody.get("project_id")));
        } catch (Exception e) {
            return Flux.error(new IllegalArgumentException("Invalid project_id"));
        }

        CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
        if (configModel == null) {
            throw new IllegalArgumentException("Project configuration does not exist: " + projectId);
        }
        spacePermissionService.checkSpaceUserPermission(configModel.getSpaceId());

        // 平台临时会话 ID：仅用于本 Flux 流终止、USER 落库占位，不可传给 Agent，不可用于 ai-session-sse
        String platformFluxSessionId = UUID.randomUUID().toString().replace("-", "");
        String requestId = UUID.randomUUID().toString().replace("-", "");
        chatBody.put("request_id", requestId);

        return Flux.<Map<String, Object>>create(sink -> {
            AtomicBoolean watcherStopped = new AtomicBoolean(false);
            AtomicBoolean stopRequested = new AtomicBoolean(false);
            try {
                sessionManager.registerSession(platformFluxSessionId, sink);
                scheduleSessionStopWatcher(platformFluxSessionId, sink, watcherStopped, stopRequested);

                sendSessionIdFlux(sink, platformFluxSessionId);

                executeChatFlux(chatBody, userContext, sink, configModel, platformFluxSessionId, requestId, stopRequested);
            } catch (Exception e) {
                log.error("[Flux Service] chat exception", e);
                sink.error(e);
            } finally {
                watcherStopped.set(true);
                // 完成流并移除会话
                try {
                    sink.complete();
                } catch (Exception e) {
                    log.debug("[Flux Service] sink already completed, ignore duplicate call", e);
                }
                sessionManager.removeSession(platformFluxSessionId);
            }
        }).onErrorResume(error -> {
            Map<String, Object> errorMap = new HashMap<>();
            errorMap.put("type", "error");
            errorMap.put("code", "0001");
            errorMap.put("message", error.getMessage());
            return Flux.just(errorMap);
        });
    }

    private void executeChatFlux(Map<String, Object> chatBody, UserContext userContext,
                                 FluxSink<Map<String, Object>> sink, CustomPageConfigModel configModel,
                                 String platformFluxSessionId, String requestId,
                                 AtomicBoolean stopRequested) {
        try {
            Long projectId = configModel.getId();

            // 1 保存会话记录
            saveConversationSafely(chatBody, projectId, platformFluxSessionId, requestId, userContext);
            throwIfStopRequested(stopRequested);

            // 2 处理原型图片
            Long multiModelId = processPrototypeImagesFlux(chatBody, projectId, userContext, sink);
            throwIfStopRequested(stopRequested);

            // 3 处理附件文件
            processAttachmentFilesFlux(chatBody, projectId, sink);
            throwIfStopRequested(stopRequested);

            // 4 处理模型配置
            Long chatModelId = processModelConfigFlux(chatBody, userContext, configModel, sink);
            throwIfStopRequested(stopRequested);

            // 5 处理数据源
            processDataSourcesFlux(chatBody, configModel, sink);
            throwIfStopRequested(stopRequested);

            // 6 处理技能列表并推送到网页应用开发工作空间
            processSkillsFlux(chatBody, projectId, userContext, sink);
            throwIfStopRequested(stopRequested);

            // 7 备份并更新版本
            // backAndUpdateVersion(userContext, chatBody, projectId, buildModel, chatModelId, multiModelId, sink, stopRequested);

            // 8 调用 AI Agent
            //sendProgressFlux(sink, "Calling AI agent...80%");
            sendHeartbeatFlux(sink);
            // 异步调用 sendChat，并在等待期间发送心跳
            prepareAgentChatBody(chatBody, platformFluxSessionId);
            Map<String, Object> chatResp = callSendChatSync(chatBody, projectId, userContext, stopRequested, platformFluxSessionId);
            if (chatResp == null) {
                sendErrorFlux(sink, "9999", "AI Agent 无响应");
                return;
            }
            throwIfStopRequested(stopRequested);
            Object code = chatResp.get("code");
            if (code == null || !"0000".equals(String.valueOf(code))) {
                String errorCode = code != null ? String.valueOf(code) : "9999";
                sendErrorFlux(sink, errorCode, String.valueOf(chatResp.get("message")));
                return;
            }

            // 9 返回结果
            //sendProgressFlux(sink, "AI returned result...100%");
            sendHeartbeatFlux(sink);
            Map<String, Object> dataMap = parseResponseData(chatResp);
            if (dataMap != null) {
                dataMap.put("request_id", requestId);
            }
            afterAgentChatAccepted(projectId, requestId, userContext);
            updateUserSessionIdBySuccess(projectId, requestId, dataMap, userContext);
            sendSuccessFlux(sink, dataMap);
        } catch (CancellationException e) {
            log.info("[Flux Service] session stopped by user, skip remaining workflow");
        } catch (Exception e) {
            log.error("[Flux Service] Flux chat exception", e);
            sendErrorFlux(sink, "0001", e.getMessage());
        }
    }

    private void backAndUpdateVersion(UserContext userContext, Map<String, Object> chatBody,
                                      Long projectId, CustomPageBuildModel buildModel,
                                      Long chatModelId, Long multiModelId,
                                      FluxSink<Map<String, Object>> sink, AtomicBoolean stopRequested) {
        // sendProgressFlux(sink, "正在备份当前版本...", 60);
        sendHeartbeatFlux(sink);

        // 备份版本
        int currentVersion = buildModel.getCodeVersion() == null ? 0 : buildModel.getCodeVersion();
        Map<String, Object> backupResp = pageAppFileClient.backupCurrentVersion(projectId, currentVersion);
        if (backupResp == null || !Boolean.parseBoolean(String.valueOf(backupResp.get("success")))) {
            String msg = backupResp != null && backupResp.get("message") != null
                    ? String.valueOf(backupResp.get("message"))
                    : "备份失败";
            sendErrorFlux(sink, "9999", msg);
            return;
        }
        throwIfStopRequested(stopRequested);

        // 更新版本
        // sendProgressFlux(sink, "正在更新版本...", 70);
        sendHeartbeatFlux(sink);

        Integer nextVersion = currentVersion + 1;
        List<VersionInfoDto> versionInfo = buildModel.getVersionInfo();
        // 仅记录提示词前100个字符到ext
        String promptStr = String.valueOf(chatBody.get("prompt"));
        String briefPrompt = promptStr.length() > 100 ? promptStr.substring(0, 100) : promptStr;
        Map<String, String> ext = new HashMap<>();
        ext.put("prompt", briefPrompt);
        versionInfo.add(VersionInfoDto.builder()
                .version(nextVersion)
                .time(DateUtil.format(new Date(), "yyyy-MM-dd HH:mm:ss"))
                .action(CustomPageActionEnum.CHAT.getCode())
                .ext(ext)
                .build());

        CustomPageBuildModel updateModel = new CustomPageBuildModel();
        updateModel.setId(buildModel.getId());
        updateModel.setCodeVersion(nextVersion);
        updateModel.setVersionInfo(versionInfo);
        updateModel.setLastChatModelId(chatModelId);
        updateModel.setLastMultiModelId(multiModelId);
        customPageBuildRepository.updateVersionInfo(updateModel, userContext);
        throwIfStopRequested(stopRequested);
    }

    private Long processPrototypeImagesFlux(Map<String, Object> chatBody, Long projectId,
                                            UserContext userContext, FluxSink<Map<String, Object>> sink) {
        Long multiModelId = null;
        Object multiModelIdObj = chatBody.get("multi_model_id");
        if (multiModelIdObj == null) {
            log.info("[Flux Service] send chat message,project Id={}, , not provided ID", projectId);

            // 从 RequestContext 获取租户配置
            TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
            if (tenantConfig == null || tenantConfig.getDefaultVisualModelId() == null
                    || tenantConfig.getDefaultVisualModelId() == 0) {
                log.info("[Flux Service] send chat message,project Id={},no default multimodal model configured, parse", projectId);
                return multiModelId;
            } else {
                multiModelId = tenantConfig.getDefaultVisualModelId();
            }
        } else {
            multiModelId = Long.valueOf(String.valueOf(multiModelIdObj));
        }

        Object prototypeImages = chatBody.get("attachment_prototype_images");
        if (!(prototypeImages instanceof List<?>) || ((List<?>) prototypeImages).isEmpty()) {
            return multiModelId;
        }


        try {
            modelApplicationService.checkModelUsePermission(multiModelId);
        } catch (Exception e) {
            log.warn("[Flux Service] unavailable: {}", e.getMessage());
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageMultimodalModelUnavailable, e.getMessage());
        }
        ModelConfigDto modelConfig = modelApplicationService.queryModelConfigById(multiModelId);

        if (modelConfig.getType() != ModelTypeEnum.Multi) {
            log.warn("[Flux Service] unsupported , parse , id={}", multiModelId);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageModelNotMultimodal, multiModelId);
        }

        TenantConfigDto tenantConfig = (TenantConfigDto) userContext.getTenantConfig();
        // 付费校验
        if (tenantConfig != null && tenantConfig.getEnableSubscription() != null && tenantConfig.getEnableSubscription() == 1) {
            PriceEstimate priceEstimate = iPricingRpcService.estimatePrice(userContext.getTenantId(), userContext.getUserId(), List.of(PriceEstimate.EstimateTarget.builder()
                    .targetType(com.xspaceagi.pricing.spec.enums.TargetTypeEnum.MODEL)
                    .targetId(modelConfig.getId().toString())
                    .build()));
            if (!priceEstimate.isPass()) {
                log.warn("[Flux Service] priceEstimate: {}", priceEstimate.getMessage());
                throw new BizException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), priceEstimate.getMessage());
            }
        }

        log.info("[Flux Service] send chat message,project Id={},prototype Images={}", projectId, JSON.toJSONString(prototypeImages));

        for (Object prototypeImage : (List<?>) prototypeImages) {
            if (prototypeImage instanceof Map<?, ?> m) {
                Object urlObj = m.get("url");
                Object fileNameObj = m.get("fileName");
                Object mimeTypeObj = m.get("mimeType");
                Object fileKeyObj = m.get("fileKey");

                if (urlObj == null) {
                    throw new IllegalArgumentException("Attachment URL cannot be empty");
                }
                if (mimeTypeObj == null) {
                    throw new IllegalArgumentException("Attachment type cannot be empty");
                }

                sendProgressFlux(sink, "开始解析原型图片[" + fileNameObj + "]", 10);

                AttachmentDto attachmentDto = new AttachmentDto();
                attachmentDto.setFileUrl(String.valueOf(urlObj));
                attachmentDto.setMimeType(String.valueOf(mimeTypeObj));
                attachmentDto.setFileName(fileNameObj != null ? String.valueOf(fileNameObj) : null);
                attachmentDto.setFileKey(fileKeyObj != null ? String.valueOf(fileKeyObj) : null);

                AgentContext agentContext = new AgentContext();
                agentContext.setAttachments(List.of(attachmentDto));
                agentContext.setRequestId(UUID.randomUUID().toString());
                agentContext.setUserId(userContext.getUserId());
                agentContext.setTenantConfig(tenantConfig);
                agentContext.setConversationId(String.valueOf(projectId));
                UserDto userDto = new UserDto();
                userDto.setUserName(userContext.getUserName());
                userDto.setNickName(userContext.getNickName());
                userDto.setId(userContext.getUserId());
                userDto.setUid(userContext.getUid());
                agentContext.setUser(userDto);

                ModelContext modelContext = new ModelContext();
                modelContext.setAgentContext(agentContext);
                modelContext.setRequestId(agentContext.getRequestId());
                modelContext.setConversationId(agentContext.getConversationId());
                modelContext.setModelConfig(modelConfig);

                ModelCallConfigDto modelCallConfig = new ModelCallConfigDto();
                modelCallConfig.setSystemPrompt(CustomPagePromptConstants.resolvePromptText(prototypeImagesSystemPrompt));
                modelCallConfig.setUserPrompt(CustomPagePromptConstants.resolvePromptText(prototypeImagesUserPrompt));
                modelCallConfig.setOutputType(OutputTypeEnum.Markdown);
                modelCallConfig.setStreamCall(true);
                modelContext.setModelCallConfig(modelCallConfig);
                // 调用多模态模型
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<Throwable> throwableAtomicReference = new AtomicReference<>();
                modelInvoker.invoke(modelContext).timeout(Duration.ofSeconds(300))
                        .doOnComplete(() -> latch.countDown())
                        .subscribe(callMessage -> {
                            sendProgressFlux(sink, callMessage.getText(), 10);
                        }, throwable -> {
                            throwableAtomicReference.set(throwable);
                            latch.countDown();
                        });
                try {
                    latch.await();
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }
                if (throwableAtomicReference.get() != null) {
                    throw new RuntimeException(throwableAtomicReference.get());
                }
                String markdownContent = modelContext.getModelCallResult().getResponseText();
                log.info("[Flux Service] project Id={} parsecompleted,url={}", projectId, urlObj);

                // 发送图片解析结果事件
                sendImageAnalysisResultFlux(sink, String.valueOf(urlObj), String.valueOf(fileNameObj), markdownContent);

                // 将解析结果添加到聊天体中
                chatBody.compute("prompt", (k, v) -> v + "\n" + markdownContent);
            }
        }
        return multiModelId;
    }

    private void processAttachmentFilesFlux(Map<String, Object> chatBody, Long projectId, FluxSink<Map<String, Object>> sink) {
        Object attachmentFiles = chatBody.get("attachment_files");
        if (attachmentFiles == null) {
            return;
        }

        log.info("[Flux Service] send chat message,project Id={},start handle ,files={}", projectId, JSON.toJSONString(attachmentFiles));

        for (Object attachment : (List<?>) attachmentFiles) {
            if (attachment instanceof Map<?, ?> m) {
                Object urlObj = m.get("url");
                Object fileNameObj = m.get("fileName");
                if (urlObj == null) {
                    throw new IllegalArgumentException("Attachment URL cannot be empty");
                }
                if (fileNameObj == null) {
                    throw new IllegalArgumentException("Attachment file name cannot be empty");
                }

                // 发送心跳
                sendHeartbeatFlux(sink);

                sendProgressFlux(sink, "正在解析附件[" + fileNameObj + "]...", 30);

                String outputPrompt = parseFileToText(projectId, String.valueOf(urlObj), String.valueOf(fileNameObj));
                if (outputPrompt != null && !outputPrompt.isEmpty()) {
                    chatBody.compute("prompt", (k, v) -> v + "\n" + outputPrompt);
                }
            }
        }
    }

    private Long processModelConfigFlux(Map<String, Object> chatBody, UserContext userContext, CustomPageConfigModel configModel,
                                        FluxSink<Map<String, Object>> sink) {
        Long projectId = configModel.getId();
        Object chatModelIdObj = chatBody.get("chat_model_id");
        log.info("[Flux Service] send chat message,project Id={},chat Model Id={}", projectId, chatModelIdObj);

        Long chatModelId;
        if (chatModelIdObj == null) {
            log.info("[Flux Service] send chat message, configchat");

            // 从 RequestContext 获取租户配置
            TenantConfigDto tenantConfig = (TenantConfigDto) RequestContext.get().getTenantConfig();
            if (tenantConfig == null || tenantConfig.getDefaultCodingModelId() == null
                    || tenantConfig.getDefaultCodingModelId() == 0) {
                log.info("[Flux Service] send chat message,project Id={},no default chat model configured, parse", projectId);
                throw new IllegalArgumentException("No default chat model is configured");
            } else {
                chatModelId = tenantConfig.getDefaultCodingModelId();
            }
        } else {
            chatModelId = Long.valueOf(String.valueOf(chatModelIdObj));
        }
        try {
            modelApplicationService.checkModelUsePermission(chatModelId);
        } catch (Exception e) {
            log.warn("[Flux Service] chat unavailable: {}", e.getMessage());
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.customPageChatModelUnavailable, e.getMessage());
        }
        // sendProgressFlux(sink, "正在配置模型...", 40);
        sendHeartbeatFlux(sink);

        ModelConfigDto modelConfigDto = modelApplicationService.queryModelConfigById(chatModelId);

        TenantConfigDto tenantConfig = (TenantConfigDto) userContext.getTenantConfig();
        // 付费校验
        if (tenantConfig != null && tenantConfig.getEnableSubscription() != null && tenantConfig.getEnableSubscription() == 1) {
            PriceEstimate priceEstimate = iPricingRpcService.estimatePrice(userContext.getTenantId(), userContext.getUserId(), List.of(PriceEstimate.EstimateTarget.builder()
                    .targetType(com.xspaceagi.pricing.spec.enums.TargetTypeEnum.MODEL)
                    .targetId(modelConfigDto.getId().toString())
                    .build()));
            if (!priceEstimate.isPass()) {
                log.warn("[Flux Service] Agent model priceEstimate: {}", priceEstimate.getMessage());
                throw new BizException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), priceEstimate.getMessage());
            }
        }

        // 按照权重随机选择一个 ApiInfo
        ModelConfigDto.ApiInfo selectedApiInfo = modelConfigDto.getApiInfoList().get((int) (projectId % modelConfigDto.getApiInfoList().size()));//selectByWeight(modelConfigDto.getApiInfoList());

        if (selectedApiInfo.getKey() != null && selectedApiInfo.getKey().contains("TENANT_SECRET")) {
            ClientSecretRequest clientSecretRequest = new ClientSecretRequest();
            clientSecretRequest.setTenantId(modelConfigDto.getTenantId());
            ClientSecretResponse clientSecretResponse = marketClientRpcService.queryClientSecret(clientSecretRequest);
            selectedApiInfo.setKey(selectedApiInfo.getKey().replace("TENANT_SECRET", clientSecretResponse.getClientSecret()));
        }

        BackendModelDto backendModelDto = new BackendModelDto();
        backendModelDto.setBaseUrl(selectedApiInfo.getUrl());
        backendModelDto.setApiKey(selectedApiInfo.getKey());
        backendModelDto.setModelName(modelConfigDto.getModel());
        backendModelDto.setProtocol(modelConfigDto.getApiProtocol().name());
        backendModelDto.setScope(modelConfigDto.getScope().name());
        backendModelDto.setModelId(modelConfigDto.getId());
        backendModelDto.setUserName(userContext.getUserName());
        backendModelDto.setConversationId(projectId.toString());
        backendModelDto.setRequestId(UUID.randomUUID().toString().replace("-", ""));
        String siteUrl = userContext.getTenantConfig() != null ? ((TenantConfigDto) userContext.getTenantConfig()).getSiteUrl() : "";
        TraceContext traceContext = TraceContext.builder()
                .traceId(backendModelDto.getRequestId())
                .tenantId(userContext.getTenantId())
                .userId(userContext.getUserId())
                .conversationId(projectId.toString())
                .userName(userContext.getUserName())
                .nickName(userContext.getNickName())
                .billUserId(userContext.getUserId())
                .traceTargets(new ArrayList<>())
                .build();
        if (tenantConfig != null) {
            traceContext.setEnableSubscription(tenantConfig.getEnableSubscription() != null && tenantConfig.getEnableSubscription() == 1);
        } else {
            traceContext.setEnableSubscription(true);
        }

        TraceContext.TraceTarget traceTarget = TraceContext.TraceTarget.builder()
                .targetType(TraceContext.TraceTargetType.Model)
                .targetId(modelConfigDto.getId().toString())
                .name(modelConfigDto.getModel())
                .description(modelConfigDto.getName())
                .spaceId(modelConfigDto.getSpaceId())
                .build();
        traceContext.getTraceTargets().add(traceTarget);
        backendModelDto.setTraceContext(traceContext);

        FrontendModelDto frontendModelDto = modelApiProxyRpcService.generateUserFrontendModelConfig(userContext.getTenantId(), userContext.getUserId()
                , -1L, backendModelDto, siteUrl);
        selectedApiInfo = new ModelConfigDto.ApiInfo();
        selectedApiInfo.setKey(frontendModelDto.getApiKey());
        selectedApiInfo.setUrl(frontendModelDto.getBaseUrl());

        Map<String, Object> modelProvider = new HashMap<>();
        modelProvider.put("api_key", selectedApiInfo.getKey());
        modelProvider.put("api_protocol", modelConfigDto.getApiProtocol().name());
        modelProvider.put("base_url", selectedApiInfo.getUrl().replace("SESSION_ID", UUID.randomUUID().toString().replace("-", "")));
        modelProvider.put("default_model", modelConfigDto.getModel());
        modelProvider.put("id", modelConfigDto.getId().toString() + "_"
                + (modelConfigDto.getModified() == null ? 0 : modelConfigDto.getModified().getTime()));
        modelProvider.put("name", modelConfigDto.getName());
        modelProvider.put("requires_openai_auth", true);
        chatBody.put("model_provider", modelProvider);
        chatBody.put("agent_config", Map.of(
                "agent_server", buildAgentServer(modelConfigDto, configModel, userContext)
        ));
        return chatModelId;
    }

    private Map<String, Object> buildAgentServer(ModelConfigDto modelConfig, CustomPageConfigModel configModel, UserContext userContext) {
        UserAccessKeyDto userAccessKeyDto = TenantFunctions.callWithIgnoreCheck(() -> userAccessKeyApiService.queryAccessKey(userContext.getUserId(), UserAccessKeyDto.AKTargetType.Sandbox, configModel.getDevAgentId().toString()));
        if (userAccessKeyDto == null) {
            userAccessKeyDto = TenantFunctions.callWithIgnoreCheck(() -> userAccessKeyApiService.newAccessKey(userContext.getTenantId(), userContext.getUserId(), UserAccessKeyDto.AKTargetType.Sandbox, configModel.getDevAgentId().toString(), new UserAccessKeyDto.UserAccessKeyConfig()));
        }
        TenantConfigDto tenantConfigDto = (TenantConfigDto) userContext.getTenantConfig();
        String baseUrl = tenantConfigDto.getSiteUrl().endsWith("/") ? tenantConfigDto.getSiteUrl().substring(0, tenantConfigDto.getSiteUrl().length() - 1) : tenantConfigDto.getSiteUrl();
        Map<String, String> env = new HashMap<>();
        env.put("PLATFORM_BASE_URL", baseUrl);
        env.put("SANDBOX_ACCESS_KEY", userAccessKeyDto.getAccessKey());
        env.put("DEV_PROJECT_ID", configModel.getId().toString());
        env.put("DEV_SPACE_ID", configModel.getSpaceId().toString());
        // 不支持claudecode的模型直接使用opencode
        if (modelConfig.getApiProtocol() == ModelApiProtocolEnum.OpenAI) {
            env.put("OPENCODE_LOG_DIR", "/app/container-logs");
            env.put("OPENCODE_MAX_TOKENS", String.valueOf(modelConfig.getMaxTokens()));
            env.put("OPENCODE_MAX_CONTEXT_TOKENS", String.valueOf(modelConfig.getMaxContextTokens()));
            env.put("OPENAI_API_KEY", "{MODEL_PROVIDER_API_KEY}");
            env.put("OPENAI_BASE_URL", "{MODEL_PROVIDER_BASE_URL}");
            env.put("OPENCODE_MODEL", "openai-compatible/" + modelConfig.getModel());
            return Map.of(
                    "agent_id", "nuwaxcode",
                    "command", "nuwaxcode",
                    "args", List.of("acp"),
                    "env", env
            );
        } else {
            env.put("ANTHROPIC_BASE_URL", "{MODEL_PROVIDER_BASE_URL}");
            env.put("ANTHROPIC_API_KEY", "{MODEL_PROVIDER_API_KEY}");
            env.put("ANTHROPIC_MODEL", modelConfig.getModel());
            env.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
            if (modelConfig.getIsReasonModel() != null && modelConfig.getIsReasonModel() == 1) {
                int maxThinkingTokens = modelConfig.getMaxTokens() == null ? 2048 : modelConfig.getMaxTokens() / 2;
                env.put("MAX_THINKING_TOKENS", maxThinkingTokens > 4096 ? "4096" : Integer.toString(maxThinkingTokens));//最大值暂时支持4096
            } else {
                env.put("CLAUDE_CODE_DISABLE_THINKING", "1");
            }
        }
        return Map.of(
                "agent_id", "claude-code-acp-ts",
                "command", "claude-code-acp-ts",
                "args", List.of(),
                "env", env
        );
    }

    private ModelConfigDto.ApiInfo selectByWeight(List<ModelConfigDto.ApiInfo> apiInfoList) {
        if (apiInfoList == null || apiInfoList.isEmpty()) {
            throw new IllegalArgumentException("Model API list is empty");
        }
        if (apiInfoList.size() == 1) {
            return apiInfoList.get(0);
        }
        long totalWeight = 0;
        for (ModelConfigDto.ApiInfo apiInfo : apiInfoList) {
            int w = apiInfo.getWeight() == null ? 1 : apiInfo.getWeight();
            if (w < 0) {
                w = 0;
            }
            totalWeight += w;
        }
        if (totalWeight <= 0) {
            // 所有权重都无效，退化为均匀随机
            int idx = ThreadLocalRandom.current().nextInt(apiInfoList.size());
            return apiInfoList.get(idx);
        }
        long r = ThreadLocalRandom.current().nextLong(1, totalWeight + 1);
        long cum = 0;
        for (ModelConfigDto.ApiInfo apiInfo : apiInfoList) {
            int w = apiInfo.getWeight() == null ? 1 : apiInfo.getWeight();
            if (w < 0) {
                w = 0;
            }
            cum += w;
            if (r <= cum) {
                return apiInfo;
            }
        }
        return apiInfoList.get(apiInfoList.size() - 1);
    }

    private void processDataSourcesFlux(Map<String, Object> chatBody, CustomPageConfigModel configModel, FluxSink<Map<String, Object>> sink) {
        Long projectId = configModel.getId();
        List<DataSourceDto> boundDataSources = configModel.getDataSources();

        // Build prompt from all bound data sources
        appendBoundDataSourcesPrompt(chatBody, projectId, boundDataSources, sink);

        // Build prompt from user at data sources
        appendUserAtDataSourcesPrompt(chatBody, projectId, boundDataSources, sink);
    }

    private void appendBoundDataSourcesPrompt(Map<String, Object> chatBody, Long projectId, List<DataSourceDto> boundDataSources, FluxSink<Map<String, Object>> sink) {
        if (CollectionUtils.isEmpty(boundDataSources)) {
            return;
        }

        // Batch fetch published info for all bound data sources
        Map<Long, PublishedDto> pluginMap = new HashMap<>();
        Map<Long, PublishedDto> workflowMap = new HashMap<>();

        List<Long> pluginIds = boundDataSources.stream()
                .filter(ds -> "plugin".equals(ds.getType()) && ds.getId() != null)
                .map(DataSourceDto::getId)
                .distinct()
                .collect(Collectors.toList());
        List<Long> workflowIds = boundDataSources.stream()
                .filter(ds -> "workflow".equals(ds.getType()) && ds.getId() != null)
                .map(DataSourceDto::getId)
                .distinct()
                .collect(Collectors.toList());

        if (!pluginIds.isEmpty()) {
            List<PublishedDto> pluginList = publishApplicationService.queryPublishedList(
                    Published.TargetType.Plugin, pluginIds);
            if (pluginList != null) {
                pluginList.forEach(dto -> pluginMap.put(dto.getTargetId(), dto));
            }
        }

        sendHeartbeatFlux(sink);

        if (!workflowIds.isEmpty()) {
            List<PublishedDto> workflowList = publishApplicationService.queryPublishedList(
                    Published.TargetType.Workflow, workflowIds);
            if (workflowList != null) {
                workflowList.forEach(dto -> workflowMap.put(dto.getTargetId(), dto));
            }
        }

        sendHeartbeatFlux(sink);

        // Build XML prompt
        StringBuilder promptBuilder = new StringBuilder();
        promptBuilder.append("The following tools are available for the current project. ");
        promptBuilder.append("You can call /api/v1/4sandbox/page/target/schema with parameters type (Plugin or Workflow), id (the dataSourceId), and projectId to retrieve the detailed API schema for each tool.\n\n");
        promptBuilder.append("<availableTools>\n");

        for (DataSourceDto ds : boundDataSources) {
            if (ds.getId() == null || ds.getType() == null) {
                continue;
            }
            String type = ds.getType();
            String typeName = "plugin".equals(type) ? "Plugin" : "workflow".equals(type) ? "Workflow" : null;
            if (typeName == null) {
                continue;
            }

            String name = "";
            String description = "";
            PublishedDto publishedDto = "plugin".equals(type) ? pluginMap.get(ds.getId()) : workflowMap.get(ds.getId());
            if (publishedDto != null) {
                if (StringUtils.isNotBlank(publishedDto.getName())) {
                    name = publishedDto.getName();
                }
                if (StringUtils.isNotBlank(publishedDto.getDescription())) {
                    description = publishedDto.getDescription();
                }
            }

            promptBuilder.append("    <tool>\n");
            promptBuilder.append("        <type>").append(typeName).append("</type>\n");
            promptBuilder.append("        <id>").append(ds.getId()).append("</id>\n");
            promptBuilder.append("        <name>").append(name).append("</name>\n");
            promptBuilder.append("        <description>").append(description).append("</description>\n");
            promptBuilder.append("    </tool>\n");
        }

        promptBuilder.append("</availableTools>");

        // chatBody.put("bound_data_sources_tool_prompt", promptBuilder.toString());
        chatBody.compute("prompt", (k, v) -> v + "\n" + promptBuilder.toString());

        log.debug("[Flux Service] send chat message,project Id={},bound data sources prompt={}", projectId, promptBuilder);
    }

    private void appendUserAtDataSourcesPrompt(Map<String, Object> chatBody, Long projectId, List<DataSourceDto> boundDataSources, FluxSink<Map<String, Object>> sink) {
        Object dataSources = chatBody.get("data_sources");
        log.info("[Flux Service] send chat message,project Id={},data Sources={}", projectId, JSON.toJSONString(dataSources));
        if (!(dataSources instanceof List)) {
            return;
        }

        List<DataSourceDto> atDataSourceList = new ArrayList<>();
        // sendProgressFlux(sink, "正在处理数据源...", 50);
        sendHeartbeatFlux(sink);

        for (Object ds : (List<?>) dataSources) {
            if (ds instanceof Map<?, ?> m) {
                DataSourceDto dataSource = new DataSourceDto();
                Object type = m.get("type");
                Object dataSourceId = m.get("dataSourceId");
                if (type != null) {
                    dataSource.setType(String.valueOf(type));
                }
                if (dataSourceId != null) {
                    dataSource.setId(Long.valueOf(String.valueOf(dataSourceId)));
                }
                atDataSourceList.add(dataSource);
            }
        }
        if (atDataSourceList.isEmpty()) {
            return;
        }

        if (CollectionUtils.isEmpty(boundDataSources)) {
            throw new IllegalArgumentException("Project has no data sources bound");
        }
        // 判断传入的dataSourceList是否都在boundDataSources中
        for (DataSourceDto incoming : atDataSourceList) {
            boolean found = boundDataSources.stream()
                    .anyMatch(existing -> existing.getId() != null
                            && existing.getId().equals(incoming.getId())
                            && existing.getType() != null && existing.getType().equals(incoming.getType()));
            if (!found) {
                throw new IllegalArgumentException(
                        "Data source not authorized: dataSouceId=" + incoming.getId() + ", type=" + incoming.getType());
            }
        }

        List<String> dataSourceSchemaList = new ArrayList<>();

        for (DataSourceDto incoming : atDataSourceList) {
            String type = incoming.getType();
            Long id = incoming.getId();

            TargetTypeEnum typeEnum = "plugin".equals(String.valueOf(type))
                    ? TargetTypeEnum.Plugin
                    : "workflow".equals(String.valueOf(type))
                    ? TargetTypeEnum.Workflow
                    : null;
            if (typeEnum == null) {
                throw new IllegalArgumentException("Unsupported data source type: " + type);
            }

            // 发送心跳
            sendHeartbeatFlux(sink);

            com.xspaceagi.agent.core.sdk.dto.ReqResult<String> queryApiSchemaResult = agentRpcService
                    .queryApiSchema(typeEnum, id, projectId);
            if (!queryApiSchemaResult.isSuccess()) {
                throw new IllegalArgumentException("Failed to query data source schema: " + queryApiSchemaResult.getMessage());
            }

            String dataSourceSchema = queryApiSchemaResult.getData();
            dataSourceSchemaList.add(dataSourceSchema);
        }
        log.info("[Flux Service] send chat message,project Id={},data source prompt={}", projectId, dataSourceSchemaList);
        chatBody.put("data_source_attachments", dataSourceSchemaList);
    }

    private void processSkillsFlux(Map<String, Object> chatBody, Long projectId, UserContext userContext, FluxSink<Map<String, Object>> sink) {
        List<Long> skillIds = parseSkillIds(chatBody.get("skill_ids"));

        TenantConfigDto tenantConfig = (TenantConfigDto) userContext.getTenantConfig();
        // 付费校验（固定技能无需校验）
        if (!skillIds.isEmpty() && tenantConfig != null && tenantConfig.getEnableSubscription() != null && tenantConfig.getEnableSubscription() == 1) {
            List<PriceEstimate.EstimateTarget> estimateTargets = skillIds.stream().map(skillId -> PriceEstimate.EstimateTarget.builder()
                    .targetType(com.xspaceagi.pricing.spec.enums.TargetTypeEnum.SKILL)
                    .targetId(skillId.toString())
                    .build()).collect(Collectors.toList());
            PriceEstimate priceEstimate = iPricingRpcService.estimatePrice(userContext.getTenantId(), userContext.getUserId(), estimateTargets);
            if (!priceEstimate.isPass()) {
                log.warn("[Flux Service] Skills priceEstimate: {}", priceEstimate.getMessage());
                throw new BizException(ErrorCodeEnum.PERMISSION_DENIED.getCode(), priceEstimate.getMessage());
            }
        }

        sendHeartbeatFlux(sink);
        log.info("[Flux Service] send chat message, project Id={}, skill ids={}", projectId, skillIds);

        List<SkillConfigDto> skillConfigs = new ArrayList<>();
        List<String> skillNamesForPrompt = new ArrayList<>();
        List<String> skillUrls = new ArrayList<>();
        for (Long skillId : skillIds) {
            PublishedDto publishedDto = publishApplicationService.queryPublished(Published.TargetType.Skill, skillId, true);
            if (publishedDto == null || StringUtils.isBlank(publishedDto.getConfig())) {
                throw new IllegalArgumentException("Skill not published or configuration missing: " + skillId);
            }
            SkillConfigDto skillConfigDto = parseSkillConfig(publishedDto.getConfig());
            SkillNameUtil.backfillName(skillConfigDto, iFileAccessService);

            if (isV2Config(skillConfigDto)) {
                collectSkillNameForPrompt(skillConfigDto, skillNamesForPrompt);
                if (StringUtils.isNotBlank(skillConfigDto.getZipFileUrl())) {
                    skillUrls.add(iFileAccessService.getFileUrlWithAk(skillConfigDto.getZipFileUrl(), true));
                }
                continue;
            }
            if (skillConfigDto == null || skillConfigDto.getFiles() == null || skillConfigDto.getFiles().isEmpty()) {
                throw new IllegalArgumentException("Skill has no files to push: " + skillId);
            }
            collectSkillNameForPrompt(skillConfigDto, skillNamesForPrompt);
            skillConfigs.add(skillConfigDto);
        }

        // 固定推送 datatable-for-page-api 技能
        SkillConfigDto datatableSkill = ClasspathSkillLoader.load("skills/datatable-for-page-api/");
        collectSkillNameForPrompt(datatableSkill, skillNamesForPrompt);
        skillConfigs.add(datatableSkill);

        // 固定推送 nuwax-pay 技能
        SkillConfigDto paymentSkill = ClasspathSkillLoader.load("skills/nuwax-pay/");
        collectSkillNameForPrompt(paymentSkill, skillNamesForPrompt);
        skillConfigs.add(paymentSkill);

        prependSkillPrompt(chatBody, skillNamesForPrompt);

        MultipartFile zipFile = buildSkillZip(skillConfigs);
        if (zipFile == null && CollectionUtils.isEmpty(skillUrls)) {
            throw new IllegalArgumentException("No valid skill files to push");
        }
        Map<String, Object> pushResp = pageAppFileClient.pushSkillsToWorkspace(projectId, zipFile, skillUrls);
        if (pushResp == null || !Boolean.parseBoolean(String.valueOf(pushResp.get("success")))) {
            String msg = pushResp != null && pushResp.get("message") != null
                    ? String.valueOf(pushResp.get("message"))
                    : "Push skills to workspace failed";
            throw new IllegalArgumentException(msg);
        }
    }

    private void collectSkillNameForPrompt(SkillConfigDto skillConfigDto, List<String> skillNamesForPrompt) {
        if (skillConfigDto == null) {
            return;
        }
        String skillName = StringUtils.defaultIfBlank(skillConfigDto.getEnName(), skillConfigDto.getName());
        if (StringUtils.isNotBlank(skillName)) {
            skillNamesForPrompt.add(skillName);
        }
    }

    private void prependSkillPrompt(Map<String, Object> chatBody, List<String> skillNamesForPrompt) {
        if (CollectionUtils.isEmpty(skillNamesForPrompt)) {
            return;
        }
        Object originPromptObj = chatBody.get("prompt");
        String originPrompt = originPromptObj == null ? "" : String.valueOf(originPromptObj);
        StringBuilder userPromptBuilder = new StringBuilder();
        userPromptBuilder.append("\n" + CustomPagePromptConstants.resolvePromptText(skillUserPrompt) + "\n");
        skillNamesForPrompt.forEach(skillName -> userPromptBuilder.append("- ").append(skillName).append("\n"));
        if (StringUtils.isNotBlank(originPrompt)) {
            userPromptBuilder.append("\n").append(originPrompt);
        }
        chatBody.put("prompt", userPromptBuilder.toString());
    }

    private List<Long> parseSkillIds(Object value) {
        if (!(value instanceof List<?> rawList) || rawList.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> skillIds = new ArrayList<>();
        for (Object item : rawList) {
            if (item == null) {
                continue;
            }
            if (item instanceof Number number) {
                skillIds.add(number.longValue());
                continue;
            }
            if (item instanceof String text && StringUtils.isNotBlank(text)) {
                try {
                    skillIds.add(Long.parseLong(text.trim()));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid skill id: " + text);
                }
                continue;
            }
            if (item instanceof Map<?, ?> mapValue) {
                Object skillIdObj = mapValue.get("skillId");
                if (skillIdObj == null) {
                    skillIdObj = mapValue.get("id");
                }
                if (skillIdObj == null) {
                    continue;
                }
                try {
                    skillIds.add(Long.parseLong(String.valueOf(skillIdObj)));
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("Invalid skill id: " + skillIdObj);
                }
            }
        }
        return skillIds;
    }

    private MultipartFile buildSkillZip(List<SkillConfigDto> skills) {
        if (skills == null || skills.isEmpty()) {
            return null;
        }

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ZipOutputStream zos = new ZipOutputStream(baos, java.nio.charset.StandardCharsets.UTF_8)) {
            Set<String> addedEntries = new HashSet<>();

            ZipEntry skillsRoot = new ZipEntry("skills/");
            zos.putNextEntry(skillsRoot);
            zos.closeEntry();
            addedEntries.add("skills/");

            for (SkillConfigDto skill : skills) {
                if (skill == null || skill.getFiles() == null || skill.getFiles().isEmpty()) {
                    continue;
                }
                String skillName = StringUtils.isNotBlank(skill.getEnName()) ? skill.getEnName() : skill.getName();
                if (StringUtils.isBlank(skillName)) {
                    continue;
                }

                String skillDir = "skills/" + skillName + "/";
                if (!addedEntries.contains(skillDir)) {
                    ZipEntry skillDirEntry = new ZipEntry(skillDir);
                    zos.putNextEntry(skillDirEntry);
                    zos.closeEntry();
                    addedEntries.add(skillDir);
                }

                for (SkillFileDto fileDto : skill.getFiles()) {
                    if (fileDto == null || StringUtils.isBlank(fileDto.getName())) {
                        continue;
                    }
                    String fileName = fileDto.getName();
                    if (fileName.startsWith("/")) {
                        fileName = fileName.substring(1);
                    }
                    String entryName = skillDir + fileName;

                    if (Boolean.TRUE.equals(fileDto.getIsDir())) {
                        if (!entryName.endsWith("/")) {
                            entryName = entryName + "/";
                        }
                        if (addedEntries.contains(entryName)) {
                            continue;
                        }
                        addedEntries.add(entryName);
                        ZipEntry entry = new ZipEntry(entryName);
                        zos.putNextEntry(entry);
                        zos.closeEntry();
                        continue;
                    }

                    ensureParentDirectories(zos, entryName, skillDir, addedEntries);
                    if (addedEntries.contains(entryName)) {
                        continue;
                    }
                    addedEntries.add(entryName);
                    ZipEntry entry = new ZipEntry(entryName);
                    zos.putNextEntry(entry);

                    String contents = fileDto.getContents();
                    if (contents != null) {
                        byte[] bytes = getFileBytes(contents, fileDto.getName());
                        zos.write(bytes);
                    }
                    zos.closeEntry();
                }
            }

            zos.finish();
            byte[] zipBytes = baos.toByteArray();
            if (zipBytes.length == 0) {
                return null;
            }
            return new InMemoryMultipartFile("file", "skills.zip", "application/zip", zipBytes);
        } catch (IOException e) {
            log.error("[Flux Service] pack skill zip failed", e);
            throw new IllegalArgumentException("Pack skill zip failed");
        }
    }

    private byte[] getFileBytes(String contents, String fileName) {
        if (StringUtils.isBlank(contents)) {
            return new byte[0];
        }
        if (FileTypeUtils.isTextFile(fileName)) {
            return contents.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        try {
            return Base64.getDecoder().decode(contents);
        } catch (IllegalArgumentException e) {
            return contents.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private void ensureParentDirectories(ZipOutputStream zos, String filePath, String baseDir, Set<String> addedEntries)
            throws IOException {
        String relativePath = filePath;
        if (filePath.startsWith(baseDir)) {
            relativePath = filePath.substring(baseDir.length());
        }
        if (!relativePath.contains("/")) {
            return;
        }

        String[] parts = relativePath.split("/");
        StringBuilder currentPath = new StringBuilder(baseDir);
        for (int i = 0; i < parts.length - 1; i++) {
            if (StringUtils.isBlank(parts[i])) {
                continue;
            }
            currentPath.append(parts[i]).append("/");
            String dirPath = currentPath.toString();
            if (!addedEntries.contains(dirPath)) {
                ZipEntry dirEntry = new ZipEntry(dirPath);
                zos.putNextEntry(dirEntry);
                zos.closeEntry();
                addedEntries.add(dirPath);
            }
        }
    }

    private SkillConfigDto parseSkillConfig(String config) {
        if (StringUtils.isBlank(config)) {
            return new SkillConfigDto();
        }
        try {
            SkillPublishedConfigDto publishedConfig = JSON.parseObject(config, SkillPublishedConfigDto.class);
            if (publishedConfig != null
                    && (SkillFileFormatConstants.SKILL_FILES_V2.equals(publishedConfig.getFormat()) || StringUtils.isNotBlank(publishedConfig.getZipFileUrl()))) {
                SkillConfigDto dto = new SkillConfigDto();
                dto.setId(publishedConfig.getId());
                dto.setName(publishedConfig.getName());
                dto.setDescription(publishedConfig.getDescription());
                dto.setIcon(publishedConfig.getIcon());
                dto.setFiles(publishedConfig.getFiles());
                dto.setZipFileUrl(publishedConfig.getZipFileUrl());
                return dto;
            }
        } catch (Exception e) {
            log.debug("[Flux Service] parse skill config as v2 failed", e);
        }
        return JSON.parseObject(config, SkillConfigDto.class);
    }

    private boolean isV2Config(SkillConfigDto skillConfig) {
        return skillConfig != null && StringUtils.isNotBlank(skillConfig.getZipFileUrl());
    }

    private void sendProgressFlux(FluxSink<Map<String, Object>> sink, String message, int progress) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "progress");
        data.put("message", message);
        data.put("progress", progress);
        sink.next(data);
        log.info("[Flux Service] Flux : {}", message);
    }

    /**
     * 下发平台临时会话 ID（与 Agent session 无关，前端协议保持不变）。
     * Agent 会话 ID 仅来自后续 {@code success} 的 {@code data.session_id}。
     */
    private void sendSessionIdFlux(FluxSink<Map<String, Object>> sink, String platformFluxSessionId) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "session_id");
        data.put("session_id", platformFluxSessionId);
        sink.next(data);
        log.info("[Flux Service] Flux session ID (platform temporary): {}", platformFluxSessionId);
    }

    private void sendHeartbeatFlux(FluxSink<Map<String, Object>> sink) {
        sendHeartbeatFlux(sink, null);
    }

    private void sendHeartbeatFlux(FluxSink<Map<String, Object>> sink, String remark) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "heartbeat");
        data.put("timestamp", System.currentTimeMillis());
        if (remark != null) {
            data.put("remark", remark);
        }
        sink.next(data);
        log.debug("[Flux Service] Flux");
    }

    /**
     * 同步调用 sendChat，带超时机制，不发送心跳
     */
    private Map<String, Object> callSendChatSync(Map<String, Object> chatBody,
                                                 Long projectId,
                                                 UserContext userContext,
                                                 AtomicBoolean stopRequested,
                                                 String platformFluxSessionId) {
        try {
            Object systemPromptObj = chatBody.get("system_prompt");
            String systemPrompt = systemPromptObj == null ? null : StringUtils.trimToNull(systemPromptObj.toString());
            if (systemPrompt == null) {
                Object systemPromptCamelObj = chatBody.get("systemPrompt");
                systemPrompt = systemPromptCamelObj == null ? null : StringUtils.trimToNull(systemPromptCamelObj.toString());
            }

            if (systemPrompt == null) {
                systemPrompt = I18nUtil.systemMessage("Backend.CustomPage.Chat.SystemPrompt");
            }
            if ("Backend.CustomPage.Chat.SystemPrompt".equals(systemPrompt)) {
                systemPrompt = CustomPagePromptConstants.resolvePromptText(CustomPagePromptConstants.baseSystemPrompt);
            }
            chatBody.put("system_prompt", systemPrompt);
            RequestContext<Object> parentContext = RequestContext.get();
            Long tenantId = parentContext != null ? parentContext.getTenantId() : userContext.getTenantId();
            Long userId = parentContext != null ? parentContext.getUserId() : userContext.getUserId();

            CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
                try {
                    RequestContext<Object> asyncContext = new RequestContext<>();
                    asyncContext.setTenantId(tenantId);
                    asyncContext.setUserId(userId);
                    RequestContext.set(asyncContext);
                    prepareAgentChatBody(chatBody, platformFluxSessionId);
                    return pageAppAIClient.sendChat(chatBody, projectId, userContext);
                } finally {
                    RequestContext.remove();
                }
            }, aiChatExecutor);

            long deadline = System.currentTimeMillis() + 65000;
            while (true) {
                if (stopRequested.get()) {
                    future.cancel(true);
                    throw new CancellationException("session stopped");
                }
                if (System.currentTimeMillis() > deadline) {
                    future.cancel(true);
                    log.warn("[Flux Service] AI Agent call timeout, exceeds 65 seconds");
                    return null;
                }
                try {
                    return future.get(500, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    if (future.isDone()) {
                        return future.get();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Flux Service] AI Agent call interrupted", e);
            throw new RuntimeException(e);
        } catch (CancellationException e) {
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            log.error("[Flux Service] call AI Agent exception", cause != null ? cause : e);
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Error err) {
                throw err;
            }
            throw new RuntimeException(cause != null ? cause.getMessage() : e.getMessage(),
                    cause != null ? cause : e);
        }
    }

    private void sendSuccessFlux(FluxSink<Map<String, Object>> sink, Map<String, Object> data) {
        Map<String, Object> result = new HashMap<>();
        result.put("type", "success");
        result.put("data", data);
        sink.next(result);
        log.info("[Flux Service] Flux succeeded");
    }

    private void sendErrorFlux(FluxSink<Map<String, Object>> sink, String code, String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("type", "error");
        result.put("code", code);
        result.put("message", message);
        sink.next(result);
        // 不在这里调用 complete()，由 finally 块统一处理
        log.error("[Flux Service] Flux : code={}, message={}", code, message);
    }

    private void sendCanceledFlux(FluxSink<Map<String, Object>> sink) {
        Map<String, Object> result = new HashMap<>();
        result.put("type", "canceled");
        result.put("code", "0000");
        result.put("message", "会话已终止");
        sink.next(result);
    }

    private void scheduleSessionStopWatcher(String sessionId, FluxSink<Map<String, Object>> sink,
                                            AtomicBoolean watcherStopped, AtomicBoolean stopRequested) {
        timeWheel.schedule((res) -> {
            if (watcherStopped.get()) {
                return;
            }
            if (sessionManager.isSessionStopRequested(sessionId)) {
                if (watcherStopped.compareAndSet(false, true)) {
                    stopRequested.set(true);
                    log.info("[Flux Service] session stop detected by time wheel, session Id={}", sessionId);
                    try {
                        sendCanceledFlux(sink);
                    } catch (Exception e) {
                        log.debug("[Flux Service] failed to send canceled event, session Id={}", sessionId, e);
                    } finally {
                        sessionManager.terminateSession(sessionId);
                    }
                }
                return;
            }
            scheduleSessionStopWatcher(sessionId, sink, watcherStopped, stopRequested);
        }, 1);
    }

    private void throwIfStopRequested(AtomicBoolean stopRequested) {
        if (stopRequested.get()) {
            throw new CancellationException("session stopped");
        }
    }

    private void sendImageAnalysisResultFlux(FluxSink<Map<String, Object>> sink, String imageUrl,
                                             String fileName, String analysisResult) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "image_analysis");
        data.put("imageUrl", imageUrl);
        data.put("fileName", fileName);
        data.put("analysisResult", analysisResult);
        data.put("timestamp", System.currentTimeMillis());
        sink.next(data);
        log.info("[Flux Service] Flux parse : file Name={}", fileName);
    }

    private Map<String, Object> parseResponseData(Map<String, Object> chatResp) {
        Object data = chatResp.get("data");
        Map<String, Object> dataMap = new HashMap<>();

        if (data != null) {
            try {
                if (data instanceof Map<?, ?>) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> existingMap = (Map<String, Object>) data;
                    dataMap = existingMap;
                } else {
                    String dataJson = data.toString();
                    dataMap = objectMapper.readValue(dataJson, new TypeReference<Map<String, Object>>() {
                    });
                }
            } catch (Exception e) {
                log.warn("Failed to parse data JSON, using raw payload", e);
                dataMap.put("data", data);
            }
        }

        if (chatResp.get("tid") != null) {
            dataMap.put("tid", chatResp.get("tid"));
        }
        if (chatResp.get("message") != null) {
            dataMap.put("message", chatResp.get("message"));
        }
        if (chatResp.get("code") != null) {
            dataMap.put("code", chatResp.get("code"));
        }

        return dataMap;
    }

    private void saveConversationSafely(Map<String, Object> chatBody, Long projectId, String sessionId, String requestId, UserContext userContext) {
        String promptStr = String.valueOf(chatBody.get("prompt"));
        String topic = buildTopic(promptStr);

        Map<String, Object> contentMap = new LinkedHashMap<>();
        contentMap.put("text", promptStr + "\n");
        contentMap.put("attachments", normalizeToList(chatBody.get("attachment_files")));
        contentMap.put("dataSources", normalizeToList(chatBody.get("data_sources")));
        contentMap.put("attachmentPrototypeImages", normalizeToList(chatBody.get("attachment_prototype_images")));

        String content = JSON.toJSONString(contentMap);

        if (projectId == null || StringUtils.isBlank(content)) {
            return;
        }
        try {
            CustomPageConversationModel conversationModel = new CustomPageConversationModel();
            conversationModel.setProjectId(projectId);
            conversationModel.setTopic(topic);
            conversationModel.setContent(content);
            conversationModel.setRole("USER");
            conversationModel.setSessionId(sessionId);
            conversationModel.setRequestId(requestId);
            customPageConversationDomainService.saveConversation(conversationModel, userContext);
        } catch (Exception e) {
            log.warn("[Flux Service] auto save conversation failed, project Id={}, topic={}", projectId, topic, e);
        }
    }

    private List<?> normalizeToList(Object value) {
        if (value instanceof List<?>) {
            return (List<?>) value;
        }
        return Collections.emptyList();
    }

    /**
     * 准备 Agent /chat 请求体中的 session_id：
     * <ul>
     * <li>平台临时 id（本 Flux 推送给前端的 32 位无横线 id）→ 剥离，不传给 Agent</li>
     * <li>前端传入的 Agent sessionId（/chat 曾返回）→ 原样传给 Agent 复用</li>
     * <li>未传 → 不带 session_id，由 Agent 创建</li>
     * </ul>
     * Agent 无终态概念；平台 DB 终态仅用于 ai-session-sse 历史回放，不影响是否传给 Agent。
     *
     * @return 实际传给 Agent 的 session_id，未传则为 null
     */
    private String prepareAgentChatBody(Map<String, Object> chatBody, String platformFluxSessionId) {
        String requestedSessionId = extractAndRemoveSessionId(chatBody);
        if (StringUtils.isBlank(requestedSessionId)) {
            return null;
        }
        if (isPlatformTemporarySessionId(requestedSessionId, platformFluxSessionId)) {
            log.info("[Flux Service] strip platform flux session_id from Agent /chat, value={}", requestedSessionId);
            return null;
        }
        chatBody.put("session_id", requestedSessionId);
        log.info("[Flux Service] pass client agent session_id to Agent /chat, session Id={}", requestedSessionId);
        return requestedSessionId;
    }

    private boolean isPlatformTemporarySessionId(String sessionId, String platformFluxSessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return false;
        }
        if (sessionId.equals(platformFluxSessionId)) {
            return true;
        }
        return !sessionId.contains("-") && sessionId.length() == 32;
    }


    private void afterAgentChatAccepted(Long projectId, String fluxRequestId, UserContext userContext) {
        if (projectId == null || StringUtils.isBlank(fluxRequestId)) {
            return;
        }
        agentProgressSessionCoordinator.prepareForNewTurn(projectId, fluxRequestId);
    }

    private String extractAndRemoveSessionId(Map<String, Object> chatBody) {
        String lastRemoved = null;
        for (String key : new String[]{"session_id", "sessionId"}) {
            Object sessionIdObj = chatBody.remove(key);
            if (sessionIdObj == null) {
                continue;
            }
            String sessionId = String.valueOf(sessionIdObj).trim();
            if (!sessionId.isEmpty()) {
                lastRemoved = sessionId;
            }
        }
        return lastRemoved;
    }

    private void updateUserSessionIdBySuccess(Long projectId, String requestId, Map<String, Object> dataMap,
                                              UserContext userContext) {
        if (projectId == null || StringUtils.isBlank(requestId) || dataMap == null) {
            return;
        }
        Object sessionIdObj = dataMap.get("session_id");
        if (sessionIdObj == null || StringUtils.isBlank(String.valueOf(sessionIdObj))) {
            return;
        }
        String successSessionId = String.valueOf(sessionIdObj);
        customPageConversationDomainService.updateUserSessionIdByRequestId(projectId, requestId, successSessionId,
                userContext);
    }

    private String buildTopic(String text) {
        if (StringUtils.isBlank(text)) {
            return "Untitled";
        }
        String normalized = text.replace("\r", "").replace("\n", " ").trim();
        if (normalized.isEmpty()) {
            return "Untitled";
        }
        return normalized.length() > 20 ? normalized.substring(0, 20) : normalized;
    }

    private String parseFileToText(Long projectId, String url, String fileName) {
        DataTypeEnum dataType = getDocumentTypeFromUrl(url);
        if (dataType == null) {
            log.warn("[Flux Service] project Id={} get file type failed,url={}", projectId, url);
            return null;
        }

        String output;
        try {
            switch (dataType) {
                case File_Doc:
                    try {
                        log.info("[Flux Service] project Id={} start parse Word , url={}", projectId, url);
                        String textContent = UrlFile.wordToMarkdown(url);
                        File tempFile = writeTextToTempFile(projectId, textContent, fileName);
                        output = uploadFileAndGeneratePrompt(projectId, tempFile, fileName,
                                "application/msword", "Word文档附件", true);
                    } catch (Exception e) {
                        log.warn("[Flux Service] project Id={} Word handle failed, url={}", projectId, url, e);
                        output = "";
                    }
                    break;
                case File_Excel:
                    try {
                        log.info("[Flux Service] project Id={} start parse Excel , url={}", projectId, url);
                        String textContent = UrlFile.excelToJson(url);
                        File tempFile = writeTextToTempFile(projectId, textContent, fileName);
                        output = uploadFileAndGeneratePrompt(projectId, tempFile, fileName, "application/vnd.ms-excel", "Excel文档附件", true);
                    } catch (Exception e) {
                        log.warn("[Flux Service] project Id={} Excel handle failed, url={}", projectId, url, e);
                        output = "";
                    }
                    break;
                case File_Txt:
                    try {
                        log.info("[Flux Service] project Id={} start parse Txt , url={}", projectId, url);
                        String textContent = UrlFile.urlToText(url, "UTF-8");
                        File tempFile = writeTextToTempFile(projectId, textContent, fileName);
                        output = uploadFileAndGeneratePrompt(projectId, tempFile, fileName, "text/plain", "文本文件附件", true);
                    } catch (Exception e) {
                        log.warn("[Flux Service] project Id={} file processing failed, url={}", projectId, url, e);
                        output = "";
                    }
                    break;
                case File_Image:
                    try {
                        log.info("[Flux Service] project Id={} start upload , url={}", projectId, url);
                        File tempFile = downloadUrlToTempFile(projectId, url, fileName);
                        output = uploadFileAndGeneratePrompt(projectId, tempFile, fileName, "image/*", "图片附件", false);
                        output += "\n" + CustomPagePromptConstants.resolvePromptText(fileImagesUserPrompt);
                    } catch (Exception e) {
                        log.warn("[Flux Service] project Id={} upload failed, url={}", projectId, url, e);
                        output = "";
                    }
                    break;
                case File_Svg:
                    try {
                        log.info("[Flux Service] project Id={} start upload SVG, url={}", projectId, url);
                        File tempFile = downloadUrlToTempFile(projectId, url, fileName);
                        output = uploadFileAndGeneratePrompt(projectId, tempFile, fileName, "image/svg+xml", "SVG附件", false);
                        output += "\n" + CustomPagePromptConstants.resolvePromptText(fileImagesUserPrompt);
                    } catch (Exception e) {
                        log.warn("[Flux Service] project Id={} SVG upload failed, url={}", projectId, url, e);
                        output = "";
                    }
                    break;
                default:
                    try {
                        log.info("[Flux Service] project Id={} start parse file, url={}", projectId, url);
                        String textContent = UrlFile.parseToString(url);
                        File tempFile = writeTextToTempFile(projectId, textContent, fileName);
                        output = uploadFileAndGeneratePrompt(projectId, tempFile, fileName, "application/octet-stream", "文件附件", true);
                    } catch (Exception e) {
                        log.warn("[Flux Service] project Id={} file processing failed, url={}", projectId, url, e);
                        output = "";
                    }
                    break;
            }
        } catch (Exception e) {
            log.warn("[Flux Service] project Id={} failed to parse file, url={}", projectId, url, e);
            try {
                String textContent = UrlFile.parseToString(url);
                File tempFile = writeTextToTempFile(projectId, textContent, fileName);
                output = uploadFileAndGeneratePrompt(projectId, tempFile, fileName, "application/octet-stream", "文件附件",
                        true);
            } catch (Exception ex) {
                log.warn("[Flux Service] project Id={} failed to parse file, url={}", projectId, url, ex);
                output = "";
            }
        }
        return output;
    }

    private String uploadFileAndGeneratePrompt(Long projectId, File file, String originalFileName,
                                               String contentType, String attachmentType, boolean isTextFile) {
        String uploadFileName = originalFileName;
        try {
            // 根据 isTextFile 参数决定文件名后缀
            if (isTextFile) {
                // 如果标记为文本文件，替换为 .md 后缀
                int lastDotIndex = originalFileName.lastIndexOf('.');
                if (lastDotIndex > 0) {
                    uploadFileName = originalFileName.substring(0, lastDotIndex) + ".md";
                } else {
                    uploadFileName = originalFileName + ".md";
                }
            }

            MultipartFile multipartFile = new FileSystemMultipartFile(file, uploadFileName, contentType);

            log.info("[Flux Service] project Id={} start upload file, upload File Name={}", projectId, uploadFileName);
            Map<String, Object> resp = pageAppFileClient.uploadAttachmentFile(projectId, multipartFile, uploadFileName);

            if (resp != null) {
                String finalFileName = resp.get("fileName") != null ? String.valueOf(resp.get("fileName"))
                        : uploadFileName;
                String relativePath = resp.get("relativePath") != null ? String.valueOf(resp.get("relativePath"))
                        : ("./attachments/" + finalFileName);
                return String.format(CustomPagePromptConstants.resolvePromptText(fileGeneralUserPrompt), attachmentType, finalFileName,
                        relativePath);
            }
            return "";
        } catch (Exception e) {
            log.warn("[Flux Service] project Id={} File upload failed, upload File Name={}", projectId, uploadFileName, e);
            return "";
        } finally {
            file.delete();
        }
    }

    private File downloadUrlToTempFile(Long projectId, String url, String fileName) throws IOException {
        log.info("[Flux Service] project Id={} start download URL file, url={}, file Name={}", projectId, url, fileName);
        File tempFile = File.createTempFile("upload_", "_" + fileName);
        String fileUrlWithAk = iFileAccessService.getFileUrlWithAk(url, true);
        URL fileUrl = new URL(fileUrlWithAk);
        try (InputStream in = fileUrl.openStream();
             OutputStream out = new FileOutputStream(tempFile)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
        }
        return tempFile;
    }

    private File writeTextToTempFile(Long projectId, String content, String fileName) throws IOException {
        log.info("[Flux Service] project Id={} start file, content={}, file Name={}", projectId, content, fileName);

        // 提取原始文件名（去掉扩展名）
        String baseFileName = fileName;
        int lastDotIndex = fileName.lastIndexOf('.');
        if (lastDotIndex > 0) {
            baseFileName = fileName.substring(0, lastDotIndex);
        }

        // 创建临时文件，使用原始文件名 + .md 扩展名
        File tempFile = File.createTempFile("upload_", "_" + baseFileName + ".md");
        try (FileWriter writer = new FileWriter(tempFile)) {
            writer.write(content);
        }
        return tempFile;
    }

    private DataTypeEnum getDocumentTypeFromUrl(String url) {
        String url0 = url;
        url = url.toLowerCase();
        if (url.endsWith(".pdf")) {
            return DataTypeEnum.File_PDF;
        } else if (url.endsWith(".doc") || url.endsWith(".docx")) {
            return DataTypeEnum.File_Doc;
        } else if (url.endsWith(".xls") || url.endsWith(".xlsx")) {
            return DataTypeEnum.File_Excel;
        } else if (url.endsWith(".ppt") || url.endsWith(".pptx")) {
            return DataTypeEnum.File_PPT;
        } else if (url.endsWith(".txt")) {
            return DataTypeEnum.File_Txt;
        } else if (url.endsWith(".text")) {
            return DataTypeEnum.File_Txt;
        } else if (url.endsWith(".json")) {
            return DataTypeEnum.File_Txt;
        } else if (url.endsWith(".html")) {
            return DataTypeEnum.File_Txt;
        } else if (url.endsWith(".htm")) {
            return DataTypeEnum.File_Txt;
        } else if (url.endsWith(".md") || url.endsWith(".markdown") || url.endsWith(".mdown") || url.endsWith(".mkd")) {
            return DataTypeEnum.File_Txt;
        } else if (url.endsWith(".jpg") || url.endsWith(".jpeg")) {
            return DataTypeEnum.File_Image;
        } else if (url.endsWith(".png")) {
            return DataTypeEnum.File_Image;
        } else if (url.endsWith(".gif")) {
            return DataTypeEnum.File_Image;
        } else if (url.endsWith(".bmp")) {
            return DataTypeEnum.File_Image;
        } else if (url.endsWith(".webp")) {
            return DataTypeEnum.File_Image;
        } else if (url.endsWith(".svg")) {
            return DataTypeEnum.File_Svg;
        } else if (url.endsWith(".ico")) {
            return DataTypeEnum.File_Image;
        } else {
            try {
                URL fileUrl = new URL(url0);
                URLConnection connection = fileUrl.openConnection();
                connection.setRequestProperty("User-Agent", "Mozilla/5.0");
                connection.connect();
                String cType = connection.getContentType();
                if (cType != null) {
                    if (cType.contains("pdf")) {
                        return DataTypeEnum.File_PDF;
                    } else if (cType.contains("word")) {
                        return DataTypeEnum.File_Doc;
                    } else if (cType.contains("excel")) {
                        return DataTypeEnum.File_Excel;
                    } else if (cType.contains("ppt")) {
                        return DataTypeEnum.File_PPT;
                    } else if (cType.contains("text")) {
                        return DataTypeEnum.File_Txt;
                    } else if (cType.contains("image")) {
                        return DataTypeEnum.File_Image;
                    }
                }
            } catch (IOException e) {
                // ignore
            }
        }

        return null;
    }

    @Override
    public boolean terminateSession(String sessionId) {
        log.info("[Flux Service] terminate session request: session Id={}", sessionId);
        return sessionManager.terminateSession(sessionId);
    }
}
