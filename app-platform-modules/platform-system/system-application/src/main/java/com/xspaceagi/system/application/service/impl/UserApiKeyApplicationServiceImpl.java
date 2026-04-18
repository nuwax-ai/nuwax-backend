package com.xspaceagi.system.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xspaceagi.system.application.dto.UpdateApiKeyDto;
import com.xspaceagi.system.application.service.UserApiKeyApplicationService;
import com.xspaceagi.system.infra.dao.entity.OpenApiDefinition;
import com.xspaceagi.system.infra.dao.entity.UserAccessKey;
import com.xspaceagi.system.infra.dao.service.OpenApiDefinitionService;
import com.xspaceagi.system.infra.dao.service.UserAccessKeyService;
import com.xspaceagi.system.sdk.server.IUserDataPermissionRpcService;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.sdk.service.dto.UserDataPermissionDto;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import com.xspaceagi.system.spec.utils.RedisUtil;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class UserApiKeyApplicationServiceImpl implements UserApiKeyApplicationService {

    @Resource
    private UserAccessKeyService userAccessKeyService;

    @Resource
    private UserAccessKeyApiService userAccessKeyApiService;

    @Resource
    private OpenApiDefinitionService openApiDefinitionService;

    @Resource
    private IUserDataPermissionRpcService userDataPermissionRpcService;

    @Resource
    private RedisUtil redisUtil;

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
        updateWrapper.set(UserAccessKey::getExpire, updateApiKeyDto.getExpire());
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
            if (apiConfigs == null) {
                List<OpenApiDefinition> openApiDefinitions = queryOpenApiDefinitions(userId);
                apiConfigs = new ArrayList<>();
                userAccessKeyDto.getConfig().setApiConfigs(apiConfigs);
                for (OpenApiDefinition openApiDefinition : openApiDefinitions) {
                    UserAccessKeyDto.ApiConfig apiConfig = new UserAccessKeyDto.ApiConfig();
                    apiConfig.setKey(openApiDefinition.getKey());
                    apiConfig.setRpm(-1);
                    if (CollectionUtils.isNotEmpty(openApiDefinition.getApiList())) {
                        for (OpenApiDefinition apiDefinition : openApiDefinition.getApiList()) {
                            UserAccessKeyDto.ApiConfig apiConfig0 = new UserAccessKeyDto.ApiConfig();
                            apiConfig0.setKey(apiDefinition.getKey());
                            apiConfig0.setRpm(-1);
                            apiConfigs.add(apiConfig0);
                        }
                    }
                }
            }
        });
        return userAccessKeys;
    }

    @Override
    public List<OpenApiDefinition> queryOpenApiDefinitions(Long userId) {
        UserDataPermissionDto userDataPermission = userDataPermissionRpcService.getUserDataPermission(userId);
        List<UserDataPermissionDto.OpenApiConfig> openApiConfigs = userDataPermission.getOpenApiConfigs();
        // 构建权限映射：key -> OpenApiConfig
        Map<String, UserDataPermissionDto.OpenApiConfig> permissionMap = CollectionUtils.isEmpty(openApiConfigs)
                ? null
                : openApiConfigs.stream().collect(Collectors.toMap(UserDataPermissionDto.OpenApiConfig::getKey, c -> c));
        // 根据用户权限过滤
        return openApiDefinitionService.queryAll().stream()
                .map(def -> filter(def, permissionMap))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    /**
     * 根据用户权限递归过滤 API 定义。
     * permissionMap 为 null 表示全部权限，不做过滤。
     * 如果当前节点及其子节点都没有权限，返回 null。
     */
    private OpenApiDefinition filter(OpenApiDefinition src, Map<String, UserDataPermissionDto.OpenApiConfig> permissionMap) {
        // permissionMap 为 null 表示用户拥有全部权限
        boolean hasPermission = permissionMap != null && permissionMap.containsKey(src.getKey());
        // 递归处理子节点
        List<OpenApiDefinition> filteredChildren;
        if (src.getApiList() != null && !src.getApiList().isEmpty()) {
            filteredChildren = src.getApiList().stream()
                    .map(child -> filter(child, permissionMap))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toCollection(ArrayList::new));
            // 替换过滤后的子节点
            src.setApiList(filteredChildren);
        }
        // 当前节点无权限且无子节点，过滤掉
        if (!hasPermission && (src.getApiList() == null || src.getApiList().isEmpty())) {
            return null;
        }
        // 合并用户的权限限制
        if (hasPermission) {
            UserDataPermissionDto.OpenApiConfig config = permissionMap.get(src.getKey());
            if (config != null) {
                src.setRpm(config.getRpm() != null ? config.getRpm() : -1);
                src.setRpd(config.getRpd() != null ? config.getRpd() : -1);
            } else {
                src.setRpm(-1);
                src.setRpd(-1);
            }
        }
        return src;
    }

    @Override
    public void requestLimitCheck(Long userId, OpenApiDefinition openApiDefinition) {
        if (openApiDefinition != null && openApiDefinition.getRpm() > 0) {
            String key = "rpm:" + userId + ":" + openApiDefinition.getKey();
            Long increment = redisUtil.increment(key, 1);
            if (increment == 1) {
                redisUtil.expire(key, 60);
            }
            if (increment > openApiDefinition.getRpm()) {
                Long time = redisUtil.getTime(key);
                if (time == null || time == -1) {
                    redisUtil.expire(key, 60);
                }
                throw BizException.of(BizExceptionCodeEnum.systemApiKeyRateLimitPerMinute);
            }
        }
        if (openApiDefinition != null && openApiDefinition.getRpd() > 0) {
            String key = "rpd:" + userId + ":" + openApiDefinition.getKey();
            Long increment = redisUtil.increment(key, 1);
            if (increment == 1) {
                redisUtil.expire(key, 60 * 60 * 24);
            }
            if (increment > openApiDefinition.getRpd()) {
                Long time = redisUtil.getTime(key);
                if (time == null || time == -1) {
                    redisUtil.expire(key, 60 * 60 * 24);
                }
                throw BizException.of(BizExceptionCodeEnum.systemApiKeyRateLimitPerDay);
            }
        }

    }
}
