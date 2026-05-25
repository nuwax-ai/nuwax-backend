package com.xspaceagi.modelproxy.application.service.impl;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.agent.core.adapter.application.ModelApplicationService;
import com.xspaceagi.agent.core.adapter.dto.config.ModelConfigDto;
import com.xspaceagi.agent.core.sdk.IModelRpcService;
import com.xspaceagi.agent.core.sdk.dto.ModelInfoDto;
import com.xspaceagi.modelproxy.infra.rpc.UserAccessKeyRpcService;
import com.xspaceagi.modelproxy.sdk.service.IModelApiProxyConfigService;
import com.xspaceagi.modelproxy.sdk.service.dto.BackendModelDto;
import com.xspaceagi.modelproxy.sdk.service.dto.FrontendModelDto;
import com.xspaceagi.pricing.sdk.dto.PriceEstimate;
import com.xspaceagi.pricing.sdk.rpc.IPricingRpcService;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import com.xspaceagi.system.application.dto.TenantConfigDto;
import com.xspaceagi.system.application.service.TenantConfigApplicationService;
import com.xspaceagi.system.sdk.common.TraceContext;
import com.xspaceagi.system.sdk.permission.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.server.IUserMetricRpcService;
import com.xspaceagi.system.sdk.service.dto.BizType;
import com.xspaceagi.system.sdk.service.dto.PeriodType;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * 模型API代理配置服务实现
 */
@Service
@Slf4j
public class ModelApiProxyConfigServiceImpl implements IModelApiProxyConfigService {

    private static final String BACKEND_MODEL_KEY_PREFIX = "backend_model:";
    @Resource
    private RedisUtil redisUtil;

    @Resource
    private UserAccessKeyRpcService userAccessKeyRpcService;

    @Resource
    private IUserMetricRpcService iUserMetricRpcService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Resource
    private IPricingRpcService iPricingRpcService;

    @Resource
    private IModelRpcService iModelRpcService;

    @Resource
    private ModelApplicationService modelApplicationService;

    @Resource
    private TenantConfigApplicationService tenantConfigApplicationService;

    @Value("${model-api-proxy.base-api-url:}")
    private String baseApiUrl;

    @Value("${model-api-proxy.enable-model-proxy:false}")
    private String enableModelProxy;

