package com.xspaceagi.credit.sdk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Schema(description = "用户积分汇总信息")
public class UserCreditSummary {

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "总积分")
    private BigDecimal totalCredit;

    @Schema(description = "订阅积分")
    private BigDecimal subscriptionCredit;

    @Schema(description = "增购积分")
    private BigDecimal purchaseCredit;

    @Schema(description = "活动积分")
    private BigDecimal activityCredit;

    @Schema(description = "手动发放积分")
    private BigDecimal manualCredit;

    @Schema(description = "每日赠积分")
    private BigDecimal dailyGiftCredit;

    @Schema(description = "用户信息")
    private CreditUser user;
}
