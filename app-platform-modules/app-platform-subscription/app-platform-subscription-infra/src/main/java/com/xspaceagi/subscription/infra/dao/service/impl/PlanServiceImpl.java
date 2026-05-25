package com.xspaceagi.subscription.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.subscription.infra.dao.entity.Plan;
import com.xspaceagi.subscription.infra.dao.mapper.PlanMapper;
import com.xspaceagi.subscription.infra.dao.service.IPlanService;
import org.springframework.stereotype.Service;

@Service
public class PlanServiceImpl extends ServiceImpl<PlanMapper, Plan> implements IPlanService {
}