    @Override
    public BackendModelDto getBackendModelConfig(String userApiKey, Long id) {
        String cacheKey = BACKEND_MODEL_KEY_PREFIX + userApiKey + (id == null ? "-" : id);
        Object val = redisUtil.get(cacheKey);
        if (val != null) {
            try {
                BackendModelDto backendModelDto = JSON.parseObject(val.toString(), BackendModelDto.class);
                if (backendModelDto != null) {
                    return backendModelDto;
                }
            } catch (RuntimeException e) {
                redisUtil.expire(cacheKey, -1);
                throw e;
            }
        }
        UserAccessKeyDto userAccessKeyDto = userAccessKeyRpcService.queryAccessKey(userApiKey);
        if (userAccessKeyDto == null || (userAccessKeyDto.getTargetType() != UserAccessKeyDto.AKTargetType.AgentModel && userAccessKeyDto.getTargetType() != UserAccessKeyDto.AKTargetType.OpenApi)
                || userAccessKeyDto.getConfig() == null) {
            return null;
        }
        if (id != null) {
            List<Long> modelIds = userAccessKeyDto.getConfig().getModelIds();
            if (modelIds == null || !modelIds.contains(id)) {
                throw new BizException("4030", "API Key do not have permission to access this model.");
            }
            List<ModelInfoDto> userModels = iModelRpcService.getUserModels(userAccessKeyDto.getTenantId(), userAccessKeyDto.getUserId());
            if (userModels == null || userModels.stream().noneMatch(modelInfoDto -> modelInfoDto.getId().equals(id))) {
                throw new BizException("4030", "You do not have permission to access this model.");
            }
            ModelConfigDto modelInfoDto = TenantFunctions.callWithIgnoreCheck(() -> modelApplicationService.queryModelConfigById(id));
            List<ModelConfigDto.ApiInfo> apiInfoList = modelInfoDto.getApiInfoList();
            if (apiInfoList == null || apiInfoList.isEmpty()) {
                throw new BizException("5000", "Model API Key is invalid.");
            }
            TenantConfigDto tenantConfigDto = tenantConfigApplicationService.getTenantConfig(userAccessKeyDto.getTenantId());
            ModelConfigDto.ApiInfo apiInfo = apiInfoList.get(userAccessKeyDto.getUserId().intValue() % apiInfoList.size());
            userAccessKeyDto.getConfig().setModelId(modelInfoDto.getId());
            userAccessKeyDto.getConfig().setModelApiKey(apiInfo.getKey());
            userAccessKeyDto.getConfig().setModelName(modelInfoDto.getModel());
            userAccessKeyDto.getConfig().setProtocol(modelInfoDto.getApiProtocol().name());
            userAccessKeyDto.getConfig().setScope(modelInfoDto.getScope().name());
            userAccessKeyDto.getConfig().setEnabled(modelInfoDto.getEnabled() != null && modelInfoDto.getEnabled() == 1);
            userAccessKeyDto.getConfig().setUserName("user" + userAccessKeyDto.getUserId());
            userAccessKeyDto.getConfig().setConversationId(userAccessKeyDto.getUserId().toString());
            userAccessKeyDto.getConfig().setRequestId(UUID.randomUUID().toString().replace("-", ""));
            userAccessKeyDto.getConfig().setModelBaseUrl(apiInfo.getUrl());
            userAccessKeyDto.getConfig().setTraceContext(TraceContext.builder()
                    .nickName(userAccessKeyDto.getConfig().getUserName())
                    .tenantId(userAccessKeyDto.getTenantId())
                    .userId(userAccessKeyDto.getUserId())
                    .conversationId(userAccessKeyDto.getConfig().getConversationId())
                    .userName(userAccessKeyDto.getConfig().getUserName())
                    .billUserId(userAccessKeyDto.getUserId())
                    .enableSubscription(tenantConfigDto.getEnableSubscription() != null && tenantConfigDto.getEnableSubscription() == 1)
                    .traceId(userAccessKeyDto.getConfig().getRequestId())
                    .traceTargets(List.of(TraceContext.TraceTarget.builder()
                            .targetId(modelInfoDto.getId().toString())
                            .targetType(TraceContext.TraceTargetType.Model)
                            .description(modelInfoDto.getName())
                            .name(modelInfoDto.getModel())
                            .build()))
                    .build());
        }
        BackendModelDto backendModelDto = new BackendModelDto();
        backendModelDto.setModelId(userAccessKeyDto.getConfig().getModelId());
        backendModelDto.setBaseUrl(userAccessKeyDto.getConfig().getModelBaseUrl());
        backendModelDto.setApiKey(userAccessKeyDto.getConfig().getModelApiKey());
        backendModelDto.setModelName(userAccessKeyDto.getConfig().getModelName());
        backendModelDto.setProtocol(userAccessKeyDto.getConfig().getProtocol());
        backendModelDto.setScope(userAccessKeyDto.getConfig().getScope());
        backendModelDto.setEnabled(userAccessKeyDto.getConfig().getEnabled() == null || userAccessKeyDto.getConfig().getEnabled());
        backendModelDto.setTenantId(userAccessKeyDto.getTenantId());
        backendModelDto.setUserId(userAccessKeyDto.getUserId());
        backendModelDto.setUserName(userAccessKeyDto.getConfig().getUserName());
        backendModelDto.setConversationId(userAccessKeyDto.getConfig().getConversationId());
        backendModelDto.setRequestId(userAccessKeyDto.getConfig().getRequestId());
        backendModelDto.setTraceContext(userAccessKeyDto.getConfig().getTraceContext());
        checkTokenLimit(backendModelDto);
        TraceContext traceContext = userAccessKeyDto.getConfig().getTraceContext();
        if (traceContext != null && traceContext.isEnableSubscription()) {
            PriceEstimate priceEstimate = iPricingRpcService.estimatePrice(userAccessKeyDto.getTenantId(), traceContext.getBillUserId(), List.of(PriceEstimate.EstimateTarget.builder()
                    .targetType(TargetTypeEnum.MODEL)
                    .targetId(userAccessKeyDto.getConfig().getModelId().toString())
                    .build()));
            if (!priceEstimate.isPass()) {
                throw new BizException("CREDIT_NOT_ENOUGH_PRICE", priceEstimate.getMessage());
            }
        }
        // 缓存1分钟，1分钟内如果token超了无法实时体现
        redisUtil.set(cacheKey, JSON.toJSONString(backendModelDto), 60);
        return backendModelDto;
    }

