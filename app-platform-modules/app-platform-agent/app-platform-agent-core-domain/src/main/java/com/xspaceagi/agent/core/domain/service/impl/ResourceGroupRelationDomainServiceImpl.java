package com.xspaceagi.agent.core.domain.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.agent.core.adapter.repository.ResourceGroupRelationRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.ResourceGroupRelation;
import com.xspaceagi.agent.core.domain.service.ResourceGroupRelationDomainService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ResourceGroupRelationDomainServiceImpl implements ResourceGroupRelationDomainService {

    @Resource
    private ResourceGroupRelationRepository resourceGroupRelationRepository;

    @Override
    public void add(ResourceGroupRelation relation) {
        resourceGroupRelationRepository.save(relation);
    }

    @Override
    public void delete(Long id) {
        resourceGroupRelationRepository.removeById(id);
    }

    @Override
    public void deleteByGroupIdAndTarget(Long groupId, String targetType, Long targetId) {
        LambdaQueryWrapper<ResourceGroupRelation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper
                .eq(ResourceGroupRelation::getGroupId, groupId)
                .eq(ResourceGroupRelation::getTargetType, targetType)
                .eq(ResourceGroupRelation::getTargetId, targetId);
        resourceGroupRelationRepository.remove(queryWrapper);
    }

    @Override
    public List<ResourceGroupRelation> listByGroupId(Long groupId) {
        LambdaQueryWrapper<ResourceGroupRelation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ResourceGroupRelation::getGroupId, groupId);
        return resourceGroupRelationRepository.list(queryWrapper);
    }

    @Override
    public List<ResourceGroupRelation> list(LambdaQueryWrapper<ResourceGroupRelation> queryWrapper) {
        return resourceGroupRelationRepository.list(queryWrapper);
    }

    @Override
    public int countByGroupId(Long groupId) {
        LambdaQueryWrapper<ResourceGroupRelation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ResourceGroupRelation::getGroupId, groupId);
        return (int) resourceGroupRelationRepository.count(queryWrapper);
    }

    @Override
    public ResourceGroupRelation queryOne(LambdaQueryWrapper<ResourceGroupRelation> queryWrapper) {
        return resourceGroupRelationRepository.getOne(queryWrapper, false);
    }

    @Override
    public List<ResourceGroupRelation> listByGroupIds(List<Long> groupIds) {
        LambdaQueryWrapper<ResourceGroupRelation> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.in(ResourceGroupRelation::getGroupId, groupIds);
        return resourceGroupRelationRepository.list(queryWrapper);
    }
}
