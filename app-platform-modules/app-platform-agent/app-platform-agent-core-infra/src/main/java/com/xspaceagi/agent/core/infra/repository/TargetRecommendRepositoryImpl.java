package com.xspaceagi.agent.core.infra.repository;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.agent.core.adapter.repository.TargetRecommendRepository;
import com.xspaceagi.agent.core.adapter.repository.entity.TargetRecommend;
import com.xspaceagi.agent.core.infra.dao.mapper.TargetRecommendMapper;
import org.springframework.stereotype.Service;

@Service
public class TargetRecommendRepositoryImpl extends ServiceImpl<TargetRecommendMapper, TargetRecommend> implements TargetRecommendRepository {
}
