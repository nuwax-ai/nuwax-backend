package com.xspaceagi.agent.core.domain.service.impl;

import com.baomidou.dynamic.datasource.annotation.DSTransactional;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.agent.core.adapter.repository.PluginConfigRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.PluginConfig;
import com.xspaceagi.agent.core.adapter.repository.entity.Published;
import com.xspaceagi.agent.core.domain.service.PluginDomainService;
import com.xspaceagi.agent.core.domain.service.PublishDomainService;
import com.xspaceagi.system.spec.enums.ErrorCodeEnum;
import com.xspaceagi.system.spec.exception.BizException;
import com.xspaceagi.system.spec.exception.BizExceptionCodeEnum;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class PluginDomainServiceImpl implements PluginDomainService {

    @Resource
    private PluginConfigRepository pluginConfigRepository;

    @Resource
    private PublishDomainService publishDomainService;

    @Override
    public void add(PluginConfig pluginConfig) {
        pluginConfigRepository.save(pluginConfig);
    }

    @Override
    @DSTransactional
    public void delete(Long pluginId) {
        pluginConfigRepository.removeById(pluginId);
        publishDomainService.deleteByTargetId(Published.TargetType.Plugin, pluginId);
        publishDomainService.deletePublishedApply(Published.TargetType.Plugin, pluginId);
    }

    @Override
    public void deleteBySpaceId(Long spaceId) {
        queryListBySpaceId(spaceId).forEach(pluginConfig -> {
            delete(pluginConfig.getId());
        });
    }

    @Override
    public void update(PluginConfig pluginConfig) {
        if (pluginConfig.getIcon() != null && pluginConfig.getIcon().contains("api/logo")) {
            pluginConfig.setIcon(null);
        }
        pluginConfigRepository.updateById(pluginConfig);
    }

    @Override
    public PluginConfig queryById(Long pluginId) {
        return pluginConfigRepository.getById(pluginId);
    }

    @Override
    public List<PluginConfig> queryListByIds(List<Long> pluginIds) {
        LambdaQueryWrapper<PluginConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(PluginConfig::getId, pluginIds);
        return pluginConfigRepository.list(queryWrapper);
    }

    @Override
    public List<PluginConfig> queryListBySpaceId(Long spaceId) {
        LambdaQueryWrapper<PluginConfig> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(PluginConfig::getSpaceId, spaceId);
        return pluginConfigRepository.list(queryWrapper);
    }

    @Override
    public Long copy(Long userId, Long pluginId) {
        PluginConfig pluginConfig = queryById(pluginId);
        if (pluginConfig == null) {
            throw BizException.of(ErrorCodeEnum.INVALID_PARAM, BizExceptionCodeEnum.agentPluginNotFound);
        }
        pluginConfig.setId(null);
        pluginConfig.setCreatorId(userId);
        pluginConfig.setModified(null);
        pluginConfig.setCreated(null);
        pluginConfigRepository.save(pluginConfig);
        return pluginConfig.getId();
    }
}
