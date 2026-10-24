package com.xspaceagi.system.infra.dao.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.system.infra.dao.entity.UserConfig;
import com.xspaceagi.system.infra.dao.mapper.UserConfigMapper;
import com.xspaceagi.system.infra.dao.service.UserConfigService;
import com.xspaceagi.system.spec.common.JsonTypeHandlerWithoutType;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

@Service
public class UserConfigServiceImpl extends ServiceImpl<UserConfigMapper, UserConfig> implements UserConfigService {

    /**
     * LambdaUpdateWrapper 的 set 默认不带 typeHandler，config_value 是可承载任意 JSON 的 Object，
     * 不显式指定会让驱动无法推断 JDBC 类型（Map/List 会直接报错），故这里显式带上。
     * <p>注意 set(column, val, mapping) 的 mapping 是<b>原样</b>拼进占位符的（MyBatis-Plus
     * {@code SqlScriptUtils#safeParam} 只做 {@code "#{" + param + "," + mapping + "}"}），
     * 因此 {@code typeHandler=} 前缀必须自己写，漏了会得到
     * {@code #{...,com.xxx.Handler}} 这种非法语法，执行时报 BuilderException（Parsing error was found in mapping）。
     */
    private static final String CONFIG_VALUE_TYPE_HANDLER = "typeHandler=" + JsonTypeHandlerWithoutType.class.getName();

    @Override
    public UserConfig getByKey(Long userId, String configKey) {
        return getOne(new LambdaQueryWrapper<UserConfig>()
                .eq(UserConfig::getUserId, userId)
                .eq(UserConfig::getConfigKey, configKey), false);
    }

    @Override
    public boolean upsert(Long tenantId, Long userId, String configKey, Object configValue, String description) {
        if (updateByKey(userId, configKey, configValue, description)) {
            return false;
        }
        UserConfig userConfig = new UserConfig();
        userConfig.setTenantId(tenantId);
        userConfig.setUserId(userId);
        userConfig.setConfigKey(configKey);
        userConfig.setConfigValue(configValue);
        userConfig.setDescription(description);
        try {
            save(userConfig);
            return true;
        } catch (DuplicateKeyException e) {
            // 并发写同一 key：唯一索引 uk_user_id_config_key 兜底，转为覆盖更新
            updateByKey(userId, configKey, configValue, description);
            return false;
        }
    }

    @Override
    public boolean deleteByKey(Long userId, String configKey) {
        return remove(new LambdaQueryWrapper<UserConfig>()
                .eq(UserConfig::getUserId, userId)
                .eq(UserConfig::getConfigKey, configKey));
    }

    @Override
    public long countByUser(Long userId) {
        return count(new LambdaQueryWrapper<UserConfig>().eq(UserConfig::getUserId, userId));
    }

    /**
     * 覆盖写单 key；description 传 null 即清空备注
     */
    private boolean updateByKey(Long userId, String configKey, Object configValue, String description) {
        return update(new LambdaUpdateWrapper<UserConfig>()
                .eq(UserConfig::getUserId, userId)
                .eq(UserConfig::getConfigKey, configKey)
                .set(UserConfig::getConfigValue, configValue, CONFIG_VALUE_TYPE_HANDLER)
                .set(UserConfig::getDescription, description));
    }
}
