package com.xspaceagi.pricing.sdk.dto;

import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "查询定价信息请求")
public class QueryPricingInfoRequest implements Serializable {

    @Schema(description = "租户ID", requiredMode = Schema.RequiredMode.REQUIRED, hidden = true)
    private Long tenantId;

    @Schema(description = "业务类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private TargetTypeEnum targetType;

    @Schema(description = "业务对象ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String targetId;

    @Schema(description = "是否返回业务对象信息")
    private Boolean showTargetObjectInfo;
}
