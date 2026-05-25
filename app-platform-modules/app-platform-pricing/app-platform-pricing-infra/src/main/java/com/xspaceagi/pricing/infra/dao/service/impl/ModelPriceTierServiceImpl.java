package com.xspaceagi.pricing.infra.dao.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.xspaceagi.pricing.infra.dao.entity.ModelPriceTier;
import com.xspaceagi.pricing.infra.dao.mapper.ModelPriceTierMapper;
import com.xspaceagi.pricing.infra.dao.service.IModelPriceTierService;
import org.springframework.stereotype.Service;

@Service
public class ModelPriceTierServiceImpl extends ServiceImpl<ModelPriceTierMapper, ModelPriceTier> implements IModelPriceTierService {
}
