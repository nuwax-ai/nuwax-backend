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
import com.xspaceagi.system.spec.enums.PermissionSubjectTypeEnum;
import com.xspaceagi.system.spec.enums.YesOrNoEnum;
import com.xspaceagi.system.spec.exception.BizException;
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
        }
    }

    @Override
    public void updateAccessControlStatus(Long id, Integer status) {
        // 先查询原始状态，用于判断是否从「受限」变为「不受限」
        ModelConfig originConfig = modelRepository.getById(id);
        if (originConfig == null) {
            throw new BizException("数据不存在,id=" + id);
        }

        int oldStatus = originConfig.getAccessControl() != null ? originConfig.getAccessControl() : 0;
        int newStatus = status == null ? 0 : status;

        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setAccessControl(newStatus);
        modelRepository.update(modelConfig, new LambdaQueryWrapper<ModelConfig>().eq(ModelConfig::getId, id));

        // 如果从受限(1)切换为不受限(0)，需要删除原有主体访问权限绑定并清除缓存
        if (oldStatus == 1 && newStatus == 0) {
            UserContext userContext = null;
            if (RequestContext.get() != null && RequestContext.get().getUser() instanceof UserContext) {
                userContext = (UserContext) RequestContext.get().getUser();
            }
            // 不设置 roleIds 和 groupIds，内部会按空集合处理，表示清空所有绑定
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
        //默认的模型不允许删除
        if (modelId.equals(DEFAULT_CHAT_MODEL_ID) || modelId.equals(DEFAULT_EMBED_MODEL_ID)) {
            throw new BizException("默认模型不允许删除");
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
            RequestContext.addTenantIgnoreEntity(ModelConfig.class);
            model.setScope(ModelConfig.ModelScopeEnum.Global);
            modelList = queryModelConfigList(queryWrapper);
            RequestContext.removeTenantIgnoreEntity(ModelConfig.class);

            model.setScope(ModelConfig.ModelScopeEnum.Tenant);
            modelList.addAll(queryModelConfigList(queryWrapper));

            //查询空间下的模型，放置在列表的最前面
            if (modelQueryDto.getSpaceId() != null) {
                model.setSpaceId(modelQueryDto.getSpaceId());
                model.setScope(ModelConfig.ModelScopeEnum.Space);
                modelList.addAll(0, queryModelConfigList(queryWrapper));
            }
        } else {
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
        //modelDtos中的creatorIdList
        completeCreator(modelDtos);
        return modelDtos;
    }

    private void completeCreator(List<ModelConfigDto> modelDtos) {
        List<Long> creatorIdList = modelDtos.stream().map(ModelConfigDto::getCreatorId).collect(Collectors.toList());
        List<UserDto> userDtos = userApplicationService.queryUserListByIds(creatorIdList);
        //userDtos转map
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
            throw new BizException("模型不存在");
        }
        // 模型使用范围为租户级别的时，判断是否为当前租户
        if (modelDto.getScope() == ModelConfig.ModelScopeEnum.Tenant
                && !modelDto.getTenantId().equals(RequestContext.get().getTenantId())) {
            throw new BizException("没有权限");
        }
        if (modelDto.getScope() == ModelConfig.ModelScopeEnum.Space) {
            spacePermissionService.checkSpaceUserPermission(modelDto.getSpaceId());
        }
    }

    @Override
    public void checkModelManagePermission(Long modelId) {
        ModelConfigDto modelDto = queryModelConfigById(modelId);
        if (modelDto == null) {
            throw new BizException("模型不存在");
        }
        if (modelDto.getScope() == ModelConfig.ModelScopeEnum.Space) {
            spacePermissionService.checkSpaceUserPermission(modelDto.getSpaceId());
        }

        // 全局模型或租户模型，判断是否为当前租户管理员
        if (modelDto.getScope() == ModelConfig.ModelScopeEnum.Global || modelDto.getScope() == ModelConfig.ModelScopeEnum.Tenant) {
            UserDto userDto = (UserDto) RequestContext.get().getUser();
            if (userDto == null || userDto.getRole() != User.Role.Admin || !Objects.equals(userDto.getTenantId(), modelDto.getTenantId())) {
                throw new BizException("没有权限");
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
            sysPrompt = "你是一个专业的JSON转换助手，能够依据用户提供的schema进行精准转换。";
        }
        if (retry > 0) {
            sysPrompt = sysPrompt + "\n用户提供的数据可能是一个格式有问题的JSON数据，请帮纠正成正确的JSON数据";
        }
        //读取type的泛型
        String jsonSchema = new BeanOutputConverter<>(type).getJsonSchema();
        String prompt = Prompts.JSON_FORMAT_PROMPT.replace("${schema}", jsonSchema);
        if (userPrompt != null) {
            prompt = userPrompt + prompt;
        }
        //只重试一次
        if (retry == 3) {
            log.warn("模型返回的JSON格式不正确，请检查提示词\n原始内容 {}", prompt);
            throw new BizException("模型返回的JSON格式不正确，请检查提示词");
        }
        ChatClient.StreamResponseSpec stream = modelClientFactory.createChatClient(modelConfig)
                .prompt(new Prompt(new SystemMessage(sysPrompt), new UserMessage(prompt))).stream();
        StringBuilder responseStr = new StringBuilder();
        Mono.create(sink -> stream.chatResponse().onErrorResume(throwable -> {
                            log.warn("call error", throwable);
                            if (throwable instanceof TimeoutException) {
                                return Mono.error(new TimeoutException("大模型执行等待超时"));
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
            log.warn("模型返回的JSON格式不正确，请检查提示词\n原始内容 {}\n返回内容：{}", prompt, responseStr);
            throw new BizException("模型返回的JSON格式不正确，请检查提示词");
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
        // 如果指定了 向量化模型id，则固定使用对应模型向量化
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
            throw new BizException("模型不存在");
        }
        if (modelDto.getScope() == ModelConfig.ModelScopeEnum.Space) {
            spacePermissionService.checkSpaceUserPermission(modelDto.getSpaceId(), userId);
        }
        if (modelDto.getScope() == ModelConfig.ModelScopeEnum.Tenant) {
            UserDto userDto = userApplicationService.queryById(userId);
            if (userDto == null || !Objects.equals(userDto.getTenantId(), modelDto.getTenantId())) {
                throw new BizException("没有权限");
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
                redisUtil.set(hashKey, "1", 86400);//一次检测结果缓存1天
            }
            return codeCheckResultDto;
        } catch (Exception e) {
            log.warn("代码安全检查失败", e);
            return CodeCheckResultDto.builder().pass(false).reason("代码安全检查失败，请重试").build();
        }
    }

    @Override
    public String testModelConnectivity(ModelConfigDto modelConfig, String testPrompt) {
        if (modelConfig.getApiInfoList() == null || modelConfig.getApiInfoList().isEmpty()) {
            return "API配置不能为空";
        }
        if (StringUtils.isBlank(testPrompt)) {
            testPrompt = "Hi";
        }
        try {
            // 向量模型，调用向量化接口测试
            if (modelConfig.getType() == ModelTypeEnum.Embeddings) {
                EmbeddingModel embeddingModel = modelClientFactory.createEmbeddingModel(modelConfig);
                List<float[]> embeddings = embeddingModel.embed(List.of(testPrompt));
                log.info("向量模型连通测试name={},返回向量维度：{}", modelConfig.getName(),
                        !embeddings.isEmpty() ? embeddings.get(0).length : 0);
                if (embeddings.isEmpty() || embeddings.get(0) == null || embeddings.get(0).length == 0) {
                    return "无响应";
                }
                return null;
            } else {
                // 非向量模型，使用天接口测试
                ChatClient chatClient = modelClientFactory.createChatClient(modelConfig);
                String response = chatClient.prompt()
                        .user(testPrompt)
                        .call().content();
                log.info("模型连通测试name={},返回：{}", modelConfig.getName(), response);
                if (response == null || response.isBlank()) {
                    return "无响应";
                }
                return null;
            }
        } catch (BizException e) {
            log.warn("模型连通业务校验失败: {}", e.getMessage());
            return e.getMessage() == null ? "无响应" : e.getMessage();
        } catch (Exception e) {
            log.error("模型连通测试异常", e);
            return e.getMessage() == null ? "无响应" : e.getMessage();
        }
    }
}
