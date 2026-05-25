package com.xspaceagi.pricing.sdk.dto;

import com.xspaceagi.pricing.spec.enums.PricingTypeEnum;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
@Schema(description = "定价配置")
public class PricingConfigDTO implements Serializable {

    @Schema(description = "配置ID")
    private Long id;

    @Schema(description = "定价对象类型")
    private TargetTypeEnum targetType;

    @Schema(description = "定价对象ID")
    private String targetId;

    @Schema(description = "定价类型：ONE_TIME-单次，BUYOUT-买断，MONTHLY-包月，SUBSCRIPTION_PLAN-订阅计划，TIERED-阶梯计费")
    private PricingTypeEnum pricingType;

    @Schema(description = "价格（单次、买断、包月时有效）")
    private BigDecimal price;

    @Schema(description = "可试用次数，0=不支持试用")
    private Integer trialCount;

    @Schema(description = "状态：0-禁用（关闭付费），1-启用（开启收费）")
    private Integer status;

    @Schema(description = "工作空间ID，默认-1")
    private Long spaceId;

    @Schema(description = "创建时间")
    private Date created;

    @Schema(description = "修改时间")
    private Date modified;

    @Schema(description = "定价对象信息")
    private TargetObjectInfo targetObjectInfo;

    @Schema(description = "模型阶梯价格配置")
    private List<ModelPriceTierDTO> modelPriceTiers;

    @Schema(description = "订阅计划")
    private List<Object> plans;
}
