package com.xspaceagi.pricing.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.pricing.infra.dao.entity.PricingConfig;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface PricingConfigMapper extends BaseMapper<PricingConfig> {
}
