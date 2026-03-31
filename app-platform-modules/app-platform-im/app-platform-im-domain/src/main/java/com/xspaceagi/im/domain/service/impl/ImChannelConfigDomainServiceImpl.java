package com.xspaceagi.im.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.im.domain.repository.ImChannelConfigRepository;
import com.xspaceagi.im.domain.service.ImChannelConfigDomainService;
import com.xspaceagi.im.infra.dao.enitity.ImChannelConfig;
import jakarta.annotation.Resource;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.util.Assert;

import java.util.List;

@Service
public class ImChannelConfigDomainServiceImpl implements ImChannelConfigDomainService {

    @Resource
    private ImChannelConfigRepository imChannelConfigRepository;

    @Override
    public ImChannelConfig findOne(String channel, String targetType, String imTargetId) {
        Assert.hasText(channel, "platform不能为空");
        Assert.hasText(targetType, "targetType不能为空");
        Assert.hasText(imTargetId, "imTargetId不能为空");

        LambdaQueryWrapper<ImChannelConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImChannelConfig::getChannel, channel)
                .eq(ImChannelConfig::getTargetType, targetType)
                .eq(ImChannelConfig::getTargetId, imTargetId)
                .eq(ImChannelConfig::getYn, 1)
                .eq(ImChannelConfig::getEnabled, true);
        return imChannelConfigRepository.getOne(wrapper, false);
    }

    @Override
    public ImChannelConfig findOneIgnoreEnabled(String channel, String targetType, String imTargetId) {
        Assert.hasText(channel, "platform不能为空");
        Assert.hasText(targetType, "targetType不能为空");
        Assert.hasText(imTargetId, "imTargetId不能为空");

        LambdaQueryWrapper<ImChannelConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImChannelConfig::getChannel, channel)
                .eq(ImChannelConfig::getTargetType, targetType)
                .eq(ImChannelConfig::getTargetId, imTargetId)
                .eq(ImChannelConfig::getYn, 1);
        return imChannelConfigRepository.getOne(wrapper, false);
    }

    @Override
    public ImChannelConfig add(ImChannelConfig config) {
        checkParams(config);

        // 检查是否已存在相同配置
        ImChannelConfig existing = findOne(
                config.getChannel(),
                config.getTargetType(),
                config.getTargetId()
        );

        if (existing != null) {
            throw new IllegalArgumentException("该渠道配置已存在，不能重复添加");
        }

        imChannelConfigRepository.save(config);
        return config;
    }

    private void checkParams(ImChannelConfig config) {
        Assert.notNull(config, "ImChannelConfig不能为空");
        Assert.notNull(config.getTenantId(), "_tenant_id不能为空");
        Assert.notNull(config.getSpaceId(), "spaceId不能为空");
        Assert.hasText(config.getChannel(), "channel不能为空");
        Assert.hasText(config.getTargetType(), "targetType不能为空");
        Assert.hasText(config.getTargetId(), "targetId不能为空");
    }

    @Override
    public List<ImChannelConfig> list(ImChannelConfig query) {
        LambdaQueryWrapper<ImChannelConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImChannelConfig::getYn, 1);

        if (query != null) {
            if (StringUtils.isNotBlank(query.getChannel())) {
                wrapper.eq(ImChannelConfig::getChannel, query.getChannel());
            }
            if (StringUtils.isNotBlank(query.getTargetType())) {
                wrapper.eq(ImChannelConfig::getTargetType, query.getTargetType());
            }
            if (query.getAgentId() != null) {
                wrapper.eq(ImChannelConfig::getAgentId, query.getAgentId());
            }
            if (query.getUserId() != null) {
                wrapper.eq(ImChannelConfig::getUserId, query.getUserId());
            }
            if (query.getEnabled() != null) {
                wrapper.eq(ImChannelConfig::getEnabled, query.getEnabled());
            }
            if (StringUtils.isNotBlank(query.getName())) {
                wrapper.like(ImChannelConfig::getName, query.getName());
            }
            if (query.getId() != null) {
                wrapper.eq(ImChannelConfig::getId, query.getId());
            }
            if (StringUtils.isNotBlank(query.getTargetId())) {
                wrapper.eq(ImChannelConfig::getTargetId, query.getTargetId());
            }
            if (query.getSpaceId() != null) {
                wrapper.eq(ImChannelConfig::getSpaceId, query.getSpaceId());
            }
            if (query.getTenantId() != null) {
                wrapper.eq(ImChannelConfig::getTenantId, query.getTenantId());
            }
        }

        wrapper.orderByDesc(ImChannelConfig::getModified);
        return imChannelConfigRepository.list(wrapper);
    }

    @Override
    public List<ImChannelConfig> listByPage(ImChannelConfig query, int offset, int limit) {
        LambdaQueryWrapper<ImChannelConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImChannelConfig::getYn, 1);

        if (query != null) {
            if (StringUtils.isNotBlank(query.getChannel())) {
                wrapper.eq(ImChannelConfig::getChannel, query.getChannel());
            }
            if (StringUtils.isNotBlank(query.getTargetType())) {
                wrapper.eq(ImChannelConfig::getTargetType, query.getTargetType());
            }
            if (query.getAgentId() != null) {
                wrapper.eq(ImChannelConfig::getAgentId, query.getAgentId());
            }
            if (query.getUserId() != null) {
                wrapper.eq(ImChannelConfig::getUserId, query.getUserId());
            }
            if (query.getEnabled() != null) {
                wrapper.eq(ImChannelConfig::getEnabled, query.getEnabled());
            }
            if (StringUtils.isNotBlank(query.getName())) {
                wrapper.like(ImChannelConfig::getName, query.getName());
            }
            if (query.getId() != null) {
                wrapper.eq(ImChannelConfig::getId, query.getId());
            }
            if (StringUtils.isNotBlank(query.getTargetId())) {
                wrapper.eq(ImChannelConfig::getTargetId, query.getTargetId());
            }
            if (query.getSpaceId() != null) {
                wrapper.eq(ImChannelConfig::getSpaceId, query.getSpaceId());
            }
            if (query.getTenantId() != null) {
                wrapper.eq(ImChannelConfig::getTenantId, query.getTenantId());
            }
        }

        wrapper.orderByDesc(ImChannelConfig::getModified);
        // 添加分页
        wrapper.last("LIMIT " + limit + " OFFSET " + offset);
        return imChannelConfigRepository.list(wrapper);
    }

    @Override
    public ImChannelConfig getById(Long id) {
        Assert.notNull(id, "id不能为空");
        LambdaQueryWrapper<ImChannelConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ImChannelConfig::getId, id)
                .eq(ImChannelConfig::getYn, 1);
        return imChannelConfigRepository.getOne(wrapper);
    }

    @Override
    public ImChannelConfig updateById(ImChannelConfig config) {
        Assert.notNull(config, "config不能为空");
        Assert.notNull(config.getId(), "id不能为空");
        imChannelConfigRepository.updateById(config);
        return config;
    }

    @Override
    public boolean updateEnabled(ImChannelConfig config) {
        Assert.notNull(config.getId(), "id不能为空");
        Assert.notNull(config.getEnabled(), "enabled不能为空");
        return imChannelConfigRepository.updateById(config);
    }

    @Override
    public boolean delete(Long id) {
        Assert.notNull(id, "id不能为空");
        return imChannelConfigRepository.removeById(id);
    }
}
