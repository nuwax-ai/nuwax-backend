package com.xspaceagi.agent.core.domain.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.xspaceagi.agent.core.adapter.repository.entity.ResourceGroupRelation;

import java.util.List;

public interface ResourceGroupRelationDomainService {

    void add(ResourceGroupRelation relation);

    void delete(Long id);

    void deleteByGroupIdAndTarget(Long groupId, String targetType, Long targetId);

    List<ResourceGroupRelation> listByGroupId(Long groupId);

    List<ResourceGroupRelation> list(LambdaQueryWrapper<ResourceGroupRelation> queryWrapper);

    int countByGroupId(Long groupId);

    ResourceGroupRelation queryOne(LambdaQueryWrapper<ResourceGroupRelation> queryWrapper);

    List<ResourceGroupRelation> listByGroupIds(List<Long> groupIds);
}
