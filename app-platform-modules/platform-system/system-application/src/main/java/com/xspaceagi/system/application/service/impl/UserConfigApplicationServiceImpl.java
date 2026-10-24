package com.xspaceagi.system.application.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.system.application.constant.UserConfigConstraints;
import com.xspaceagi.system.application.dto.UserConfigItemDto;
import com.xspaceagi.system.application.service.UserConfigApplicationService;
import com.xspaceagi.system.infra.dao.entity.UserConfig;
import com.xspaceagi.system.infra.dao.service.UserConfigService;
import com.xspaceagi.system.spec.common.RequestContext;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import com.xspaceagi.system.spec.jackson.JsonSerializeUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UserConfigApplicationServiceImpl implements UserConfigApplicationService {

    @Resource
    private UserConfigService userConfigService;

    @Override
    public Map<String, Object> queryConfigMap(Long userId) {
        Map<String, Object> configMap = new LinkedHashMap<>();
        listByUser(userId).forEach(userConfig -> configMap.put(userConfig.getConfigKey(), userConfig.getConfigValue()));
        return configMap;
    }

    @Override
    public List<UserConfigItemDto> queryConfigList(Long userId) {
        return listByUser(userId).stream().map(UserConfigApplicationServiceImpl::convert).collect(Collectors.toList());
    }

    @Override
    public Object queryConfig(Long userId, String key) {
        UserConfig userConfig = userConfigService.getByKey(userId, requireValidKey(key));
        return userConfig == null ? null : userConfig.getConfigValue();
    }

    @Override
    public void setConfig(Long userId, String key, Object value, String description) {
        String configKey = requireValidKey(key);
        requireValidValue(value);
        requireValidDescription(description);
        // 数量上限只在新增时校验：已存在的 key 覆盖更新不该被上限挡住
        if (userConfigService.getByKey(userId, configKey) == null
                && userConfigService.countByUser(userId) >= UserConfigConstraints.MAX_KEYS_PER_USER) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userConfigCountLimit);
        }
        userConfigService.upsert(RequestContext.get().getTenantId(), userId, configKey, value, description);
    }

    @Override
    public void deleteConfig(Long userId, String key) {
        // 幂等：key 本就不存在也按成功处理，仅 key 格式非法才报错
        userConfigService.deleteByKey(userId, requireValidKey(key));
    }

    private List<UserConfig> listByUser(Long userId) {
        return userConfigService.list(new LambdaQueryWrapper<UserConfig>()
                .eq(UserConfig::getUserId, userId)
                .orderByAsc(UserConfig::getConfigKey));
    }

    private String requireValidKey(String key) {
        if (!UserConfigConstraints.isValidKey(key)) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userConfigKeyInvalid);
        }
        return key;
    }

    private void requireValidValue(Object value) {
        if (value == null) {
            // 值缺失按「值为空」处理：需要清空请走删除接口，避免出现行存在但值为 null 的歧义
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userConfigKeyInvalid);
        }
        String valueJson;
        try {
            valueJson = JsonSerializeUtil.toJSONStringGeneric(value);
        } catch (RuntimeException e) {
            // 不可序列化的值一律按非法值处理，不透出内部异常细节
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userConfigValueTooLarge);
        }
        if (valueJson == null || valueJson.getBytes(StandardCharsets.UTF_8).length > UserConfigConstraints.MAX_VALUE_BYTES) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userConfigValueTooLarge);
        }
    }

    private void requireValidDescription(String description) {
        if (description != null && description.length() > UserConfigConstraints.MAX_DESCRIPTION_LENGTH) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.userConfigValueTooLarge);
        }
    }

    private static UserConfigItemDto convert(UserConfig userConfig) {
        UserConfigItemDto dto = new UserConfigItemDto();
        dto.setKey(userConfig.getConfigKey());
        dto.setValue(userConfig.getConfigValue());
        dto.setDescription(userConfig.getDescription());
        dto.setCreated(userConfig.getCreated());
        dto.setModified(userConfig.getModified());
        return dto;
    }
}
