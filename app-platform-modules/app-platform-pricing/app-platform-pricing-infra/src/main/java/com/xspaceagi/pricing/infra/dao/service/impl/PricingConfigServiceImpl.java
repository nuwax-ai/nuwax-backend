package com.xspaceagi.pricing.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.pricing.infra.dao.entity.PricingConfig;
import com.xspaceagi.pricing.infra.dao.mapper.PricingConfigMapper;
import com.xspaceagi.pricing.infra.dao.service.IPricingConfigService;
import org.springframework.stereotype.Service;

@Service
public class PricingConfigServiceImpl extends ServiceImpl<PricingConfigMapper, PricingConfig> implements IPricingConfigService {
}
