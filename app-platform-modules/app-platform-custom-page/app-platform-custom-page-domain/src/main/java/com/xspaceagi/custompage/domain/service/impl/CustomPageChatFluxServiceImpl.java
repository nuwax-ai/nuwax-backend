package com.xspaceagi.custompage.domain.service.impl;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.AttachmentDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.infra.component.agent.AgentContext;
import com.xspaceagi.agent.core.infra.component.model.ModelContext;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.infra.component.model.dto.ModelCallConfigDto;
import com.xspaceagi.agent.core.infra.rpc.MarketClientRpcService;
import com.xspaceagi.agent.core.infra.rpc.ModelApiProxyRpcService;
import com.xspaceagi.agent.core.sdk.IAgentRpcService;
import com.xspaceagi.agent.core.sdk.enums.TargetTypeEnum;
import com.xspaceagi.agent.core.spec.enums.DataTypeEnum;
import com.xspaceagi.agent.core.spec.enums.ModelTypeEnum;
import com.xspaceagi.agent.core.spec.enums.OutputTypeEnum;
import com.xspaceagi.agent.core.spec.utils.UrlFile;
import com.xspaceagi.custompage.domain.gateway.AiAgentClient;
import com.xspaceagi.custompage.domain.gateway.PageFileBuildClient;
import com.xspaceagi.custompage.domain.model.CustomPageBuildModel;
import com.xspaceagi.custompage.domain.model.CustomPageConfigModel;
import com.xspaceagi.custompage.domain.repository.ICustomPageBuildRepository;
import com.xspaceagi.custompage.domain.repository.ICustomPageConfigRepository;
import com.xspaceagi.custompage.domain.service.CustomPageChatSessionManager;
import com.xspaceagi.custompage.domain.service.ICustomPageChatFluxService;
import com.xspaceagi.custompage.sdk.dto.DataSourceDto;
import com.xspaceagi.custompage.sdk.dto.VersionInfoDto;
import com.xspaceagi.custompage.sdk.enums.CustomPageActionEnum;
import com.xspaceagi.eco.market.sdk.reponse.ClientSecretResponse;
import com.xspaceagi.eco.market.sdk.request.ClientSecretRequest;
import com.xspaceagi.modelproxy.sdk.service.dto.BackendModelDto;
import com.xspaceagi.modelproxy.sdk.service.dto.FrontendModelDto;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.file.FileSystemMultipartFile;
import com.xspaceagi.system.spec.utils.DateUtil;
import com.xspaceagi.system.spec.utils.FileAkUtil;
import com.xspaceagi.system.spec.utils.I18nUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
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
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class CustomPageChatFluxServiceImpl implements ICustomPageChatFluxService {

    @Resource
    private FileAkUtil fileAkUtil;
    @Resource
    private AiAgentClient aiAgentClient;
    @Resource
    private IAgentRpcService agentRpcService;
    @Resource
    private PageFileBuildClient pageFileBuildClient;
    @Resource
    private SpacePermissionService spacePermissionService;
    @Resource
    private ModelApplicationService modelApplicationService;
    @Resource
    private ICustomPageBuildRepository customPageBuildRepository;
    @Resource
    private ICustomPageConfigRepository customPageConfigRepository;
    @Resource
    private CustomPageChatSessionManager sessionManager;
    @Resource
    private MarketClientRpcService marketClientRpcService;
    @Resource
    @Qualifier("aiChatCallExecutor")
    private Executor aiChatCallExecutor;

    @Resource
    private ModelInvoker modelInvoker;

    @Resource
    private ModelApiProxyRpcService modelApiProxyRpcService;

    private final static ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);


    @Override
    public Flux<Map<String, Object>> sendAgentChatFlux(Map<String, Object> chatBody, UserContext userContext) {
        // 验证参数
        if (chatBody == null) {
            return Flux.error(new IllegalArgumentException("Request body cannot be empty"));
        }

        Long projectId;
        Object projectIdObj = chatBody.get("project_id");
        Object promptObj = chatBody.get("prompt");

        if (projectIdObj == null) {
            return Flux.error(new IllegalArgumentException("project_id is required"));
        }
        if (promptObj == null || StringUtils.isBlank(String.valueOf(promptObj))) {
            return Flux.error(new IllegalArgumentException("prompt is required"));
        }
        try {
            projectId = Long.valueOf(String.valueOf(projectIdObj));
        } catch (Exception e) {
            return Flux.error(new IllegalArgumentException("Invalid project_id"));
        }
        CustomPageBuildModel buildModel = customPageBuildRepository.getByProjectId(projectId);
        if (buildModel == null) {
            return Flux.error(new IllegalArgumentException("Project does not exist"));
        }
        spacePermissionService.checkSpaceUserPermission(buildModel.getSpaceId());

        // 生成会话ID
        String sessionId = UUID.randomUUID().toString().replace("-", "");

        return Flux.<Map<String, Object>>create(sink -> {
            try {
                // 注册会话
                sessionManager.registerSession(sessionId, sink);

                // 发送会话ID
                sendSessionIdFlux(sink, sessionId);

                executeChatFlux(chatBody, userContext, sink, buildModel, promptObj);
            } catch (Exception e) {
                log.error("[Flux Service] chat exception", e);
                sink.error(e);
            } finally {
                // 完成流并移除会话
                try {
                    sink.complete();
                } catch (Exception e) {
                    log.debug("[Flux Service] sink already completed, ignore duplicate call", e);
                }
                sessionManager.removeSession(sessionId);
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
                                 FluxSink<Map<String, Object>> sink, CustomPageBuildModel buildModel, Object promptObj) {
        try {
            Long projectId = buildModel.getProjectId();

            // 1: 处理原型图片
            Long multiModelId = processPrototypeImagesFlux(chatBody, projectId, sink);

            // 2: 处理附件文件
            processAttachmentFilesFlux(chatBody, projectId, promptObj, sink);

            // 3: 处理模型配置
            Long chatModelId = processModelConfigFlux(chatBody, userContext, sink);

            // 4: 处理数据源
            processDataSourcesFlux(chatBody, projectId, sink);

            // 5: 备份当前版本
            // sendProgressFlux(sink, "正在备份当前版本...", 60);
            sendHeartbeatFlux(sink);

            Integer currentVersion = buildModel.getCodeVersion() == null ? 0 : buildModel.getCodeVersion();
            Map<String, Object> backupResp = pageFileBuildClient.backupCurrentVersion(projectId, currentVersion);
            if (backupResp == null || !Boolean.parseBoolean(String.valueOf(backupResp.get("success")))) {
                String msg = backupResp != null && backupResp.get("message") != null
                        ? String.valueOf(backupResp.get("message"))
                        : "备份失败";
                sendErrorFlux(sink, "9999", msg);
                return;
            }

            // 6: 更新版本
            // sendProgressFlux(sink, "正在更新版本...", 70);
            sendHeartbeatFlux(sink);

            Integer nextVersion = currentVersion + 1;
            List<VersionInfoDto> versionInfo = buildModel.getVersionInfo();
            // 仅记录提示词前100个字符到ext
            String promptStr = String.valueOf(promptObj);
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

            // 7: 调用 AI Agent
            //sendProgressFlux(sink, "正在与 AI 对话...", 80);
            sendHeartbeatFlux(sink, "Calling AI agent...80%");

            // 异步调用 sendChat，并在等待期间发送心跳
            //Map<String, Object> chatResp = callSendChatWithHeartbeat(chatBody, sink);
            Map<String, Object> chatResp = callSendChatSync(chatBody);
            if (chatResp == null) {
                sendErrorFlux(sink, "9999", "AI Agent 无响应");
                return;
            }

            Object code = chatResp.get("code");
            if (code == null || !"0000".equals(String.valueOf(code))) {
                String errorCode = code != null ? String.valueOf(code) : "9999";
                sendErrorFlux(sink, errorCode, String.valueOf(chatResp.get("message")));
                return;
            }

            // 8: 返回结果
            //sendProgressFlux(sink, "AI 处理中...", 100);
            sendHeartbeatFlux(sink, "AI returned result...100%");

            Map<String, Object> dataMap = parseResponseData(chatResp);
            sendSuccessFlux(sink, dataMap);

        } catch (Exception e) {
            log.error("[Flux Service] Flux chat exception", e);
            sendErrorFlux(sink, "0001", "执行失败: " + e.getMessage());
        }
    }

    private Long processPrototypeImagesFlux(Map<String, Object> chatBody, Long projectId,
                                            FluxSink<Map<String, Object>> sink) {
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

        log.info("[Flux Service] send chat message,project Id={},prototype Images={}", projectId, JSON.toJSONString(prototypeImages));

        Object promptObj = chatBody.get("prompt");

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

                ModelContext modelContext = new ModelContext();
                modelContext.setAgentContext(agentContext);
                modelContext.setRequestId(agentContext.getRequestId());
                modelContext.setModelConfig(modelConfig);

                ModelCallConfigDto modelCallConfig = new ModelCallConfigDto();
                modelCallConfig.setSystemPrompt(
                        "你是一个专业的原型图分析助手，专门将UI原型图转换为结构化的Markdown描述，供AI编码工具生成网页代码。你的任务是准确识别页面布局、UI组件、样式和交互元素，并用清晰、结构化的Markdown格式输出。");
                modelCallConfig.setUserPrompt(
                        "请分析这张UI原型图，识别并描述以下内容，使用Markdown格式输出：\n\n## 页面整体布局\n- 描述页面的整体布局结构（如：顶部导航栏、侧边栏、主内容区等）\n- 说明各组件的层级关系和位置关系\n\n## UI组件详情\n对于每个重要的UI组件，请描述：\n- 组件类型（如：按钮、输入框、表格、卡片、列表等）\n- 组件位置和尺寸\n- 组件内容（文字、图标等）\n- 组件样式（颜色、字体大小、边框、圆角等）\n\n## 样式信息\n- 主色调和辅助色\n- 字体大小和字重\n- 间距和边距\n- 圆角、阴影等视觉效果\n\n## 交互说明\n- 按钮点击效果\n- 表单输入说明\n- 其他交互提示\n\n请确保输出清晰、准确、结构完整，便于编码工具理解并生成对应的网页代码。");
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
                chatBody.put("prompt", promptObj + "\n" + markdownContent);
            }
        }
        return multiModelId;
    }

    private void processAttachmentFilesFlux(Map<String, Object> chatBody, Long projectId, Object promptObj,
                                            FluxSink<Map<String, Object>> sink) {
        Object attachmentFiles = chatBody.get("attachment_files");
        if (attachmentFiles == null) {
            return;
        }

        log.info("[Flux Service] send chat message,project Id={},starthandle ,files={}", projectId, JSON.toJSONString(attachmentFiles));

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
                    chatBody.put("prompt", promptObj + "\n" + outputPrompt);
                }
            }
        }
    }

    private Long processModelConfigFlux(Map<String, Object> chatBody, UserContext userContext,
                                        FluxSink<Map<String, Object>> sink) {
        Long projectId = Long.valueOf(String.valueOf(chatBody.get("project_id")));
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

        // 按照权重随机选择一个 ApiInfo
        ModelConfigDto.ApiInfo selectedApiInfo = modelConfigDto.getApiInfoList().get((int) (projectId % modelConfigDto.getApiInfoList().size()));//selectByWeight(modelConfigDto.getApiInfoList());

        if (selectedApiInfo.getKey() != null && selectedApiInfo.getKey().contains("TENANT_SECRET")) {
            ClientSecretRequest clientSecretRequest = new ClientSecretRequest();
            clientSecretRequest.setTenantId(modelConfigDto.getTenantId());
            ClientSecretResponse clientSecretResponse = marketClientRpcService.queryClientSecret(clientSecretRequest);
            selectedApiInfo.setKey(selectedApiInfo.getKey().replace("TENANT_SECRET", clientSecretResponse.getClientSecret()));
        }

        // 全局模型走代理模式，为用户生成独立的key
        if (modelConfigDto.getScope() == ModelConfig.ModelScopeEnum.Tenant) {
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
            FrontendModelDto frontendModelDto = modelApiProxyRpcService.generateUserFrontendModelConfig(userContext.getTenantId(), userContext.getUserId()
                    , -1L, backendModelDto, siteUrl);
            selectedApiInfo = new ModelConfigDto.ApiInfo();
            selectedApiInfo.setKey(frontendModelDto.getApiKey());
            selectedApiInfo.setUrl(frontendModelDto.getBaseUrl());
        }


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
        return chatModelId;
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

    private void processDataSourcesFlux(Map<String, Object> chatBody, Long projectId,
                                        FluxSink<Map<String, Object>> sink) {
        Object dataSources = chatBody.get("data_sources");
        if (dataSources == null) {
            return;
        }

        log.info("[Flux Service] send chat message,project Id={},data Sources={}", projectId, JSON.toJSONString(dataSources));

        List<DataSourceDto> dataSourceList = new ArrayList<>();
        if (dataSources instanceof List<?>) {
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
                    dataSourceList.add(dataSource);
                }
            }

            if (dataSourceList.size() > 0) {
                CustomPageConfigModel configModel = customPageConfigRepository.getById(projectId);
                if (configModel == null) {
                    throw new IllegalArgumentException("Project configuration does not exist: " + projectId);
                }
                List<DataSourceDto> existingDataSources = Optional.ofNullable(configModel.getDataSources())
                        .orElseThrow(() -> new IllegalArgumentException("Project has no data sources bound"));

                // 判断传入的dataSourceList是否都在existingDataSources中
                for (DataSourceDto incoming : dataSourceList) {
                    boolean found = existingDataSources.stream()
                            .anyMatch(existing -> existing.getId() != null
                                    && existing.getId().equals(incoming.getId())
                                    && existing.getType() != null && existing.getType().equals(incoming.getType()));
                    if (!found) {
                        throw new IllegalArgumentException(
                                "Data source not authorized: dataSouceId=" + incoming.getId() + ", type=" + incoming.getType());
                    }
                }

                List<String> dataSourceSchemaList = new ArrayList<>();

                for (DataSourceDto incoming : dataSourceList) {
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
        }
    }

    private void sendProgressFlux(FluxSink<Map<String, Object>> sink, String message, int progress) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "progress");
        data.put("message", message);
        data.put("progress", progress);
        sink.next(data);
        log.info("[Flux Service] Flux : {}", message);
    }

    private void sendSessionIdFlux(FluxSink<Map<String, Object>> sink, String sessionId) {
        Map<String, Object> data = new HashMap<>();
        data.put("type", "session_id");
        data.put("session_id", sessionId);
        sink.next(data);
        log.info("[Flux Service] Flux session ID: {}", sessionId);
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
    private Map<String, Object> callSendChatSync(Map<String, Object> chatBody) {
        try {
            String systemPrompt = I18nUtil.systemMessage("Backend.CustomPage.Chat.SystemPrompt");
            if ("Backend.CustomPage.Chat.SystemPrompt".equals(systemPrompt)) {
                systemPrompt = "<SYSTEM_INSTRUCTIONS>\n" +
                        "\n" +
                        "You are a professional frontend project development expert integrated with MCP (Model Context Protocol) tools. You are proficient in modern frontend technology stacks including React, Vue, Vite, TypeScript and other mainstream frameworks and tools. You are designed to identify the framework used by a project and develop based on the existing technology stack, rather than forcibly converting frameworks.\n" +
                        "\n" +
                        "**Core Capabilities**:\n" +
                        "• **Framework Identification**: Automatically identify the frontend framework used by the project (React, Vue, etc.)\n" +
                        "• **Framework Adaptation**: Write code based on the project's current framework, maintaining technology stack consistency\n" +
                        "• **General Tools**: Vite, TypeScript, Tailwind CSS, ESLint, Prettier\n" +
                        "• **HTTP Clients**: Axios, Fetch API\n" +
                        "• **Package Managers**: pnpm, npm, yarn\n" +
                        "• **Build Tools**: Vite (Hot Module Replacement, Fast Builds)\n" +
                        "• **Code Standards**: ESLint + Prettier + TypeScript Strict Mode\n" +
                        "\n" +
                        "**Key Principles**:\n" +
                        "1. **Prioritize Framework Identification**: Before modifying code, first detect the framework used by the project (through package.json, file structure, etc.)\n" +
                        "2. **Maintain Technology Stack Consistency**: If the project uses Vue, develop with Vue; if it's React, develop with React\n" +
                        "3. **No Forced Framework Conversion**: Never convert Vue code to React or React code to Vue\n" +
                        "4. **Project Development**: Develop new features or fix existing features based on the existing project structure\n" +
                        "\n" +
                        "<ROLE_DEFINITION>\n" +
                        "You are a professional frontend development expert proficient in multiple modern frontend frameworks and toolchains. You have access to various MCP tools, including context7 for web search and documentation retrieval.\n" +
                        "**Technical Capability Scope**:\n" +
                        "• **Mainstream Frameworks**: React, Vue, Angular, Svelte and other modern frontend frameworks with their ecosystems\n" +
                        "• **Development Languages**: TypeScript, JavaScript (ES6+), HTML5, CSS3\n" +
                        "• **Styling Solutions**: Tailwind CSS, CSS Modules, Sass, Less, Styled Components\n" +
                        "• **Build Tools**: Vite, Webpack, Rollup, esbuild and other modern build tools\n" +
                        "• **State Management**: State management solutions for each framework (Redux, Pinia, NgRx, Zustand, etc.)\n" +
                        "• **HTTP Clients**: Axios, Fetch API, HTTP libraries for each framework\n" +
                        "• **Code Quality Tools**: ESLint, Prettier, TSLint and other code quality tools\n" +
                        "\n" +
                        "**Core Working Principles**:\n" +
                        "1. **Identify Framework First**: Must identify the project's framework and technology stack before writing code\n" +
                        "2. **Respect Existing Technology Stack**: Develop based on the project's existing frameworks and tools without unauthorized changes\n" +
                        "3. **Maintain Consistency**: Use the project's current framework syntax, conventions, and best practices\n" +
                        "4. **Use Tools**: Use available MCP tools when they can provide better answers\n" +
                        "5. **Best Practices**: Follow the latest best practices and design patterns for each framework and tool\n" +
                        "\n" +
                        "<CODE_FORMAT_RULES>\n" +
                        "**General Code Standards**:\n" +
                        "1. Always write code in TypeScript strict mode\n" +
                        "2. Component files use PascalCase naming, utility functions use camelCase\n" +
                        "3. Interface types use PascalCase with 'Interface' or 'Type' suffix\n" +
                        "4. Prefer Tailwind CSS for styling\n" +
                        "5. API calls use Axios client or Fetch API\n" +
                        "6. Add JSDoc-style comments for complex logic\n" +
                        "7. Follow the project's code conventions and file structure conventions\n" +
                        "8. Ensure code formatting is correct and readable\n" +
                        "9. Consider error handling and edge cases\n" +
                        "10. Use appropriate variable and function names\n" +
                        "11. Leverage Vite's fast builds and hot module replacement\n" +
                        "12. The 'title' tag in the 'index.html' file in the project root directory should NOT contain any frontend framework names such as: React, Vite, Vue, Antd, Angular, etc.\n" +
                        "13. **Important: Router Mode Specification**: When developing involving routing, you MUST use hash mode. For example: React Router uses `HashRouter`, Vue Router configures `mode: 'hash'`, Angular Router uses `HashLocationStrategy` from LocationStrategy.\n" +
                        "14. **Important: Protect Injected Code Blocks**: It is strictly forbidden to delete or modify code blocks surrounded by `DEV-INJECT-START` and `DEV-INJECT-END` markers. These code blocks are automatically injected by development tools and must be completely preserved. When editing code, preserve these markers and all content between them.\n" +
                        "\n" +
                        "**React Project Specific Standards**:\n" +
                        "• Follow React functional component best practices, use React.FC types\n" +
                        "• Use Radix UI component library for building UI\n" +
                        "• Forms use React Hook Form + Zod for validation\n" +
                        "• Use React.memo, useCallback, useMemo for performance optimization\n" +
                        "• Follow React Hooks rules\n" +
                        "• Routing must use `HashRouter` (from react-router-dom), do not use `BrowserRouter`\n" +
                        "\n" +
                        "**Vue Project Specific Standards**:\n" +
                        "• Prefer Composition API (setup syntax sugar)\n" +
                        "• Use Element Plus or other Vue UI component libraries\n" +
                        "• Use Pinia for state management\n" +
                        "• Follow Vue best practices and reactivity system rules\n" +
                        "• Use computed, watch, ref, reactive and other Composition API features\n" +
                        "• Vue Router must be configured in hash mode: `createRouter({ history: createWebHashHistory(), ... })`\n" +
                        "\n" +
                        "<DEVELOPMENT_CONSTRAINTS>\n" +
                        "**Strictly Prohibited Operations - Absolutely Not Allowed**:\n" +
                        "\n" +
                        "\uD83D\uDEAB **Security Ban** (Highest Priority):\n" +
                        "- **Absolutely prohibited** from probing, scanning, or accessing private network IP addresses (such as 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, 127.0.0.0/8)\n" +
                        "- **Absolutely prohibited** from attempting to access local services (localhost, 127.0.0.1, 0.0.0.0)\n" +
                        "- **Absolutely prohibited** from port scanning, network probing, private network service discovery, etc.\n" +
                        "- **Absolutely prohibited** from hardcoding private network IP addresses or private network addresses in code\n" +
                        "- **Absolutely prohibited** from using curl, wget, nc, telnet, nmap and other tools to probe private networks\n" +
                        "- **Absolutely prohibited** from executing any commands or code that may compromise system security\n" +
                        "- **Absolutely prohibited** from bypassing security restrictions or attempting privilege escalation\n" +
                        "- **Absolutely prohibited** from executing reverse shells, remote code execution, or other malicious operations\n" +
                        "- **Core Principle**: All network requests must point to public network services or legitimate API endpoints explicitly provided by the user\n" +
                        "\n" +
                        "\uD83D\uDEAB **Framework Conversion Ban** (Most Important):\n" +
                        "- **Absolutely prohibited** from rewriting Vue code as React code\n" +
                        "- **Absolutely prohibited** from rewriting React code as Vue code\n" +
                        "- **Absolutely prohibited** from arbitrarily switching frameworks in existing projects\n" +
                        "- **Must Follow**: After identifying the project framework, only use that framework's syntax and APIs\n" +
                        "- **Core Principle**: Respect the project's existing technology stack and maintain framework consistency\n" +
                        "\n" +
                        "\uD83D\uDEAB **Project Initialization Ban**:\n" +
                        "- Prohibited from using npm create, npm init\n" +
                        "- Prohibited from using yarn create, yarn init\n" +
                        "- Prohibited from using npx create-react-app, npx create-vue\n" +
                        "- Prohibited from using pnpm create\n" +
                        "- Prohibited from using any shell commands for project initialization\n" +
                        "- Prohibited from instructing users on how to use npm dev, npm build and other commands (because the project is a server-deployed service, users do not have permission to execute these)\n" +
                        "\n" +
                        "\uD83D\uDEAB **File/Script Creation Ban**:\n" +
                        "- **Prohibited** from creating, referencing, or injecting files or scripts named 'dev-monitor.js' in the project\n" +
                        "\n" +
                        "\uD83D\uDEAB **Code Block Protection Ban** (Important):\n" +
                        "- **Absolutely prohibited** from deleting or modifying code blocks surrounded by `DEV-INJECT-START` and `DEV-INJECT-END` markers\n" +
                        "- **Absolutely prohibited** from removing these markers or their content when editing code\n" +
                        "- **Must Follow**: These code blocks are automatically injected by development tools and must be completely preserved\n" +
                        "- **Core Principle**: When modifying code, if encountering these markers, bypass or preserve all content between these markers\n" +
                        "\n" +
                        "✅ **Allowed Operation Scope**:\n" +
                        "- **Primary Task**: Identify the framework used by the project (check package.json, file structure, etc.)\n" +
                        "- Focus on writing and modifying frontend code files\n" +
                        "- Create components, pages, style files based on the project framework (.vue for Vue, .tsx/.jsx for React)\n" +
                        "- Modify existing TypeScript/JavaScript code (maintaining framework syntax)\n" +
                        "- Write Tailwind CSS or other styles\n" +
                        "- Use the project's corresponding UI component library (Radix UI for React, Element Plus for Vue)\n" +
                        "- Code-level modifications to configuration files (such as tsconfig.json, vite.config.ts)\n" +
                        "- Follow the project's code conventions and file structure\n" +
                        "- **Only Allowed Access**: Public API endpoints or legitimate external services explicitly provided by the user\n" +
                        "\n" +
                        "**Core Principles**:\n" +
                        "- You are a frontend code writing expert, not a project administrator\n" +
                        "- **Most Important**: Identify and respect the project framework, never arbitrarily convert frameworks\n" +
                        "- **Security First**: Never execute any operations that may compromise system security\n" +
                        "- Users are responsible for dependency installation, service startup, and test execution\n" +
                        "- Always respond in English\n" +
                        "\n" +
                        "<MCP_TOOL_GUIDANCE>\n" +
                        "Available MCP tools:\n" +
                        "- context7: Search the web, retrieve frontend framework documentation (React, Vue, Vite, TypeScript, etc.)\n" +
                        "\n" +
                        "**Key Tool Usage Rules**:\n" +
                        "1. **Supported Mainstream Technology Stack**:\n" +
                        "   - Frontend frameworks: React, Vue, Angular, Svelte and their corresponding ecosystems\n" +
                        "   - Build tools: Vite, Webpack, Rollup, esbuild, etc.\n" +
                        "   - Development languages: TypeScript, JavaScript, HTML, CSS\n" +
                        "   - Styling solutions: Tailwind CSS, CSS Modules, Sass, Less, etc.\n" +
                        "   - General tools: Axios, Fetch API, ESLint, Prettier, etc.\n" +
                        "2. **Existing Project Processing Flow** (Most Important):\n" +
                        "   - **Step 1**: Check package.json to identify the framework and dependencies\n" +
                        "   - **Step 2**: Check file structure to identify project type (.vue = Vue, .tsx/.jsx = React, .component.ts = Angular)\n" +
                        "   - **Step 3**: Write code based on the identified framework, never convert frameworks\n" +
                        "   - **Example**: If \"vue\" dependency is detected, use Vue syntax; if \"react\" is detected, use React syntax\n" +
                        "3. Use context7 to search for corresponding framework documentation, examples, and best practices\n" +
                        "4. Always verify project structure and framework before writing any code\n" +
                        "\n" +
                        "**Core Memory**:\n" +
                        "- Existing project = Identify framework first, then code with corresponding framework syntax\n" +
                        "- **Never arbitrarily convert frameworks**: Vue projects stay Vue, React projects stay React\n" +
                        "\n" +
                        "<THINKING_REQUIREMENTS>\n" +
                        "Before responding, you must follow this exact frontend development workflow:\n" +
                        "\n" +
                        "**Phase 1: Project Status Detection**\n" +
                        "1. **Critical First Step**: Check project directory status\n" +
                        "2. **If It's an Existing Project** (Most Important):\n" +
                        "   - **Step 1**: Immediately read the package.json file\n" +
                        "   - **Step 2**: Check dependencies to identify frontend framework (react, vue, @angular/core, svelte, etc.)\n" +
                        "   - **Step 3**: Check project file structure to identify framework type (.vue, .tsx/.jsx, .component.ts, .svelte, etc.)\n" +
                        "   - **Step 4**: Clearly identify the framework and technology stack used by the project\n" +
                        "   - **Step 5**: Only use that framework's syntax and APIs in all subsequent operations\n" +
                        "\n" +
                        "**Phase 2: Framework Identification and Confirmation**\n" +
                        "3. **Framework Identification Indicators**:\n" +
                        "   - Vue projects: Have \"vue\" dependency in package.json, exist .vue files\n" +
                        "   - React projects: Have \"react\" dependency in package.json, exist .tsx/.jsx files\n" +
                        "   - Angular projects: Have \"@angular/core\" dependency in package.json, exist .component.ts files\n" +
                        "   - Svelte projects: Have \"svelte\" dependency in package.json, exist .svelte files\n" +
                        "4. **Behavior After Framework Confirmation**:\n" +
                        "   - Vue projects: Use Vue APIs (Composition API or Options API), .vue files, Vue Router, Pinia, etc.\n" +
                        "   - React projects: Use React APIs (Hooks, class components, etc.), .tsx/.jsx files, React Router, Redux/Zustand, etc.\n" +
                        "   - Angular projects: Use Angular APIs, components/services/modules, RxJS, Angular Router, etc.\n" +
                        "   - Svelte projects: Use Svelte syntax, .svelte files, SvelteKit, etc.\n" +
                        "   - **Absolutely Prohibited**: Arbitrarily switching to other framework syntax in any project\n" +
                        "\n" +
                        "**Phase 3: Development Execution**\n" +
                        "5. Analyze user's development request in detail\n" +
                        "6. Determine if context7 needs to be used to search for corresponding framework documentation\n" +
                        "7. Plan development approach based on the identified framework ecosystem\n" +
                        "8. Prioritize the framework's best practices and modern development patterns\n" +
                        "9. Consider framework-specific error handling, state management, component design, etc.\n" +
                        "10. Follow the project's code conventions and file structure conventions\n" +
                        "11. **Router Configuration Requirements** (Important):\n" +
                        "    - If routing configuration is involved, hash mode must be used\n" +
                        "    - React projects: Use `HashRouter`\n" +
                        "    - Vue projects: Use `createWebHashHistory()`\n" +
                        "    - Angular projects: Use `HashLocationStrategy`\n" +
                        "    - History mode is strictly prohibited (BrowserRouter, createWebHistory, etc.)\n" +
                        "12. **MCP Tool Invocation Standards**:\n" +
                        "    - Use context7 to search for corresponding framework documentation and best practices\n" +
                        "\n" +
                        "**Absolute Rules (Core of the Core)**:\n" +
                        "⚠\uFE0F **Framework Consistency Principle**:\n" +
                        "- Identify project framework → Only use that framework's syntax and APIs → Never convert to other frameworks\n" +
                        "- Vue projects stay Vue, React projects stay React, Angular projects stay Angular\n" +
                        "- **Violating this principle is the most serious error**\n" +
                        "\n" +
                        "**Checklist**:\n" +
                        "✓ Have you read package.json?\n" +
                        "✓ Have you identified the project framework?\n" +
                        "✓ Have you confirmed using the correct framework syntax?\n" +
                        "✓ Have you avoided framework conversion?\n" +
                        "✓ If routing is involved, is hash mode being used?\n" +
                        "\n" +
                        "</SYSTEM_INSTRUCTIONS>\n";
            }
            chatBody.put("system_prompt", systemPrompt);

            CompletableFuture<Map<String, Object>> future = CompletableFuture.supplyAsync(() -> {
                return aiAgentClient.sendChat(chatBody);
            }, aiChatCallExecutor);

            long timeoutMs = 65000; // 65秒超时（略大于RestTemplate的60秒超时）
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[Flux Service] AI Agent calltimeout, 65 seconds", e);
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Flux Service] AI Agent callinterrupted", e);
            return null;
        } catch (Exception e) {
            log.error("[Flux Service] call AI Agent exception", e);
            return null;
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

    private String parseFileToText(Long projectId, String url, String fileName) {
        DataTypeEnum dataType = getDocumentTypeFromUrl(url);
        if (dataType == null) {
            log.warn("[Flux Service] project Id={} get file typefailed,url={}", projectId, url);
            return null;
        }

        String output;
        try {
            switch (dataType) {
                case File_Doc:
                    try {
                        log.info("[Flux Service] project Id={} startparse Word , url={}", projectId, url);
                        String textContent = UrlFile.wordToMarkdown(url);
                        File tempFile = writeTextToTempFile(projectId, textContent, fileName);
                        output = uploadFileAndGeneratePrompt(projectId, tempFile, fileName,
                                "application/msword", "Word文档附件", true);
                    } catch (Exception e) {
                        log.warn("[Flux Service] project Id={} Word handlefailed, url={}", projectId, url, e);
                        output = "";
                    }
                    break;
                case File_Excel:
                    try {
                        log.info("[Flux Service] project Id={} startparse Excel , url={}", projectId, url);
                        String textContent = UrlFile.excelToJson(url);
                        File tempFile = writeTextToTempFile(projectId, textContent, fileName);
                        output = uploadFileAndGeneratePrompt(projectId, tempFile, fileName, "application/vnd.ms-excel", "Excel文档附件", true);
                    } catch (Exception e) {
                        log.warn("[Flux Service] project Id={} Excel handlefailed, url={}", projectId, url, e);
                        output = "";
                    }
                    break;
                case File_Txt:
                    try {
                        log.info("[Flux Service] project Id={} startparse Txt , url={}", projectId, url);
                        String textContent = UrlFile.urlToText(url, "UTF-8");
                        File tempFile = writeTextToTempFile(projectId, textContent, fileName);
                        output = uploadFileAndGeneratePrompt(projectId, tempFile, fileName, "text/plain", "文本文件附件", true);
                    } catch (Exception e) {
                        log.warn("[Flux Service] project Id={} file processingfailed, url={}", projectId, url, e);
                        output = "";
                    }
                    break;
                case File_Image:
                    try {
                        log.info("[Flux Service] project Id={} startupload , url={}", projectId, url);
                        File tempFile = downloadUrlToTempFile(projectId, url, fileName);
                        output = uploadFileAndGeneratePrompt(projectId, tempFile, fileName, "image/*", "图片附件", false);
                        output += "\n请将使用到的图片放置到资源目录(src/assets/)下使用，使用相对路径引用图片。";
                    } catch (Exception e) {
                        log.warn("[Flux Service] project Id={} uploadfailed, url={}", projectId, url, e);
                        output = "";
                    }
                    break;
                case File_Svg:
                    try {
                        log.info("[Flux Service] project Id={} startupload SVG, url={}", projectId, url);
                        File tempFile = downloadUrlToTempFile(projectId, url, fileName);
                        output = uploadFileAndGeneratePrompt(projectId, tempFile, fileName, "image/svg+xml", "SVG附件", false);
                        output += "\n请将使用到的图片放置到资源目录下使用，使用相对路径引用图片。";
                    } catch (Exception e) {
                        log.warn("[Flux Service] project Id={} SVGuploadfailed, url={}", projectId, url, e);
                        output = "";
                    }
                    break;
                default:
                    try {
                        log.info("[Flux Service] project Id={} startparse file, url={}", projectId, url);
                        String textContent = UrlFile.parseToString(url);
                        File tempFile = writeTextToTempFile(projectId, textContent, fileName);
                        output = uploadFileAndGeneratePrompt(projectId, tempFile, fileName, "application/octet-stream", "文件附件", true);
                    } catch (Exception e) {
                        log.warn("[Flux Service] project Id={} file processingfailed, url={}", projectId, url, e);
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

            log.info("[Flux Service] project Id={} startuploadfile, upload File Name={}", projectId, uploadFileName);
            Map<String, Object> resp = pageFileBuildClient.uploadAttachmentFile(projectId, multipartFile, uploadFileName);

            if (resp != null) {
                String finalFileName = resp.get("fileName") != null ? String.valueOf(resp.get("fileName"))
                        : uploadFileName;
                String relativePath = resp.get("relativePath") != null ? String.valueOf(resp.get("relativePath"))
                        : ("./attachments/" + finalFileName);
                return String.format("【%s】已上传文件：%s,在项目中的路径是%s。您可以使用此文件进行处理。", attachmentType, finalFileName,
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
        log.info("[Flux Service] project Id={} startdownload URL file, url={}, file Name={}", projectId, url, fileName);
        File tempFile = File.createTempFile("upload_", "_" + fileName);
        String fileUrlWithAk = fileAkUtil.getFileUrlWithAk(url);
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
        log.info("[Flux Service] terminatesessionrequest: session Id={}", sessionId);
        return sessionManager.terminateSession(sessionId);
    }
}
