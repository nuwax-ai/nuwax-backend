package com.xspaceagi.agent.core.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.agent.core.adapter.repository.entity.ResourceGroup;

import java.util.List;

public interface ResourceGroupDomainService {

    Long add(ResourceGroup resourceGroup);

    void delete(Long id);

    void update(ResourceGroup resourceGroup);

    ResourceGroup queryById(Long id);

    List<ResourceGroup> list(LambdaQueryWrapper<ResourceGroup> queryWrapper);
}
