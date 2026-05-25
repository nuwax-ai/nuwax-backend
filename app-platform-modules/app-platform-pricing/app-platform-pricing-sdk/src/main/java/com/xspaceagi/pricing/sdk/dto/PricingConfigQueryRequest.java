package com.xspaceagi.pricing.sdk.dto;

import com.xspaceagi.pricing.spec.enums.PricingTypeEnum;
import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@Schema(description = "定价配置查询请求")
public class PricingConfigQueryRequest implements Serializable {

    @Schema(description = "租户ID", requiredMode = Schema.RequiredMode.REQUIRED, hidden = true)
    private Long tenantId;

    @Schema(description = "定价对象类型")
    private TargetTypeEnum targetType;

    @Schema(description = "定价对象类型列表，比如查询工具列表下面包含了 MCP、PLUGIN、WORKFLOW")
    private List<TargetTypeEnum> targetTypes;

    @Schema(description = "定价对象ID")
    private String targetId;

    @Schema(description = "定价类型")
    private PricingTypeEnum pricingType;

    @Schema(description = "状态：0-禁用，1-启用")
    private Integer status;

    @Schema(description = "工作空间ID，系统管理端传-1", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long spaceId;
}
