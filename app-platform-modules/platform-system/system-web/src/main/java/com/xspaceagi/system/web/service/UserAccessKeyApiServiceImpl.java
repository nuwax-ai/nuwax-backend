package com.xspaceagi.system.web.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.xspaceagi.system.application.dto.UserDto;
import com.xspaceagi.system.application.service.UserApplicationService;
import com.xspaceagi.system.infra.dao.entity.UserAccessKey;
import com.xspaceagi.system.infra.dao.service.UserAccessKeyService;
import com.xspaceagi.system.sdk.service.UserAccessKeyApiService;
import com.xspaceagi.system.sdk.service.dto.UserAccessKeyDto;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import jakarta.annotation.Resource;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class UserAccessKeyApiServiceImpl implements UserAccessKeyApiService {

    @Resource
    private UserAccessKeyService userAccessKeyService;

    @Resource
    private UserApplicationService userApplicationService;

    @Override
    public UserAccessKeyDto newAccessKey(Long userId, UserAccessKeyDto.AKTargetType targetType, String targetId) {
        return newAccessKey(userId, targetType, targetId, "ak-" + UUID.randomUUID().toString().replace("-", ""));
    }

    @Override
    public UserAccessKeyDto newAccessKey(Long userId, UserAccessKeyDto.AKTargetType targetType, String targetId, String accessKey) {
        UserAccessKey userAccessKey = new UserAccessKey();
        userAccessKey.setUserId(userId);
        userAccessKey.setTargetType(targetType);
        userAccessKey.setTargetId(targetId);
        userAccessKey.setAccessKey(accessKey);
        userAccessKey.setConfig(UserAccessKeyDto.UserAccessKeyConfig.builder().isDevMode(0).enabled(true).build());
        userAccessKeyService.save(userAccessKey);
        UserAccessKeyDto userAccessKeyDto = new UserAccessKeyDto();
        BeanUtils.copyProperties(userAccessKey, userAccessKeyDto);
        return userAccessKeyDto;
    }

    @Override
    public UserAccessKeyDto newAccessKey(Long tenantId, Long userId, UserAccessKeyDto.AKTargetType targetType, String targetId, UserAccessKeyDto.UserAccessKeyConfig userAccessKeyConfig) {
        UserAccessKey userAccessKey = new UserAccessKey();
        userAccessKey.setTenantId(tenantId);
        userAccessKey.setUserId(userId);
        userAccessKey.setTargetType(targetType);
        userAccessKey.setTargetId(targetId);
        userAccessKey.setAccessKey("ak-" + UUID.randomUUID().toString().replace("-", ""));
        userAccessKey.setConfig(userAccessKeyConfig);
        userAccessKeyService.save(userAccessKey);
        UserAccessKeyDto userAccessKeyDto = new UserAccessKeyDto();
        BeanUtils.copyProperties(userAccessKey, userAccessKeyDto);
        return userAccessKeyDto;
    }

    @Override
    public void deleteAccessKey(Long userId, String accessKey) {
        userAccessKeyService.remove(new QueryWrapper<UserAccessKey>().eq("access_key", accessKey).eq("user_id", userId));
    }

    @Override
    public void deleteAccessKeyWithAgentId(Long agentId, String accessKey) {
        userAccessKeyService.remove(new LambdaQueryWrapper<UserAccessKey>().eq(UserAccessKey::getAccessKey, accessKey)
                .eq(UserAccessKey::getTargetType, UserAccessKeyDto.AKTargetType.Agent)
                .eq(UserAccessKey::getTargetId, agentId)
        );
    }

    @Override
    public List<UserAccessKeyDto> queryAccessKeyList(Long userId, UserAccessKeyDto.AKTargetType targetType, String targetId) {
        LambdaQueryWrapper<UserAccessKey> queryWrapper = new LambdaQueryWrapper<>();
        if (userId != null) {
            queryWrapper.eq(UserAccessKey::getUserId, userId);
        }
        if (targetType != null) {
            queryWrapper.eq(UserAccessKey::getTargetType, targetType);
        }
        if (targetId != null) {
            queryWrapper.eq(UserAccessKey::getTargetId, targetId);
        }
        List<UserAccessKey> userAccessKeys = userAccessKeyService.list(queryWrapper);
        //userIds
        List<Long> userIds = userAccessKeys.stream().map(UserAccessKey::getUserId).distinct().collect(Collectors.toList());
        Map<Long, UserDto> userMap = new HashMap<>();
        if (CollectionUtils.isNotEmpty(userIds)) {
            List<UserDto> users = userApplicationService.queryUserListByIds(userIds);
            users.forEach(userDto -> userMap.put(userDto.getId(), userDto));
        }

        return userAccessKeys.stream().map(userAccessKey -> {
                    UserDto userDto = userMap.get(userAccessKey.getUserId());
                    UserAccessKeyDto.Creator creator = null;
                    if (userDto != null) {
                        creator = new UserAccessKeyDto.Creator();
                        creator.setUserName(userDto.getNickName() == null ? userDto.getUserName() : userDto.getNickName());
                        creator.setUserId(userDto.getId());
                    }
                    return UserAccessKeyDto.builder()
                            .id(userAccessKey.getId())
                            .tenantId(userAccessKey.getTenantId())
                            .userId(userAccessKey.getUserId())
                            .targetType(userAccessKey.getTargetType())
                            .targetId(userAccessKey.getTargetId())
                            .accessKey(userAccessKey.getAccessKey())
                            .config(userAccessKey.getConfig())
                            .created(userAccessKey.getCreated())
                            .status(userAccessKey.getStatus())
                            .expire(userAccessKey.getExpire())
                            .name(userAccessKey.getName())
                            .creator(creator)
                            .build();
                }
        ).collect(Collectors.toList());
    }

    @Override
    public UserAccessKeyDto queryAccessKey(Long userId, UserAccessKeyDto.AKTargetType targetType, String targetId) {
        UserAccessKey userAccessKey = userAccessKeyService.getOne(new QueryWrapper<UserAccessKey>()
                .eq("user_id", userId)
                .eq("target_type", targetType)
                .eq("target_id", targetId), false
        );
        if (userAccessKey == null) {
            return null;
        }
        return UserAccessKeyDto.builder()
                .id(userAccessKey.getId())
                .tenantId(userAccessKey.getTenantId())
                .userId(userAccessKey.getUserId())
                .targetType(userAccessKey.getTargetType())
                .targetId(userAccessKey.getTargetId())
                .accessKey(userAccessKey.getAccessKey())
                .config(userAccessKey.getConfig())
                .created(userAccessKey.getCreated())
                .build();
    }

    @Override
    public UserAccessKeyDto queryAccessKey(String accessKey) {
        if (accessKey == null) {
            return null;
        }
        UserAccessKey userAccessKey = userAccessKeyService.getOne(new QueryWrapper<UserAccessKey>().eq("access_key", accessKey));
        if (userAccessKey == null) {
            return null;
        }
        return UserAccessKeyDto.builder()
                .id(userAccessKey.getId())
                .tenantId(userAccessKey.getTenantId())
                .name(userAccessKey.getName())
                .userId(userAccessKey.getUserId())
                .targetType(userAccessKey.getTargetType())
                .targetId(userAccessKey.getTargetId())
                .accessKey(userAccessKey.getAccessKey())
                .config(userAccessKey.getConfig())
                .created(userAccessKey.getCreated())
                .expire(userAccessKey.getExpire())
                .status(userAccessKey.getStatus())
                .build();
    }

    @Override
    public UserAccessKeyDto refreshAccessKey(Long id) {
        UserAccessKey userAccessKeyUpdate = new UserAccessKey();
        userAccessKeyUpdate.setId(id);
        userAccessKeyUpdate.setAccessKey(UUID.randomUUID().toString().replace("-", ""));
        userAccessKeyService.updateById(userAccessKeyUpdate);
        UserAccessKey userAccessKey = userAccessKeyService.getById(id);
        if (userAccessKey == null) {
            return null;
        }
        return UserAccessKeyDto.builder()
                .id(userAccessKey.getId())
                .tenantId(userAccessKey.getTenantId())
                .userId(userAccessKey.getUserId())
                .targetType(userAccessKey.getTargetType())
                .targetId(userAccessKey.getTargetId())
                .accessKey(userAccessKey.getAccessKey())
                .config(userAccessKey.getConfig())
                .build();
    }

    @Override
    public void updateAgentDevMode(Long userId, Long agentId, String accessKey, Integer devMode) {
        if (devMode == null || !devMode.equals(1)) {
            devMode = 0;
        }
        LambdaUpdateWrapper<UserAccessKey> updateWrapper = new LambdaUpdateWrapper<>();
        if (userId != null) {
            updateWrapper.eq(UserAccessKey::getUserId, userId);
        }
        updateWrapper.eq(UserAccessKey::getTargetId, agentId);
        updateWrapper.eq(UserAccessKey::getTargetType, UserAccessKeyDto.AKTargetType.Agent);
        updateWrapper.eq(UserAccessKey::getAccessKey, accessKey);
        updateWrapper.set(UserAccessKey::getConfig, JsonSerializeUtil.toJSONStringGeneric(UserAccessKeyDto.UserAccessKeyConfig.builder().isDevMode(devMode).build()));
        userAccessKeyService.update(updateWrapper);
    }

    @Override
    public void updateUserAccessKeyConfig(Long id, UserAccessKeyDto.UserAccessKeyConfig userAccessKeyConfig) {
        LambdaUpdateWrapper<UserAccessKey> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(UserAccessKey::getId, id);
        updateWrapper.set(UserAccessKey::getConfig, JsonSerializeUtil.toJSONStringGeneric(userAccessKeyConfig));
        userAccessKeyService.update(updateWrapper);
    }
}
