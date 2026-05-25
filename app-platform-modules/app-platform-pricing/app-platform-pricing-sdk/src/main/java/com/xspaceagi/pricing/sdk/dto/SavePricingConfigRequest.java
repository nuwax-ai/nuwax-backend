package com.xspaceagi.pricing.sdk.dto;

import com.xspaceagi.pricing.spec.enums.PricingTypeEnum;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Schema(description = "保存定价配置请求")
public class SavePricingConfigRequest implements Serializable {

    @Schema(description = "租户ID", requiredMode = Schema.RequiredMode.REQUIRED, hidden = true)
    private Long tenantId;

    @Schema(description = "配置ID，传值则为更新", hidden = true)
    private Long id;

    @Schema(description = "定价对象类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private TargetTypeEnum targetType;

    @Schema(description = "定价对象ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String targetId;

    @Schema(description = "定价类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private PricingTypeEnum pricingType;

    @Schema(description = "价格")
    private BigDecimal price;

    @Schema(description = "可试用次数")
    private Integer trialCount;

    @Schema(description = "状态：0-禁用，1-启用")
    private Integer status;

    @Schema(description = "工作空间ID，默认-1，工作空间下的定价管理时，该字段为必须")
    private Long spaceId;
}
