package com.xspaceagi.system.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xspaceagi.system.application.dto.UpdateApiKeyDto;
import com.xspaceagi.system.application.service.UserApiKeyApplicationService;
import com.xspaceagi.system.infra.dao.entity.OpenApiDefinition;
import com.xspaceagi.system.infra.dao.entity.UserAccessKey;
import com.xspaceagi.system.infra.dao.service.OpenApiDefinitionService;
import com.xspaceagi.system.infra.dao.service.UserAccessKeyService;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class UserApiKeyApplicationServiceImpl implements UserApiKeyApplicationService {

    @Resource
    private UserAccessKeyService userAccessKeyService;

    @Resource
    private UserAccessKeyApiService userAccessKeyApiService;

    @Resource
    private OpenApiDefinitionService openApiDefinitionService;

    @Override
    public UserAccessKeyDto createUserApiKey(Long userId, String name, Date expire) {
        UserAccessKey userAccessKey = new UserAccessKey();
        userAccessKey.setUserId(userId);
        userAccessKey.setAccessKey("ak-" + UUID.randomUUID().toString().replace("-", ""));
        userAccessKey.setTargetType(UserAccessKeyDto.AKTargetType.OpenApi);
        userAccessKey.setTargetId("");
        userAccessKey.setConfig(UserAccessKeyDto.UserAccessKeyConfig.builder().build());
        userAccessKey.setName(name);
        userAccessKey.setStatus(1);
        userAccessKey.setExpire(expire);
        userAccessKeyService.save(userAccessKey);
        return userAccessKeyApiService.queryAccessKey(userAccessKey.getAccessKey());
    }

    @Override
    public void deleteUserApiKey(Long userId, String apiKey) {
        userAccessKeyApiService.deleteAccessKey(userId, apiKey);
    }

    @Override
    public void updateUserApiKey(UpdateApiKeyDto updateApiKeyDto) {
        Assert.notNull(updateApiKeyDto, "updateApiKeyDto must be non-null");
        Assert.hasText(updateApiKeyDto.getAccessKey(), "accessKey must be non-null");
        Assert.notNull(updateApiKeyDto.getUserId(), "userId must be non-null");
        LambdaUpdateWrapper<UserAccessKey> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(UserAccessKey::getUserId, updateApiKeyDto.getUserId());
        updateWrapper.eq(UserAccessKey::getAccessKey, updateApiKeyDto.getAccessKey());
        updateWrapper.set(StringUtils.isNotBlank(updateApiKeyDto.getName()), UserAccessKey::getName, updateApiKeyDto.getName());
        updateWrapper.set(updateApiKeyDto.getStatus() != null, UserAccessKey::getStatus, updateApiKeyDto.getStatus());
        updateWrapper.set(updateApiKeyDto.getExpire() != null, UserAccessKey::getExpire, updateApiKeyDto.getExpire());
        if (updateApiKeyDto.getApiConfigs() != null) {
            updateWrapper.set(UserAccessKey::getConfig, JsonSerializeUtil.toJSONStringGeneric(UserAccessKeyDto.UserAccessKeyConfig.builder().apiConfigs(updateApiKeyDto.getApiConfigs()).build()));
        }
        userAccessKeyService.update(updateWrapper);
    }

    @Override
    public List<UserAccessKeyDto> getUserApiKeys(Long userId) {
        List<UserAccessKeyDto> userAccessKeys = userAccessKeyApiService.queryAccessKeyList(userId, UserAccessKeyDto.AKTargetType.OpenApi, null);
        userAccessKeys.forEach(userAccessKeyDto -> {
            List<UserAccessKeyDto.ApiConfig> apiConfigs = userAccessKeyDto.getConfig().getApiConfigs();
            if (CollectionUtils.isEmpty(apiConfigs)) {
                List<OpenApiDefinition> openApiDefinitions = queryOpenApiDefinitions(userId);
                apiConfigs = new ArrayList<>();
                for (OpenApiDefinition openApiDefinition : openApiDefinitions) {
                    UserAccessKeyDto.ApiConfig apiConfig = new UserAccessKeyDto.ApiConfig();
                    apiConfig.setKey(openApiDefinition.getKey());
                    apiConfig.setRpm(-1);
                    if (CollectionUtils.isNotEmpty(openApiDefinition.getApiList())) {
                        for (OpenApiDefinition apiDefinition : openApiDefinition.getApiList()) {
                            UserAccessKeyDto.ApiConfig apiConfig0 = new UserAccessKeyDto.ApiConfig();
                            apiConfig0.setKey(apiDefinition.getKey());
                            apiConfig0.setRpm(-1);
                            apiConfigs.add(apiConfig);
                        }
                    }
                }
            }
        });
        return userAccessKeys;
    }

    @Override
    public List<OpenApiDefinition> queryOpenApiDefinitions(Long userId) {
        // TODO 根据用户实际的权限过滤
        return openApiDefinitionService.queryAll();
    }
}