    /**
     * 检查token限制，未来支持订阅后取消改实现
     *
     * @param backendModelDto 后端模型配置
     */
    private void checkTokenLimit(BackendModelDto backendModelDto) {
        // 用户数据权限
        UserDataPermissionDto userDataPermission = TenantFunctions.callWithIgnoreCheck(() -> userDataPermissionRpcService.getUserDataPermission(backendModelDto.getUserId()));
        BigDecimal tokenCount = iUserMetricRpcService.queryMetricCurrent(backendModelDto.getTenantId(), backendModelDto.getUserId(), BizType.TOKEN_USAGE.getCode(), PeriodType.DAY);
        if (userDataPermission.getTokenLimit() != null && userDataPermission.getTokenLimit().getLimitPerDay() != null && userDataPermission.getTokenLimit().getLimitPerDay() >= 0
                && tokenCount.compareTo(BigDecimal.valueOf(userDataPermission.getTokenLimit().getLimitPerDay())) >= 0) {
            log.warn("token limit exceeded, userId: {}, modelId: {}, tokenCount: {}, limitPerDay: {}", backendModelDto.getUserId(), backendModelDto.getModelId(), tokenCount, userDataPermission.getTokenLimit().getLimitPerDay());
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.modelProxyTokenLimitExceeded);
        }
    }

    @Override
    public FrontendModelDto generateUserFrontendModelConfig(Long tenantId, Long userId, Long agentId, BackendModelDto backendModel, String siteUrl) {
        if ("false".equalsIgnoreCase(enableModelProxy) || userId == null || userId == -1L) {
            TraceContext traceContext = backendModel.getTraceContext();
            if (traceContext == null || !traceContext.isEnableSubscription()) {
                FrontendModelDto frontendModelDto = new FrontendModelDto();
                frontendModelDto.setBaseUrl(backendModel.getBaseUrl());
                frontendModelDto.setApiKey(backendModel.getApiKey());
                return frontendModelDto;
            }
        }
        UserAccessKeyDto userAccessKeyDto = userAccessKeyRpcService.queryAgentModelAccessKey(userId, agentId, backendModel.getModelId());
        if (userAccessKeyDto == null) {
            userAccessKeyDto = userAccessKeyRpcService.newAccessKey(tenantId, userId, UserAccessKeyDto.AKTargetType.AgentModel, agentId.toString(),
                    UserAccessKeyDto.UserAccessKeyConfig.builder()
                            .modelId(backendModel.getModelId())
                            .modelApiKey(backendModel.getApiKey())
                            .modelBaseUrl(backendModel.getBaseUrl())
                            .modelName(backendModel.getModelName())
                            .protocol(backendModel.getProtocol())
                            .scope(backendModel.getScope())
                            .enabled(true)
                            .userName(backendModel.getUserName())
                            .conversationId(backendModel.getConversationId())
                            .requestId(backendModel.getRequestId())
                            .traceContext(backendModel.getTraceContext())
                            .build());
        } else {
            userAccessKeyRpcService.updateAccessKey(userAccessKeyDto.getId(), UserAccessKeyDto.UserAccessKeyConfig.builder()
                    .modelId(backendModel.getModelId())
                    .modelApiKey(backendModel.getApiKey())
                    .modelBaseUrl(backendModel.getBaseUrl())
                    .modelName(backendModel.getModelName())
                    .protocol(backendModel.getProtocol())
                    .scope(backendModel.getScope())
                    .enabled(true)
                    .userName(backendModel.getUserName())
                    .conversationId(backendModel.getConversationId())
                    .requestId(backendModel.getRequestId())
                    .traceContext(backendModel.getTraceContext())
                    .build());
            redisUtil.expire(BACKEND_MODEL_KEY_PREFIX + userAccessKeyDto.getAccessKey(), -1);
        }
        if (StringUtils.isNotBlank(baseApiUrl)) {
            siteUrl = baseApiUrl;
        }
        if (siteUrl.endsWith("/")) {
            siteUrl = siteUrl.substring(0, siteUrl.length() - 1);
        }
        FrontendModelDto frontendModelDto = new FrontendModelDto();
        frontendModelDto.setBaseUrl(siteUrl + "/api/proxy/model");
        frontendModelDto.setApiKey(userAccessKeyDto.getAccessKey());
        return frontendModelDto;
    }
}
