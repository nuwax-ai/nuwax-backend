package com.xspaceagi.pricing.app.service;

import com.xspaceagi.pricing.sdk.dto.*;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;

import java.util.List;

public interface PricingAppService {

    /**
     * 创建或更新定价配置（按 targetType + targetId 唯一）
     */
    Long savePricingConfig(SavePricingConfigRequest request);

    /**
     * 查询定价配置列表（可根据类型筛选）
     */
    List<PricingConfigDTO> listPricingConfigs(PricingConfigQueryRequest request);

    /**
     * 批量查询定价配置列表
     */
    List<PricingConfigDTO> listPricingConfigs(TargetTypeEnum targetType, List<String> targetIds);

    PriceEstimate estimatePrice(Long tenantId, Long userId, List<PriceEstimate.EstimateTarget> estimateTargets);


    /**
     * 新增模型价格档位
     */
    Long addModelPriceTier(ModelPriceTierDTO dto);

    /**
     * 修改模型价格档位
     */
    boolean updateModelPriceTier(ModelPriceTierDTO dto);

    /**
     * 删除模型价格档位
     */
    boolean deleteModelPriceTier(Long id);

    /**
     * 查询模型价格档位列表
     */
    List<ModelPriceTierDTO> listModelPriceTiers(Long modelId);

    ModelPriceTierDTO queryModelPriceTier(Long id);

    PricingConfigDTO queryPricingConfig(Long id);

    void deletePricingConfig(Long id);

    TrialRecordDTO updateTrialCount(UpdateTrialCountRequest request);

    TrialRecordDTO getTrialCount(UpdateTrialCountRequest request);

    PricingConfigDTO queryPricingInfo(QueryPricingInfoRequest request);
}
