package com.xspaceagi.agent.core.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.agent.core.adapter.repository.ResourceGroupRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.ResourceGroup;
import com.xspaceagi.agent.core.domain.service.ResourceGroupDomainService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ResourceGroupDomainServiceImpl implements ResourceGroupDomainService {

    @Resource
    private ResourceGroupRepository resourceGroupRepository;

    @Override
    public Long add(ResourceGroup resourceGroup) {
        resourceGroupRepository.save(resourceGroup);
        return resourceGroup.getId();
    }

    @Override
    public void delete(Long id) {
        resourceGroupRepository.removeById(id);
    }

    @Override
    public void update(ResourceGroup resourceGroup) {
        resourceGroupRepository.updateById(resourceGroup);
    }

    @Override
    public ResourceGroup queryById(Long id) {
        return resourceGroupRepository.getById(id);
    }

    @Override
    public List<ResourceGroup> list(LambdaQueryWrapper<ResourceGroup> queryWrapper) {
        return resourceGroupRepository.list(queryWrapper);
    }
}
