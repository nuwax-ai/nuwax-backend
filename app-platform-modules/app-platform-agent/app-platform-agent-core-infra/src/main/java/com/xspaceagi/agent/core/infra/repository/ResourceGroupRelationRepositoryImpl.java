package com.xspaceagi.agent.core.infra.repository;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.agent.core.adapter.repository.ResourceGroupRelationRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.ResourceGroupRelation;
import com.xspaceagi.agent.core.infra.dao.mapper.ResourceGroupRelationMapper;
import org.springframework.stereotype.Service;

@Service
public class ResourceGroupRelationRepositoryImpl extends ServiceImpl<ResourceGroupRelationMapper, ResourceGroupRelation> implements ResourceGroupRelationRepository {
}
