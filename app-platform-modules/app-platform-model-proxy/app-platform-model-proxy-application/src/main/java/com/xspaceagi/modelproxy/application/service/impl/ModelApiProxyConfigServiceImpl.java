package com.xspaceagi.modelproxy.application.service.impl;

import com.alibaba.fastjson2.JSON;
import com.xspaceagi.modelproxy.infra.rpc.UserAccessKeyRpcService;
import com.xspaceagi.modelproxy.sdk.service.IModelApiProxyConfigService;
import com.xspaceagi.modelproxy.sdk.service.dto.BackendModelDto;
import com.xspaceagi.modelproxy.sdk.service.dto.FrontendModelDto;
import com.xspaceagi.system.sdk.server.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.server.IUserMetricRpcService;
import com.xspaceagi.system.sdk.service.dto.BizType;
import com.xspaceagi.system.sdk.service.dto.PeriodType;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

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

    @Value("${model-api-proxy.base-api-url:}")
    private String baseApiUrl;

    @Value("${model-api-proxy.enable-model-proxy:false}")
    private String enableModelProxy;

    @Override
    public BackendModelDto getBackendModelConfig(String userApiKey) {
        Object val = redisUtil.get(BACKEND_MODEL_KEY_PREFIX + userApiKey);
        if (val != null) {
            try {
                BackendModelDto backendModelDto = JSON.parseObject(val.toString(), BackendModelDto.class);
                if (backendModelDto != null) {
                    return backendModelDto;
                }
            } catch (RuntimeException e) {
                redisUtil.expire(BACKEND_MODEL_KEY_PREFIX + userApiKey, -1);
                throw e;
            }
        }
        UserAccessKeyDto userAccessKeyDto = userAccessKeyRpcService.queryAccessKey(userApiKey);
        if (userAccessKeyDto == null || userAccessKeyDto.getTargetType() != UserAccessKeyDto.AKTargetType.AgentModel || userAccessKeyDto.getConfig() == null) {
            return null;
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
        checkTokenLimit(backendModelDto);
        // 缓存1分钟，1分钟内如果token超了无法实时体现
        redisUtil.set(BACKEND_MODEL_KEY_PREFIX + userAccessKeyDto.getAccessKey(), JSON.toJSONString(backendModelDto), 60);
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
            throw new BizException("token limit exceeded");
        }
    }

    @Override
    public FrontendModelDto generateUserFrontendModelConfig(Long tenantId, Long userId, Long agentId, BackendModelDto backendModel, String siteUrl) {
        if ("false".equalsIgnoreCase(enableModelProxy)) {
            FrontendModelDto frontendModelDto = new FrontendModelDto();
            frontendModelDto.setBaseUrl(backendModel.getBaseUrl());
            frontendModelDto.setApiKey(backendModel.getApiKey());
            return frontendModelDto;
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
