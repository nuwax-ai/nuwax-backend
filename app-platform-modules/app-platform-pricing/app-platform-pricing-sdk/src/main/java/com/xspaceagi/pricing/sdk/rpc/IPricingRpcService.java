package com.xspaceagi.pricing.sdk.rpc;

import com.xspaceagi.pricing.sdk.dto.*;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;

import java.util.List;

public interface IPricingRpcService {

    /**
     * 创建或更新定价配置（按 targetType + targetId 唯一）
     */
    Long savePricingConfig(SavePricingConfigRequest request);

    /**
     * 查询定价配置列表（可根据类型筛选）
     */
    List<PricingConfigDTO> listPricingConfigs(PricingConfigQueryRequest request);

    /**
     * 查询定价配置列表（按 targetType + targetId 唯一）
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
    boolean deleteModelPriceTier(Long tenantId, Long id);

    /**
     * 查询模型价格档位列表
     */
    List<ModelPriceTierDTO> listModelPriceTiers(Long tenantId, Long modelId);

    /**
     * 更新试用次数（试用+1），返回更新后的试用记录
     */
    TrialRecordDTO updateTrialCount(UpdateTrialCountRequest request);

    /**
     * 查询试用次数
     */
    TrialRecordDTO getTrialCount(UpdateTrialCountRequest request);

    /**
     * 查询指定对象的定价信息（含关联对象详情和模型档位）
     */
    PricingConfigDTO queryPricingInfo(QueryPricingInfoRequest request);
}
