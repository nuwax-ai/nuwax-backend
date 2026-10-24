package com.xspaceagi.sandbox.infra.dao.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.sandbox.infra.dao.entity.SandboxConfig;
import com.xspaceagi.sandbox.infra.dao.mapper.SandboxConfigMapper;
import com.xspaceagi.sandbox.infra.dao.service.SandboxConfigService;
import com.xspaceagi.sandbox.spec.enums.SandboxScopeEnum;
import com.xspaceagi.system.spec.tenant.thread.TenantFunctions;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 沙盒配置服务实现
 */
@Service
public class SandboxConfigServiceImpl extends ServiceImpl<SandboxConfigMapper, SandboxConfig> implements SandboxConfigService {

    @Override
    public List<SandboxConfig> queryUserConfigsByType(Long userId) {
        LambdaQueryWrapper<SandboxConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SandboxConfig::getScope, SandboxScopeEnum.USER)
                .eq(SandboxConfig::getUserId, userId)
                .orderByDesc(SandboxConfig::getId);

        return list(queryWrapper);
    }

    @Override
    public List<SandboxConfig> queryGlobalConfigs(Boolean isActive) {
        LambdaQueryWrapper<SandboxConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SandboxConfig::getScope, SandboxScopeEnum.GLOBAL)
                .eq(isActive != null, SandboxConfig::getIsActive, isActive)
                .orderByAsc(SandboxConfig::getId);

        return list(queryWrapper);
    }

    @Override
    public SandboxConfig queryUserConfigByKey(String configKey) {
        LambdaQueryWrapper<SandboxConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SandboxConfig::getScope, SandboxScopeEnum.USER)
                .eq(SandboxConfig::getConfigKey, configKey)
                .eq(SandboxConfig::getIsActive, true)
                .last("LIMIT 1");

        return TenantFunctions.callWithIgnoreCheck(() -> getOne(queryWrapper));
    }

    @Override
    public SandboxConfig queryUserConfigByDeviceId(Long userId, String deviceId) {
        if (userId == null || StringUtils.isBlank(deviceId)) {
            return null;
        }
        LambdaQueryWrapper<SandboxConfig> queryWrapper = new LambdaQueryWrapper<>();
        // 注意：此处刻意不过滤 isActive——若存在 inactive 的同 (userId, deviceId) 行，
        // 过滤后会查不到从而走「新建」路径，反而撞 uk_user_device 唯一键。
        queryWrapper.eq(SandboxConfig::getScope, SandboxScopeEnum.USER)
                .eq(SandboxConfig::getUserId, userId)
                .eq(SandboxConfig::getDeviceId, deviceId)
                .last("LIMIT 1");

        return TenantFunctions.callWithIgnoreCheck(() -> getOne(queryWrapper));
    }

    @Override
    public SandboxConfig queryGlobalConfigByKey(String configKey) {
        LambdaQueryWrapper<SandboxConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SandboxConfig::getScope, SandboxScopeEnum.GLOBAL)
                .eq(SandboxConfig::getConfigKey, configKey)
                .eq(SandboxConfig::getIsActive, true)
                .last("LIMIT 1");

        return getOne(queryWrapper);
    }

    @Override
    public SandboxConfig querySandboxConfigById(Long sandboxId) {
        LambdaQueryWrapper<SandboxConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(SandboxConfig::getId, sandboxId)
                .eq(SandboxConfig::getIsActive, true)
                .last("LIMIT 1");
        return getOne(queryWrapper);
    }
}
