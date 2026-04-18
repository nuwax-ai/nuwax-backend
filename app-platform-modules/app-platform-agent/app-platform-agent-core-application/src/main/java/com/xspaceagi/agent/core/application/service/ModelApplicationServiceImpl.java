package com.xspaceagi.agent.core.application.service;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.CodeCheckResultDto;
import com.xspaceagi.agent.core.adapter.dto.CreatorDto;
import com.xspaceagi.agent.core.adapter.dto.ModelQueryDto;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.adapter.repository.ModelConfigRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.ModelConfig;
import com.xspaceagi.agent.core.infra.component.model.ModelClientFactory;
import com.xspaceagi.agent.core.infra.component.model.ModelInvoker;
import com.xspaceagi.agent.core.spec.constant.Prompts;
import com.xspaceagi.agent.core.spec.enums.ModelTypeEnum;
import com.xspaceagi.agent.core.spec.enums.UsageScenarioEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.dto.permission.BindRestrictionTargetsDto;
import com.xspaceagi.system.application.service.SysSubjectPermissionApplicationService;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.User;
import com.xspaceagi.system.sdk.permission.SpacePermissionService;
import com.xspaceagi.system.sdk.server.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.common.UserContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.enums.PermissionSubjectTypeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.MD5;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.BeanUtils;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ModelApplicationServiceImpl implements ModelApplicationService {

    private static final Long DEFAULT_CHAT_MODEL_ID = 1L;

    private static final Long DEFAULT_EMBED_MODEL_ID = 2L;

    @Resource
    private ModelConfigRepository modelRepository;

    @Resource
    private SpacePermissionService spacePermissionService;

    @Resource
    private UserApplicationService userApplicationService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Resource
    private SysSubjectPermissionApplicationService sysSubjectPermissionApplicationService;

    @Resource
    private ModelClientFactory modelClientFactory;

    @Resource
    private RedisUtil redisUtil;

    @Override
    public void addOrUpdate(ModelConfigDto modelDto) {
        modelDto.setTenantId(RequestContext.get().getTenantId());
        ModelConfig model = new ModelConfig();
        BeanUtils.copyProperties(modelDto, model);
        if (modelDto.getApiInfoList() != null && !modelDto.getApiInfoList().isEmpty()) {
            model.setApiInfo(JSONObject.toJSONString(modelDto.getApiInfoList()));
        }
        if (modelDto.getUsageScenarios() != null) {
            model.setUsageScenario(JSONObject.toJSONString(modelDto.getUsageScenarios()));
        }
        ModelConfigDto modelConfigDto = null;
        if (modelDto.getId() != null) {
            modelConfigDto = TenantFunctions.callWithIgnoreCheck(() -> queryModelConfigById(modelDto.getId()));
        }
        if (modelDto.getId() != null && modelConfigDto != null) {
            modelRepository.updateById(model);
        } else {
            if (model.getSpaceId() == null && model.getScope() == null) {
                model.setScope(ModelConfig.ModelScopeEnum.Tenant);
            } else if (model.getScope() != null && model.getSpaceId() != null && model.getSpaceId() != -1) {
                model.setScope(ModelConfig.ModelScopeEnum.Space);
            }
            modelRepository.save(model);
            modelDto.setId(model.getId());
        }
    }

    @Override
    public void updateAccessControlStatus(Long id, Integer status) {
        // Query original status first, used to determine if changing from "restricted" to "unrestricted"
        ModelConfig originConfig = modelRepository.getById(id);
        if (originConfig == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentDataNotFoundWithId, id);
        }

        int oldStatus = originConfig.getAccessControl() != null ? originConfig.getAccessControl() : 0;
        int newStatus = status == null ? 0 : status;

        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setAccessControl(newStatus);
        modelRepository.update(modelConfig, new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getId, id));

        // If switching from restricted(1) to unrestricted(0), need to delete original subject access permission binding and clear cache
        if (oldStatus == 1 && newStatus == 0) {
            UserContext userContext = null;
            if (RequestContext.get() != null && RequestContext.get().getUser() instanceof UserContext) {
                userContext = (UserContext) RequestContext.get().getUser();
            }
            // Do not set roleIds and groupIds, internally will handle as empty set, representing clearing all bindings
            sysSubjectPermissionApplicationService.bindRestrictionTargets(
                    PermissionSubjectTypeEnum.MODEL,
                    id,
                    new BindRestrictionTargetsDto(),
                    userContext
            );
        }
    }

    @Override
    public void delete(Long modelId) {
        // Default model deletion not allowed
        if (modelId.equals(DEFAULT_CHAT_MODEL_ID) || modelId.equals(DEFAULT_EMBED_MODEL_ID)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentDefaultModelDeleteForbidden);
        }
        modelRepository.removeById(modelId);
    }

    @Override
    public List<ModelConfigDto> queryModelConfigList(ModelQueryDto modelQueryDto) {
        LambdaQueryWrapper<ModelConfig> queryWrapper = new LambdaQueryWrapper<>();
        ModelConfig model = new ModelConfig();
        model.setApiProtocol(modelQueryDto.getApiProtocol());
        model.setScope(modelQueryDto.getScope());
        if (modelQueryDto.getEnabled() != null) {
            model.setEnabled(modelQueryDto.getEnabled());
        }
        queryWrapper.setEntity(model);
        if (modelQueryDto.getModelType() == ModelTypeEnum.Chat) {
            queryWrapper.in(ModelConfig::getType, ModelTypeEnum.Chat, ModelTypeEnum.Multi);
        } else {
            model.setType(modelQueryDto.getModelType());
        }
        List<ModelConfigDto> modelList;
        if (modelQueryDto.getScope() == null) {
            model.setScope(ModelConfig.ModelScopeEnum.Tenant);
            modelList = queryModelConfigList(queryWrapper);

            // Query models under space, place them at the front of the list
            if (modelQueryDto.getSpaceId() != null) {
                model.setSpaceId(modelQueryDto.getSpaceId());
                model.setScope(ModelConfig.ModelScopeEnum.Space);
                modelList.addAll(0, queryModelConfigList(queryWrapper));
            }
        } else {
            model.setSpaceId(modelQueryDto.getSpaceId());
            modelList = queryModelConfigList(queryWrapper);
        }
        if (RequestContext.get() != null && RequestContext.get().getUserId() != null) {
            UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(RequestContext.get().getUserId());
            modelList.removeIf(modelConfigDto -> {
                if (modelConfigDto.getScope() == ModelConfig.ModelScopeEnum.Space || modelConfigDto.getAccessControl() == null || !modelConfigDto.getAccessControl().equals(YesOrNoEnum.Y.getKey())) {
                    return false;
                }
                return userDataPermission.getModelIds() == null || !userDataPermission.getModelIds().contains(modelConfigDto.getId());
            });
        }
        return modelList;
    }

    @Override
    public List<ModelConfigDto> queryTenantModelConfigList(Integer accessControlStatus) {
        return queryModelConfigList(new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getScope, ModelConfig.ModelScopeEnum.Tenant).eq(accessControlStatus != null, ModelConfig::getAccessControl, accessControlStatus).orderByDesc(ModelConfig::getId));
    }

    @Override
    public List<ModelConfigDto> queryModelConfigLisBySpaceId(Long spaceId) {
        LambdaQueryWrapper<ModelConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ModelConfig::getSpaceId, spaceId);
        return queryModelConfigList(queryWrapper);
    }

    @Override
    public ModelConfigDto queryModelConfigById(Long modelId) {
        ModelConfig model = modelRepository.getById(modelId);
        if (model == null) {
            return null;
        }
        ModelConfigDto modelDto = new ModelConfigDto();
        BeanUtils.copyProperties(model, modelDto);
        if (model.getUsageScenario() != null && JSON.isValidArray(model.getUsageScenario())) {
            modelDto.setUsageScenarios(JSON.parseArray(model.getUsageScenario(), UsageScenarioEnum.class));
        } else {
            modelDto.setUsageScenarios(List.of(UsageScenarioEnum.Workflow, UsageScenarioEnum.TaskAgent, UsageScenarioEnum.PageApp, UsageScenarioEnum.ChatBot, UsageScenarioEnum.OpenApi));
        }
        modelDto.setApiInfoList(convert(model.getApiInfo()));
        modelDto.setNatInfoList(convertNatInfo(model.getNatInfo()));
        completeCreator(List.of(modelDto));
        return modelDto;
    }

    @Override
    public ModelConfigDto queryDefaultModelConfig() {
        return queryDefaultModelConfig(RequestContext.get() != null ? RequestContext.get().getTenantId() : null);
    }

    @Override
    public ModelConfigDto queryDefaultModelConfig(Long tenantId) {
        Long modelId = DEFAULT_CHAT_MODEL_ID;
        if (tenantId != null) {
            TenantConfigDto tenantConfigDto = tenantConfigApplicationService.getTenantConfig(tenantId);
            if (tenantConfigDto != null && tenantConfigDto.getDefaultChatModelId() != null && tenantConfigDto.getDefaultChatModelId() > 0) {
                modelId = tenantConfigDto.getDefaultChatModelId();
            }
        }
        return queryModelConfigById(modelId);
    }

    private List<ModelConfigDto.ApiInfo> convert(String apiInfoStr) {
        if (apiInfoStr == null) {
            return new ArrayList<>();
        }
        List<ModelConfigDto.ApiInfo> apiInfoList = new ArrayList<>();
        JSONArray array = JSON.parseArray(apiInfoStr);
        for (int i = 0; i < array.size(); i++) {
            JSONObject obj = array.getJSONObject(i);
            ModelConfigDto.ApiInfo apiInfo = new ModelConfigDto.ApiInfo();
            apiInfo.setUrl(obj.getString("url"));
            apiInfo.setKey(obj.getString("key"));
            apiInfo.setWeight(obj.getInteger("weight"));
            apiInfoList.add(apiInfo);
        }
        return apiInfoList;
    }

    private List<ModelConfigDto.NatInfo> convertNatInfo(String natInfoStr) {
        if (natInfoStr == null) {
            return new ArrayList<>();
        }
        List<ModelConfigDto.NatInfo> natInfoList = new ArrayList<>();
        JSONArray array = JSON.parseArray(natInfoStr);
        for (int i = 0; i < array.size(); i++) {
            JSONObject obj = array.getJSONObject(i);
            ModelConfigDto.NatInfo natInfo = new ModelConfigDto.NatInfo();
            natInfo.setModelApiUrl(obj.getString("url"));
            natInfo.setNatConfigId(obj.getString("natConfigId"));
            natInfo.setNatHost(obj.getString("natHost"));
            natInfo.setNatPort(obj.getInteger("natPort"));
            natInfoList.add(natInfo);
        }
        return natInfoList;
    }

    private List<ModelConfigDto> queryModelConfigList(LambdaQueryWrapper<ModelConfig> queryWrapper) {
        List<ModelConfigDto> modelDtos = new ArrayList<>();
        modelRepository.list(queryWrapper).forEach(model1 -> {
            ModelConfigDto modelDto = new ModelConfigDto();
            BeanUtils.copyProperties(model1, modelDto);
            modelDto.setApiInfoList(null);
            if (model1.getUsageScenario() != null && JSON.isValidArray(model1.getUsageScenario())) {
                modelDto.setUsageScenarios(JSON.parseArray(model1.getUsageScenario(), UsageScenarioEnum.class));
            } else {
                modelDto.setUsageScenarios(List.of(UsageScenarioEnum.Workflow, UsageScenarioEnum.TaskAgent, UsageScenarioEnum.PageApp, UsageScenarioEnum.ChatBot, UsageScenarioEnum.OpenApi));
            }
            modelDtos.add(modelDto);
        });
        // creatorIdList in modelDtos
        completeCreator(modelDtos);
        return modelDtos;
    }

    private void completeCreator(List<ModelConfigDto> modelDtos) {
        List<Long> creatorIdList = modelDtos.stream().map(ModelConfigDto::getCreatorId).collect(Collectors.toList());
        List<UserDto> userDtos = userApplicationService.queryUserListByIds(creatorIdList);
        // userDtos converted to map
        Map<Long, UserDto> userMap = userDtos.stream().collect(Collectors.toMap(UserDto::getId, userDto -> userDto));
        modelDtos.forEach(modelDto -> {
            UserDto userDto = userMap.get(modelDto.getCreatorId());
            if (userDto != null) {
                CreatorDto creatorDto = CreatorDto.builder()
                        .userId(userDto.getId())
                        .avatar(userDto.getAvatar())
                        .nickName(userDto.getNickName())
                        .userName(userDto.getUserName())
                        .build();
                modelDto.setCreator(creatorDto);
            } else {
                modelDto.setCreator(CreatorDto.builder().userId(-1L).nickName("--").build());
            }
        });
    }

    @Override
    public void checkModelUsePermission(Long modelId) {
        ModelConfigDto modelDto = queryModelConfigById(modelId);
        if (modelDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentModelNotFound);
        }
        // When model usage scope is tenant level, determine if it is current tenant
        if (modelDto.getScope() == ModelConfig.ModelScopeEnum.Tenant
                && !modelDto.getTenantId().equals(RequestContext.get().getTenantId())) {
            throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
        }
        if (modelDto.getScope() == ModelConfig.ModelScopeEnum.Space) {
            spacePermissionService.checkSpaceUserPermission(modelDto.getSpaceId());
        }
    }

    @Override
    public void checkModelManagePermission(Long modelId) {
        ModelConfigDto modelDto = queryModelConfigById(modelId);
        if (modelDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentModelNotFound);
        }
        if (modelDto.getScope() == ModelConfig.ModelScopeEnum.Space) {
            spacePermissionService.checkSpaceUserPermission(modelDto.getSpaceId());
        }

        // Global model or tenant model, determine if it is current tenant admin
        if (modelDto.getScope() == ModelConfig.ModelScopeEnum.Global || modelDto.getScope() == ModelConfig.ModelScopeEnum.Tenant) {
            UserDto userDto = (UserDto) RequestContext.get().getUser();
            if (userDto == null || userDto.getRole() != User.Role.Admin || !Objects.equals(userDto.getTenantId(), modelDto.getTenantId())) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
        }
    }

    public <T> T call(String sysPrompt, String userPrompt, ParameterizedTypeReference<T> type) {
        ModelConfigDto modelConfig = queryDefaultModelConfig();
        return call(modelConfig, sysPrompt, userPrompt, type, 0);
    }

    @Override
    public <T> T call(Long modelId, String sysPrompt, String userPrompt, ParameterizedTypeReference<T> type) {
        ModelConfigDto modelConfig = queryModelConfigById(modelId);
        return call(modelConfig, sysPrompt, userPrompt, type, 0);
    }

    private <T> T call(ModelConfigDto modelConfig, String sysPrompt, String userPrompt, ParameterizedTypeReference<T> type, int retry) {
        if (sysPrompt == null) {
            sysPrompt = "You are a professional JSON conversion assistant, capable of precise conversion based on the schema provided by users.";
        }
        if (retry > 0) {
            sysPrompt = sysPrompt + "\nThe data provided by users may be a JSON data with formatting issues, please help correct it into valid JSON data";
        }
        // Read generic type of type
        String jsonSchema = new BeanOutputConverter<>(type).getJsonSchema();
        String prompt = Prompts.JSON_FORMAT_PROMPT.replace("${schema}", jsonSchema);
        if (userPrompt != null) {
            prompt = userPrompt + prompt;
        }
        // Only retry once
        if (retry == 3) {
            log.warn("Model returned JSON format is incorrect, please check prompt\nOriginal content {}", prompt);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentModelJsonInvalid);
        }
        ChatClient.StreamResponseSpec stream = modelClientFactory.createChatClient(modelConfig)
                .prompt(new Prompt(new SystemMessage(sysPrompt), new UserMessage(prompt))).stream();
        StringBuilder responseStr = new StringBuilder();
        Mono.create(sink -> stream.chatResponse().onErrorResume(throwable -> {
                            log.warn("call error", throwable);
                            if (throwable instanceof TimeoutException) {
                                return Mono.error(new TimeoutException("Large model execution timeout"));
                            }
                            return Mono.error(throwable);
                        })
                        .doOnComplete(sink::success).doOnError(sink::error)
                        .subscribe(chatResponse -> {
                            Generation result = chatResponse.getResult();
                            if (result == null || result.getOutput() == null) {
                                return;
                            }
                            AssistantMessage assistantMessage = result.getOutput();
                            if (assistantMessage != null && assistantMessage.getText() != null) {
                                responseStr.append(assistantMessage.getText());
                            }
                            if (assistantMessage != null && assistantMessage.getMetadata() != null) {
                                Object finishReason = assistantMessage.getMetadata().get("finishReason");
                                if (finishReason != null && finishReason.toString().equals("STOP")) {
                                    sink.success();
                                }
                            }
                        }))
                .block();
        String jsonText = ModelInvoker.getJSONText(responseStr.toString());
        if (!JSON.isValid(jsonText)) {
            return call(modelConfig, sysPrompt, jsonText, type, retry + 1);
        }
        Object object = JSON.parseObject(jsonText, type.getType());
        if (object == null) {
            log.warn("Model returned JSON format is incorrect, please check prompt\nOriginal content {}\nReturned content：{}", prompt, responseStr);
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentModelJsonInvalid);
        }
        return (T) object;
    }

    public <T> T call(String userPrompt, ParameterizedTypeReference<T> type) {
        ModelConfigDto modelConfig = queryDefaultModelConfig();
        return call(modelConfig, null, userPrompt, type, 0);
    }

    public String call(String userPrompt) {
        ModelConfigDto modelConfig = queryDefaultModelConfig();
        return modelClientFactory.createChatClient(modelConfig).prompt()
                .user(userPrompt)
                .call().content();
    }

    public List<float[]> embeddings(List<String> texts, Long modelId) {
        // If vectorization model ID is specified, use the corresponding model for vectorization
        ModelConfigDto modelConfig = null;
        if (modelId != null) {
            modelConfig = queryModelConfigById(modelId);
        } else {
            modelConfig = getDefaultEmbedModel();
        }
        EmbeddingModel embeddingModel = modelClientFactory.createEmbeddingModel(modelConfig);
        return embeddingModel.embed(texts);
    }

    @Override
    public ModelConfigDto getDefaultEmbedModel() {
        Long modelId = DEFAULT_EMBED_MODEL_ID;
        Long tenantId = RequestContext.get() != null ? RequestContext.get().getTenantId() : null;
        if (tenantId != null) {
            TenantConfigDto tenantConfigDto = tenantConfigApplicationService.getTenantConfig(tenantId);
            if (tenantConfigDto != null && tenantConfigDto.getDefaultEmbedModelId() != null && tenantConfigDto.getDefaultEmbedModelId() > 0) {
                modelId = tenantConfigDto.getDefaultEmbedModelId();
            }
        }
        return queryModelConfigById(modelId);
    }

    @Override
    public void checkUserModelPermission(Long userId, Long modelId) {
        ModelConfigDto modelDto = queryModelConfigById(modelId);
        if (modelDto == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentModelNotFound);
        }
        if (modelDto.getScope() == ModelConfig.ModelScopeEnum.Space) {
            spacePermissionService.checkSpaceUserPermission(modelDto.getSpaceId(), userId);
        }
        if (modelDto.getScope() == ModelConfig.ModelScopeEnum.Tenant) {
            UserDto userDto = userApplicationService.queryById(userId);
            if (userDto == null || !Objects.equals(userDto.getTenantId(), modelDto.getTenantId())) {
                throw BizException.of(ErrorCodeEnum.PERMISSION_DENIED, BizExceptionCodeEnum.permissionDenied);
            }
        }
    }

    @Override
    public CodeCheckResultDto codeSaleCheck(String code) {
        TenantConfigDto tenantConfigDto = (TenantConfigDto) RequestContext.get().getTenantConfig();
        if (tenantConfigDto.getOpenCodeSafeCheck() == null || tenantConfigDto.getOpenCodeSafeCheck() == 0) {
            return CodeCheckResultDto.builder().pass(true).build();
        }
        UserDto userDto = (UserDto) RequestContext.get().getUser();
        if (CollectionUtils.isNotEmpty(tenantConfigDto.getUserWhiteList()) && tenantConfigDto.getUserWhiteList().contains(userDto.getUserName()) || StringUtils.isBlank(tenantConfigDto.getCodeSafeCheckPrompt())) {
            return CodeCheckResultDto.builder().pass(true).build();
        }
        String hashKey = "code:" + MD5.MD5Encode(code);
        if (redisUtil.get(hashKey) != null) {
            return CodeCheckResultDto.builder().pass(true).build();
        }
        try {
            CodeCheckResultDto codeCheckResultDto = call(queryDefaultModelConfig(), tenantConfigDto.getCodeSafeCheckPrompt(), code + "\n请检查上面的代码，并按照下面定义的格式返回", new ParameterizedTypeReference<CodeCheckResultDto>() {
            }, 0);
            if (codeCheckResultDto.getPass() != null && codeCheckResultDto.getPass()) {
                redisUtil.set(hashKey, "1", 86400);// Cache check result for 1 day
            }
            return codeCheckResultDto;
        } catch (Exception e) {
            log.warn("Code security check failed", e);
            return CodeCheckResultDto.builder().pass(false).reason("Code security check failed, please retry").build();
        }
    }

    @Override
    public String testModelConnectivity(ModelConfigDto modelConfig, String testPrompt) {
        if (modelConfig.getApiInfoList() == null || modelConfig.getApiInfoList().isEmpty()) {
            return "API configuration cannot be empty";
        }
        if (StringUtils.isBlank(testPrompt)) {
            testPrompt = "Hi";
        }
        try {
            // Vector model, call vectorization interface for testing
            if (modelConfig.getType() == ModelTypeEnum.Embeddings) {
                EmbeddingModel embeddingModel = modelClientFactory.createEmbeddingModel(modelConfig);
                List<float[]> embeddings = embeddingModel.embed(List.of(testPrompt));
                log.info("Vector model connectivity test name={}, return vector dimension: {}", modelConfig.getName(),
                        !embeddings.isEmpty() ? embeddings.get(0).length : 0);
                if (embeddings.isEmpty() || embeddings.get(0) == null || embeddings.get(0).length == 0) {
                    return "No response";
                }
                return null;
            } else {
                // Non-vector model, use chat interface for testing
                ChatClient chatClient = modelClientFactory.createChatClient(modelConfig);
                String response = chatClient.prompt()
                        .user(testPrompt)
                        .call().content();
                log.info("Model connectivity test name={}, return: {}", modelConfig.getName(), response);
                if (response == null || response.isBlank()) {
                    return "No response";
                }
                return null;
            }
        } catch (BizException e) {
            log.warn("Model connectivity business validation failed: {}", e.getMessage());
            return e.getMessage() == null ? "No response" : e.getMessage();
        } catch (Exception e) {
            log.error("Model connectivity test exception", e);
            return e.getMessage() == null ? "No response" : e.getMessage();
        }
    }
}
