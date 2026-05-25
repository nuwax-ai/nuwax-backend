package com.xspaceagi.pricing.infra.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.xspaceagi.pricing.infra.dao.entity.ModelPriceTier;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ModelPriceTierMapper extends BaseMapper<ModelPriceTier> {

    List<ModelPriceTier> selectByModelId(@Param("modelId") Long modelId);
}
