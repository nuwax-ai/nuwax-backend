package com.xspaceagi.pricing.sdk.dto;

import com.xspaceagi.pricing.spec.enums.TargetTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "更新试用次数请求")
public class UpdateTrialCountRequest implements Serializable {

    @Schema(description = "租户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long tenantId;

    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @Schema(description = "业务类型", requiredMode = Schema.RequiredMode.REQUIRED)
    private TargetTypeEnum targetType;

    @Schema(description = "业务对象ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private String targetId;
}
