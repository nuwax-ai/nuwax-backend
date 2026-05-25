package com.xspaceagi.bill.sdk.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

@Data
@Schema(description = "收益统计")
public class RevenueStatsDTO implements Serializable {

    @Schema(description = "总收益")
    private BigDecimal totalRevenue;

    @Schema(description = "今日收益")
    private BigDecimal todayRevenue;

    @Schema(description = "本月收益")
    private BigDecimal monthRevenue;

    @Schema(description = "可提现金额")
    private BigDecimal pendingAmount;

    @Schema(description = "待结算金额")
    private BigDecimal unsettledAmount;

    @Schema(description = "已结算金额")
    private BigDecimal settledAmount;

    @Schema(description = "每日收益列表")
    private List<DailyRevenueDTO> dailyRevenues;

    @Schema(description = "用户收益排行")
    private List<UserRevenueRank> userRankings;

    @Schema(description = "总记录数")
    private Long total;

    @Schema(description = "页码")
    private Integer pageNum;

    @Schema(description = "每页大小")
    private Integer pageSize;

    @Data
    @Schema(description = "用户收益排行项")
    public static class UserRevenueRank implements Serializable {

        @Schema(description = "用户ID")
        private Long userId;

        @Schema(description = "用户名称")
        private String userName;

        @Schema(description = "收益金额")
        private BigDecimal amount;
    }
}
