package com.xspaceagi.agent.core.infra.repository;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.agent.core.adapter.repository.ResourceGroupRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.ResourceGroup;
import com.xspaceagi.agent.core.infra.dao.mapper.ResourceGroupMapper;
import org.springframework.stereotype.Service;

@Service
public class ResourceGroupRepositoryImpl extends ServiceImpl<ResourceGroupMapper, ResourceGroup> implements ResourceGroupRepository {
}
