package com.xspaceagi.credit.sdk.dto;

import com.xspaceagi.credit.spec.enums.CreditTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@Schema(description = "用户积分请求")
public class CreditRequest implements Serializable {

    @Schema(description = "租户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long tenantId;

    @Schema(description = "用户ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long userId;

    @Schema(description = "积分类型：SUBSCRIPTION-订阅积分，PURCHASE-增购积分，ACTIVITY-活动积分，MANUAL-手动发放", requiredMode = Schema.RequiredMode.REQUIRED)
    private CreditTypeEnum creditType;

    @Schema(description = "积分数量", requiredMode = Schema.RequiredMode.REQUIRED)
    private BigDecimal amount;

    @Schema(description = "业务单号，用于幂等")
    private String bizNo;
}
